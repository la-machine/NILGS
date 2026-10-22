// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.snigf.mvp.support.PostgisIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared setup of the audit integration tests: the whole application against real PostGIS
 * (migrations applied as snigf_migrator, connected as snigf_app), one cached context. The
 * database is shared by every test, so tests use positions they created themselves and
 * undo any tampering.
 */
public abstract class AuditIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    protected AuditRecorder recorder;

    @Autowired
    protected AuditTrail trail;

    @Autowired
    protected PlatformTransactionManager transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(tx -> work.get());
    }

    protected AuditReceipt recordEvent(String marker) {
        return inTransaction(() -> recorder.record(AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS)
                .withEntityRef("TEST-" + marker.hashCode())
                .withDetails(Map.of("marker", marker))));
    }
}
