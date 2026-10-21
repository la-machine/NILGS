// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code keys}: down when key custody cannot serve requests (OpenBao
 * sealed, unreachable or refusing the application's login). Part of the readiness group,
 * so the proxy stops routing to an instance that could not sign or decrypt.
 */
@Component("keys")
class KeysHealthIndicator extends AbstractHealthIndicator {

    private final CustodyKeyService keys;

    KeysHealthIndicator(CustodyKeyService keys) {
        super("Key custody unavailable");
        this.keys = keys;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        keys.checkAvailable();
        builder.up();
    }
}
