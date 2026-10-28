// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.application.PaymentLookup;
import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationState;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.application.domain.CompletenessRecord;
import org.snigf.mvp.application.domain.Requirements;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submitted applications as staff of their office handle them (MVP-03, E-14, ADR-034): the
 * office's queue, one case with its automatic check, and the intake officer's two outcomes.
 * <ul>
 *   <li>Accept: refused while anything required is missing (409 {@code ITEMS_MISSING}, every
 *       item listed), and while the DEMO fee is unpaid with the payment gate on (409
 *       {@code PAYMENT_REQUIRED}, ADR-037); the case goes to review.</li>
 *   <li>Request information: the document categories wanted and a message; the applicant may
 *       then add files in those categories only.</li>
 * </ul>
 * Nobody handles their own application. The service refuses first, with a precise code; the
 * database (V15) checks the same rules again, and computes what is missing itself. Every
 * outcome and every refusal is audited.
 */
@Service
public class CaseService {

    static final String ENTITY = "APPLICATION";

    private final ApplicationRepository applications;
    private final DatabaseActor actor;
    private final AuditRecorder audit;
    private final ApplicationProperties settings;
    private final ObjectProvider<PaymentLookup> payments;

    public CaseService(ApplicationRepository applications, DatabaseActor actor, AuditRecorder audit,
                       ApplicationProperties settings, ObjectProvider<PaymentLookup> payments) {
        this.applications = applications;
        this.actor = actor;
        this.audit = audit;
        this.settings = settings;
        this.payments = payments;
    }

    /**
     * A case: the application, its applicant, its type version, history, completeness, what is
     * missing now, and where its DEMO fee stands (null when the payment module is absent).
     */
    public record CaseView(ApplicationRepository.Case found, ApplicationType type, List<Application.Transition> history,
                           List<CompletenessRecord> completeness, List<String> missing,
                           PaymentLookup.PaymentSummary payment) {
    }

