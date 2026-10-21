// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SNIGF/NILGS — demonstration software using synthetic data. It is not approved,
 * deployed or endorsed by any government institution and issues no legally valid
 * titles or decisions.
 *
 * <p>One modular monolith (ADR-001). Each direct sub-package is an application module;
 * modules talk to each other only through their top-level public types, which
 * {@code ModularityTest} verifies.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SnigfApplication {

    public static void main(String[] args) {


        SpringApplication.run(SnigfApplication.class, args);

    }

}
