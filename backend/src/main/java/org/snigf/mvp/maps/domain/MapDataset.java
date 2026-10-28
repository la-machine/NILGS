// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An installed tile archive, as its manifest describes it ({@code manifest.json}, written by
 * the maps tools, ADR-029). A manifest is data from a volume, so it is checked before use:
 * an archive with an invalid manifest is never offered.
 *
 * @param file        the archive's file name in the kind's directory ({@code tiles.pmtiles})
 * @param attribution what the map must show while the background is displayed
 * @param license     the licence(s) the data is used under
 * @param date        when the data was produced or captured (YYYY-MM-DD)
 * @param bounds      west, south, east, north in degrees
 * @param synthetic   true for generated test data, never real: the interface says so
 * @param details     sharper archives drawn over this one where they exist (may be empty)
 */
public record MapDataset(BackgroundKind kind, String name, String file, String tileType, String attribution,
                         String license, String source, String date, double[] bounds, int minzoom, int maxzoom,
                         boolean synthetic, List<MapDetail> details) {

    public MapDataset {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static final String FORMAT = "snigf-map-manifest/1";
    private static final Pattern FILE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,60}\\.pmtiles$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Problems with a manifest, as stable codes; empty when it can be used. */
    public static List<String> problems(BackgroundKind kind, String format, String name, String file, String tileType,
                                        String attribution, String license, String source, String date,
                                        double[] bounds, int minzoom, int maxzoom) {
        List<String> problems = new ArrayList<>();
        if (!FORMAT.equals(format)) {
            problems.add("FORMAT_UNKNOWN");
        }
        if (!kind.needsData()) {
            problems.add("KIND_HAS_NO_DATA");
        } else if (!kind.tileType().equals(tileType)) {
            problems.add("TILE_TYPE_MISMATCH");
        }
        if (file == null || !FILE.matcher(file).matches()) {
            problems.add("FILE_NAME_INVALID");
        }
        if (!text(name, 200) || !text(attribution, 300) || !text(license, 300) || !text(source, 300)) {
            problems.add("DESCRIPTION_INCOMPLETE");
        }
        if (date == null || !DATE.matcher(date).matches()) {
            problems.add("DATE_INVALID");
        }
        if (bounds == null || bounds.length != 4 || bounds[0] < -180 || bounds[2] > 180 || bounds[1] < -85.06
                || bounds[3] > 85.06 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) {
            problems.add("BOUNDS_INVALID");
        }
        if (minzoom < 0 || maxzoom > 24 || minzoom > maxzoom) {
            problems.add("ZOOM_INVALID");
        }
        return problems;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
