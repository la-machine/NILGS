// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.web.CorrelationIdFilter;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole skeleton against real PostGIS: the context starts under the test profile
 * (so EnvironmentGuard passes), connects as snigf_app, reports healthy, and refuses
 * anonymous calls to protected endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationSmokeIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgisDatabase db = PostgisDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.password", db::appPassword);
    }

    @Autowired
    Environment environment;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void healthIsUpIncludingTheDatabaseOnTheLoopbackManagementPort() throws Exception {
        HttpResponse<String> response = get("local.management.port", "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void protectedEndpointsRequireAuthenticationAndCarryAServerCorrelationId() throws Exception {
        HttpResponse<String> response = get("local.server.port", "/api/v1/parcels");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTH_REQUIRED");
        String correlationId = response.headers().firstValue(CorrelationIdFilter.HEADER).orElseThrow();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void applicationConnectsAsLeastPrivilegeRoleThatCannotChangeTheSchema() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("snigf_app");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE snigf.should_not_exist (id int)"))
                .rootCause().hasMessageContaining("permission denied for schema snigf");
        assertThatThrownBy(() -> jdbc.execute("UPDATE snigf.audit_event SET reason = 'x'"))
                .rootCause().hasMessageContaining("permission denied for table audit_event");
    }

    @Test
    void managementEndpointsAreNotServedOnTheApiPort() throws Exception {
        HttpResponse<String> response = get("local.server.port", "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("\"status\":\"UP\"");
    }

    private HttpResponse<String> get(String portProperty, String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getProperty(portProperty) + path);
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
