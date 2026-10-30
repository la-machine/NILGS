// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.snigf.mvp.application.ApplicationSubmitted;
import org.snigf.mvp.application.DecisionLookup;
import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationReference;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationState;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.application.domain.CompletenessRecord;
import org.snigf.mvp.application.domain.Tracking;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.parcel.ParcelDirectory;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.snigf.mvp.shared.id.CheckCharacter;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The applicant's applications (MVP-02, E-12). Rules:
 * <ul>
 *   <li>Only the owner sees or changes an application; anyone else gets 404, never 403, so
 *       nothing tells them it exists. Every use case names its actor to the database first,
 *       whose row-level security applies the same rule (V13).</li>
 *   <li>A draft follows the version of its type in force when last saved; a parcel is optional
 *       at draft and required at submission, and must then be active. The office is the
 *       parcel's.</li>
 *   <li>Submission needs an {@code Idempotency-Key}: a retry gets the first answer and creates
 *       nothing. The reference is issued then, not before. An incomplete draft stays a draft:
 *       422 with every missing item.</li>
 *   <li>Every change is audited; refusals of a submission too, in their own transaction.</li>
 * </ul>
 * Payment requests (MVP-08) and the completeness check (MVP-03) join the submission in later
 * slices (E-14, E-17).
 */
@Service
public class ApplicationService {

    static final String ENTITY = "APPLICATION";
    static final String CREATE_ENDPOINT = "POST /api/v1/applications";
    static final String SUBMIT_ENDPOINT = "POST /api/v1/applications/{id}/submission";
    private static final Pattern PARCEL_REFERENCE = Pattern.compile("^DEMO-PCL-[A-Z0-9]{2,16}-[0-9]{4}-[0-9]{6}-[0-9A-Z]$");

    private final ApplicationRepository applications;
    private final ParcelDirectory parcels;
    private final DatabaseActor actor;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final ApplicationProperties settings;
    private final Clock clock;
    private final ObjectProvider<DecisionLookup> decisions;
    private final ApplicationEventPublisher events;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApplicationService(ApplicationRepository applications, ParcelDirectory parcels, DatabaseActor actor,
                              IdempotencyStore idempotency, AuditRecorder audit, ApplicationProperties settings,
                              Clock clock, ObjectProvider<DecisionLookup> decisions, ApplicationEventPublisher events) {
        this.applications = applications;
        this.parcels = parcels;
        this.actor = actor;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.decisions = decisions;
        this.events = events;
    }

    /** The form as the applicant sends it: every value is text. */
    public record Form(String parcelReference, Map<String, String> data) {
    }

    /** The answer to a creation or a submission; stored for idempotent retries. */
    public record Written(UUID id, String reference, String state, long version) {
    }

    public record Outcome(Written written, boolean replayed) {
    }

    /**
     * An application as its applicant follows it: the type version it follows, its tracking
     * (history and completeness review without staff names or internal reasons, MVP-06) and the
     * decision.
     */
    public record View(Application application, ApplicationType type, Tracking tracking,
                       Optional<DecisionLookup.DecisionSummary> decision) {
    }

    @Transactional(readOnly = true)
    public List<ApplicationType> types() {
        return applications.availableTypes();
    }

    @Transactional
    public Outcome create(AuthenticatedUser applicant, String typeCode, Form form, String idempotencyKey) {
        actor.bind(applicant.userId());
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        String requestHash = IdempotencyStore.sha256(json.writeValueAsString(Map.of("type", String.valueOf(typeCode),
                "form", normalised(form))));
        Optional<Outcome> replay = replay(applicant, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ApplicationType type = currentType(typeCode);
        if (applications.draftCount(applicant.userId()) >= settings.maxDrafts()) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "DRAFT_LIMIT_REACHED");
        }
        Form clean = normalised(form);
        requireValid(type, clean);
        UUID id = applications.createDraft(applicant.userId(), type.code(), type.id(), clean.parcelReference(), clean.data());
        audit.record(AuditEvent.of(AuditAction.APPLICATION_DRAFT_CREATED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withDetails(Map.of("type", type.code(), "typeVersion", type.versionNo())));
        Written written = new Written(id, null, ApplicationState.DRAFT.name(), 0);
        save(applicant, idempotencyKey, CREATE_ENDPOINT, requestHash, 201, written);
        return new Outcome(written, false);
    }

    @Transactional(readOnly = true)
    public List<Application> mine(AuthenticatedUser applicant, int page) {
        actor.bind(applicant.userId());
        if (page < 0 || page > 10_000) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return applications.ofApplicant(applicant.userId(), settings.pageSize(), page * settings.pageSize());
    }

