// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.support;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests and the deployed stack must use the same PostGIS image and port (ADR-003, ADR-015). */
class ComposeImageParityTest {

    @Test
    void testcontainersImageMatchesComposeImage() throws Exception {
        String compose = Files.readString(PostgisDatabase.RepositoryPaths.file("compose.yml"));

        assertThat(compose).contains("x-postgis-image: &postgis-image " + PostgisDatabase.IMAGE);
    }

    @Test
    void testcontainersPortMatchesTheConfiguredDatabasePort() throws Exception {
        String env = Files.readString(PostgisDatabase.RepositoryPaths.file(".env.example"));

        assertThat(env).containsPattern("(?m)^SNIGF_DB_PORT=" + PostgisDatabase.PORT + "$");
    }
}
