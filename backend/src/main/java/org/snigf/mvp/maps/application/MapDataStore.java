// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.snigf.mvp.maps.domain.BackgroundKind;
import org.snigf.mvp.maps.domain.MapDataset;

/**
 * The map data directory (a read-only volume in the container), as the catalogue needs it.
 * An application port, not a domain one: it hands out files to deliver.
 */
public interface MapDataStore {

    /** What is installed for a kind: a usable dataset, or why there is none. */
    record Installed(MapDataset dataset, String unavailableReason, List<String> problems) {

        public static Installed usable(MapDataset dataset) {
            return new Installed(dataset, null, List.of());
        }

        public static Installed unavailable(String reason, List<String> problems) {
            return new Installed(null, reason, List.copyOf(problems));
        }

        public boolean available() {
            return dataset != null;
        }
    }

    /** Reads and checks the kind's manifest and archive: NOT_INSTALLED, MANIFEST_INVALID or FILE_MISSING otherwise. */
    Installed installed(BackgroundKind kind);

    /** The archive of a usable dataset. */
    Optional<Path> archive(BackgroundKind kind);

    /** The base archive or one of its detail archives, by file name: only files its manifest lists. */
    Optional<Path> archive(BackgroundKind kind, String file);

    /** A label glyph file of the geographic background, if installed; the names are checked by the caller. */
    Optional<Path> glyphs(String fontstack, String range);
}
