// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The detection thresholds (MVP-04, "DEMO thresholds, configurable"): an overlap counts from
 * {@code minOverlapM2} square metres on the spheroid; it is a possible duplicate when it covers
 * at least {@code duplicateShare} of each parcel. The database applies them (V11) and accepts
 * values only within the same bounds.
 */
public record ConflictPolicy(BigDecimal minOverlapM2, BigDecimal duplicateShare) {

    public static final BigDecimal MIN_OVERLAP_FLOOR = new BigDecimal("0.01");
    public static final BigDecimal MIN_OVERLAP_CEILING = new BigDecimal("1000");
    public static final BigDecimal SHARE_FLOOR = new BigDecimal("0.5");

    /** Problems with the values, as stable codes; empty when they can be applied. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (minOverlapM2 == null || minOverlapM2.compareTo(MIN_OVERLAP_FLOOR) < 0
                || minOverlapM2.compareTo(MIN_OVERLAP_CEILING) > 0 || minOverlapM2.scale() > 2) {
            problems.add("MIN_OVERLAP_OUT_OF_RANGE");
        }
        if (duplicateShare == null || duplicateShare.compareTo(SHARE_FLOOR) < 0
                || duplicateShare.compareTo(BigDecimal.ONE) > 0 || duplicateShare.scale() > 3) {
            problems.add("DUPLICATE_SHARE_OUT_OF_RANGE");
        }
        return problems;
    }

    /** The same thresholds, whatever the trailing zeros. */
    public boolean sameAs(ConflictPolicy other) {
        return other != null && minOverlapM2.compareTo(other.minOverlapM2) == 0
                && duplicateShare.compareTo(other.duplicateShare) == 0;
    }
}
