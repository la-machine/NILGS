// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.web;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.procedure.application.ObjectionService;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public notices of procedures (ADR-042 §3, step 4): the notices whose objection period is open, for anyone,
 * throttled per address. Each says only what public notification needs: the notice's number, the procedure, the
 * parcel's reference and locality, the office where an objection is received, and the period. Never the applicant,
 * the case's reference, a document or an objection. DEMO publication, no legal effect.
 */
@RestController
@RequestMapping("/api/v1/public/notices")
class PublicNoticeController {

    private final ObjectionService objections;
    private final NoticeRateLimiter limiter;

    PublicNoticeController(ObjectionService objections, NoticeRateLimiter limiter) {
        this.objections = objections;
        this.limiter = limiter;
    }

    record Office(String code, String nameFr, String nameEn) {
    }

    record NoticeResponse(String number, String procedure, String parcelReference, String locality, Office office, Instant opensAt,
                          Instant closesAt) {
    }

    /** {@code demo} is always true: these notices have no legal effect. */
    record NoticesResponse(boolean demo, List<NoticeResponse> notices) {
    }

    @GetMapping
    @PublicEndpoint("anyone may read the notices whose objection period is open, to object at the office's counter; throttled, minimal (ADR-042)")
    NoticesResponse list(HttpServletRequest request, @RequestParam(defaultValue = "0") int page) {
        limiter.check(request);
        return new NoticesResponse(true, objections.publicNotices(page).stream().map(n -> new NoticeResponse(n.number(), n.procedure(),
                n.parcelReference(), n.locality(), new Office(n.officeCode(), n.officeNameFr(), n.officeNameEn()), n.opensAt(), n.closesAt()))
                .toList());
    }
}
