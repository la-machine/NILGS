// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

/** Deployment environments (Release plan §4). Each has a Spring profile of the same name. */
public enum DeploymentEnvironment {
    DEV,
    TEST,
    STAGING,
    PROD;

    public String profile() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
