// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditIntegrationTest;
import org.snigf.mvp.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-AC5, docs/audit.md "Canonical encoding": the Java encoding ({@link ChainedEvent})
 * produces exactly the bytes and hashes of {@code audit_canonical} in the database, for
 * values chosen to break a naive implementation.
 */
class CanonicalHashParityIT extends AuditIntegrationTest {

    @Autowired
    AuditEventRepository events;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void javaAndSqlProduceIdenticalCanonicalBytesAndHashes() {
        List<Long> positions = new ArrayList<>();
        inTransaction(() -> {
            positions.add(append(Actor.anonymous(), AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILED),
                    null, "{}"));
            positions.add(append(Actor.user(UUID.randomUUID(), List.of("REVIEWER", "AUDITOR")),
                    AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS)
                            .withEntity("PARCEL", UUID.randomUUID()).withEntityRef("DEMO|1:2~3")
                            .withReason("Décision — 土地 🏠 \"quoted\" a|b 3:x ~\nnew line\ttab\\"),
                    "203.0.113.7",
                    "{\"z\": 1, \"a\": [1.50, 1e3, -0, true, null], \"nested\": {\"é\": \"\\u0001\\\"\"}}"));
            positions.add(append(Actor.service("audit-parity"), AuditEvent.of(AuditAction.AUDIT_VERIFIED,
                    AuditOutcome.SUCCESS), "2001:db8::1", "{\"emoji\": \"🏠\", \"empty\": \"\"}"));
            positions.add(append(Actor.anonymous(), AuditEvent.of(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .withReason(""), "::ffff:192.0.2.1", "{}"));
            return null;
        });

        for (long pos : positions) {
            List<ChainedEvent> read = new ArrayList<>();
            inTransaction(() -> {
                events.forEach(pos, pos, read::add);
                return null;
            });
            ChainedEvent event = read.getFirst();
            byte[] sql = jdbc.queryForObject("SELECT audit_canonical(e) FROM audit_event e WHERE chain_pos = ?",
                    byte[].class, pos);

            assertThat(new String(event.canonical(), java.nio.charset.StandardCharsets.UTF_8))
                    .as("canonical form at %d", pos)
                    .isEqualTo(new String(sql, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(event.canonical()).isEqualTo(sql);
            assertThat(event.computeHash()).as("hash at %d", pos).isEqualTo(event.hash());
        }
    }

    @Test
    void roleArraysAreEscapedLikePostgres() {
        List<String> values = Arrays.asList("plain", "q\"b\\s", "\n\t\r\b\f", "\u0001\u001f", "é土🏠", null, "");
        String sql = jdbc.execute((ConnectionCallback<String>) connection -> {
            Array array = connection.createArrayOf("text", values.toArray());
            try (var statement = connection.prepareStatement("SELECT array_to_json(?::text[])::text")) {
                statement.setArray(1, array);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });

        assertThat(ChainedEvent.jsonArray(values)).isEqualTo(sql);
    }

    private long append(Actor actor, AuditEvent event, String ip, String details) {
        return events.append(UUID.randomUUID(), actor, event, UUID.randomUUID(), ip, details);
    }
}
