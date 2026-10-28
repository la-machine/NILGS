// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An overlap or duplicate signal between two exact parcel versions (MVP-04), raised by the
 * database as a version is stored (V11). It is evidence for a cadastral officer to review,
 * never a finding of fraud or of competing rights. Parcel A has the smaller id.
 *
 * @param intersectionGeoJson the intersection (EPSG:4326 GeoJSON); null for a duplicate reference
 * @param raisedByVersionId   the version whose writing raised it
 * @param version             optimistic lock for resolution
 */
public record ConflictAlert(UUID id, Type type, Status status,
                            Side a, Side b,
                            BigDecimal intersectionM2, String intersectionGeoJson, String normalizedReference,
                            UUID raisedByVersionId, Instant raisedAt,
                            Resolution resolution, String resolutionNote, UUID resolvedBy, String resolvedByName,
                            Instant resolvedAt, UUID supersededByVersionId, Instant supersededAt, long version) {

    /** Bounds of a resolution note (V11, V12): the reason must say something, and stay readable. */
    public static final int NOTE_MIN = 10;
    public static final int NOTE_MAX = 2000;

    public enum Type { OVERLAP, DUPLICATE_GEOMETRY, DUPLICATE_REFERENCE }

    public enum Status { OPEN, RESOLVED, SUPERSEDED }

    /** The Baseline's three outcomes of a review (MVP-04). */
    public enum Resolution { NO_CONFLICT, DATA_ERROR_CORRECTED, REFERRED_FOR_ADJUDICATION }

    /**
     * One of the two compared parcels, as compared: its version (author, area) and the share the
     * intersection covers, with the parcel's current version for comparison.
     */
    public record Side(UUID parcelId, String reference, String officeCode, UUID versionId, int versionNo,
                       int currentVersionNo, UUID versionAuthor, String versionAuthorName, BigDecimal areaM2,
                       BigDecimal share) {
    }

    /** The compared side of the given parcel. */
    public Side side(UUID parcelId) {
        return a.parcelId().equals(parcelId) ? a : b;
    }

    /** The other parcel of the pair, seen from the given one. */
    public Side other(UUID parcelId) {
        return a.parcelId().equals(parcelId) ? b : a;
    }

    /** Whether this person wrote either compared version: they may not resolve it (C6b). */
    public boolean authoredBy(UUID user) {
        return user.equals(a.versionAuthor()) || user.equals(b.versionAuthor());
    }

    /** The offices of both parcels (one when they share it): resolving needs the permission at either. */
    public List<String> offices() {
        List<String> offices = new ArrayList<>(List.of(a.officeCode()));
        if (!b.officeCode().equals(a.officeCode())) {
            offices.add(b.officeCode());
        }
        return offices;
    }

    /** What is wrong with a proposed resolution, as stable codes (empty when it can be applied). */
    public static List<String> problems(Resolution resolution, String note) {
        List<String> problems = new ArrayList<>();
        if (resolution == null) {
            problems.add("RESOLUTION_REQUIRED");
        }
        String text = note == null ? "" : note.strip();
        if (text.length() < NOTE_MIN) {
            problems.add("NOTE_REQUIRED");
        } else if (text.length() > NOTE_MAX) {
            problems.add("NOTE_TOO_LONG");
        }
        return problems;
    }
}
