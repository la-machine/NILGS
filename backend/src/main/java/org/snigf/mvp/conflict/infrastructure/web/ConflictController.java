// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.conflict.application.ConflictService;
import org.snigf.mvp.conflict.domain.ConflictAlert;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The overlap and duplicate alerts of a parcel (MVP-04), for whoever reviews alerts at the
 * parcel's office ({@code alert.review}, cadastral officers). Each alert is shown from the
 * parcel's side: the other parcel, both compared versions, the intersection with both shares,
 * and the intersection geometry for the map. Review and resolution: {@link AlertController}.
 */
@RestController
@RequestMapping("/api/v1/parcels")
class ConflictController {

    private final ConflictService conflicts;
    private final JsonMapper json = JsonMapper.builder().build();

    ConflictController(ConflictService conflicts) {
        this.conflicts = conflicts;
    }

    record AlertResponse(UUID id, String type, String status, String otherReference, String otherOfficeCode,
                         int thisVersionNo, int otherVersionNo, BigDecimal intersectionM2, BigDecimal shareOfThisParcel,
                         BigDecimal shareOfOtherParcel, JsonNode intersection, String normalizedReference,
                         Instant raisedAt, String resolution, String resolutionNote, Instant resolvedAt,
                         Instant supersededAt) {
    }

    @GetMapping("/{reference}/alerts")
    @PreAuthorize("@access.canAt(authentication, 'alert.review', @parcelOffices.of(#reference))")
    List<AlertResponse> alerts(@PathVariable String reference) {
        return conflicts.ofParcel(reference).stream().map(a -> {
            boolean thisIsA = a.a().reference().equals(reference);
            ConflictAlert.Side self = thisIsA ? a.a() : a.b();
            ConflictAlert.Side other = thisIsA ? a.b() : a.a();
            return new AlertResponse(a.id(), a.type().name(), a.status().name(), other.reference(), other.officeCode(),
                    self.versionNo(), other.versionNo(), a.intersectionM2(), self.share(), other.share(),
                    a.intersectionGeoJson() == null ? null : json.readTree(a.intersectionGeoJson()),
                    a.normalizedReference(), a.raisedAt(), a.resolution() == null ? null : a.resolution().name(),
                    a.resolutionNote(), a.resolvedAt(),
                    a.supersededAt());
        }).toList();
    }
}
