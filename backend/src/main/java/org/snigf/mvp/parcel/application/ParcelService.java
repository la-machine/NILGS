// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.parcel.ParcelVersionChecks;
import org.snigf.mvp.parcel.domain.GeometryCheck;
import org.snigf.mvp.parcel.domain.Parcel;
import org.snigf.mvp.parcel.domain.ParcelReference;
import org.snigf.mvp.parcel.domain.ParcelRepository;
import org.snigf.mvp.parcel.domain.ParcelRules;
import org.snigf.mvp.parcel.domain.ParcelVersion;
import org.snigf.mvp.shared.id.UuidV7;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parcel use cases (MVP-01, E-05): create a parcel from GeoJSON, correct it with a new
 * version, read it with its history, list an office's parcels. Who may do what is decided
 * at the endpoint, on the parcel's own office ({@code @access.canAt}); this service judges the
 * request, stores it and records it in the audit trail.
 *
 * <ul>
 *   <li>The geometry is judged by PostGIS; a refused one is never stored or repaired, and the
 *       refusal is audited with its code.</li>
 *   <li>Creation needs an {@code Idempotency-Key} (ADR-013): a retry returns the first answer.</li>
 *   <li>A correction states the version it corrects; if another came first, it is refused
 *       (409) instead of silently overwriting.</li>
 *   <li>Every version is checked for overlaps and duplicates as it is stored (MVP-04, by the
 *       database); the alerts it raised are in the answer ({@link ParcelVersionChecks}).</li>
 * </ul>
 */
@Service
public class ParcelService {

    static final String ENTITY_PARCEL = "PARCEL";
    static final String CREATE_ENDPOINT = "POST /api/v1/parcels";

    /** What the officer submits to create a parcel. */
    public record NewParcel(String officeCode, String locality, String externalReference, Integer srid,
                            String geometry) {
    }

    /** What the officer submits to correct a parcel. */
    public record Correction(int expectedVersion, Integer srid, String geometry, String locality,
                             String externalReference, String reason) {
    }

    /** The answer to a creation; stored for idempotent retries. */
    public record Created(String reference, int versionNo, BigDecimal areaM2, List<ParcelVersionChecks.RaisedAlert> alerts) {
    }

    /** The answer to a creation, and whether it is the replay of an earlier one. */
    public record Creation(Created created, boolean replayed) {
    }

    /** A parcel with its current version and the list of its versions. */
    public record ParcelView(Parcel parcel, ParcelVersion current, List<ParcelVersion> history) {
    }

    private final ParcelRepository parcels;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ZoneId referenceZone;
    private final int pageSize;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ObjectProvider<ParcelVersionChecks> checks;

    public ParcelService(ParcelRepository parcels, IdempotencyStore idempotency, AuditRecorder audit, Clock clock,
                         ParcelProperties properties, ObjectProvider<ParcelVersionChecks> checks) {
        this.checks = checks;
        this.parcels = parcels;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.referenceZone = properties.referenceZone();
        this.pageSize = properties.pageSize();
    }

