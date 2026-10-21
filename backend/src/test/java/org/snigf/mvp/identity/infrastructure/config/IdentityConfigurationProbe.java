// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.shared.config.DeploymentEnvironment;

/** Test access to the package-private checks of {@link IdentityConfiguration}. */
public final class IdentityConfigurationProbe {

    private IdentityConfigurationProbe() {
    }

    public static List<String> check(IdentityProperties identity, DeploymentEnvironment environment, Duration idle) {
        return IdentityConfiguration.check(identity, environment, idle);
    }

    public static Set<String> commonPasswords() {
        return IdentityConfiguration.commonPasswords();
    }
}
