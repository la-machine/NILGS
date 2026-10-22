// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.domain.BaselineRole;
import org.snigf.mvp.shared.security.Permission;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-025: migration V7 initialised exactly the catalogue and the baseline roles of the code.
 * (The application would not have started otherwise for the catalogue; this also covers the
 * roles, their names and their scopes.)
 */
class RoleSeedIT extends IdentityIntegrationTest {

    @Test
    void theDatabaseHoldsTheCatalogueOfTheCode() {
        Map<String, String> database = new TreeMap<>();
        jdbc.query("SELECT code, family, delegable, array_to_string(scopes, ',') FROM permission", rs -> {
            database.put(rs.getString(1), rs.getString(2) + "/" + rs.getBoolean(3) + "/"
                    + Arrays.stream(rs.getString(4).split(",")).sorted().collect(Collectors.joining(",")));
        });
        Map<String, String> code = new TreeMap<>();
        for (Permission p : Permission.values()) {
            code.put(p.code(), p.family() + "/" + p.delegable() + "/"
                    + p.scopes().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        }
        assertThat(database).isEqualTo(code);
    }

    @Test
    void theBaselineRolesAreSeededAsImmutableSystemRoles() {
        Map<String, String> database = new TreeMap<>();
        jdbc.query("""
                SELECT d.code, d.name_fr, d.name_en, d.assignable, d.status,
                       string_agg(p.permission || '=' || p.scope, ',' ORDER BY p.permission COLLATE "C") AS permissions
                  FROM role_definition d JOIN role_permission p ON p.role_code = d.code
                 WHERE d.system GROUP BY d.code""", rs -> {
            database.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getBoolean(4) + "|"
                    + rs.getString(5) + "|" + rs.getString(6));
        });
        Map<String, String> code = new TreeMap<>();
        for (BaselineRole role : BaselineRole.values()) {
            code.put(role.name(), role.nameFr() + "|" + role.nameEn() + "|" + (role != BaselineRole.APPLICANT) + "|ACTIVE|"
                    + role.permissions().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Permission::code)))
                    .map(e -> e.getKey().code() + "=" + e.getValue()).collect(Collectors.joining(",")));
        }
        assertThat(database).isEqualTo(code);
    }
}
