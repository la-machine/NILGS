// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** ADR-001 / C10: module boundaries are verified on every build. */
class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(SnigfApplication.class).verify();
    }
}
