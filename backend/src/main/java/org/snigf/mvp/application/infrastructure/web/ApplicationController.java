// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.application.application.ApplicationService;
import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.application.domain.CompletenessRecord;
import org.snigf.mvp.application.domain.Requirements;
import org.snigf.mvp.application.domain.Tracking;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The applicant's applications (MVP-02, E-12). Each endpoint needs its permission for the
 * caller's own records ({@code application.create#own} ...); the service works only on the
 * caller's applications and answers 404 for any other id. Creation and submission need an
 * {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1")
class ApplicationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ApplicationService applications;

    ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    record CreateBody(@NotNull @Size(max = 48) String typeCode, @Size(max = 40) String parcelReference,
                      @Size(max = Requirements.MAX_FIELDS) Map<String, String> data) {
    }

    /** {@code declaration}: the persons, roles, shares and mandates (ADR-043), for a type that registers a title. */
    record UpdateBody(long expectedVersion, @Size(max = 40) String parcelReference,
                      @Size(max = Requirements.MAX_FIELDS) Map<String, String> data, tools.jackson.databind.JsonNode declaration) {
    }

    record SubmitBody(long expectedVersion) {
    }

    record RespondBody(long expectedVersion, @Size(max = 4000) String message) {
    }

    /** One step of the completeness review, as the applicant and staff see it. */
    record CompletenessResponse(String kind, List<String> missing, List<String> requested, String message,
                                String actorName, Instant at, List<ReplacementResponse> replacements) {
    }

    /** A document an information request asks to be replaced, by its category, and why (ADR-041 D19). */
    record ReplacementResponse(String category, String reason) {

        static List<ReplacementResponse> of(List<CompletenessRecord.Replacement> replacements) {
            return replacements.stream().map(r -> new ReplacementResponse(r.category(), r.reason())).toList();
        }
    }

    record TypeResponse(String code, int versionNo, String nameFr, String nameEn, Requirements requirements,
                        Instant effectiveFrom) {
    }

    record SummaryResponse(UUID id, String reference, String typeCode, String state, String parcelReference,
                           String officeCode, Instant updatedAt, Instant submittedAt, long version) {
    }

    /** One state change as staff see it: who, and the reason recorded with it. */
    record TransitionResponse(String from, String to, String actorName, String reason, Instant at) {
    }

    /** One state change as the applicant sees it: {@code by} is APPLICANT, OFFICE or SYSTEM, never a person. */
    record StepResponse(String from, String to, String by, Instant at) {
    }

    /** One step of the completeness review as the applicant sees it: no person named. */
    record ApplicantCompletenessResponse(String kind, List<String> missing, List<String> requested, String message,
                                         String by, Instant at, List<ReplacementResponse> replacements) {
    }

    record PendingRequestResponse(List<String> categories, String message, Instant since, List<ReplacementResponse> replacements) {
    }

    /**
     * The applicant's application (MVP-06): nothing internal. Its fields are pinned by
     * {@code ApplicantPayloadSchema}; a new one is added there knowingly, or the build fails.
     *
     * @param since     when it entered its current state, from the history
     * @param waitingOn APPLICANT or OFFICE; null once it has ended
     */
    record ApplicationResponse(UUID id, String reference, TypeResponse type, String state, String parcelReference,
                               String officeCode, Map<String, String> data, Instant createdAt, Instant updatedAt,
                               Instant submittedAt, long version, Instant since, String waitingOn,
                               PendingRequestResponse pendingRequest, List<StepResponse> history,
                               List<ApplicantCompletenessResponse> completeness, DecisionResponse decision,
                               List<StageProgressResponse> stages, List<InstructionResponse> procedureInstructions,
                               RegistrationResponse registration, tools.jackson.databind.JsonNode declaration,
                               List<String> declarationProblems, List<PartyResponse> parties,
                               List<ReconsiderationResponse> reconsiderations) {
    }

    /**
     * The case is reconsidered (ADR-043 step 5b): the round, the stages carried out again, since when, and whether the new
     * decision is made. Never why, nor the objections or who opened it.
     */
    record ReconsiderationResponse(int round, List<String> stages, Instant openedAt, boolean decided) {
    }

    /**
     * A person of the case (ADR-043) as its applicant reads them: never the officer, the documents or a check's note.
     * {@code verification} is always {@code UNVERIFIED_DEMO}: a {@code MATCHES_DOCUMENT} status says the entry corresponds to
     * the document presented, never that the identity was authenticated.
     */
    record PartyResponse(String ref, List<String> roles, String fullName, String status, String verification) {
    }

    /**
     * "Registered: DEMO-TF-…" (ADR-042 §4) or "Transfer registered" (step 6): the title's number, when, and whether the case
     * created the title or transferred it ({@code kind}); null until registered. Never a title document nor the holders.
     */
    record RegistrationResponse(String titleNumber, Instant registeredAt, String kind) {
    }

    /** What the office asks to carry out again (ADR-042, decision 5): stages, the applicant's action, never the reason. */
    record InstructionResponse(List<String> stages, String action, Instant at, boolean pending) {
    }

    /** A stage of the case's procedure (ADR-042): its status and date, never who or why. */
    record StageProgressResponse(String stage, String status, Instant at) {
    }

    /** The decision as the applicant reads it: simulated, no legal effect (MVP-05). */
    record DecisionResponse(String outcome, String grounds, boolean simulated, Instant decidedAt) {
    }

    @GetMapping("/application-types")
    @PreAuthorize("@access.canOwn(authentication, 'application.create')")
    List<TypeResponse> types() {
        return applications.types().stream().map(ApplicationController::type).toList();
    }

    @GetMapping("/applications")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    List<SummaryResponse> mine(@AuthenticationPrincipal AuthenticatedUser applicant,
                               @RequestParam(defaultValue = "0") int page) {
        return applications.mine(applicant, page).stream()
                .map(a -> new SummaryResponse(a.id(), a.reference(), a.typeCode(), a.state().name(), a.parcelReference(),
                        a.officeCode(), a.updatedAt(), a.submittedAt(), a.version()))
                .toList();
    }

    @PostMapping("/applications")
    @PreAuthorize("@access.canOwn(authentication, 'application.create')")
    ResponseEntity<ApplicationService.Written> create(@AuthenticationPrincipal AuthenticatedUser applicant,
                                                      @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                      @Valid @RequestBody CreateBody body) {
        ApplicationService.Outcome outcome = applications.create(applicant, body.typeCode(),
                new ApplicationService.Form(body.parcelReference(), body.data()), key);
        return ResponseEntity.created(URI.create("/api/v1/applications/" + outcome.written().id()))
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .body(outcome.written());
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    ApplicationResponse read(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id) {
        return response(applications.read(applicant, id));
    }

    @PutMapping("/applications/{id}")
    @PreAuthorize("@access.canOwn(authentication, 'application.create')")
    ApplicationResponse update(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id,
                               @Valid @RequestBody UpdateBody body) {
        return response(applications.update(applicant, id, body.expectedVersion(),
                new ApplicationService.Form(body.parcelReference(), body.data(),
                        body.declaration() == null || body.declaration().isNull() ? null : body.declaration().toString())));
    }

    @DeleteMapping("/applications/{id}")
    @PreAuthorize("@access.canOwn(authentication, 'application.create')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id,
                                @RequestParam long expectedVersion) {
        applications.delete(applicant, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/applications/{id}/submission")
    @PreAuthorize("@access.canOwn(authentication, 'application.submit')")
    ResponseEntity<ApplicationService.Written> submit(@AuthenticationPrincipal AuthenticatedUser applicant,
                                                      @PathVariable UUID id,
                                                      @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                      @Valid @RequestBody SubmitBody body) {
        ApplicationService.Outcome outcome = applications.submit(applicant, id, body.expectedVersion(), key);
        return ResponseEntity.ok().header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .body(outcome.written());
    }

    @PostMapping("/applications/{id}/response")
    @PreAuthorize("@access.canOwn(authentication, 'application.respond')")
    ApplicationResponse respond(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id,
                                @Valid @RequestBody RespondBody body) {
        return response(applications.respond(applicant, id, body.expectedVersion(), body.message()));
    }

    static TypeResponse type(ApplicationType t) {
        return new TypeResponse(t.code(), t.versionNo(), t.nameFr(), t.nameEn(), t.requirements(), t.effectiveFrom());
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static ApplicationResponse response(ApplicationService.View view) {
        Application a = view.application();
        Tracking t = view.tracking();
        return new ApplicationResponse(a.id(), a.reference(), type(view.type()), a.state().name(), a.parcelReference(),
                a.officeCode(), a.data(), a.createdAt(), a.updatedAt(), a.submittedAt(), a.version(), t.since(),
                t.waitingOn() == null ? null : t.waitingOn().name(),
                t.pendingRequest() == null ? null
                        : new PendingRequestResponse(t.pendingRequest().categories(), t.pendingRequest().message(),
                                t.pendingRequest().since(), ReplacementResponse.of(t.pendingRequest().replacements())),
                t.history().stream().map(s -> new StepResponse(s.from().name(), s.to().name(), s.by().name(), s.at())).toList(),
                t.completeness().stream().map(c -> new ApplicantCompletenessResponse(c.kind().name(), c.missing(),
                        c.requested(), c.message(), c.by().name(), c.at(), ReplacementResponse.of(c.replacements()))).toList(),
                view.decision().map(d -> new DecisionResponse(d.outcome(), d.grounds(), d.simulated(), d.decidedAt()))
                        .orElse(null),
                view.stages().stream().map(s -> new StageProgressResponse(s.stage(), s.status(), s.at())).toList(),
                view.instructions().stream().map(i -> new InstructionResponse(i.stages(), i.action(), i.at(), i.pending())).toList(),
                view.registration().map(r -> new RegistrationResponse(r.titleNumber(), r.registeredAt(), r.kind())).orElse(null),
                a.declaration() == null ? null : JSON.readTree(a.declaration()), view.declarationProblems(),
                view.parties().stream().map(p -> new PartyResponse(p.ref(), p.roles(), p.fullName(), p.status(), "UNVERIFIED_DEMO")).toList(),
                view.reconsiderations().stream().map(r -> new ReconsiderationResponse(r.round(), r.stages(), r.openedAt(), r.decided())).toList());
    }

    static TransitionResponse transition(Application.Transition t) {
        return new TransitionResponse(t.from().name(), t.to().name(), t.actorName(), t.reason(), t.at());
    }

    static CompletenessResponse completeness(CompletenessRecord r) {
        return new CompletenessResponse(r.kind().name(), r.missing(), r.requested(), r.message(), r.actorName(), r.at(),
                ReplacementResponse.of(r.replacements()));
    }
}
