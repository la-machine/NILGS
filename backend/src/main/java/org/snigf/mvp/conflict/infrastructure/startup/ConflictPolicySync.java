// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.conflict.application.ConflictPolicyService;
import org.snigf.mvp.conflict.application.ConflictProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Applies the detection thresholds before the application takes traffic (runners finish
 * before readiness). A change is recorded under the service identity {@code conflict-policy};
 * invalid values stop the start.
 */
@Component
class ConflictPolicySync implements ApplicationRunner {

    static final String ACTOR = "conflict-policy";
    private static final Logger log = LoggerFactory.getLogger(ConflictPolicySync.class);

    private final ConflictPolicyService policy;
    private final ConflictProperties settings;

    ConflictPolicySync(ConflictPolicyService policy, ConflictProperties settings) {
        this.policy = policy;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean changed = SystemActor.call(ACTOR, () -> policy.apply(settings));
        log.info("Conflict detection: overlaps from {} m2, duplicates from {} of each parcel{}",
                settings.minOverlapM2(), settings.duplicateShare(), changed ? " (policy updated)" : "");
    }
}
