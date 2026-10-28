// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVP-06 acceptance: "the applicant API response contains no internal fields (tested by schema)".
 * The applicant's application may carry exactly these fields, at any step of its life; a new
 * field fails the tests until it is added here knowingly (and judged public). The application
 * type (the public catalogue) and the applicant's own answers ({@code data}) are taken whole.
 */
public final class ApplicantPayloadSchema {

    /** Every field path the applicant may receive; {@code []} marks the items of a list. */
    public static final Set<String> ALLOWED = Set.of(
            "id", "reference", "type", "state", "parcelReference", "officeCode", "data", "createdAt", "updatedAt",
            "submittedAt", "version", "since", "waitingOn",
            "pendingRequest", "pendingRequest.categories", "pendingRequest.message", "pendingRequest.since",
            "history", "history[].from", "history[].to", "history[].by", "history[].at",
            "completeness", "completeness[].kind", "completeness[].missing", "completeness[].requested",
            "completeness[].message", "completeness[].by", "completeness[].at",
            "decision", "decision.outcome", "decision.grounds", "decision.simulated", "decision.decidedAt");

    /** Taken whole: the public type catalogue and the applicant's own answers. */
    private static final Set<String> WHOLE = Set.of("type", "data");

    /** Who had the application in hand: never a person. */
    private static final Set<String> PARTIES = Set.of("APPLICANT", "OFFICE", "SYSTEM");

    /** Every staff account of the integration tests has this name ({@code insertStaff}). */
    private static final String STAFF_NAME = "Demo Officer";

    private ApplicantPayloadSchema() {
    }

    /**
     * Fails unless the payload has only allowed fields, names no person, and contains none of the
     * internal texts given (findings, return reasons, recommendation codes).
     */
    public static void assertSafe(JsonNode application, String... internalTexts) {
        Set<String> paths = new TreeSet<>();
        collect(application, "", paths);
        assertThat(paths).as("fields of the applicant's application (MVP-06): only those of ApplicantPayloadSchema")
                .isSubsetOf(ALLOWED);
        List<String> parties = new ArrayList<>();
        application.path("history").forEach(step -> parties.add(step.path("by").asString("")));
        application.path("completeness").forEach(step -> parties.add(step.path("by").asString("")));
        assertThat(parties).as("steps name a party, never a person").allMatch(PARTIES::contains);
        if (!application.path("waitingOn").isNull()) {
            assertThat(application.path("waitingOn").asString("")).isIn("APPLICANT", "OFFICE");
        }
        String text = application.toString();
        assertThat(text).as("no staff name").doesNotContain(STAFF_NAME);
        for (String internal : internalTexts) {
            assertThat(text).as("internal text").doesNotContain(internal);
        }
    }

    private static void collect(JsonNode node, String prefix, Set<String> paths) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                paths.add(path);
                if (!WHOLE.contains(path)) {
                    collect(field.getValue(), path, paths);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject() || item.isArray()) {
                    collect(item, prefix + "[]", paths);
                }
            }
        }
    }
}
