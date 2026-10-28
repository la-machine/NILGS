// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.application.application.CaseService;
import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationState;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submitted applications for staff of their office (MVP-03, E-14): the queue
 * ({@code application.read} at the office), one case with its automatic check, and the intake
 * officer's outcomes ({@code completeness.review} to accept, {@code completeness.request-info}
 * to request information) at the office of the case ({@link CaseOffices}).
 */
@RestController
@RequestMapping("/api/v1/cases")
class CaseController {

    private final CaseService cases;

    CaseController(CaseService cases) {
        this.cases = cases;
    }

    record AcceptBody(long expectedVersion) {
    }

    record InformationRequestBody(long expectedVersion, @Size(max = 20) List<String> categories,
                                  @Size(max = 4000) String message) {
    }

    record CaseSummary(UUID id, String reference, String typeCode, String state, String parcelReference,
                       String officeCode, String applicantName, Instant submittedAt, Instant updatedAt, long version) {
    }

    /**
     * Where the DEMO fee stands (ADR-037).
     *
     * @param standing {@code NOT_REQUIRED}, {@code DUE}, {@code PENDING} or {@code PAID}
     */
    record PaymentResponse(String standing, String reference, Long amount, String currency, boolean blocksAcceptance) {
    }

    /**
     * @param missing what the automatic check finds missing now
     * @param payment null when the payment module is absent
     */
    record CaseResponse(UUID id, String reference, ApplicationController.TypeResponse type, String state,
                        String parcelReference, String officeCode, String applicantName, Map<String, String> data,
                        Instant submittedAt, Instant updatedAt, long version, List<String> missing,
                        List<ApplicationController.CompletenessResponse> completeness,
                        List<ApplicationController.TransitionResponse> history, PaymentResponse payment) {
    }

    @GetMapping
    @PreAuthorize("@access.canAt(authentication, 'application.read', #office)")
    List<CaseSummary> queue(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                            @RequestParam(required = false) ApplicationState state,
                            @RequestParam(defaultValue = "0") int page) {
        return cases.queue(staff, office, state, page).stream().map(CaseController::summary).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    CaseResponse read(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return response(cases.read(staff, id));
    }

    @PostMapping("/{id}/acceptance")
    @PreAuthorize("@access.canAt(authentication, 'completeness.review', @caseOffices.of(#id))")
    CaseResponse accept(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id,
                        @Valid @RequestBody AcceptBody body) {
        return response(cases.accept(officer, id, body.expectedVersion()));
    }

    @PostMapping("/{id}/information-request")
    @PreAuthorize("@access.canAt(authentication, 'completeness.request-info', @caseOffices.of(#id))")
    CaseResponse requestInformation(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id,
                                    @Valid @RequestBody InformationRequestBody body) {
        return response(cases.requestInformation(officer, id, body.expectedVersion(), body.categories(), body.message()));
    }

    private static CaseSummary summary(ApplicationRepository.Case c) {
        Application a = c.application();
        return new CaseSummary(a.id(), a.reference(), a.typeCode(), a.state().name(), a.parcelReference(), a.officeCode(),
                c.applicantName(), a.submittedAt(), a.updatedAt(), a.version());
    }

    static CaseResponse response(CaseService.CaseView view) {
        Application a = view.found().application();
        return new CaseResponse(a.id(), a.reference(), ApplicationController.type(view.type()), a.state().name(),
                a.parcelReference(), a.officeCode(), view.found().applicantName(), a.data(), a.submittedAt(),
                a.updatedAt(), a.version(), view.missing(),
                view.completeness().stream().map(ApplicationController::completeness).toList(),
                view.history().stream().map(ApplicationController::transition).toList(),
                view.payment() == null ? null : new PaymentResponse(view.payment().standing(), view.payment().reference(),
                        view.payment().amount(), view.payment().currency(), view.payment().blocksAcceptance()));
    }
}