    @Transactional(readOnly = true)
    public List<ApplicationRepository.Case> queue(AuthenticatedUser staff, String officeCode, ApplicationState state, int page) {
        actor.bind(staff.userId());
        if (page < 0 || page > 10_000) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        if (state == ApplicationState.DRAFT) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "STATE_NOT_LISTED");
        }
        return applications.ofOffice(officeCode, state, settings.pageSize(), page * settings.pageSize());
    }

    @Transactional(readOnly = true)
    public CaseView read(AuthenticatedUser staff, UUID id) {
        actor.bind(staff.userId());
        ApplicationRepository.Case found = visible(id);
        PaymentLookup lookup = payments.getIfAvailable();
        return new CaseView(found, applications.type(found.application().typeVersionId()).orElseThrow(),
                applications.transitions(id), applications.completeness(id), applications.missingItems(id),
                lookup == null ? null : lookup.of(id));
    }

    /** The office of a case, for the endpoints' permission checks; null when unknown or a draft. */
    @Transactional(readOnly = true)
    public String officeOf(UUID id) {
        return applications.officeCode(id).orElse(null);
    }

    @Transactional
    public CaseView accept(AuthenticatedUser officer, UUID id, long expectedVersion) {
        actor.bind(officer.userId());
        Application application = handled(officer, id, expectedVersion);
        List<String> missing = applications.missingItems(id);
        if (!missing.isEmpty()) {
            throw refused(application, "ITEMS_MISSING", ApplicationRefused.Kind.CONFLICT, missing);
        }
        PaymentLookup lookup = payments.getIfAvailable();
        if (lookup != null && lookup.of(id).blocksAcceptance()) {
            throw refused(application, "PAYMENT_REQUIRED", ApplicationRefused.Kind.CONFLICT, List.of());
        }
        guarded(application, () -> {
            applications.record(id, CompletenessRecord.Kind.COMPLETE, null, null, officer.userId());
            return applications.move(id, expectedVersion, ApplicationState.COMPLETENESS_REVIEW, ApplicationState.UNDER_REVIEW,
                    officer.userId(), null);
        });
        audit.record(AuditEvent.of(AuditAction.COMPLETENESS_ACCEPTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(application.reference())
                .withDetails(Map.of("office", application.officeCode())));
        return read(officer, id);
    }

    @Transactional
    public CaseView requestInformation(AuthenticatedUser officer, UUID id, long expectedVersion, List<String> categories,
                                       String message) {
        actor.bind(officer.userId());
        Application application = handled(officer, id, expectedVersion);
        Requirements requirements = applications.type(application.typeVersionId()).orElseThrow().requirements();
        Set<String> asked = new LinkedHashSet<>(categories == null ? List.of() : categories);
        if (asked.isEmpty()) {
            throw refused(application, "CATEGORIES_REQUIRED", ApplicationRefused.Kind.INVALID, List.of());
        }
        List<String> unknown = asked.stream()
                .filter(c -> requirements.documents().stream().noneMatch(d -> d.code().equals(c))).toList();
        if (!unknown.isEmpty()) {
            throw refused(application, "CATEGORY_UNKNOWN", ApplicationRefused.Kind.INVALID, unknown);
        }
        String text = message == null ? "" : message.strip();
        if (text.length() < CompletenessRecord.MESSAGE_MIN || text.length() > CompletenessRecord.MESSAGE_MAX) {
            throw refused(application, "MESSAGE_REQUIRED", ApplicationRefused.Kind.INVALID, List.of());
        }
        guarded(application, () -> {
            applications.record(id, CompletenessRecord.Kind.INFO_REQUESTED, List.copyOf(asked), text, officer.userId());
            return applications.move(id, expectedVersion, ApplicationState.COMPLETENESS_REVIEW,
                    ApplicationState.INFO_REQUESTED, officer.userId(), text);
        });
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("office", application.officeCode());
        facts.put("categories", List.copyOf(asked));
        audit.record(AuditEvent.of(AuditAction.COMPLETENESS_INFO_REQUESTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(application.reference()).withDetails(facts));
        return read(officer, id);
    }

    /** A case the officer may decide on now: visible, theirs to handle, in completeness review, unchanged. */
    private Application handled(AuthenticatedUser officer, UUID id, long expectedVersion) {
        Application application = visible(id).application();
        if (application.ownedBy(officer.userId())) {
            throw refused(application, "APPLICANT_CANNOT_REVIEW", ApplicationRefused.Kind.FORBIDDEN, List.of());
        }
        if (application.state() != ApplicationState.COMPLETENESS_REVIEW) {
            throw refused(application, "INVALID_TRANSITION", ApplicationRefused.Kind.CONFLICT, List.of());
        }
        if (application.version() != expectedVersion) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        return application;
    }

    private ApplicationRepository.Case visible(UUID id) {
        return applications.findCase(id)
                .orElseThrow(() -> new ApplicationRefused(ApplicationRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    /** Runs the record-and-move; the database's disagreement becomes a precise refusal. */
    private void guarded(Application application, java.util.function.BooleanSupplier step) {
        boolean moved;
        try {
            moved = step.getAsBoolean();
        } catch (DataAccessException e) {
            String state = sqlState(e);
            switch (state) {
                case "23514" -> throw refused(application, "ITEMS_MISSING", ApplicationRefused.Kind.CONFLICT,
                        List.of());
                case "42501" -> throw refused(application, "COMPLETENESS_REFUSED", ApplicationRefused.Kind.FORBIDDEN,
                        List.of());
                case "40001" -> throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
                // The payment gate (V18) disagreed: the fee was not paid after all.
                case "55000" -> throw refused(application, "PAYMENT_REQUIRED", ApplicationRefused.Kind.CONFLICT,
                        List.of());
                case "23001" -> throw refused(application, "INVALID_TRANSITION", ApplicationRefused.Kind.CONFLICT,
                        List.of());
                default -> throw e;
            }
        }
        if (!moved) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
    }

    private ApplicationRefused refused(Application application, String code, ApplicationRefused.Kind kind, List<String> items) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("code", code);
        facts.put("state", application.state().name());
        if (!items.isEmpty()) {
            facts.put("items", items);
        }
        audit.recordIndependently(AuditEvent.of(AuditAction.COMPLETENESS_REJECTED, AuditOutcome.FAILED)
                .withEntity(ENTITY, application.id()).withEntityRef(application.reference()).withReason(code)
                .withDetails(facts));
        return new ApplicationRefused(kind, code, items);
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
