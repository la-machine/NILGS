// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.application;

import java.util.List;
import java.util.Set;

/** A professional-account operation refused, with a precise code the screens can show. */
public class ProfessionalRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID, TOO_LARGE, BROKEN }

    private static final Set<String> FORBIDDEN = Set.of("PROFESSIONAL_FORBIDDEN", "SELF_ACTION", "FOUR_EYES", "NOT_THE_VERIFIER");
    private static final Set<String> CONFLICT = Set.of("INVALID_STATUS", "REQUEST_CLOSED", "REQUEST_ALREADY_OPEN", "REQUEST_EXPIRED",
            "VERIFICATION_CLOSED", "SECURITY_KEYS_REQUIRED", "REQUEST_IN_PROGRESS");

    private final Kind kind;
    private final String code;
    private final List<String> violations;

    public ProfessionalRefused(Kind kind, String code) {
        this(kind, code, List.of());
    }

    public ProfessionalRefused(Kind kind, String code, List<String> violations) {
        super(code);
        this.kind = kind;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    /** The kind of a reason code raised by the database. */
    public static ProfessionalRefused of(String code) {
        Kind kind = code.endsWith("_NOT_FOUND") ? Kind.NOT_FOUND
                : FORBIDDEN.contains(code) ? Kind.FORBIDDEN
                : CONFLICT.contains(code) ? Kind.CONFLICT
                : Kind.INVALID;
        return new ProfessionalRefused(kind, code);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<String> violations() {
        return violations;
    }
}
