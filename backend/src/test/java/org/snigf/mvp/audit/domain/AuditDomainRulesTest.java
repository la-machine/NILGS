// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEncoding;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Value rules of the audit domain: what an event may say and how it is encoded. */
class AuditDomainRulesTest {

    @Test
    void fieldsAreLengthPrefixedInUtf8BytesSoNoValueCanImitateASeparator() {
        assertThat(AuditEncoding.field(null)).isEqualTo("~");
        assertThat(AuditEncoding.field("")).isEqualTo("0:");
        assertThat(AuditEncoding.field("a|b")).isEqualTo("3:a|b");
        assertThat(AuditEncoding.field("é")).as("two UTF-8 bytes").isEqualTo("2:é");
    }

    @Test
    void roleArraysUsePostgresJsonEscaping() {
        assertThat(ChainedEvent.jsonArray(List.of())).isEqualTo("[]");
        assertThat(ChainedEvent.jsonArray(List.of("A", "B_C"))).isEqualTo("[\"A\",\"B_C\"]");
        assertThat(ChainedEvent.jsonArray(Arrays.asList("q\"b\\s", "\n\t\u0001", null)))
                .isEqualTo("[\"q\\\"b\\\\s\",\"\\n\\t\\u0001\",null]");
    }

    @Test
    void theCanonicalFormCoversEveryFieldInOrder() {
        UUID id = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID corr = UUID.fromString("00000000-0000-4000-8000-000000000002");
        ChainedEvent e = new ChainedEvent(7, id, Instant.parse("2026-10-21T08:00:00.000001Z"), "SERVICE", null,
                "job", List.of(), "TEST_EVENT", null, null, null, "SUCCESS", null, corr, null, "{}",
                null, new byte[32], new byte[32]);

        assertThat(new String(e.canonical(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "snigf-audit-v1|1:7|36:" + id + "|27:2026-10-21T08:00:00.000001Z|7:SERVICE|~|3:job|2:[]"
                        + "|10:TEST_EVENT|~|~|~|7:SUCCESS|~|36:" + corr + "|~|2:{}");
    }

    @Test
    void anEventWithSubjectsIsEncodedV2AndItsSubjectsAreHashed() {
        UUID id = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID corr = UUID.fromString("00000000-0000-4000-8000-000000000002");
        String subjects = "[{\"id\": \"00000000-0000-7000-8000-0000000000a1\", \"type\": \"PARCEL\"}]";
        ChainedEvent e = new ChainedEvent(7, id, Instant.parse("2026-10-21T08:00:00.000001Z"), "SERVICE", null,
                "job", List.of(), "TEST_EVENT", null, null, null, "SUCCESS", null, corr, null, "{}",
                subjects, new byte[32], new byte[32]);

        assertThat(new String(e.canonical(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "snigf-audit-v2|1:7|36:" + id + "|27:2026-10-21T08:00:00.000001Z|7:SERVICE|~|3:job|2:[]"
                        + "|10:TEST_EVENT|~|~|~|7:SUCCESS|~|36:" + corr + "|~|2:{}|" + subjects.length() + ":" + subjects);
        ChainedEvent without = new ChainedEvent(7, id, e.occurredAt(), "SERVICE", null, "job", List.of(), "TEST_EVENT",
                null, null, null, "SUCCESS", null, corr, null, "{}", null, new byte[32], new byte[32]);
        assertThat(e.computeHash()).as("removing the subjects changes the hash").isNotEqualTo(without.computeHash());
    }

    @Test
    void actorsAreCoherentAndRolesAreSafeNames() {
        UUID user = UUID.randomUUID();
        assertThat(Actor.user(user, List.of("REVIEWER", "AUDITOR")).roles()).containsExactly("AUDITOR", "REVIEWER");
        assertThat(Actor.service("audit-verifier").label()).isEqualTo("audit-verifier");
        assertThat(Actor.anonymous().type()).isEqualTo("ANONYMOUS");
        assertThatThrownBy(() -> Actor.user(user, List.of("AUDITOR}', 'X"))).hasMessageContaining("role name");
        assertThatThrownBy(() -> Actor.service("Audit Verifier")).hasMessageContaining("label");
        assertThatThrownBy(() -> Actor.user(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void detailsHoldPlainValuesOnly() {
        AuditDetails.requirePlain(Map.of("id", UUID.randomUUID(), "n", 3, "ok", true, "when", Instant.now(),
                "list", List.of("a", 1), "nested", Map.of("k", "v"), "kind", AuditOutcome.DENIED));

        assertThatThrownBy(() -> AuditDetails.requirePlain(Map.of("user", new Object())))
                .hasMessageContaining("unsupported type");
        assertThatThrownBy(() -> AuditDetails.requirePlain(Map.of("nested", Map.of(1, "v"))))
                .hasMessageContaining("keys must be strings");
        assertThatThrownBy(() -> AuditDetails.requirePlain(Map.of("s", "a\0b")))
                .hasMessageContaining("NUL");
    }

    @Test
    void eventsRefuseMalformedFields() {
        AuditEvent ok = AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS)
                .withEntity("PARCEL", UUID.randomUUID()).withEntityRef("DEMO-0001").withReason("reason");
        assertThat(ok.details()).isEmpty();

        assertThatThrownBy(() -> ok.withEntity("parcel", null)).hasMessageContaining("upper snake case");
        assertThatThrownBy(() -> ok.withEntityRef("x".repeat(65))).hasMessageContaining("64");
        assertThatThrownBy(() -> ok.withReason("x".repeat(1001))).hasMessageContaining("1000");
        assertThatThrownBy(() -> AuditEvent.of(null, AuditOutcome.SUCCESS)).isInstanceOf(NullPointerException.class);
    }
}
