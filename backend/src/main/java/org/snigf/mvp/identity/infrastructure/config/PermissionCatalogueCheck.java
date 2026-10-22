// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.config;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.snigf.mvp.shared.security.Permission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the permission catalogue in the code and the {@code permission} table
 * disagree (ADR-025): a permission the code checks but the database lacks could never be
 * granted, and one the database has but the code does not check would grant nothing while
 * looking real. A new permission arrives with a migration in the same change.
 */
@Component
public class PermissionCatalogueCheck {

    record Row(String family, boolean delegable, Set<String> scopes) {
    }

    public PermissionCatalogueCheck(JdbcClient jdbc) {
        Map<String, Row> database = new HashMap<>();
        jdbc.sql("SELECT code, family, delegable, scopes FROM permission").query(rs -> {
            Array scopes = rs.getArray(4);
            database.put(rs.getString(1), new Row(rs.getString(2), rs.getBoolean(3),
                    new TreeSet<>(Arrays.asList((String[]) scopes.getArray()))));
        });
        List<String> problems = check(database);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Permission catalogue differs from the database, refusing to start:\n - "
                    + String.join("\n - ", problems));
        }
    }

    static List<String> check(Map<String, Row> database) {
        List<String> problems = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Row row = database.get(p.code());
            Set<String> scopes = p.scopes().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
            if (row == null) {
                problems.add(p.code() + " is missing from the permission table (add it in a migration)");
            } else if (!row.family().equals(p.family().name()) || row.delegable() != p.delegable()
                    || !row.scopes().equals(scopes)) {
                problems.add(p.code() + " differs: database " + row + ", code " + p.family() + "/" + p.delegable() + "/" + scopes);
            }
        }
        Set<String> codes = Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());
        database.keySet().stream().filter(code -> !codes.contains(code)).sorted()
                .forEach(code -> problems.add(code + " is in the database but not in the code"));
        return problems;
    }
}
