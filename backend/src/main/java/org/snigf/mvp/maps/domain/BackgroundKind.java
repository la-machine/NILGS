// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.domain;

import java.util.Locale;

/**
 * The three map views (ADR-029). The technical view needs no data; the other two each need a
 * tile archive installed in the map data directory, under {@link #directory()}.
 */
public enum BackgroundKind {
    TECHNICAL(null),
    GEOGRAPHIC("vector"),
    SATELLITE("raster");

    private final String tileType;

    BackgroundKind(String tileType) {
        this.tileType = tileType;
    }

    /** The tile type its archive must contain; null for the technical view. */
    public String tileType() {
        return tileType;
    }

    public boolean needsData() {
        return tileType != null;
    }

    /** The sub-directory of the map data directory, and the path segment of its URLs. */
    public String directory() {
        return name().toLowerCase(Locale.ROOT);
    }
}
