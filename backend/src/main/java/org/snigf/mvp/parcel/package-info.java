// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Cadastral parcels: stable reference plus append-only geometry versions in EPSG:4326. A parcel record never implies ownership.
 *
 * <p>Delivered from E-05 (slice 1). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Parcels")
package org.snigf.mvp.parcel;

import org.springframework.modulith.ApplicationModule;
