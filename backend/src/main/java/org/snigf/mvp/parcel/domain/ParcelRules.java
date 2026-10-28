// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The text rules of a parcel version (MVP-01, V2 checks), as stable codes. The geometry is
 * judged by PostGIS ({@link GeometryCheck}); everything else is judged here, before any
 * database work.
 */
public final class ParcelRules {

    /** Accepted input CRS (provisional P1): WGS84 and UTM zones 32N and 33N. */
    public static final List<Integer> ACCEPTED_SRIDS = List.of(4326, 32632, 32633);
    public static final int MIN_REASON = 10;

    private ParcelRules() {
    }

    public static List<String> problems(String locality, String externalReference) {
        List<String> problems = new ArrayList<>();
        if (!text(locality, 2, 200)) {
            problems.add("LOCALITY_INVALID");
        }
        if (externalReference != null && !externalReference.isBlank() && !text(externalReference, 1, 100)) {
            problems.add("EXTERNAL_REFERENCE_INVALID");
        }
        return problems;
    }

    /** A correction always says why (C8: at least 10 characters). */
    public static boolean validReason(String reason) {
        return text(reason, MIN_REASON, 500);
    }

    /** The UTM zone used to display a geometry: 32N west of 12°E, 33N from 12°E (ADR-029). */
    public static int displaySrid(double centroidLongitude) {
        return centroidLongitude < 12.0 ? 32632 : 32633;
    }

    private static boolean text(String value, int min, int max) {
        return value != null && value.strip().length() >= min && value.length() <= max
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n');
    }
}
