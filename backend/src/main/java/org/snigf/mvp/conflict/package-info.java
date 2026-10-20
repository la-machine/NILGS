// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Overlap and duplicate alerts between exact parcel versions. An alert is evidence for review, never a finding of fraud or of competing rights.
 *
 * <p>Delivered from E-10 (slice 2). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Conflict alerts")
package org.snigf.mvp.conflict;

import org.springframework.modulith.ApplicationModule;
