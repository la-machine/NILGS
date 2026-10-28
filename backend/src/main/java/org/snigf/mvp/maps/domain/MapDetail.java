// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A detail archive drawn over a background's base where it exists: sharper imagery of a town
 * over a national mosaic, for example (ADR-029). It has its own source, licence, attribution
 * and date, shown while it is displayed, and covers only its bounds and zoom range.
 *
 * @param file the archive's file name in the kind's directory, never the base's
 */
public record MapDetail(String name, String file, String attribution, String license, String source, String date,
                        double[] bounds, int minzoom, int maxzoom) {

    public static final int MAX_DETAILS = 20;
    private static final Pattern FILE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,60}\\.pmtiles$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Problems with one detail entry, as stable codes; empty when it can be used. */
    public static List<String> problems(MapDetail detail, String baseFile) {
        List<String> problems = new ArrayList<>();
        if (detail.file() == null || !FILE.matcher(detail.file()).matches() || detail.file().equals(baseFile)) {
            problems.add("DETAIL_FILE_NAME_INVALID");
        }
        if (!text(detail.name(), 200) || !text(detail.attribution(), 300) || !text(detail.license(), 300)
                || !text(detail.source(), 300)) {
            problems.add("DETAIL_DESCRIPTION_INCOMPLETE");
        }
        if (detail.date() == null || !DATE.matcher(detail.date()).matches()) {
            problems.add("DETAIL_DATE_INVALID");
        }
        double[] b = detail.bounds();
        if (b == null || b.length != 4 || b[0] < -180 || b[2] > 180 || b[1] < -85.06 || b[3] > 85.06
                || b[0] >= b[2] || b[1] >= b[3]) {
            problems.add("DETAIL_BOUNDS_INVALID");
        }
        if (detail.minzoom() < 0 || detail.maxzoom() > 24 || detail.minzoom() > detail.maxzoom()) {
            problems.add("DETAIL_ZOOM_INVALID");
        }
        return problems;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
