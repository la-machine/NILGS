// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEncoding;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditEventFilter;
import org.snigf.mvp.audit.AuditEventView;
import org.snigf.mvp.audit.AuditIntegrityException;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.audit.AuditTimeline;
import org.snigf.mvp.audit.AuditTrail;
import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.domain.AuditEventRepository;
import org.snigf.mvp.audit.domain.ChainHead;
import org.snigf.mvp.audit.domain.ChainedEvent;
import org.snigf.mvp.audit.domain.CheckpointRepository;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Use cases "verify the chain" and "sign a checkpoint" (Slice 1 design §7). Every run is
 * itself audited; every failure is logged at ERROR and reported to the
 * {@link AuditChainMonitor}.
 */
@Service
public class AuditTrailService implements AuditTrail {

    static final String ENTITY_CHECKPOINT = "AUDIT_CHECKPOINT";
    static final String ENTITY_CHAIN = "AUDIT_CHAIN";
    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);

    private final AuditEventRepository events;
    private final CheckpointRepository checkpoints;
    private final CheckpointArchive archive;
    private final ChainVerificationService verification;
    private final KeyService keys;
    private final AuditRecorder recorder;
    private final AuditChainMonitor monitor;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;
    private final DatabaseActor actor;
    private final JsonMapper json = JsonMapper.builder().build();

    public AuditTrailService(AuditEventRepository events, CheckpointRepository checkpoints, CheckpointArchive archive,
                             ChainVerificationService verification, KeyService keys, AuditRecorder recorder,
                             AuditChainMonitor monitor, PlatformTransactionManager transactions, DatabaseActor actor) {
        this.events = events;
        this.checkpoints = checkpoints;
        this.archive = archive;
        this.verification = verification;
        this.keys = keys;
        this.recorder = recorder;
        this.monitor = monitor;
        this.writing = new TransactionTemplate(transactions);
        writing.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reading = new TransactionTemplate(transactions);
        reading.setReadOnly(true);
        this.actor = actor;
    }

    @Override
    public ChainVerification verify(Long fromPos, Long toPos) {
        ChainVerification result = verification.verify(fromPos, toPos);
        boolean full = fromPos == null && toPos == null;
        if (full) {
            monitor.report(result);
        }
        if (result.verified()) {
            log.info("Audit chain verified: positions {}..{}, {} events, {} checkpoints",
                    result.checkedFrom(), result.checkedTo(), result.rowsChecked(), result.checkpoints().size());
            writing.executeWithoutResult(tx -> recorder.record(verifiedEvent(result, full)));
        } else {
            log.error("AUDIT CHAIN VERIFICATION FAILED: first mismatch at position {} ({}), checked {}..{}",
                    result.firstMismatchPos(), result.mismatchKind(), result.checkedFrom(), result.checkedTo());
            recorder.recordIndependently(verifiedEvent(result, full));
        }
        return result;
    }

    @Override
    public Checkpoint createCheckpoint() {
        try {
            return writing.execute(tx -> signHead());
        } catch (AuditIntegrityException e) {
            ChainVerification v = e.verification();
            monitor.report(v);
            log.error("AUDIT CHECKPOINT REFUSED: chain broken at position {} ({})",
                    v.firstMismatchPos(), v.mismatchKind());
            recorder.recordIndependently(AuditEvent.of(AuditAction.AUDIT_CHECKPOINT_CREATED, AuditOutcome.FAILED)
                    .withEntity(ENTITY_CHECKPOINT, null)
                    .withReason("the chain does not verify since the last checkpoint")
                    .withDetails(summary(v, false)));
            throw e;
        }
    }

    /** Inside the writing transaction, under the checkpoint lock. */
    private Checkpoint signHead() {
        if (!checkpoints.tryLock()) {
            throw new IllegalStateException("another checkpoint is being created");
        }
        ChainHead head = events.head().orElseThrow(() -> new IllegalStateException("the audit chain is empty"));

        // Never sign a tampered chain: that would make the tampering look legitimate.
        ChainVerification check = verification.verifySinceLastCheckpoint();
        if (!check.verified()) {
            throw new AuditIntegrityException("the audit chain does not verify since the last checkpoint", check);
        }

        UUID id = UuidV7.next();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] payload = AuditEncoding.checkpointPayload(id, head.chainPos(), head.hash(), createdAt);
        Signature signature = keys.sign(SigningKey.AUDIT_CHECKPOINT, payload);
        if (!keys.verify(SigningKey.AUDIT_CHECKPOINT, payload, signature)) {
            throw new KeyServiceException("the new checkpoint signature does not verify");
        }
        Checkpoint checkpoint = new Checkpoint(id, head.chainPos(), head.hash(), createdAt, signature);

        checkpoints.add(checkpoint);
        // Archived before commit: a checkpoint never exists without its off-database copy.
        archive.store(checkpoint);
        archive.storePublicKeys(keys.publicKeys(SigningKey.AUDIT_CHECKPOINT));
        recorder.record(AuditEvent.of(AuditAction.AUDIT_CHECKPOINT_CREATED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_CHECKPOINT, id)
                .withDetails(Map.of(
                        "chainPos", checkpoint.chainPos(),
                        "headHash", checkpoint.headHashHex(),
                        "keyVersion", signature.keyVersion())));
        log.info("Audit checkpoint {} signed at position {} (key version {})",
                id, checkpoint.chainPos(), signature.keyVersion());
        return checkpoint;
    }

    /** True when events other than the last checkpoint's own record were added since it. */
    public boolean checkpointDue() {
        Optional<ChainHead> head = events.head();
        if (head.isEmpty()) {
            return false;
        }
        long last = checkpoints.all().stream().mapToLong(Checkpoint::chainPos).max().orElse(0);
        return last == 0 || head.get().chainPos() > last + 1;
    }

    @Override
    public AuditEventView.Page events(UUID reader, AuditEventFilter filter) {
        List<AuditEventView> found = reading.execute(tx -> {
            actor.bind(reader);
            return views(events.search(filter, filter.size() + 1, filter.page() * filter.size()));
        });
        boolean more = found.size() > filter.size();
        Map<String, Object> details = new LinkedHashMap<>();
        if (filter.entityType() != null) {
            details.put("entityType", filter.entityType());
        }
        if (filter.entityId() != null) {
            details.put("entityId", filter.entityId());
        }
        if (filter.subjectType() != null) {
            details.put("subjectType", filter.subjectType());
            details.put("subjectId", filter.subjectId());
        }
        if (filter.actorId() != null) {
            details.put("actorId", filter.actorId());
        }
        if (filter.action() != null) {
            details.put("action", filter.action());
        }
        if (filter.from() != null) {
            details.put("from", filter.from().toString());
        }
        if (filter.to() != null) {
            details.put("to", filter.to().toString());
        }
        details.put("page", filter.page());
        details.put("returned", Math.min(found.size(), filter.size()));
        writing.executeWithoutResult(tx -> recorder.record(AuditEvent.of(AuditAction.AUDIT_EVENTS_READ, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_CHAIN, null).withDetails(details)));
        return new AuditEventView.Page(more ? found.subList(0, filter.size()) : found, filter.page(), filter.size(), more);
    }

    /** Records that someone downloaded a checkpoint file: reading audit data is audited. */
    public void recordCheckpointDownload(long chainPos) {
        writing.executeWithoutResult(tx -> recorder.record(AuditEvent.of(AuditAction.AUDIT_EVENTS_READ, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_CHECKPOINT, null).withDetails(Map.of("checkpointFile", chainPos))));
    }

    @Override
    public Optional<AuditTimeline> timeline(UUID reader, String subjectType, UUID subjectId, String reference) {
        if (subjectType == null || !AuditTimeline.SUBJECT_TYPES.contains(subjectType)
                || (subjectId == null) == (reference == null || reference.isBlank())) {
            throw new IllegalArgumentException("a subject type and either an id or a reference");
        }
        Optional<AuditTimeline> found = reading.execute(tx -> {
            actor.bind(reader);
            Optional<UUID> id = subjectId != null ? Optional.of(subjectId)
                    : events.subjectByReference(subjectType, reference.strip());
            if (id.isEmpty()) {
                return Optional.<AuditTimeline>empty();
            }
            List<ChainedEvent> rows = events.timeline(subjectType, id.get(), AuditTimeline.MAX_EVENTS + 1);
            if (rows.isEmpty()) {
                return Optional.<AuditTimeline>empty();
            }
            boolean truncated = rows.size() > AuditTimeline.MAX_EVENTS;
            List<AuditEventView> shown = views(truncated ? rows.subList(0, AuditTimeline.MAX_EVENTS) : rows);
            String ref = reference != null ? reference.strip() : events.referenceOf(subjectType, id.get()).orElse(null);
            return Optional.of(new AuditTimeline(subjectType, id.get(), ref, shown, truncated));
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", subjectType);
        found.ifPresent(t -> details.put("subjectId", t.subjectId()));
        if (reference != null) {
            details.put("reference", reference.strip());
        }
        details.put("returned", found.map(t -> t.events().size()).orElse(0));
        writing.executeWithoutResult(tx -> recorder.record(AuditEvent.of(AuditAction.AUDIT_TIMELINE_READ, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_CHAIN, null).withDetails(details)));
        return found;
    }

    /** Events as read: subjects with their office codes, the actor's current name, and whether each is intact. */
    private List<AuditEventView> views(List<ChainedEvent> rows) {
        Map<UUID, String> offices = events.officeCodes();
        Map<UUID, String> names = events.accountNames(rows.stream().map(ChainedEvent::actorId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        return rows.stream().map(e -> new AuditEventView(e.chainPos(), e.id(), e.occurredAt(), e.actorType(), e.actorId(),
                e.actorLabel(), e.actorRoles(), e.action(), e.entityType(), e.entityId(), e.entityRef(), e.outcome(),
                e.reason(), e.correlationId(), e.sourceIp(), e.details(), AuditEventView.hex(e.prevHash()),
                AuditEventView.hex(e.hash()), e.actorId() == null ? null : names.get(e.actorId()),
                subjects(e.subjects(), offices), java.security.MessageDigest.isEqual(e.hash(), e.computeHash()))).toList();
    }

    private List<AuditEventView.Subject> subjects(String json, Map<UUID, String> offices) {
        if (json == null) {
            return List.of();
        }
        List<AuditEventView.Subject> out = new java.util.ArrayList<>();
        for (JsonNode s : this.json.readTree(json)) {
            String office = s.path("office").asString(null);
            out.add(new AuditEventView.Subject(s.path("type").asString(""), UUID.fromString(s.path("id").asString("")),
                    office == null ? null : offices.get(UUID.fromString(office))));
        }
        return List.copyOf(out);
    }

    @Override
    public List<Checkpoint> checkpoints() {
        return checkpoints.all();
    }

    @Override
    public Optional<byte[]> checkpointFile(long chainPos) {
        return archive.read(chainPos);
    }

    @Override
    public List<SigningPublicKey> checkpointPublicKeys() {
        return keys.publicKeys(SigningKey.AUDIT_CHECKPOINT);
    }

    private static AuditEvent verifiedEvent(ChainVerification v, boolean full) {
        return AuditEvent.of(AuditAction.AUDIT_VERIFIED, v.verified() ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
                .withEntity(ENTITY_CHAIN, null)
                .withDetails(summary(v, full));
    }

    private static Map<String, Object> summary(ChainVerification v, boolean full) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verified", v.verified());
        details.put("full", full);
        details.put("checkedFrom", v.checkedFrom());
        details.put("checkedTo", v.checkedTo());
        details.put("rowsChecked", v.rowsChecked());
        details.put("checkpoints", v.checkpoints().size());
        if (v.firstMismatchPos() != null) {
            details.put("firstMismatchPos", v.firstMismatchPos());
            details.put("mismatchKind", v.mismatchKind().name());
        }
        return details;
    }
}
