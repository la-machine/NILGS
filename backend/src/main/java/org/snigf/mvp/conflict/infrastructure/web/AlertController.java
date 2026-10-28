// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.conflict.application.ConflictService;
import org.snigf.mvp.conflict.domain.ConflictAlert;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alert review (MVP-04, E-11). The list is by office ({@code alert.review} there); an alert is
 * read and resolved by whoever holds the permission at the office of either parcel
 * ({@link AlertOffices}). The resolver states one of the Baseline's outcomes with a note, against
 * the version they read; the author of a compared version is refused ({@code RESOLVER_IS_AUTHOR}),
 * by the service and again by the database.
 */
@RestController
@RequestMapping("/api/v1/alerts")
class AlertController {

    private final ConflictService conflicts;
    private final JsonMapper json = JsonMapper.builder().build();

    AlertController(ConflictService conflicts) {
        this.conflicts = conflicts;
    }

    record ResolveBody(long expectedVersion, @NotNull ConflictAlert.Resolution resolution,
                       @NotNull @Size(max = 4000) String note) {
    }

    /** One parcel of the pair, as compared. */
    record SideResponse(String reference, String officeCode, int versionNo, int currentVersionNo, String authorName,
                        BigDecimal areaM2, BigDecimal share) {
    }

    record SummaryResponse(UUID id, String type, String status, SideResponse a, SideResponse b,
                           BigDecimal intersectionM2, String normalizedReference, Instant raisedAt,
                           String resolution, Instant resolvedAt, Instant supersededAt) {
    }

    /** The three shapes in one UTM zone (metres), for the review map; null parts are absent. */
    record DisplayResponse(int srid, JsonNode a, JsonNode b, JsonNode intersection) {
    }

    /**
     * @param resolverIsAuthor whether the person asking wrote either compared version, so cannot resolve it
     */
    record DetailResponse(UUID id, String type, String status, long version, SideResponse a, SideResponse b,
                          BigDecimal intersectionM2, String normalizedReference, DisplayResponse display,
                          Instant raisedAt, String raisedByReference, String resolution, String resolutionNote,
                          String resolvedByName, Instant resolvedAt, Instant supersededAt, boolean resolverIsAuthor) {
    }

    @GetMapping
    @PreAuthorize("@access.canAt(authentication, 'alert.review', #office)")
    List<SummaryResponse> list(@RequestParam String office,
                               @RequestParam(required = false) ConflictAlert.Status status,
                               @RequestParam(required = false) ConflictAlert.Type type,
                               @RequestParam(defaultValue = "0") int page) {
        return conflicts.ofOffice(office, status, type, page).stream()
                .map(a -> new SummaryResponse(a.id(), a.type().name(), a.status().name(), side(a.a()), side(a.b()),
                        a.intersectionM2(), a.normalizedReference(), a.raisedAt(), name(a.resolution()),
                        a.resolvedAt(), a.supersededAt()))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canAtAny(authentication, 'alert.review', @alertOffices.of(#id))")
    DetailResponse read(@AuthenticationPrincipal AuthenticatedUser reader, @PathVariable UUID id) {
        return detail(conflicts.review(id), reader);
    }

    @PostMapping("/{id}/resolution")
    @PreAuthorize("@access.canAtAny(authentication, 'alert.resolve', @alertOffices.of(#id))")
    DetailResponse resolve(@AuthenticationPrincipal AuthenticatedUser resolver, @PathVariable UUID id,
                           @Valid @RequestBody ResolveBody body) {
        conflicts.resolve(resolver, id, new ConflictService.Resolve(body.expectedVersion(), body.resolution(), body.note()));
        return detail(conflicts.review(id), resolver);
    }

    private DetailResponse detail(ConflictService.Review review, AuthenticatedUser reader) {
        ConflictAlert a = review.alert();
        DisplayResponse display = review.display().map(d -> new DisplayResponse(d.srid(), tree(d.a()), tree(d.b()),
                tree(d.intersection()))).orElse(null);
        String raisedBy = a.raisedByVersionId().equals(a.a().versionId()) ? a.a().reference() : a.b().reference();
        return new DetailResponse(a.id(), a.type().name(), a.status().name(), a.version(), side(a.a()), side(a.b()),
                a.intersectionM2(), a.normalizedReference(), display, a.raisedAt(), raisedBy, name(a.resolution()),
                a.resolutionNote(), a.resolvedByName(), a.resolvedAt(), a.supersededAt(), a.authoredBy(reader.userId()));
    }

    private static SideResponse side(ConflictAlert.Side s) {
        return new SideResponse(s.reference(), s.officeCode(), s.versionNo(), s.currentVersionNo(), s.versionAuthorName(),
                s.areaM2(), s.share());
    }

    private static String name(ConflictAlert.Resolution resolution) {
        return resolution == null ? null : resolution.name();
    }

    private JsonNode tree(String geoJson) {
        return geoJson == null ? null : json.readTree(geoJson);
    }
}
