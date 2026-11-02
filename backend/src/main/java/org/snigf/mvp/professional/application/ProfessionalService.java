// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.application;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.identity.AccountRegistration;
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.professional.domain.Professional;
import org.snigf.mvp.professional.domain.ProfessionalRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The professional account lifecycle (ADR-041 §3). Every change is one call to a database function,
 * which decides: two administrators for everything but a suspension, never the professional, never the
 * verifier approving. A refusal becomes a {@link ProfessionalRefused} and is audited on its own. Each
 * audit event about a professional names their capacity (profession). Nothing here grants a business
 * permission.
 */
@Service
public class ProfessionalService {

    static final String ENTITY = "PROFESSIONAL";
    private static final String EVIDENCE_ENDPOINT = "POST /api/v1/admin/professionals/{id}/verification/documents";
    private static final Pattern REASON = Pattern.compile(": ([A-Z][A-Z0-9_]{2,63})\\b");
    private static final int PAGE_SIZE = 50;

    /** What an administrator reads of one professional; {@code verification} only for verifiers and approvers. */
    public record Detail(Professional.Profile profile, List<Professional.Request> requests, Optional<Professional.Verification> verification,
                         List<Professional.HistoryEntry> history) {
    }

    /** What a professional reads of their own account. */
    public record Own(Professional.Profile profile, List<Professional.HistoryEntry> history) {
    }

    /** A file to send, already checked against its hash. */
    public record EvidenceDownload(Professional.Evidence evidence, InputStream content) {
    }

    private final ProfessionalRepository professionals;
    private final AccountRegistration accounts;
    private final DocumentFiles files;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final DatabaseActor actor;
    private final ProfessionalProperties properties;
    private final TransactionTemplate separately;

    public ProfessionalService(ProfessionalRepository professionals, AccountRegistration accounts, DocumentFiles files, IdempotencyStore idempotency,
                               AuditRecorder audit, DatabaseActor actor, ProfessionalProperties properties, PlatformTransactionManager transactions) {
        this.separately = new TransactionTemplate(transactions);
        this.separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.professionals = professionals;
        this.accounts = accounts;
        this.files = files;
        this.idempotency = idempotency;
        this.audit = audit;
        this.actor = actor;
        this.properties = properties;
    }

    // --- The professional ------------------------------------------------------------------------------

