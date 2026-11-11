// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.retention.domain.Retention;
import org.snigf.mvp.retention.domain.RetentionRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention metadata and legal holds (ADR-041 D18). Every read is audited ({@code RECORD_RETENTION_READ}); a hold is
 * placed by one system administrator and released only when a second one approves the first one's request. The
 * database decides who may and refuses the requester as decider; a refusal is audited on its own
 * ({@code RECORD_REFUSED}). Nothing here deletes or disposes of anything.
 */
@Service
public class RetentionService {

    static final String ENTITY_HOLD = "LEGAL_HOLD";
    public static final int PAGE_SIZE = 50;

    private final RetentionRepository retention;
    private final DatabaseActor actor;
    private final AuditRecorder audit;

    public RetentionService(RetentionRepository retention, DatabaseActor actor, AuditRecorder audit) {
        this.retention = retention;
        this.actor = actor;
        this.audit = audit;
    }

    @Transactional
    public List<Retention.RetentionClass> classes(AuthenticatedUser reader) {
        actor.bind(reader.userId());
        List<Retention.RetentionClass> classes = retention.classes();
        audit.record(AuditEvent.of(AuditAction.RECORD_RETENTION_READ, AuditOutcome.SUCCESS).withDetails(Map.of("read", "CLASSES")));
        return classes;
    }

    /** The holds naming one subject, or every hold, newest first; each with its release history. */
    @Transactional
    public List<Retention.Hold> holds(AuthenticatedUser reader, String subjectKind, UUID subjectId, int page) {
        actor.bind(reader.userId());
        if ((subjectKind == null) != (subjectId == null) || (subjectKind != null && !Retention.SUBJECT_KINDS.contains(subjectKind))) {
            throw new RetentionRefused(RetentionRefused.Kind.INVALID, "SUBJECT_KIND_INVALID");
        }
        if (page < 0 || page > 10_000) {
            throw new RetentionRefused(RetentionRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        List<Retention.Hold> holds = retention.holds(subjectKind, subjectId, PAGE_SIZE, page * PAGE_SIZE);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("read", "HOLDS");
        if (subjectKind != null) {
            details.put("subjectKind", subjectKind);
            details.put("subject", subjectId.toString());
        }
        audit.record(AuditEvent.of(AuditAction.RECORD_RETENTION_READ, AuditOutcome.SUCCESS).withDetails(details));
        return holds;
    }

    /** One record's class, standing, holds in force and whether disposal would be allowed: today never. */
    @Transactional
    public Retention.RecordRetention retentionOf(AuthenticatedUser reader, String kind, UUID id) {
        actor.bind(reader.userId());
        if (!Retention.RECORD_KINDS.contains(kind)) {
            throw new RetentionRefused(RetentionRefused.Kind.INVALID, "SUBJECT_KIND_INVALID");
        }
        Retention.RecordRetention record = guarded(null, "READ", () -> retention.retentionOf(kind, id))
                .orElseThrow(() -> RetentionRefused.of("SUBJECT_NOT_FOUND"));
        audit.record(AuditEvent.of(AuditAction.RECORD_RETENTION_READ, AuditOutcome.SUCCESS)
                .withDetails(Map.of("read", "RECORD", "subjectKind", kind, "subject", id.toString())));
        return record;
    }

    @Transactional
    public Retention.Hold place(AuthenticatedUser admin, String subjectKind, UUID subjectId, String reason) {
        actor.bind(admin.userId());
        UUID id = guarded(null, "PLACE", () -> retention.place(subjectKind, subjectId, reason));
        audit.record(AuditEvent.of(AuditAction.RECORD_HOLD_PLACED, AuditOutcome.SUCCESS).withEntity(ENTITY_HOLD, id).withReason(truncate(reason))
                .withDetails(Map.of("subjectKind", subjectKind, "subject", subjectId.toString())));
        return retention.hold(id).orElseThrow();
    }

    /** The first of the two people: asks for the release, with a reason. */
    @Transactional
    public Retention.Hold requestRelease(AuthenticatedUser admin, UUID holdId, String reason) {
        actor.bind(admin.userId());
        Retention.Hold hold = retention.hold(holdId).orElseThrow(() -> refused(holdId, "REQUEST_RELEASE", RetentionRefused.of("HOLD_NOT_FOUND")));
        UUID release = guarded(holdId, "REQUEST_RELEASE", () -> retention.requestRelease(holdId, reason));
        audit.record(AuditEvent.of(AuditAction.RECORD_HOLD_RELEASE_REQUESTED, AuditOutcome.SUCCESS).withEntity(ENTITY_HOLD, holdId)
                .withReason(truncate(reason)).withDetails(Map.of("release", release.toString(), "subjectKind", hold.subjectKind(),
                        "subject", hold.subjectId().toString())));
        return retention.hold(holdId).orElseThrow();
    }

    /** The second person: approves (the hold is released) or rejects (it stays in force) another's request. */
    @Transactional
    public Retention.Hold decideRelease(AuthenticatedUser admin, UUID holdId, UUID releaseId, String outcome, String reason) {
        actor.bind(admin.userId());
        Retention.Hold hold = retention.hold(holdId).orElseThrow(() -> refused(holdId, "DECIDE_RELEASE", RetentionRefused.of("HOLD_NOT_FOUND")));
        if (hold.releases().stream().noneMatch(r -> r.id().equals(releaseId))) {
            throw refused(holdId, "DECIDE_RELEASE", RetentionRefused.of("RELEASE_NOT_FOUND"));
        }
        if (!"APPROVED".equals(outcome) && !"REJECTED".equals(outcome)) {
            throw refused(holdId, "DECIDE_RELEASE", new RetentionRefused(RetentionRefused.Kind.INVALID, "OUTCOME_REQUIRED"));
        }
        String given = reason == null || reason.isBlank() ? null : reason;
        guarded(holdId, "DECIDE_RELEASE", () -> {
            retention.decideRelease(releaseId, outcome, given);
            return releaseId;
        });
        AuditEvent event = AuditEvent.of("APPROVED".equals(outcome) ? AuditAction.RECORD_HOLD_RELEASED : AuditAction.RECORD_HOLD_RELEASE_REJECTED,
                AuditOutcome.SUCCESS).withEntity(ENTITY_HOLD, holdId)
                .withDetails(Map.of("release", releaseId.toString(), "subjectKind", hold.subjectKind(), "subject", hold.subjectId().toString()));
        audit.record(given == null ? event : event.withReason(truncate(given)));
        return retention.hold(holdId).orElseThrow();
    }

    private <T> T guarded(UUID holdId, String operation, Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            throw refused(holdId, operation, RetentionRefused.translated(e));
        }
    }

    private RetentionRefused refused(UUID holdId, String operation, RetentionRefused refused) {
        AuditEvent event = AuditEvent.of(AuditAction.RECORD_REFUSED, AuditOutcome.FAILED).withReason(refused.code())
                .withDetails(Map.of("code", refused.code(), "operation", operation));
        audit.recordIndependently(holdId == null ? event : event.withEntity(ENTITY_HOLD, holdId));
        return refused;
    }

    private static String truncate(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() > 1000 ? stripped.substring(0, 999) + "…" : stripped;
    }
}
