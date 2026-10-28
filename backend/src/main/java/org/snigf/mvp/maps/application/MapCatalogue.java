// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.application;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.snigf.mvp.maps.domain.BackgroundKind;
import org.snigf.mvp.maps.domain.MapDataset;
import org.springframework.stereotype.Service;

/**
 * Which map views are available, and their data (ADR-029). The technical view always is; a
 * geographic or satellite view is available only when its archive is installed with a valid
 * manifest. An unavailable view says why, so the interface can show it disabled with the
 * reason instead of failing. Only our own archives are ever offered: there is no external
 * provider to choose from.
 */
@Service
public class MapCatalogue {

    private static final Pattern FONTSTACK = Pattern.compile("^[A-Za-z0-9 ,-]{1,100}$");
    private static final Pattern RANGE = Pattern.compile("^\\d{1,5}-\\d{1,5}$");

    /** A view as the interface needs it. */
    public record Background(BackgroundKind kind, boolean available, String unavailableReason, MapDataset dataset) {
    }

    private final MapDataStore store;

    public MapCatalogue(MapDataStore store) {
        this.store = store;
    }

    public List<Background> backgrounds() {
        return Arrays.stream(BackgroundKind.values()).map(kind -> {
            if (!kind.needsData()) {
                return new Background(kind, true, null, null);
            }
            MapDataStore.Installed installed = store.installed(kind);
            return new Background(kind, installed.available(), installed.unavailableReason(), installed.dataset());
        }).toList();
    }

    /** The base or a detail archive of an available view, by the file name its manifest lists. */
    public Optional<Path> archive(BackgroundKind kind, String file) {
        return kind.needsData() && file != null ? store.archive(kind, file) : Optional.empty();
    }

    /** A label glyph file; names outside the expected shape are refused before touching the disk. */
    public Optional<Path> glyphs(String fontstack, String range) {
        if (fontstack == null || range == null || !FONTSTACK.matcher(fontstack).matches()
                || !RANGE.matcher(range).matches()) {
            return Optional.empty();
        }
        return store.glyphs(fontstack, range);
    }
}
