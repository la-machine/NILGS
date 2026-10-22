// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.snigf.mvp.shared.security.Permission;
import org.snigf.mvp.shared.security.Permission.Scope;

/**
 * A role: a named bundle of catalogue permissions, each with a scope (ADR-025). System roles
 * are the baseline ({@link BaselineRole}) and never change; custom roles are defined by
 * administrators through approved requests.
 *
 * @param code        identifier, upper snake case
 * @param nameFr      name in French
 * @param nameEn      name in English
 * @param description optional description
 * @param system      a baseline role
 * @param assignable  may be granted at an office (false only for APPLICANT)
 * @param active      false once retired
 * @param permissions permission code to scope
 */
public record RoleDefinition(String code, String nameFr, String nameEn, String description, boolean system,
                             boolean assignable, boolean active, Map<String, Scope> permissions) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,39}");

    public RoleDefinition {
        permissions = Map.copyOf(permissions);
    }

    /** The catalogue permissions of this role, ignoring codes the catalogue no longer knows. */
    public Map<Permission, Scope> catalogued() {
        Map<Permission, Scope> result = new LinkedHashMap<>();
        permissions.forEach((code, scope) -> Permission.ofCode(code).ifPresent(p -> result.put(p, scope)));
        return result;
    }

    /**
     * Problems with a proposed custom role, as stable codes: the code shape, names, an empty or
     * unknown permission, a scope the catalogue does not allow, or administration and audit
     * together (separation of duties).
     */
    public static List<String> problems(String code, String nameFr, String nameEn, String description,
                                        Map<String, Scope> permissions) {
        List<String> problems = new ArrayList<>();
        if (code == null || !CODE.matcher(code).matches()) {
            problems.add("ROLE_CODE_INVALID");
        }
        if (!name(nameFr) || !name(nameEn)) {
            problems.add("ROLE_NAME_INVALID");
        }
        if (description != null && description.length() > 500) {
            problems.add("ROLE_DESCRIPTION_TOO_LONG");
        }
        if (permissions == null || permissions.isEmpty()) {
            problems.add("ROLE_WITHOUT_PERMISSION");
            return problems;
        }
        List<Permission> known = new ArrayList<>();
        for (Map.Entry<String, Scope> entry : permissions.entrySet()) {
            Optional<Permission> permission = Permission.ofCode(entry.getKey());
            if (permission.isEmpty()) {
                problems.add("PERMISSION_UNKNOWN");
            } else if (entry.getValue() == null || !permission.get().scopes().contains(entry.getValue())) {
                problems.add("SCOPE_NOT_ALLOWED");
            } else {
                known.add(permission.get());
            }
        }
        if (SeparationOfDuties.conflicting(known)) {
            problems.add("SOD_CONFLICT");
        }
        return problems.stream().distinct().toList();
    }

    private static boolean name(String value) {
        return value != null && value.strip().length() >= 2 && value.length() <= 100
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
