// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One version of an application type (C4): the DEMO service catalogue keeps every version,
 * insert-only (V13). A draft follows the version it was started with; a submission is checked
 * against the version in force, and records it.
 */
public record ApplicationType(UUID id, String code, int versionNo, boolean available, String nameFr, String nameEn,
                              Requirements requirements, Instant effectiveFrom) {

    private static final Pattern CODE = Pattern.compile("^DEMO_[A-Z0-9_]{2,40}$");

    /** A type as configured, before it is stored as a version. */
    public record Definition(String code, String nameFr, String nameEn, Requirements requirements) {

        /** What is wrong with it, as readable reasons. */
        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            if (code == null || !CODE.matcher(code).matches()) {
                problems.add("type " + code + ": code must be DEMO_ followed by UPPER_SNAKE_CASE");
            }
            for (String name : new String[] {nameFr, nameEn}) {
                if (name == null || name.length() < 3 || name.length() > 120 || !name.contains("DEMO")) {
                    problems.add("type " + code + ": names of 3 to 120 characters, saying DEMO in both languages (C4)");
                    break;
                }
            }
            if (requirements == null) {
                problems.add("type " + code + ": requirements are required");
            } else {
                requirements.problems().forEach(p -> problems.add("type " + code + ", " + p));
            }
            return problems;
        }
    }

    /** Whether this version already says what the definition says. */
    public boolean sameAs(Definition definition) {
        return available && code.equals(definition.code()) && nameFr.equals(definition.nameFr())
                && nameEn.equals(definition.nameEn()) && requirements.equals(definition.requirements());
    }
}
