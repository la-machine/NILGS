// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Checks that follow every parcel version written, in the same transaction (ADR-030). The
 * parcel module publishes this contract and other modules implement it, so parcels never depend
 * on them: the conflict module reports the overlap and duplicate alerts the version raised
 * (detected by the database as the version is stored) and records them in the audit trail.
 * The officer who recorded the parcel sees them in the answer.
 */
public interface ParcelVersionChecks {

    /** The alerts this version raised. */
    List<RaisedAlert> afterVersionWritten(VersionWritten written);

    /** A version just stored. */
    record VersionWritten(UUID parcelId, String reference, UUID versionId, int versionNo, String officeCode) {
    }

    /**
     * An alert raised by the version: its kind (OVERLAP, DUPLICATE_GEOMETRY, DUPLICATE_REFERENCE),
     * the other parcel, and for a geometric alert the intersection and the share of this parcel.
     */
    record RaisedAlert(UUID id, String type, String otherReference, BigDecimal intersectionM2,
                       BigDecimal shareOfThisParcel) {
    }
}
