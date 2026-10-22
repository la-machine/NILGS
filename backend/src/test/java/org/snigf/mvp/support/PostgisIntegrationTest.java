// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The one application context the integration tests share: the whole application on the
 * shared PostGIS (profile {@code test}), with MockMvc. Every test class that needs the
 * context extends this class, so Spring caches a single context. That matters: the test
 * profile generates its keys in memory per context, and checkpoints signed by one context
 * would rightly fail verification in another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgisIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgisDatabase db = PostgisDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.password", db::appPassword);
    }
}
