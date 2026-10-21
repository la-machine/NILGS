// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What an event may carry. Details hold plain values only: no domain object, whose
 * serialisation could leak fields nobody meant to record (password hashes, secrets, full
 * geometries). Text never contains NUL, which PostgreSQL text cannot store.
 */
public final class AuditDetails {

    private AuditDetails() {
    }

    /** Refuses anything but strings, numbers, booleans, UUIDs, enums, times, maps and lists of them. */
    public static void requirePlain(Map<String, Object> details) {
        details.forEach(AuditDetails::requirePlain);
    }

    public static void requireNoNul(String value, String what) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("audit " + what + " contains a NUL character");
        }
    }

    private static void requirePlain(String path, Object value) {
        switch (value) {
            case null -> { }
            case String s -> requireNoNul(s, "detail " + path);
            case Number n -> { }
            case Boolean b -> { }
            case UUID u -> { }
            case Enum<?> e -> { }
            case TemporalAccessor t -> { }
            case Map<?, ?> map -> map.forEach((k, v) -> {
                if (!(k instanceof String key)) {
                    throw new IllegalArgumentException("audit detail keys must be strings at " + path);
                }
                requirePlain(path + "." + key, v);
            });
            case Collection<?> list -> list.forEach(v -> requirePlain(path + "[]", v));
            default -> throw new IllegalArgumentException(
                    "audit detail " + path + " has unsupported type " + value.getClass().getName());
        }
    }
}
