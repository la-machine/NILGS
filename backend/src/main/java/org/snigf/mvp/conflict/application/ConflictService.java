// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.application;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.conflict.domain.ConflictAlert;
import org.snigf.mvp.conflict.domain.ConflictAlertRepository;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.parcel.ParcelVersionChecks;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overlap and duplicate alerts (MVP-04). The database raises them as each parcel version is
 * stored (V11); after every write this service reports what the version raised and closed and
 * records each in the audit trail under the same correlation as the parcel write (E-10). It
 * lists alerts by parcel and by office, and resolves them (E-11): the resolver states one of the
 * Baseline's outcomes with a note, and may not have authored either compared version (C6b). The
 * service refuses first, with a precise code; the database's guard (V12) checks again.
 */
@Service
public class ConflictService implements ParcelVersionChecks {

    static final String ENTITY = "CONFLICT_ALERT";
    /** Office lists come in pages of this size. */
    public static final int PAGE_SIZE = 50;

    private final ConflictAlertRepository alerts;
    private final AuditRecorder audit;

    public ConflictService(ConflictAlertRepository alerts, AuditRecorder audit) {
        this.alerts = alerts;
        this.audit = audit;
    }

    /** A resolution as the reviewer states it, against the alert version they read. */
    public record Resolve(long expectedVersion, ConflictAlert.Resolution resolution, String note) {
    }

    /** An alert with its three shapes in one UTM zone, for the review map. */
    public record Review(ConflictAlert alert, Optional<ConflictAlertRepository.Display> display) {
    }

    @Override
    @Transactional
    public List<RaisedAlert> afterVersionWritten(VersionWritten written) {
        for (ConflictAlert closed : alerts.supersededBy(written.versionId())) {
            audit.record(AuditEvent.of(AuditAction.CONFLICT_ALERT_SUPERSEDED, AuditOutcome.SUCCESS)
                    .withEntity(ENTITY, closed.id()).withEntityRef(written.reference())
                    .withDetails(facts(closed, written)));
        }
        List<ConflictAlert> raised = alerts.raisedBy(written.versionId());
        for (ConflictAlert alert : raised) {
            audit.record(AuditEvent.of(AuditAction.CONFLICT_ALERT_RAISED, AuditOutcome.SUCCESS)
                    .withEntity(ENTITY, alert.id()).withEntityRef(written.reference())
                    .withDetails(facts(alert, written)));
        }
        return raised.stream().map(a -> new RaisedAlert(a.id(), a.type().name(),
                a.other(written.parcelId()).reference(), a.intersectionM2(), a.side(written.parcelId()).share())).toList();
    }

    /** Every alert of a parcel; empty for an unknown reference (the endpoint checks access first). */
    @Transactional(readOnly = true)
    public List<ConflictAlert> ofParcel(String reference) {
        return alerts.forParcel(reference);
    }