    /**
     * The public request: an account and its profile, or nothing. The answer is the same whether the
     * address was new or not, so the form reveals no account.
     */
    @Transactional
    public void request(Professional.NewRequest request) {
        if (request.profession() == null || !Professional.PROFESSIONS.contains(request.profession())) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "PROFESSION_INVALID");
        }
        Optional<UUID> created;
        try {
            created = accounts.registerProfessional(new AccountRegistration.Account(request.email(), request.displayName(), request.password(),
                    request.preferredLanguage()));
        } catch (AccountRegistration.Rejected e) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, e.onlyPassword() ? "PASSWORD_WEAK" : "VALIDATION_FAILED", e.codes());
        }
        if (created.isEmpty()) {
            return;
        }
        UUID id = created.get();
        guarded(null, id, "REQUEST", () -> {
            professionals.createProfile(id, request.profession(), request.chamber(), request.registrationNumber(), request.jurisdiction());
            return id;
        });
        audit.record(event(AuditAction.PROFESSIONAL_REQUESTED, id, request.profession()));
    }

    @Transactional(readOnly = true)
    public Own own(AuthenticatedUser user) {
        if (user.accountType() != AccountType.PROFESSIONAL) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "PROFESSIONAL_NOT_FOUND");
        }
        actor.bind(user.userId());
        Professional.Profile profile = professionals.profile(user.userId())
                .orElseThrow(() -> new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "PROFESSIONAL_NOT_FOUND"));
        return new Own(profile, professionals.history(user.userId()));
    }

    // --- Administrators: reading --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Professional.Profile> profiles(AuthenticatedUser admin, String status, int page) {
        actor.bind(admin.userId());
        return professionals.profiles(status, PAGE_SIZE, offset(page));
    }

    /** Pending requests; those past their time to live are closed as expired first, and audited. */
    @Transactional
    public List<Professional.Request> pendingRequests(AuthenticatedUser admin, int page) {
        actor.bind(admin.userId());
        for (Professional.Request r : professionals.pendingRequests(PAGE_SIZE, 0)) {
            expireIfStale(admin, r);
        }
        return professionals.pendingRequests(PAGE_SIZE, offset(page));
    }

    /** One professional; the verification record and its evidence only for verifiers and approvers, and audited. */
    @Transactional
    public Detail detail(AuthenticatedUser admin, UUID userId) {
        actor.bind(admin.userId());
        Professional.Profile profile = professionals.profile(userId)
                .orElseThrow(() -> new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "PROFESSIONAL_NOT_FOUND"));
        Optional<Professional.Verification> verification = professionals.verification(userId);
        verification.ifPresent(v -> audit.record(event(AuditAction.PROFESSIONAL_EVIDENCE_READ, userId, profile.profession())
                .withDetails(Map.of("capacity", profile.profession(), "verification", v.id().toString(), "documents", v.documents().size()))));
        return new Detail(profile, professionals.requests(userId), verification, professionals.history(userId));
    }

    @Transactional
    public EvidenceDownload evidence(AuthenticatedUser admin, UUID userId, UUID documentId) {
        actor.bind(admin.userId());
        Professional.Evidence e = professionals.evidence(userId, documentId)
                .orElseThrow(() -> new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "DOCUMENT_NOT_FOUND"));
        if (!files.intact(e.storageKey(), e.sha256(), e.sizeBytes())) {
            audit.recordIndependently(AuditEvent.of(AuditAction.PROFESSIONAL_REFUSED, AuditOutcome.FAILED).withEntity(ENTITY, userId)
                    .withReason("DOCUMENT_INTEGRITY_FAILED").withDetails(Map.of("document", documentId.toString())));
            throw new ProfessionalRefused(ProfessionalRefused.Kind.BROKEN, "DOCUMENT_INTEGRITY_FAILED");
        }
        audit.record(AuditEvent.of(AuditAction.PROFESSIONAL_EVIDENCE_READ, AuditOutcome.SUCCESS).withEntity(ENTITY, userId)
                .withDetails(Map.of("document", documentId.toString())));
        return new EvidenceDownload(e, files.open(e.storageKey()));
    }

    // --- Administrators: the lifecycle ---------------------------------------------------------------------

    /** Administrator A: the credential and identity were checked; the offices proposed. Opens the activation. */
    @Transactional
    public UUID verify(AuthenticatedUser admin, UUID userId, Professional.NewVerification v) {
        actor.bind(admin.userId());
        List<UUID> offices = offices(admin, userId, v.officeCodes());
        UUID request = guarded(admin, userId, "VERIFY", () -> professionals.verify(userId, v.body(), v.reference(), v.checkedOn(), v.note(), offices));
        audit.record(event(AuditAction.PROFESSIONAL_VERIFIED, userId, capacity(userId))
                .withDetails(Map.of("capacity", capacity(userId), "request", request.toString(), "offices", v.officeCodes())));
        return request;
    }

    /** A file supporting the verification, checked like every document (ADR-033). Only its verifier adds one. */
    @Transactional
    public UUID addEvidence(AuthenticatedUser admin, UUID userId, String givenName, InputStream content, String idempotencyKey) {
        actor.bind(admin.userId());
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        Professional.Verification verification = professionals.verification(userId)
                .orElseThrow(() -> new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "VERIFICATION_NOT_FOUND"));
        String key = null;
        try (DocumentFiles.Received received = files.receive(content, givenName)) {
            String hash = IdempotencyStore.sha256(userId + ":" + received.sha256() + ":" + received.name());
            var earlier = idempotency.find(admin.userId(), idempotencyKey);
            if (earlier.isPresent()) {
                if (!earlier.get().requestSha256().equals(hash)) {
                    throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REUSED");
                }
                return UUID.fromString(earlier.get().body().replaceAll("[^0-9a-f-]", ""));
            }
            key = received.keep();
            String storageKey = key;
            UUID id = guarded(admin, userId, "ADD_EVIDENCE", () -> professionals.addEvidence(verification.id(), storageKey, received.sha256(),
                    received.sizeBytes(), received.mediaType(), received.name()));
            audit.record(event(AuditAction.PROFESSIONAL_EVIDENCE_ADDED, userId, capacity(userId))
                    .withDetails(Map.of("capacity", capacity(userId), "type", received.mediaType(), "sizeBytes", received.sizeBytes(),
                            "sha256", received.sha256())));
            try {
                idempotency.save(admin.userId(), idempotencyKey, EVIDENCE_ENDPOINT, hash, 201, "\"" + id + "\"");
            } catch (IdempotencyStore.InProgress e) {
                throw new ProfessionalRefused(ProfessionalRefused.Kind.CONFLICT, "REQUEST_IN_PROGRESS");
            }
            return id;
        } catch (DocumentFiles.Refused e) {
            audit.recordIndependently(AuditEvent.of(AuditAction.PROFESSIONAL_REFUSED, AuditOutcome.FAILED).withEntity(ENTITY, userId)
                    .withReason(e.code()).withDetails(Map.of("code", e.code(), "operation", "ADD_EVIDENCE")));
            throw new ProfessionalRefused("FILE_TOO_LARGE".equals(e.code()) ? ProfessionalRefused.Kind.TOO_LARGE : ProfessionalRefused.Kind.INVALID,
                    e.code());
        } catch (RuntimeException e) {
            if (key != null) {
                files.forget(key);
            }
            throw e;
        }
    }

    /** One administrator stops an active professional at once. */
    @Transactional
    public void suspend(AuthenticatedUser admin, UUID userId, String reason) {
        actor.bind(admin.userId());
        guarded(admin, userId, "SUSPEND", () -> {
            professionals.suspend(userId, reason);
            return userId;
        });
        audit.record(event(AuditAction.PROFESSIONAL_SUSPENDED, userId, capacity(userId)).withReason(truncate(reason)));
    }

    /** A reinstatement, revocation or office change, for a second administrator to decide. */
    @Transactional
    public UUID requestChange(AuthenticatedUser admin, UUID userId, Professional.NewChange change) {
        actor.bind(admin.userId());
        if (change.kind() == null || !Professional.CHANGE_KINDS.contains(change.kind())) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "KIND_INVALID");
        }
        professionals.requests(userId).stream().filter(r -> "PENDING".equals(r.status())).forEach(r -> expireIfStale(admin, r));
        List<UUID> add = offices(admin, userId, change.officesAdd());
        List<UUID> remove = offices(admin, userId, change.officesRemove());
        UUID request = guarded(admin, userId, "REQUEST_" + change.kind(), () -> professionals.requestChange(userId, change.kind(), add, remove, change.reason()));
        AuditAction action = switch (change.kind()) {
            case "REINSTATEMENT" -> AuditAction.PROFESSIONAL_REINSTATEMENT_REQUESTED;
            case "REVOCATION" -> AuditAction.PROFESSIONAL_REVOCATION_REQUESTED;
            default -> AuditAction.PROFESSIONAL_OFFICE_CHANGE_REQUESTED;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capacity", capacity(userId));
        details.put("request", request.toString());
        if ("OFFICE_CHANGE".equals(change.kind())) {
            details.put("add", change.officesAdd() == null ? List.of() : change.officesAdd());
            details.put("remove", change.officesRemove() == null ? List.of() : change.officesRemove());
        }
        audit.record(event(action, userId, capacity(userId)).withReason(truncate(change.reason())).withDetails(details));
        return request;
    }

    /** The second administrator's decision. */
    @Transactional
    public Professional.Request decide(AuthenticatedUser admin, UUID requestId, boolean approve, String reason) {
        actor.bind(admin.userId());
        Professional.Request r = professionals.request(requestId)
                .orElseThrow(() -> new ProfessionalRefused(ProfessionalRefused.Kind.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (expireIfStale(admin, r)) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.CONFLICT, "REQUEST_EXPIRED");
        }
        guarded(admin, r.userId(), approve ? "APPROVE" : "REJECT", () -> {
            professionals.decide(requestId, approve, reason == null || reason.isBlank() ? null : reason);
            return requestId;
        });
        AuditAction action = !approve ? ("ACTIVATION".equals(r.kind()) ? AuditAction.PROFESSIONAL_REJECTED : AuditAction.PROFESSIONAL_CHANGE_REJECTED)
                : switch (r.kind()) {
                    case "ACTIVATION" -> AuditAction.PROFESSIONAL_APPROVED;
                    case "REINSTATEMENT" -> AuditAction.PROFESSIONAL_REINSTATED;
                    case "REVOCATION" -> AuditAction.PROFESSIONAL_REVOKED;
                    default -> AuditAction.PROFESSIONAL_OFFICES_CHANGED;
                };
        AuditEvent event = event(action, r.userId(), capacity(r.userId()))
                .withDetails(Map.of("capacity", capacity(r.userId()), "request", requestId.toString(), "kind", r.kind()));
        audit.record(reason == null || reason.isBlank() ? event : event.withReason(truncate(reason)));
        return professionals.request(requestId).orElseThrow();
    }

    // --- Rules -----------------------------------------------------------------------------------------------

    /** Closes a stale request in its own transaction: a refusal that follows must not undo the expiry. */
    private boolean expireIfStale(AuthenticatedUser admin, Professional.Request r) {
        return Boolean.TRUE.equals(separately.execute(tx -> {
            actor.bind(admin.userId());
            if (!professionals.expire(r.id(), properties.requestTtl())) {
                return false;
            }
            audit.record(event(AuditAction.PROFESSIONAL_REQUEST_EXPIRED, r.userId(), capacity(r.userId()))
                    .withDetails(Map.of("capacity", capacity(r.userId()), "request", r.id().toString(), "kind", r.kind())));
            return true;
        }));
    }

    private List<UUID> offices(AuthenticatedUser admin, UUID userId, List<String> codes) {
        List<String> given = codes == null ? List.of() : codes.stream().map(c -> c == null ? "" : c.strip()).distinct().toList();
        List<UUID> ids = professionals.officeIds(given);
        if (ids.size() != given.size()) {
            throw refused(admin, userId, "OFFICES", ProfessionalRefused.of("OFFICES_INVALID"));
        }
        return ids;
    }

    private String capacity(UUID userId) {
        return professionals.profile(userId).map(Professional.Profile::profession).orElse("UNKNOWN");
    }

    private static AuditEvent event(AuditAction action, UUID userId, String capacity) {
        return AuditEvent.of(action, AuditOutcome.SUCCESS).withEntity(ENTITY, userId).withDetails(Map.of("capacity", capacity));
    }

    /** Runs a write; the database's refusal becomes a precise code and an independent audit event. */
    private UUID guarded(AuthenticatedUser admin, UUID userId, String operation, Supplier<UUID> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            throw refused(admin, userId, operation, translated(e));
        }
    }

    private ProfessionalRefused refused(AuthenticatedUser admin, UUID userId, String operation, ProfessionalRefused refused) {
        audit.recordIndependently(AuditEvent.of(AuditAction.PROFESSIONAL_REFUSED, AuditOutcome.FAILED).withEntity(ENTITY, userId)
                .withReason(refused.code()).withDetails(Map.of("code", refused.code(), "operation", operation)));
        return refused;
    }

    private static ProfessionalRefused translated(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if ("PR001".equals(state)) {
                    Matcher reason = REASON.matcher(String.valueOf(sql.getMessage()));
                    return ProfessionalRefused.of(reason.find() ? reason.group(1) : "PROFESSIONAL_REFUSED");
                }
                if (state.startsWith("23") || state.startsWith("22")) {
                    return new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "PROFESSIONAL_INVALID");
                }
            }
        }
        throw e;
    }

    private static int offset(int page) {
        if (page < 0 || page > 10_000) {
            throw new ProfessionalRefused(ProfessionalRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return page * PAGE_SIZE;
    }

    private static String truncate(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() > 1000 ? stripped.substring(0, 999) + "…" : stripped;
    }
}
