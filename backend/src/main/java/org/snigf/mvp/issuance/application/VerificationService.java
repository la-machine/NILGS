// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.application;

import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.issuance.domain.DocumentNumber;
import org.snigf.mvp.issuance.domain.DocumentRenderer;
import org.snigf.mvp.issuance.domain.IssuanceRepository;
import org.snigf.mvp.issuance.domain.Manifest;
import org.snigf.mvp.issuance.domain.Verification;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifying a document presented as issued by SNIGF, and the incidents that follow (ADR-039).
 * <ul>
 *   <li>Anyone may verify, signed in or not; every attempt is recorded and audited, with its
 *       outcome. What the caller learns is the outcome and the document's status, type, date and
 *       issuing office, never who it was issued to or what it concerns.</li>
 *   <li>A suspicious outcome opens an incident: an altered file, a file claiming SNIGF that nothing
 *       matches, a registry row that no longer agrees with its signature. A typed number that
 *       matches nothing is recorded but opens none: a typo is not evidence. An incident accuses
 *       nobody; a document officer reviews it.</li>
 *   <li>Incidents are read and resolved by holders of {@code document.incident.review}, once, with a
 *       note; never one about their own document (the database agrees).</li>
 * </ul>
 */
@Service
public class VerificationService {

    static final String INCIDENT_ENTITY = "VERIFICATION_INCIDENT";

    private final IssuanceRepository registry;
    private final DocumentRenderer reader;
    private final KeyService keys;
    private final DatabaseActor actor;
    private final AuditRecorder audit;
    private final IssuanceProperties settings;
    private final TransactionTemplate transaction;

    public VerificationService(IssuanceRepository registry, DocumentRenderer reader, KeyService keys, DatabaseActor actor,
                               AuditRecorder audit, IssuanceProperties settings, PlatformTransactionManager transactions) {
        this.registry = registry;
        this.reader = reader;
        this.keys = keys;
        this.actor = actor;
        this.audit = audit;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** A verification's answer, and the reference of the recorded attempt. */
    public record Answer(UUID verificationId, Verification.Channel channel, Verification.Result result) {
    }

    /**
     * Verifies a number (from the QR code, a printed or a typed copy).
     *
     * @param caller the signed-in account, or null for the public page
     */
    public Answer byNumber(String typed, UUID caller) {
        String number = DocumentNumber.normalise(typed);
        if (!DocumentNumber.isValid(number)) {
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "INVALID_NUMBER");
        }
        return transaction.execute(tx -> {
            bind(caller);
            Verification.Result result = Verification.ofNumber(registry.evidenceByNumber(number), this::signed);
            return record(Verification.Channel.NUMBER, number, null, result, caller);
        });
    }

