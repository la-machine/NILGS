// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recording (Slice 1 design §7): inside the business transaction, or independently for
 * denials; the actor comes from the context, never the caller; the app role can only append.
 */
class AuditRecorderIT extends AuditIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    record StaffPrincipal(UUID userId, Set<String> auditRoles) implements AuditedPrincipal {
    }

    @Test
    void recordsInsideTheCallersTransactionWithDatabaseAssignedChainFields() {
        AuditReceipt receipt = recordEvent("inside");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT chain_pos, id, action, outcome, actor_type, details->>'marker' AS marker FROM audit_event WHERE id = ?",
                receipt.id());
        assertThat(row).containsEntry("chain_pos", receipt.chainPos()).containsEntry("action", "PARCEL_CREATED")
                .containsEntry("outcome", "SUCCESS").containsEntry("marker", "inside");
        assertThat(receipt.id().version()).as("UUIDv7, ADR-008").isEqualTo(7);
    }

    @Test
    void refusesToRecordWithoutATransaction() {
        assertThatThrownBy(() -> recorder.record(AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackBusinessChangeTakesItsEventWithItButLeavesNoGap() {
        long before = head();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            recorder.record(AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS));
            status.setRollbackOnly();
        });
        AuditReceipt next = recordEvent("after rollback");

        assertThat(next.chainPos()).isEqualTo(before + 1);
    }

    @Test
    void aDenialSurvivesTheRollbackOfTheAttempt() {
        UUID[] denied = new UUID[1];
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            denied[0] = recorder.recordIndependently(AuditEvent.of(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .withReason("missing permission")).id();
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id = ?", Long.class, denied[0]))
                .isEqualTo(1);
        assertThatThrownBy(() -> recorder.recordIndependently(AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS)))
                .hasMessageContaining("business transaction");
    }

    @Test
    void refusesReadOnlyAndStricterIsolationTransactions() {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(s ->
                recorder.record(AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS))))
                .hasMessageContaining("read-only");

        TransactionTemplate repeatable = new TransactionTemplate(transactions);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.executeWithoutResult(s ->
                recorder.record(AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS))))
                .hasMessageContaining("READ COMMITTED");
    }

    @Test
    void attributesEventsToTheAuthenticatedUserAndTheirRoles() {
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                new StaffPrincipal(user, Set.of("REVIEWER", "AUDITOR")), null, "ROLE_STAFF"));

        AuditReceipt receipt = recordEvent("user");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_type, actor_id, array_to_string(actor_roles, ',') AS roles FROM audit_event WHERE id = ?",
                receipt.id());
        assertThat(row).containsEntry("actor_type", "USER").containsEntry("actor_id", user)
                .containsEntry("roles", "AUDITOR,REVIEWER");
    }

    @Test
    void refusesAnAuthenticatedPrincipalItCannotAttribute() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("just-a-name", null, "ROLE_STAFF"));

        assertThatThrownBy(() -> recordEvent("unattributable")).hasMessageContaining("AuditedPrincipal");
    }

    @Test
    void attributesScheduledWorkToTheNamedServiceUnderAFreshCorrelationId() {
        AuditReceipt first = SystemActor.call("audit-test-job", () -> recordEvent("service 1"));
        AuditReceipt second = SystemActor.call("audit-test-job", () -> recordEvent("service 2"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_type, actor_label, correlation_id FROM audit_event WHERE id IN (?, ?)",
                first.id(), second.id());
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("actor_type", "SERVICE")
                .containsEntry("actor_label", "audit-test-job"));
        assertThat(rows.get(0).get("correlation_id")).isNotEqualTo(rows.get(1).get("correlation_id"));
        assertThat(SystemActor.current()).as("the binding ends with the task").isEmpty();
    }

    @Test
    void anonymousCallersAreRecordedAsAnonymous() {
        AuditReceipt receipt = recordEvent("anonymous");

        assertThat(jdbc.queryForObject("SELECT actor_type FROM audit_event WHERE id = ?", String.class, receipt.id()))
                .isEqualTo("ANONYMOUS");
    }

    @Test
    void refusesOversizedOrNonPlainDetails() {
        assertThatThrownBy(() -> inTransaction(() -> recorder.record(
                AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS).withDetails(Map.of("big", "x".repeat(9000))))))
                .hasMessageContaining("exceed");
        assertThatThrownBy(() -> inTransaction(() -> recorder.record(
                AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS).withDetails(Map.of("user", new Object())))))
                .hasMessageContaining("unsupported type");
    }

    @Test
    void theApplicationRoleCanNeverChangeOrDeleteAuditRecords() {
        recordEvent("immutable");
        for (String sql : List.of("UPDATE audit_event SET reason = 'x'", "DELETE FROM audit_event",
                "TRUNCATE audit_event", "UPDATE audit_checkpoint SET key_id = 'x'", "DELETE FROM audit_checkpoint")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).rootCause().hasMessageContaining("permission denied");
        }
    }

    private long head() {
        Long pos = jdbc.queryForObject("SELECT max(chain_pos) FROM audit_event", Long.class);
        return pos == null ? 0 : pos;
    }
}
