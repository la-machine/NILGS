// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parcels and their versions (V2). Geometry is validated, transformed and measured by
 * PostGIS, never in application code (MVP-04 rule); the database also keeps versions
 * immutable and numbered without gaps, and the current pointer only moving forward.
 */
public interface ParcelRepository {

    /** Another correction was recorded since the version the caller saw. */
    class StaleVersion extends RuntimeException {
        public StaleVersion() {
            super("the parcel changed meanwhile");
        }
    }

    /** Validates and normalizes a submitted GeoJSON geometry without storing anything. */
    GeometryCheck check(String geoJson, int srid);

    Optional<UUID> officeId(String officeCode);

    /** The next number of the office's parcel sequence for the year (never reused). */
    long nextSequence(String officeCode, int year);

    /** Inserts the parcel and its version 1 in the current transaction. */
    void create(UUID parcelId, ParcelReference reference, UUID officeId, NewVersion first);

    /**
     * Appends a version and moves the parcel to it, only if its current version is still
     * {@code expectedCurrent}.
     *
     * @throws StaleVersion if another correction came first
     */
    void correct(UUID parcelId, int expectedCurrent, NewVersion next);

    Optional<Parcel> find(ParcelReference reference);

    /** The parcel's office code, for authorization; empty if the reference is unknown. */
    Optional<String> officeOf(ParcelReference reference);

    Optional<ParcelVersion> version(UUID parcelId, int versionNo);

    /** Every version, oldest first. */
    List<ParcelVersion> versions(UUID parcelId);

    /** Parcels of an office whose reference or current locality contains the text, newest first. */
    List<ParcelSummary> search(String officeCode, String text, int limit, int offset);

    /** What a new version is made of (the geometry as submitted; PostGIS normalizes it again on insert). */
    record NewVersion(UUID versionId, int versionNo, String geoJson, int srid, String sourceSha256, String locality,
                      String externalReference, String changeReason, UUID createdBy) {
    }

    /** A line of a parcel list. */
    record ParcelSummary(String reference, String officeCode, String status, int currentVersion, String locality,
                         java.math.BigDecimal areaM2, java.time.Instant updatedAt) {
    }
}