    @Transactional
    public Creation create(AuthenticatedUser officer, NewParcel request, String idempotencyKey) {
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new ParcelRefused(ParcelRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        String requestHash = IdempotencyStore.sha256(json.writeValueAsString(request));
        var earlier = idempotency.find(officer.userId(), idempotencyKey);
        if (earlier.isPresent()) {
            if (!earlier.get().requestSha256().equals(requestHash)) {
                throw new ParcelRefused(ParcelRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REUSED");
            }
            return new Creation(json.readValue(earlier.get().body(), Created.class), true);
        }
        requireText(ParcelRules.problems(request.locality(), request.externalReference()));
        String officeCode = request.officeCode() == null ? "" : request.officeCode().strip();
        UUID officeId = parcels.officeId(officeCode)
                .orElseThrow(() -> new ParcelRefused(ParcelRefused.Kind.NOT_FOUND, "OFFICE_NOT_FOUND"));
        GeometryCheck check = judge(request.geometry(), request.srid());
        if (!check.ok()) {
            refusedGeometry(AuditAction.PARCEL_CREATE_REJECTED, null, officeCode, request.srid(), check);
        }

        int year = LocalDate.now(clock.withZone(referenceZone)).getYear();
        ParcelReference reference = ParcelReference.issue(officeCode, year, parcels.nextSequence(officeCode, year));
        UUID parcelId = UuidV7.next();
        UUID versionId = UuidV7.next();
        parcels.create(parcelId, reference, officeId, new ParcelRepository.NewVersion(versionId, 1,
                request.geometry(), request.srid(), IdempotencyStore.sha256(request.geometry()),
                request.locality().strip(), blankToNull(request.externalReference()), null, officer.userId()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("office", officeCode);
        details.put("versionNo", 1);
        details.put("areaM2", check.areaM2());
        details.put("sourceSrid", request.srid());
        audit.record(AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_PARCEL, parcelId).withEntityRef(reference.value()).withDetails(details));

        // Overlap and duplicate detection (MVP-04) ran in the database as the version was stored.
        List<ParcelVersionChecks.RaisedAlert> alerts = afterWrite(new ParcelVersionChecks.VersionWritten(
                parcelId, reference.value(), versionId, 1, officeCode));
        Created created = new Created(reference.value(), 1, check.areaM2(), alerts);
        try {
            idempotency.save(officer.userId(), idempotencyKey, CREATE_ENDPOINT, requestHash, 201,
                    json.writeValueAsString(created));
        } catch (IdempotencyStore.InProgress e) {
            throw new ParcelRefused(ParcelRefused.Kind.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
        }
        return new Creation(created, false);
    }

    @Transactional
    public Created correct(AuthenticatedUser officer, String referenceText, Correction request) {
        Parcel parcel = existing(referenceText);
        if (!ParcelRules.validReason(request.reason())) {
            throw new ParcelRefused(ParcelRefused.Kind.INVALID, "REASON_REQUIRED");
        }
        ParcelVersion current = parcels.version(parcel.id(), parcel.currentVersion()).orElseThrow();
        String locality = request.locality() == null || request.locality().isBlank() ? current.locality()
                : request.locality().strip();
        String externalReference = request.externalReference() == null ? current.externalReference()
                : blankToNull(request.externalReference());
        requireText(ParcelRules.problems(locality, externalReference));
        if (request.expectedVersion() != parcel.currentVersion()) {
            throw new ParcelRefused(ParcelRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        GeometryCheck check = judge(request.geometry(), request.srid());
        if (!check.ok()) {
            refusedGeometry(AuditAction.PARCEL_CORRECT_REJECTED, parcel, parcel.officeCode(), request.srid(), check);
        }
        int next = parcel.currentVersion() + 1;
        UUID versionId = UuidV7.next();
        try {
            parcels.correct(parcel.id(), parcel.currentVersion(), new ParcelRepository.NewVersion(versionId, next,
                    request.geometry(), request.srid(), IdempotencyStore.sha256(request.geometry()), locality,
                    externalReference, request.reason().strip(), officer.userId()));
        } catch (ParcelRepository.StaleVersion e) {
            throw new ParcelRefused(ParcelRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("office", parcel.officeCode());
        details.put("fromVersion", parcel.currentVersion());
        details.put("versionNo", next);
        details.put("areaM2", check.areaM2());
        details.put("sourceSrid", request.srid());
        audit.record(AuditEvent.of(AuditAction.PARCEL_CORRECTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_PARCEL, parcel.id()).withEntityRef(parcel.reference().value())
                .withReason(request.reason().strip()).withDetails(details));
        List<ParcelVersionChecks.RaisedAlert> alerts = afterWrite(new ParcelVersionChecks.VersionWritten(
                parcel.id(), parcel.reference().value(), versionId, next, parcel.officeCode()));
        return new Created(parcel.reference().value(), next, check.areaM2(), alerts);
    }

    /** What the modules that react to a version report (the alerts it raised), in this transaction. */
    private List<ParcelVersionChecks.RaisedAlert> afterWrite(ParcelVersionChecks.VersionWritten written) {
        return checks.orderedStream().flatMap(c -> c.afterVersionWritten(written).stream()).toList();
    }

    @Transactional(readOnly = true)
    public ParcelView read(String referenceText) {
        Parcel parcel = existing(referenceText);
        List<ParcelVersion> history = parcels.versions(parcel.id());
        ParcelVersion current = history.stream().filter(v -> v.versionNo() == parcel.currentVersion()).findFirst()
                .orElseThrow();
        return new ParcelView(parcel, current, history);
    }

    @Transactional(readOnly = true)
    public ParcelVersion version(String referenceText, int versionNo) {
        Parcel parcel = existing(referenceText);
        return parcels.version(parcel.id(), versionNo)
                .orElseThrow(() -> new ParcelRefused(ParcelRefused.Kind.NOT_FOUND, "VERSION_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<ParcelRepository.ParcelSummary> search(String officeCode, String text, int page) {
        String query = text == null ? "" : text.strip();
        if (query.length() > 100) {
            throw new ParcelRefused(ParcelRefused.Kind.INVALID, "QUERY_TOO_LONG");
        }
        return parcels.search(officeCode, query, pageSize, Math.max(page, 0) * pageSize);
    }

    /** The parcel's office for the authorization check; empty for an unknown or malformed reference. */
    @Transactional(readOnly = true)
    public String officeOf(String referenceText) {
        if (!ParcelReference.isValid(referenceText)) {
            return "";
        }
        return parcels.officeOf(new ParcelReference(referenceText)).orElse("");
    }

    private Parcel existing(String referenceText) {
        if (!ParcelReference.isValid(referenceText)) {
            throw new ParcelRefused(ParcelRefused.Kind.NOT_FOUND, "PARCEL_NOT_FOUND");
        }
        return parcels.find(new ParcelReference(referenceText))
                .orElseThrow(() -> new ParcelRefused(ParcelRefused.Kind.NOT_FOUND, "PARCEL_NOT_FOUND"));
    }

    private GeometryCheck judge(String geometry, Integer srid) {
        if (srid == null) {
            return GeometryCheck.refused("CRS_MISSING", "the srid field is required");
        }
        if (geometry == null || geometry.isBlank()) {
            return GeometryCheck.refused("GEOMETRY_MALFORMED", "a GeoJSON geometry object is required");
        }
        return parcels.check(geometry, srid);
    }

    /** Audits the refusal in its own transaction (this one rolls back), then refuses with PostGIS's reason. */
    private void refusedGeometry(AuditAction action, Parcel parcel, String officeCode, Integer srid, GeometryCheck check) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("office", officeCode);
        details.put("code", check.errorCode());
        details.put("sourceSrid", srid == null ? "" : srid);
        AuditEvent event = AuditEvent.of(action, AuditOutcome.FAILED).withDetails(details)
                .withReason(check.errorDetail() == null ? check.errorCode() : truncate(check.errorDetail()));
        if (parcel != null) {
            event = event.withEntity(ENTITY_PARCEL, parcel.id()).withEntityRef(parcel.reference().value());
        }
        audit.recordIndependently(event);
        throw new ParcelRefused(ParcelRefused.Kind.INVALID, check.errorCode(), check.errorDetail());
    }

    private static void requireText(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new ParcelRefused(ParcelRefused.Kind.INVALID, problems.getFirst());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
