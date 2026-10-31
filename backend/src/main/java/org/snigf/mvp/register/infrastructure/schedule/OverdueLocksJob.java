// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.schedule;

import org.snigf.mvp.register.application.OverdueLocks;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Records overdue register locks ({@code SNIGF_REGISTER_OVERDUE_CRON}; {@code -} turns it off). */
@Component
class OverdueLocksJob {

    private final OverdueLocks locks;

    OverdueLocksJob(OverdueLocks locks) {
        this.locks = locks;
    }

    @Scheduled(cron = "${snigf.register.overdue-cron}")
    void run() {
        locks.record();
    }
}
