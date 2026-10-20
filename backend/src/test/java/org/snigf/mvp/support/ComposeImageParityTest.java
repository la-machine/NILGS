// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.support;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests and the deployed stack must use the same PostGIS image (ADR-003). */
class ComposeImageParityTest {

    @Test
    void testcontainersImageMatchesComposeImage() throws Exception {
        String compose = Files.readString(PostgisDatabase.RepositoryPaths.file("compose.yml"));

        assertThat(compose).contains("x-postgis-image: &postgis-image " + PostgisDatabase.IMAGE);
    }
}
