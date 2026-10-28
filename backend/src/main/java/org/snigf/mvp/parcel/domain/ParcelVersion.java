// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable geometry version of a parcel (append-only history, V2).
 *
 * @param geometry     the authoritative geometry, GeoJSON MultiPolygon in EPSG:4326, as stored
 *                     after validation and snapping to about 1 cm
 * @param display      the same geometry transformed by PostGIS into the parcel's UTM zone, for
 *                     the technical view (ADR-029); never stored, never sent back
 * @param areaM2       area on the WGS84 spheroid, square metres (authoritative)
 * @param sourceSrid   the CRS the officer submitted (4326, 32632 or 32633)
 * @param changeReason null for version 1; the reason of the correction otherwise
 */
public record ParcelVersion(int versionNo, String geometry, Display display, BigDecimal areaM2, int sourceSrid,
                            String locality, String externalReference, String verificationStatus,
                            String changeReason, UUID createdBy, Instant createdAt) {

    /** A geometry for display in a projected CRS (metres). */
    public record Display(int srid, String geometry) {
    }
}
