// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.RoleGrant;
import org.snigf.mvp.shared.security.Permission;
import org.snigf.mvp.shared.security.Permission.Scope;
import org.snigf.mvp.support.PostgisDatabase.RepositoryPaths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-025: the baseline roles are code ({@link BaselineRole}), seeded by V7 and reviewed through
 * docs/permission-matrix.md. The document's tables must be exactly the ones rendered here;
 * change both together. Also checks how role grants become authorities, and the catalogue's
 * own rules.
 */
class PermissionMatrixTest {

    /** The baseline roles as the database holds them. */
    static Map<String, RoleDefinition> baseline() {
        return Arrays.stream(BaselineRole.values()).collect(Collectors.toMap(Enum::name, r -> new RoleDefinition(
                r.name(), r.nameFr(), r.nameEn(), null, true, r != BaselineRole.APPLICANT, true,
                r.permissions().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().code(), Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new)))));
    }

    /** The catalogue table, as docs/permission-matrix.md must show it. */
    static String renderCatalogue() {
        StringBuilder out = new StringBuilder("| Permission | Family | Delegable | Scopes | Description |\n");
        out.append("| --- | --- | --- | --- | --- |");
        for (Permission p : Permission.values()) {
            out.append("\n| `").append(p.code()).append("` | ").append(p.family().name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(p.delegable() ? "yes" : "no").append(" | ")
                    .append(p.scopes().stream().map(s -> s.name().toLowerCase(Locale.ROOT)).sorted()
                            .collect(Collectors.joining(", ")))
                    .append(" | ").append(p.descriptionEn()).append(" |");
        }
        return out.toString();
    }

    /** The baseline role matrix, as docs/permission-matrix.md must show it. */
    static String renderRoles() {
        StringBuilder out = new StringBuilder("| Permission |");
        for (BaselineRole role : BaselineRole.values()) {
            out.append(' ').append(role.name()).append(" |");
        }
        out.append("\n| --- |").append(" --- |".repeat(BaselineRole.values().length));
        for (Permission permission : Permission.values()) {
            out.append("\n| `").append(permission.code()).append("` |");
            for (BaselineRole role : BaselineRole.values()) {
                Scope scope = role.permissions().get(permission);
                out.append(' ').append(scope == null ? "—" : scope.name().toLowerCase(Locale.ROOT)).append(" |");
            }
        }
        return out.toString();
    }

    @Test
    void theDocumentShowsExactlyTheMatrixInCode() throws Exception {
        String document = Files.readString(RepositoryPaths.file("docs/permission-matrix.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertThat(document).as("docs/permission-matrix.md must contain this table:\n" + renderCatalogue())
                .contains(renderCatalogue());
        assertThat(document).as("docs/permission-matrix.md must contain this table:\n" + renderRoles())
                .contains(renderRoles());
    }

    @Test
    void grantsBecomeAuthoritiesByScope() {
        Map<String, RoleDefinition> roles = baseline();
        Set<String> officer = EffectivePermissions.authorities(Set.of(new RoleGrant("CADASTRAL_OFFICER", "DEMO1")), roles);
        assertThat(officer).contains("parcel.create@DEMO1", "parcel.correct@DEMO1", "parcel.read", "alert.resolve@DEMO1")
                .doesNotContain("parcel.create", "audit.event.search", "admin.role.assign");

        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("AUDITOR", "DEMO1")), roles))
                .containsExactlyInAnyOrder("audit.event.search", "audit.chain.verify", "audit.checkpoint.create",
                        "audit.checkpoint.list", "audit.checkpoint.download", "audit.key.read", "parcel.read",
                        "application.read");
        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("SUPERVISOR", "DEMO2")), roles))
                .contains("audit.event.search@DEMO2", "audit.chain.verify", "identity.reset.confirm@DEMO2")
                .doesNotContain("audit.event.search", "identity.reset.confirm");
        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("SYSTEM_ADMIN", "DEMO1")), roles))
                .hasSize(13).allMatch(a -> a.startsWith("admin."));
        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("NOT_A_ROLE", "DEMO1")), roles)).isEmpty();
    }

    @Test
    void relativeScopesNeverCountAsEverywhere() {
        Map<String, RoleDefinition> roles = baseline();
        Set<String> applicant = EffectivePermissions.authorities(Set.of(new RoleGrant("APPLICANT", null)), roles);
        assertThat(applicant).contains("parcel.read#linked", "application.read#own", "payment.pay#own")
                .doesNotContain("parcel.read", "application.read", "payment.pay");
        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("REVIEWER", "DEMO1")), roles))
                .contains("parcel.read", "application.read#assigned", "review.recommend#assigned")
                .doesNotContain("application.read", "review.recommend");
    }

    @Test
    void aRetiredRoleGrantsNothing() {
        RoleDefinition retired = new RoleDefinition("CLERK", "Commis", "Clerk", null, false, true, false,
                Map.of("parcel.read", Scope.ANY));
        assertThat(EffectivePermissions.authorities(Set.of(new RoleGrant("CLERK", "DEMO1")), Map.of("CLERK", retired)))
                .isEmpty();
    }

    @Test
    void noBaselineRoleBothAdministersAndAudits() {
        for (BaselineRole role : BaselineRole.values()) {
            assertThat(SeparationOfDuties.conflicting(role.permissions().keySet())).as(role.name()).isFalse();
        }
    }

    @Test
    void everyRoleScopeIsOneTheCatalogueAllows() {
        for (BaselineRole role : BaselineRole.values()) {
            role.permissions().forEach((permission, scope) ->
                    assertThat(permission.scopes()).as(role + " " + permission.code()).contains(scope));
        }
    }

    @Test
    void administrationIsNeverDelegableAndCodesAreUnique() {
        assertThat(Arrays.stream(Permission.values()).filter(p -> p.family() == Permission.Family.ADMINISTRATION))
                .allMatch(p -> !p.delegable());
        List<String> codes = Arrays.stream(Permission.values()).map(Permission::code).toList();
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[a-z]+(\\.[a-z-]+)+"));
        assertThat(Arrays.stream(Permission.values()).collect(Collectors.toMap(Permission::code, Function.identity())))
                .allSatisfy((code, p) -> assertThat(Permission.ofCode(code)).contains(p));
    }

    @Test
    void customRolesAreValidatedAgainstTheCatalogue() {
        assertThat(RoleDefinition.problems("LAND_CLERK", "Commis foncier", "Land clerk", null,
                Map.of("parcel.read", Scope.ANY))).isEmpty();
        assertThat(RoleDefinition.problems("land clerk", "Commis", "Clerk", null, Map.of("parcel.read", Scope.ANY)))
                .contains("ROLE_CODE_INVALID");
        assertThat(RoleDefinition.problems("CLERK", "Commis", "Clerk", null, Map.of())).contains("ROLE_WITHOUT_PERMISSION");
        assertThat(RoleDefinition.problems("CLERK", "Commis", "Clerk", null, Map.of("parcel.fly", Scope.ANY)))
                .contains("PERMISSION_UNKNOWN");
        assertThat(RoleDefinition.problems("CLERK", "Commis", "Clerk", null, Map.of("payment.reconcile", Scope.OFFICE)))
                .contains("SCOPE_NOT_ALLOWED");
        assertThat(RoleDefinition.problems("CLERK", "Commis", "Clerk", null,
                Map.of("admin.role.assign", Scope.ANY, "audit.chain.verify", Scope.ANY))).contains("SOD_CONFLICT");
    }
}
