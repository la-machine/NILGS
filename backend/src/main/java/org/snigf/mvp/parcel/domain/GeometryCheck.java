// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.math.BigDecimal;

/**
 * The verdict of PostGIS on a submitted geometry ({@code normalize_parcel_geometry}, V2):
 * either accepted, with its area, or refused with a stable code and PostGIS's reason. A
 * geometry is never silently repaired.
 *
 * @param errorCode   CRS_MISSING, CRS_UNSUPPORTED, GEOMETRY_MALFORMED, GEOMETRY_TYPE_UNSUPPORTED,
 *                    GEOMETRY_EMPTY, GEOMETRY_TOO_COMPLEX, GEOMETRY_INVALID,
 *                    GEOMETRY_INVALID_AFTER_TRANSFORM, GEOMETRY_OUT_OF_BOUNDS
 * @param errorDetail for example the ST_IsValidReason text ("Self-intersection[...]")
 */
public record GeometryCheck(boolean ok, String errorCode, String errorDetail, BigDecimal areaM2) {

    public static GeometryCheck accepted(BigDecimal areaM2) {
        return new GeometryCheck(true, null, null, areaM2);
    }

    public static GeometryCheck refused(String code, String detail) {
        return new GeometryCheck(false, code, detail, null);
    }
}
