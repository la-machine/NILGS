// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who an event is attributed to, coherent with the {@code ck_actor} constraint: a USER has
 * an id and its roles, a SERVICE a label, ANONYMOUS neither.
 *
 * @param type   USER, SERVICE or ANONYMOUS
 * @param userId the user, for USER only
 * @param label  the service name, for SERVICE only
 * @param roles  role names held, sorted, upper snake case
 */
public record Actor(String type, UUID userId, String label, List<String> roles) {

    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9-]{2,63}");

    public Actor {
        roles = List.copyOf(roles);
        for (String role : roles) {
            if (!ROLE.matcher(role).matches()) {
                // Also guarantees the role list can be written as an array literal without quoting.
                throw new IllegalArgumentException("role name is not upper snake case: " + role);
            }
        }
    }

    public static Actor user(UUID id, List<String> roles) {
        return new Actor("USER", Objects.requireNonNull(id, "user id"), null, roles.stream().sorted().toList());
    }

    public static Actor service(String label) {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("service label must be lower kebab case: " + label);
        }
        return new Actor("SERVICE", null, label, List.of());
    }

    public static Actor anonymous() {
        return new Actor("ANONYMOUS", null, null, List.of());
    }
}
