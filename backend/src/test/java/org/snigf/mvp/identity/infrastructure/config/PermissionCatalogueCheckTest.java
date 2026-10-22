// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.security.Permission;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-025: the startup check names every difference between the code and the database. */
class PermissionCatalogueCheckTest {

    static Map<String, PermissionCatalogueCheck.Row> asInCode() {
        Map<String, PermissionCatalogueCheck.Row> rows = new HashMap<>();
        for (Permission p : Permission.values()) {
            rows.put(p.code(), new PermissionCatalogueCheck.Row(p.family().name(), p.delegable(),
                    p.scopes().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new))));
        }
        return rows;
    }

    @Test
    void theSameCatalogueHasNoProblem() {
        assertThat(PermissionCatalogueCheck.check(asInCode())).isEmpty();
    }

    @Test
    void everyKindOfDifferenceIsNamed() {
        Map<String, PermissionCatalogueCheck.Row> database = asInCode();
        database.remove("parcel.create");
        database.put("audit.key.read", new PermissionCatalogueCheck.Row("AUDIT", true, Set.of("ANY", "OFFICE")));
        database.put("admin.mfa.reset", new PermissionCatalogueCheck.Row("ADMINISTRATION", true, Set.of("ANY")));
        database.put("parcel.delete", new PermissionCatalogueCheck.Row("BUSINESS", true, Set.of("ANY")));

        assertThat(PermissionCatalogueCheck.check(database)).hasSize(4)
                .anyMatch(p -> p.startsWith("parcel.create is missing"))
                .anyMatch(p -> p.startsWith("audit.key.read differs"))
                .anyMatch(p -> p.startsWith("admin.mfa.reset differs"))
                .anyMatch(p -> p.startsWith("parcel.delete is in the database but not in the code"));
    }
}