    @Transactional(readOnly = true)
    public View read(AuthenticatedUser applicant, UUID id) {
        actor.bind(applicant.userId());
        Application application = owned(applicant, id);
        ApplicationType type = applications.type(application.typeVersionId()).orElseThrow();
        Optional<DecisionLookup.DecisionSummary> decision = Optional.ofNullable(decisions.getIfAvailable())
                .flatMap(lookup -> lookup.of(id));
        return new View(application, type, Tracking.of(application, applications.transitions(id), applications.completeness(id)),
                decision);
    }

    /** Replaces a draft's form, under the type version now in force. */
    @Transactional
    public View update(AuthenticatedUser applicant, UUID id, long expectedVersion, Form form) {
        actor.bind(applicant.userId());
        Application draft = owned(applicant, id);
        requireDraft(draft, expectedVersion);
        ApplicationType type = currentType(draft.typeCode());
        Form clean = normalised(form);
        requireValid(type, clean);
        if (!applications.updateDraft(id, expectedVersion, type.id(), clean.parcelReference(), clean.data())) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        audit.record(AuditEvent.of(AuditAction.APPLICATION_DRAFT_UPDATED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withDetails(Map.of("typeVersion", type.versionNo(),
                        "fields", List.copyOf(clean.data().keySet()), "parcelNamed", clean.parcelReference() != null)));
        return read(applicant, id);
    }

    @Transactional
    public void delete(AuthenticatedUser applicant, UUID id, long expectedVersion) {
        actor.bind(applicant.userId());
        Application draft = owned(applicant, id);
        requireDraft(draft, expectedVersion);
        if (!applications.deleteDraft(id, expectedVersion)) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        audit.record(AuditEvent.of(AuditAction.APPLICATION_DRAFT_DELETED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withDetails(Map.of("type", draft.typeCode())));
    }

    /**
     * Submits a complete draft: the reference is issued, the parcel resolved, the office set, the
     * state recorded in the history (by the database). Idempotent per key.
     */
    @Transactional
    public Outcome submit(AuthenticatedUser applicant, UUID id, long expectedVersion, String idempotencyKey) {
        actor.bind(applicant.userId());
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        String requestHash = IdempotencyStore.sha256(id + ":" + expectedVersion);
        Optional<Outcome> replay = replay(applicant, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Application draft = owned(applicant, id);
        if (draft.state() != ApplicationState.DRAFT) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "INVALID_TRANSITION");
        }
        if (draft.version() != expectedVersion) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        ApplicationType type = currentType(draft.typeCode());
        List<String> missing = new ArrayList<>(type.requirements().check(draft.data(), true));
        Optional<ParcelDirectory.ActiveParcel> parcel = Optional.empty();
        if (draft.parcelReference() == null) {
            missing.add("MISSING:parcelReference");
        } else {
            parcel = parcels.active(draft.parcelReference());
            if (parcel.isEmpty()) {
                missing.add("PARCEL_NOT_FOUND");
            }
        }
        if (!missing.isEmpty()) {
            throw incomplete(draft, missing);
        }
        int year = LocalDate.now(clock.withZone(settings.referenceZone())).getYear();
        ApplicationReference reference = ApplicationReference.issue(year, applications.nextSequence(year));
        ParcelDirectory.ActiveParcel linked = parcel.get();
        boolean submitted;
        try {
            submitted = applications.submit(id, expectedVersion, new ApplicationRepository.Submission(type.id(),
                    linked.id(), linked.reference(), linked.officeId(), reference, applicant.userId()));
        } catch (DataAccessException e) {
            // The guard (V13) disagreed: the parcel changed in between, or the draft did.
            if ("23514".equals(sqlState(e))) {
                throw incomplete(draft, List.of("PARCEL_NOT_FOUND"));
            }
            if ("40001".equals(sqlState(e))) {
                throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
            }
            throw e;
        }
        if (!submitted) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        // The automatic completeness check (MVP-03): recorded by the database, then the system
        // moves the application to completeness review, with no person named.
        applications.record(id, CompletenessRecord.Kind.AUTOMATIC, null, null, null);
        applications.move(id, expectedVersion + 1, ApplicationState.SUBMITTED, ApplicationState.COMPLETENESS_REVIEW,
                null, null);
        List<String> stillMissing = applications.missingItems(id);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("type", type.code());
        facts.put("typeVersion", type.versionNo());
        facts.put("office", linked.officeCode());
        facts.put("parcel", linked.reference());
        facts.put("missing", stillMissing);
        audit.record(AuditEvent.of(AuditAction.APPLICATION_SUBMITTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(reference.value()).withDetails(facts));
        Written written = new Written(id, reference.value(), ApplicationState.COMPLETENESS_REVIEW.name(), expectedVersion + 2);
        save(applicant, idempotencyKey, SUBMIT_ENDPOINT, requestHash, 200, written);
        // The acknowledgment (ADR-039) is issued after the commit, by whoever listens.
        events.publishEvent(new ApplicationSubmitted(id, applicant.userId()));
        return new Outcome(written, false);
    }

    /**
     * The applicant's response to an information request (MVP-03): the files they added are
     * already new versions; the response records an optional message, the automatic check runs
     * again, and the application goes back to completeness review.
     */
    @Transactional
    public View respond(AuthenticatedUser applicant, UUID id, long expectedVersion, String message) {
        actor.bind(applicant.userId());
        Application application = owned(applicant, id);
        if (application.state() != ApplicationState.INFO_REQUESTED) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "INVALID_TRANSITION");
        }
        if (application.version() != expectedVersion) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        String text = message == null || message.isBlank() ? null : message.strip();
        if (text != null && text.length() > CompletenessRecord.MESSAGE_MAX) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "MESSAGE_TOO_LONG");
        }
        applications.record(id, CompletenessRecord.Kind.RESPONSE, null, text, applicant.userId());
        applications.record(id, CompletenessRecord.Kind.AUTOMATIC, null, null, null);
        if (!applications.move(id, expectedVersion, ApplicationState.INFO_REQUESTED, ApplicationState.COMPLETENESS_REVIEW,
                applicant.userId(), "RESPONSE")) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("withMessage", text != null);
        facts.put("missing", applications.missingItems(id));
        audit.record(AuditEvent.of(AuditAction.COMPLETENESS_RESPONSE_SENT, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(application.reference()).withDetails(facts));
        return read(applicant, id);
    }

    /** Whether a parcel reference is well formed with the right check character (no lookup). */
    public static boolean parcelReferenceWellFormed(String reference) {
        return reference != null && PARCEL_REFERENCE.matcher(reference).matches() && CheckCharacter.matches(reference);
    }

    private ApplicationType currentType(String code) {
        return applications.latestType(code == null ? "" : code).filter(ApplicationType::available)
                .orElseThrow(() -> new ApplicationRefused(ApplicationRefused.Kind.INVALID, "TYPE_NOT_AVAILABLE"));
    }

    /**
     * The actor's application. Anything else (unknown, or someone else's, which row-level
     * security already hides) is "not found", and the attempt is recorded.
     */
    private Application owned(AuthenticatedUser applicant, UUID id) {
        Optional<Application> found = applications.find(id).filter(a -> a.ownedBy(applicant.userId()));
        if (found.isEmpty()) {
            audit.recordIndependently(AuditEvent.of(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .withEntity(ENTITY, id).withReason("APPLICATION_NOT_FOUND"));
            throw new ApplicationRefused(ApplicationRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND");
        }
        return found.get();
    }

    private static void requireDraft(Application application, long expectedVersion) {
        if (application.state() != ApplicationState.DRAFT) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "NOT_A_DRAFT");
        }
        if (application.version() != expectedVersion) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "STALE_VERSION");
        }
    }

    private static void requireValid(ApplicationType type, Form form) {
        List<String> problems = new ArrayList<>(type.requirements().check(form.data(), false));
        if (form.parcelReference() != null && !parcelReferenceWellFormed(form.parcelReference())) {
            problems.add("PARCEL_REFERENCE_INVALID");
        }
        if (!problems.isEmpty()) {
            throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "FORM_INVALID", problems);
        }
    }

    /** Values stripped, empty ones dropped, the parcel reference upper-cased. */
    private static Form normalised(Form form) {
        Map<String, String> data = new LinkedHashMap<>();
        if (form != null && form.data() != null) {
            form.data().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    data.put(k, v.strip());
                }
            });
        }
        String parcel = form == null || form.parcelReference() == null || form.parcelReference().isBlank()
                ? null : form.parcelReference().strip().toUpperCase(java.util.Locale.ROOT);
        return new Form(parcel, data);
    }

    private ApplicationRefused incomplete(Application draft, List<String> missing) {
        audit.recordIndependently(AuditEvent.of(AuditAction.APPLICATION_SUBMIT_REJECTED, AuditOutcome.FAILED)
                .withEntity(ENTITY, draft.id()).withReason("APPLICATION_INCOMPLETE")
                .withDetails(Map.of("items", missing, "type", draft.typeCode())));
        return new ApplicationRefused(ApplicationRefused.Kind.INVALID, "APPLICATION_INCOMPLETE", missing);
    }

    private Optional<Outcome> replay(AuthenticatedUser user, String key, String requestHash) {
        return idempotency.find(user.userId(), key).map(earlier -> {
            if (!earlier.requestSha256().equals(requestHash)) {
                throw new ApplicationRefused(ApplicationRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REUSED");
            }
            return new Outcome(json.readValue(earlier.body(), Written.class), true);
        });
    }

    private void save(AuthenticatedUser user, String key, String endpoint, String hash, int status, Written written) {
        try {
            idempotency.save(user.userId(), key, endpoint, hash, status, json.writeValueAsString(written));
        } catch (IdempotencyStore.InProgress e) {
            throw new ApplicationRefused(ApplicationRefused.Kind.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
        }
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
