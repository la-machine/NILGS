// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.parcel.application.ParcelService;
import org.snigf.mvp.parcel.domain.ParcelRepository;
import org.snigf.mvp.parcel.domain.ParcelVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parcel endpoints (MVP-01, E-05). Each needs its permission at the parcel's office (or
 * everywhere): creation at the office named in the request, correction and reading at the
 * office the parcel belongs to ({@link ParcelOffices}). Refused calls are audited as
 * ACCESS_DENIED by the security layer; creations, corrections and refused geometries by the
 * service.
 */
@RestController
@RequestMapping("/api/v1/parcels")
class ParcelController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ParcelService parcels;
    private final JsonMapper json = JsonMapper.builder().build();

    ParcelController(ParcelService parcels) {
        this.parcels = parcels;
    }

    /** {@code geometry}: a GeoJSON Polygon or MultiPolygon in the CRS {@code srid} (4326, 32632, 32633). */
    record CreateBody(@NotNull @Size(max = 16) String officeCode, @NotNull @Size(max = 200) String locality,
                      @Size(max = 100) String externalReference, Integer srid, JsonNode geometry) {
    }

    record CorrectBody(int expectedVersion, Integer srid, JsonNode geometry, @Size(max = 200) String locality,
                       @Size(max = 100) String externalReference, @NotNull @Size(max = 500) String reason) {
    }

    record DisplayResponse(int srid, JsonNode geometry) {
    }

    record VersionResponse(int versionNo, JsonNode geometry, DisplayResponse display, BigDecimal areaM2, int sourceSrid,
                           String locality, String externalReference, String verificationStatus,
                           String changeReason, UUID createdBy, Instant createdAt) {
    }

    record VersionSummary(int versionNo, BigDecimal areaM2, int sourceSrid, String changeReason, Instant createdAt) {
    }

    record ParcelResponse(String reference, String officeCode, String status, int currentVersion, Instant createdAt,
                          VersionResponse current, List<VersionSummary> versions) {
    }

    @PostMapping
    @PreAuthorize("@access.canAt(authentication, 'parcel.create', #body.officeCode())")
    ResponseEntity<ParcelService.Created> create(@AuthenticationPrincipal AuthenticatedUser officer,
                                                 @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                 @Valid @RequestBody CreateBody body) {
        ParcelService.Creation creation = parcels.create(officer, new ParcelService.NewParcel(body.officeCode(),
                body.locality(), body.externalReference(), body.srid(), text(body.geometry())), key);
        return ResponseEntity.created(URI.create("/api/v1/parcels/" + creation.created().reference()))
                .header("Idempotent-Replayed", Boolean.toString(creation.replayed()))
                .body(creation.created());
    }

    @PostMapping("/{reference}/versions")
    @PreAuthorize("@access.canAt(authentication, 'parcel.correct', @parcelOffices.of(#reference))")
    ResponseEntity<ParcelService.Created> correct(@AuthenticationPrincipal AuthenticatedUser officer,
                                                  @PathVariable String reference, @Valid @RequestBody CorrectBody body) {
        ParcelService.Created corrected = parcels.correct(officer, reference, new ParcelService.Correction(
                body.expectedVersion(), body.srid(), text(body.geometry()), body.locality(), body.externalReference(),
                body.reason()));
        return ResponseEntity.created(URI.create("/api/v1/parcels/" + reference + "/versions/" + corrected.versionNo()))
                .body(corrected);
    }

    @GetMapping("/{reference}")
    @PreAuthorize("@access.canAt(authentication, 'parcel.read', @parcelOffices.of(#reference))")
    ParcelResponse read(@PathVariable String reference) {
        ParcelService.ParcelView view = parcels.read(reference);
        return new ParcelResponse(view.parcel().reference().value(), view.parcel().officeCode(),
                view.parcel().status().name(), view.parcel().currentVersion(), view.parcel().createdAt(),
                version(view.current()), view.history().stream().map(v -> new VersionSummary(v.versionNo(),
                        v.areaM2(), v.sourceSrid(), v.changeReason(), v.createdAt())).toList());
    }

    @GetMapping("/{reference}/versions/{versionNo}")
    @PreAuthorize("@access.canAt(authentication, 'parcel.read', @parcelOffices.of(#reference))")
    VersionResponse version(@PathVariable String reference, @PathVariable int versionNo) {
        return version(parcels.version(reference, versionNo));
    }

    @GetMapping
    @PreAuthorize("@access.canAt(authentication, 'parcel.read', #office)")
    List<ParcelRepository.ParcelSummary> search(@RequestParam String office, @RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "0") int page) {
        return parcels.search(office, q, page);
    }

    private VersionResponse version(ParcelVersion v) {
        return new VersionResponse(v.versionNo(), json.readTree(v.geometry()),
                new DisplayResponse(v.display().srid(), json.readTree(v.display().geometry())), v.areaM2(),
                v.sourceSrid(), v.locality(), v.externalReference(), v.verificationStatus(), v.changeReason(),
                v.createdBy(), v.createdAt());
    }

    private static String text(JsonNode geometry) {
        return geometry == null || geometry.isNull() ? null : geometry.toString();
    }
}
