// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostGIS server per test JVM, prepared exactly like a deployment:
 * {@code infra/db/init/01-roles.sql} as superuser, then every migration as
 * {@code snigf_migrator} (ADR-009). Tests connect as {@code snigf_app}.
 * PostgreSQL listens on the same non-default port as in compose.yml.
 * Passwords are random per run.
 */
public final class PostgisDatabase {

    /** Must equal {@code x-postgis-image} in compose.yml; {@code ComposeImageParityTest} checks it. */
    public static final String IMAGE =
            "postgis/postgis:18-3.6@sha256:b4466ea2ba7e3af54fcbba996d45325720f4b3ecb4f43f99b2f41367dc196f23";

    /** Same as PGPORT in compose.yml. */
    public static final int PORT = 47432;

    private static PostgisDatabase shared;

    private final GenericContainer<?> container;
    private final String appPassword;
    private final String migratorPassword;

    private PostgisDatabase() {
        this.appPassword = randomSecret();
        this.migratorPassword = randomSecret();
        this.container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("POSTGRES_DB", "snigf")
                .withEnv("POSTGRES_PASSWORD", randomSecret())
                .withEnv("PGPORT", String.valueOf(PORT))
                .withExposedPorts(PORT)
                // Logged once by the initialisation server and once by the real one.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        applyRoles();
        migrate();
    }

    public static synchronized PostgisDatabase shared() {
        if (shared == null) {
            shared = new PostgisDatabase();
        }
        return shared;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(PORT) + "/snigf";
    }

    public String appPassword() {
        return appPassword;
    }

    private void applyRoles() {
        container.copyFileToContainer(
                MountableFile.forHostPath(RepositoryPaths.file("infra/db/init/01-roles.sql")), "/tmp/01-roles.sql");
        try {
            ExecResult result = container.execInContainer(
                    "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "snigf",
                    "-v", "migrator_pw=" + migratorPassword,
                    "-v", "app_pw=" + appPassword,
                    "-v", "auditor_pw=" + randomSecret(),
                    "-f", "/tmp/01-roles.sql");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("01-roles.sql failed: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), "snigf_migrator", migratorPassword)
                .schemas("snigf")
                .defaultSchema("snigf")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String randomSecret() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Locates files relative to the repository root, whether tests run from backend/ or the root. */
    public static final class RepositoryPaths {

        private RepositoryPaths() {
        }

        public static Path file(String relative) {
            for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(relative);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(relative + " not found above " + Path.of("").toAbsolutePath());
        }
    }
}