    /** One page of the alerts involving a parcel of this office, newest first. */
    @Transactional(readOnly = true)
    public List<ConflictAlert> ofOffice(String officeCode, ConflictAlert.Status status, ConflictAlert.Type type, int page) {
        if (page < 0 || page > 10_000) {
            throw new ConflictRefused(ConflictRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return alerts.forOffice(officeCode, status, type, PAGE_SIZE, page * PAGE_SIZE);
    }

    /** The offices of an alert's two parcels, for the endpoint's permission check; empty when unknown. */
    @Transactional(readOnly = true)
    public List<String> officesOf(UUID id) {
        return alerts.find(id).map(ConflictAlert::offices).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public Review review(UUID id) {
        ConflictAlert alert = alerts.find(id)
                .orElseThrow(() -> new ConflictRefused(ConflictRefused.Kind.NOT_FOUND, "ALERT_NOT_FOUND"));
        return new Review(alert, alerts.display(id));
    }

    /**
     * Resolves an open alert. Refusals are audited (in their own transaction) with their code;
     * a resolution is audited with the outcome, both parcels and versions, and the note.
     */
    @Transactional
    public ConflictAlert resolve(AuthenticatedUser resolver, UUID id, Resolve request) {
        ConflictAlert alert = alerts.find(id)
                .orElseThrow(() -> new ConflictRefused(ConflictRefused.Kind.NOT_FOUND, "ALERT_NOT_FOUND"));
        List<String> problems = ConflictAlert.problems(request.resolution(), request.note());
        if (!problems.isEmpty()) {
            throw refused(alert, ConflictRefused.Kind.INVALID, problems.getFirst());
        }
        if (alert.status() != ConflictAlert.Status.OPEN) {
            throw refused(alert, ConflictRefused.Kind.CONFLICT, "ALERT_NOT_OPEN");
        }
        if (alert.version() != request.expectedVersion()) {
            throw refused(alert, ConflictRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        if (alert.authoredBy(resolver.userId())) {
            throw refused(alert, ConflictRefused.Kind.FORBIDDEN, "RESOLVER_IS_AUTHOR");
        }
        String note = request.note().strip();
        boolean resolved;
        try {
            resolved = alerts.resolve(id, request.expectedVersion(), request.resolution(), note, resolver.userId());
        } catch (DataAccessException e) {
            // The guard (V12) disagreed with the checks above: a change in between, or a permission gone.
            String state = sqlState(e);
            String code = switch (state) {
                case "40001" -> "STALE_VERSION";
                case "23001" -> "ALERT_NOT_OPEN";
                case "42501" -> "RESOLUTION_REFUSED";
                default -> null;
            };
            if (code == null) {
                throw e;
            }
            throw refused(alert, "42501".equals(state) ? ConflictRefused.Kind.FORBIDDEN : ConflictRefused.Kind.CONFLICT, code);
        }
        if (!resolved) {
            throw refused(alert, ConflictRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        Map<String, Object> facts = pair(alert);
        facts.put("resolution", request.resolution().name());
        facts.put("noteLength", note.length());
        audit.record(AuditEvent.of(AuditAction.CONFLICT_ALERT_RESOLVED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, alert.id()).withEntityRef(alert.a().reference())
                .withReason(note.length() > 1000 ? note.substring(0, 999) + "…" : note)
                .withDetails(facts));
        return alerts.find(id).orElseThrow();
    }

    /** Audits a refused resolution in its own transaction (this one rolls back), then returns the refusal. */
    private ConflictRefused refused(ConflictAlert alert, ConflictRefused.Kind kind, String code) {
        Map<String, Object> facts = pair(alert);
        facts.put("code", code);
        audit.recordIndependently(AuditEvent.of(AuditAction.CONFLICT_ALERT_RESOLVE_REJECTED, AuditOutcome.FAILED)
                .withEntity(ENTITY, alert.id()).withEntityRef(alert.a().reference()).withReason(code).withDetails(facts));
        return new ConflictRefused(kind, code);
    }

    /** Both compared parcels and versions, as the audit trail keeps them. */
    private static Map<String, Object> pair(ConflictAlert alert) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("type", alert.type().name());
        facts.put("status", alert.status().name());
        facts.put("parcelA", alert.a().reference());
        facts.put("versionNoA", alert.a().versionNo());
        facts.put("parcelB", alert.b().reference());
        facts.put("versionNoB", alert.b().versionNo());
        return facts;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    /** What the audit trail keeps of an alert: never the geometry, only the facts. */
    private static Map<String, Object> facts(ConflictAlert alert, VersionWritten written) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("type", alert.type().name());
        facts.put("office", written.officeCode());
        facts.put("versionNo", written.versionNo());
        facts.put("otherParcel", alert.other(written.parcelId()).reference());
        facts.put("otherVersionNo", alert.other(written.parcelId()).versionNo());
        if (alert.intersectionM2() != null) {
            facts.put("intersectionM2", alert.intersectionM2());
            facts.put("shareOfThisParcel", alert.side(written.parcelId()).share());
            facts.put("shareOfOtherParcel", alert.other(written.parcelId()).share());
        }
        if (alert.normalizedReference() != null) {
            facts.put("normalizedReference", alert.normalizedReference());
        }
        return facts;
    }
}
