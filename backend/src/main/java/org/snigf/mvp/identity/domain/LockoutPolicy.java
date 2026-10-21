// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Lockout P6: after {@code maxFailures} consecutive wrong passwords the account is locked
 * for {@code lockDuration}, and the counter starts again. A locked account answers exactly
 * like a wrong password, so an attacker cannot tell.
 *
 * @param maxFailures  consecutive failures that lock the account
 * @param lockDuration how long it stays locked
 */
public record LockoutPolicy(int maxFailures, Duration lockDuration) {

    public LockoutPolicy {
        if (maxFailures < 1 || lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("lockout policy needs at least one failure and a positive duration");
        }
    }

    public Instant lockedUntil(Instant now) {
        return now.plus(lockDuration);
    }
}
