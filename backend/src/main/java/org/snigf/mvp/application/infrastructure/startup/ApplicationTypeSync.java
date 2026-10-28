// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.startup;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.application.application.ApplicationProperties;
import org.snigf.mvp.application.application.ApplicationTypeCatalogue;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.audit.SystemActor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the application type catalogue ({@code SNIGF_APPLICATION_TYPES}) before the
 * application takes traffic. Unknown properties in the file are refused, so a misspelt setting
 * never passes silently; an invalid catalogue stops the start. Changes are recorded under the
 * service identity {@code application-types}.
 */
@Component
class ApplicationTypeSync implements ApplicationRunner {

    static final String ACTOR = "application-types";
    private static final Logger log = LoggerFactory.getLogger(ApplicationTypeSync.class);

    /** The file: {@code {"types": [ ... ]}}. */
    record Catalogue(List<ApplicationType.Definition> types) {
    }

    private final ApplicationTypeCatalogue catalogue;
    private final ApplicationProperties settings;
    private final ResourceLoader resources;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    ApplicationTypeSync(ApplicationTypeCatalogue catalogue, ApplicationProperties settings, ResourceLoader resources) {
        this.catalogue = catalogue;
        this.settings = settings;
        this.resources = resources;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Resource file = resources.getResource(settings.types());
        if (!file.exists()) {
            throw new IllegalStateException("Application type catalogue not found: " + settings.types());
        }
        Catalogue read;
        try (InputStream in = file.getInputStream()) {
            read = json.readValue(in, Catalogue.class);
        }
        List<ApplicationType.Definition> types = read.types() == null ? List.of() : read.types();
        int written = SystemActor.call(ACTOR, () -> catalogue.apply(types));
        log.info("Application types: {} configured from {}{}", types.size(), settings.types(),
                written > 0 ? " (" + written + " new version(s))" : "");
    }
}
