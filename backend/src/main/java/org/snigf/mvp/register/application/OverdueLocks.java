// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.util.List;
import java.util.Map;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.register.domain.Register;
import org.snigf.mvp.register.domain.RegisterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records holding locks past their review date, once each, under the service identity
 * {@value #ACTOR} (ADR-040 §4, D8). It only makes them visible: nothing expires and nothing is
 * released automatically; a conservator decides, with a reason.
 */
@Service
public class OverdueLocks {

    static final String ACTOR = "register-overdue-locks";

    private final RegisterRepository register;
    private final AuditRecorder audit;

    public OverdueLocks(RegisterRepository register, AuditRecorder audit) {
        this.register = register;
        this.audit = audit;
    }

    /** Returns how many locks were newly recorded as overdue. */
    @Transactional
    public int record() {
        return SystemActor.call(ACTOR, () -> {
            List<Register.OverdueLock> locks = register.recordOverdueLocks();
            for (Register.OverdueLock lock : locks) {
                audit.record(AuditEvent.of(AuditAction.REGISTER_LOCK_OVERDUE, AuditOutcome.SUCCESS)
                        .withEntity(RegisterService.ENTITY_TRANSACTION, lock.transactionId())
                        .withDetails(Map.of("dueAt", lock.dueAt().toString())));
            }
            return locks.size();
        });
    }
}
