// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.health;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.application.AuditChainMonitor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code auditChain}: the latest verdict on the whole chain. UNKNOWN
 * until a full verification ran, DOWN once the chain failed (verification or refused
 * checkpoint). Deliberately outside the readiness group: a broken chain is an incident to
 * investigate, and taking the service offline would not repair it. Monitoring alerts on it.
 */
@Component("auditChain")
public class AuditChainHealthIndicator extends AbstractHealthIndicator implements AuditChainMonitor {

    private record Verdict(ChainVerification result, Instant at) {
    }

    private final AtomicReference<Verdict> last = new AtomicReference<>();

    public AuditChainHealthIndicator() {
        super("Audit chain status unavailable");
    }

    @Override
    public void report(ChainVerification verification) {
        last.set(new Verdict(verification, Instant.now()));
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Verdict verdict = last.get();
        if (verdict == null) {
            builder.unknown();
            return;
        }
        ChainVerification v = verdict.result();
        (v.verified() ? builder.up() : builder.down())
                .withDetail("verifiedAt", verdict.at().toString())
                .withDetail("checkedTo", v.checkedTo());
        if (!v.verified()) {
            builder.withDetail("firstMismatchPos", v.firstMismatchPos())
                    .withDetail("mismatchKind", v.mismatchKind().name());
        }
    }
}
