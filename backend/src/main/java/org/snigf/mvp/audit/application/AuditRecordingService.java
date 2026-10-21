// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;

import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditReceipt;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.audit.domain.AuditDetails;
import org.snigf.mvp.audit.domain.AuditEventRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Use case "record an audit event". Checks what the database cannot: the transaction is
 * the caller's and suitable, and {@code details} holds only plain values within the size
 * limit. The actor, address and correlation id come from {@link AuditRequestContext}.
 */
@Service
public class AuditRecordingService implements AuditRecorder {

    private final AuditEventRepository events;
    private final AuditRequestContext context;
    private final JsonMapper json;
    private final int maxDetailsBytes;

    public AuditRecordingService(AuditEventRepository events, AuditRequestContext context, JsonMapper json,
                                 AuditProperties properties) {
        this.events = events;
        this.context = context;
        this.json = json;
        this.maxDetailsBytes = properties.maxDetailsBytes();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditReceipt record(AuditEvent event) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("audit events cannot be recorded in a read-only transaction");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
            // Under a stricter level the chain trigger reads a stale head (V3__audit.sql).
            throw new IllegalTransactionStateException("audit events require READ COMMITTED, found level " + isolation);
        }
        return append(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public AuditReceipt recordIndependently(AuditEvent event) {
        if (event.outcome() == AuditOutcome.SUCCESS) {
            throw new IllegalArgumentException("a successful event belongs to its business transaction: use record()");
        }
        return append(event);
    }

    private AuditReceipt append(AuditEvent event) {
        AuditDetails.requirePlain(event.details());
        AuditDetails.requireNoNul(event.reason(), "reason");
        AuditDetails.requireNoNul(event.entityRef(), "entity reference");
        String details = json.writeValueAsString(event.details());
        if (details.getBytes(StandardCharsets.UTF_8).length > maxDetailsBytes) {
            throw new IllegalArgumentException("audit details exceed " + maxDetailsBytes + " bytes");
        }
        UUID id = UuidV7.next();
        long chainPos = events.append(id, context.actor(), event, context.correlationId(), context.sourceIp(), details);
        return new AuditReceipt(id, chainPos);
    }
}
