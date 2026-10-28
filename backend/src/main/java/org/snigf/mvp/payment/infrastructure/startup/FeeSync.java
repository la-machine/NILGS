// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.startup;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.payment.application.FeeCatalogue;
import org.snigf.mvp.payment.application.PaymentProperties;
import org.snigf.mvp.payment.domain.Fee;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the DEMO fee catalogue ({@code SNIGF_PAYMENT_FEES}) and the payment gate
 * ({@code SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE}) before the application takes traffic.
 * Unknown properties in the file are refused, so a misspelt setting never passes silently; an
 * invalid catalogue stops the start. Changes are recorded under the service identity
 * {@code payment-fees}.
 */
@Component
class FeeSync implements ApplicationRunner {

    static final String ACTOR = "payment-fees";
    private static final Logger log = LoggerFactory.getLogger(FeeSync.class);

    /** The file: {@code {"fees": [ ... ]}}. */
    record Catalogue(List<Fee.Definition> fees) {
    }

    private final FeeCatalogue catalogue;
    private final PaymentProperties settings;
    private final ResourceLoader resources;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    FeeSync(FeeCatalogue catalogue, PaymentProperties settings, ResourceLoader resources) {
        this.catalogue = catalogue;
        this.settings = settings;
        this.resources = resources;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Resource file = resources.getResource(settings.fees());
        if (!file.exists()) {
            throw new IllegalStateException("DEMO fee catalogue not found: " + settings.fees());
        }
        Catalogue read;
        try (InputStream in = file.getInputStream()) {
            read = json.readValue(in, Catalogue.class);
        }
        List<Fee.Definition> fees = read.fees() == null ? List.of() : read.fees();
        int written = SystemActor.call(ACTOR, () -> catalogue.apply(fees, settings.requiredBeforeAcceptance()));
        log.info("DEMO fees: {} configured from {}{}; payment before acceptance {}", fees.size(), settings.fees(),
                written > 0 ? " (" + written + " change(s))" : "", settings.requiredBeforeAcceptance() ? "required" : "not required");
    }
}