    /** Verifies a file: only an identical PDF is authentic. */
    public Answer byFile(byte[] bytes, UUID caller) {
        if (bytes == null || bytes.length == 0) {
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "FILE_REQUIRED");
        }
        if (bytes.length > settings.maxUploadBytes()) {
            throw new IssuanceRefused(IssuanceRefused.Kind.TOO_LARGE, "FILE_TOO_LARGE");
        }
        byte[] sha256 = Manifest.sha256(bytes);
        Verification.Claim claim = reader.read(bytes);
        String claimed = claim.number() != null && DocumentNumber.isValid(claim.number()) ? claim.number() : null;
        return transaction.execute(tx -> {
            bind(caller);
            Optional<Verification.Evidence> bySha = registry.evidenceBySha(sha256);
            Optional<Verification.Evidence> byNumber = bySha.isEmpty() && claimed != null
                    ? registry.evidenceByNumber(claimed) : Optional.empty();
            Verification.Result result = Verification.ofFile(sha256, claim, bySha, byNumber, this::signed);
            return record(Verification.Channel.FILE, claimed, sha256, result, caller);
        });
    }

    public List<IssuanceRepository.Incident> incidents(UUID officer, IssuanceRepository.Incident.Status status, int page) {
        if (page < 0 || page > 10_000) {
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return transaction.execute(tx -> {
            actor.bind(officer);
            return registry.incidents(status, settings.pageSize(), page * settings.pageSize());
        });
    }

    public IssuanceRepository.Incident resolve(UUID officer, UUID id, long version,
                                               IssuanceRepository.Incident.Resolution resolution, String note) {
        String text = note == null ? "" : note.strip();
        if (resolution == null) {
            throw refused(id, "RESOLUTION_REQUIRED", IssuanceRefused.Kind.INVALID);
        }
        if (text.length() < 10 || text.length() > 2000) {
            throw refused(id, "NOTE_REQUIRED", IssuanceRefused.Kind.INVALID);
        }
        return transaction.execute(tx -> {
            actor.bind(officer);
            IssuanceRepository.Incident incident = registry.incident(id)
                    .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "INCIDENT_NOT_FOUND"));
            if (incident.status() != IssuanceRepository.Incident.Status.OPEN) {
                throw refused(id, "INCIDENT_RESOLVED", IssuanceRefused.Kind.CONFLICT);
            }
            boolean done;
            try {
                done = registry.resolveIncident(id, version, resolution, text, officer);
            } catch (DataAccessException e) {
                if ("42501".equals(sqlState(e))) {
                    throw refused(id, "OWN_DOCUMENT", IssuanceRefused.Kind.FORBIDDEN);
                }
                throw e;
            }
            if (!done) {
                throw refused(id, "STALE_VERSION", IssuanceRefused.Kind.CONFLICT);
            }
            audit.record(AuditEvent.of(AuditAction.VERIFICATION_INCIDENT_RESOLVED, AuditOutcome.SUCCESS)
                    .withEntity(INCIDENT_ENTITY, id).withEntityRef(incident.documentNumber())
                    .withReason(resolution.name()).withDetails(Map.of("kind", incident.kind().name(),
                            "resolution", resolution.name(), "noteLength", text.length())));
            return registry.incident(id).orElseThrow();
        });
    }

    /** Records the attempt and, when it is suspicious, the incident; audits both. */
    private Answer record(Verification.Channel channel, String claimed, byte[] sha256, Verification.Result result,
                          UUID caller) {
        UUID documentId = result.evidence() == null ? null : result.evidence().id();
        UUID verification = registry.recordVerification(channel, claimed, sha256, documentId, result.outcome(),
                result.reason(), caller);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("channel", channel.name());
        facts.put("outcome", result.outcome().name());
        facts.put("reason", result.reason());
        facts.put("verification", verification.toString());
        if (claimed != null) {
            facts.put("claimedNumber", claimed);
        }
        if (sha256 != null) {
            facts.put("presentedSha256", HexFormat.of().formatHex(sha256));
        }
        AuditEvent verified = AuditEvent.of(AuditAction.DOCUMENT_VERIFIED, AuditOutcome.SUCCESS)
                .withReason(result.outcome().name()).withDetails(facts);
        audit.record(documentId == null ? verified : verified.withEntity(IssuanceService.ENTITY, documentId)
                .withEntityRef(result.evidence().number()));
        if (result.incident() != null) {
            UUID incident = registry.openIncident(verification, result.incident(), documentId);
            audit.record(AuditEvent.of(AuditAction.VERIFICATION_INCIDENT_OPENED, AuditOutcome.SUCCESS)
                    .withEntity(INCIDENT_ENTITY, incident).withReason(result.incident().name()).withDetails(facts));
        }
        return new Answer(verification, channel, result);
    }

    /** The evidence's signature, recomputed against the public key of the version that made it. */
    private boolean signed(Verification.Evidence e) {
        try {
            Signature signature = Signature.parse(e.signature());
            return signature.keyVersion() == e.keyVersion()
                    && keys.verify(SigningKey.DOCUMENT_ISSUANCE, e.manifest(), signature);
        } catch (IllegalArgumentException | KeyServiceException ex) {
            return false;
        }
    }

    private void bind(UUID caller) {
        if (caller != null) {
            actor.bind(caller);
        }
    }

    private IssuanceRefused refused(UUID incident, String code, IssuanceRefused.Kind kind) {
        audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_STATUS_REFUSED,
                        kind == IssuanceRefused.Kind.FORBIDDEN ? AuditOutcome.DENIED : AuditOutcome.FAILED)
                .withEntity(INCIDENT_ENTITY, incident).withReason(code));
        return new IssuanceRefused(kind, code);
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }
}
