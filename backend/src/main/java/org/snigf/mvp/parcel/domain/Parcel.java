// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A cadastral parcel: a stable reference and the number of its current geometry version. A
 * parcel record never implies ownership (Baseline, "Concepts that must stay separate").
 *
 * @param officeCode     the office the parcel belongs to (its office-scoped permissions apply)
 * @param currentVersion the version shown by default; it only moves forward
 */
public record Parcel(UUID id, ParcelReference reference, String officeCode, Status status, int currentVersion,
                     UUID createdBy, Instant createdAt) {

    public enum Status { ACTIVE, SUPERSEDED, RETIRED }
}
