// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel;

import java.util.Optional;
import java.util.UUID;

/**
 * Finds an active parcel by its reference, for modules that link a record to a parcel (an
 * application at submission, ADR-032). It answers identity and office only, never the
 * geometry or the attributes: reading a parcel stays the parcel endpoints' business.
 */
public interface ParcelDirectory {

    /** The active parcel with this reference; empty when malformed, unknown or not active. */
    Optional<ActiveParcel> active(String reference);

    record ActiveParcel(UUID id, String reference, UUID officeId, String officeCode) {
    }
}
