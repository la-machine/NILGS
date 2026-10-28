// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Map backgrounds (ADR-029): which views have data, and the delivery of our own tile
 * archives (PMTiles) with their attribution and licence. Parcel geometry never passes
 * through this module: it comes from the parcel API. External providers are disabled.
 */
@ApplicationModule(displayName = "Maps")
package org.snigf.mvp.maps;

import org.springframework.modulith.ApplicationModule;
