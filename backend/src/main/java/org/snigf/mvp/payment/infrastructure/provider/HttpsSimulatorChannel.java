// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import java.util.Map;

import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.ProviderReport;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Charges to the {@code payment-sim} service (ADR-037) over mutual TLS 1.3: the application
 * presents its provider-client certificate, and verifies the simulator's against the SNIGF CA and
 * its name. {@code POST /v1/charges} answers the transaction id; the simulator then sends its
 * callbacks through the proxy. Error messages carry the status only, never a body.
 */
class HttpsSimulatorChannel implements SimulatorChannel {

    private final RestClient rest;

    HttpsSimulatorChannel(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public String charge(PaymentProvider.ChargeOrder order) {
        JsonNode answer;
        try {
            answer = rest.post().uri("v1/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("reference", order.reference(), "amount", order.amount(), "currency", order.currency(),
                            "scenario", order.scenario().name(), "demo", true))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentProvider.Unavailable("the DEMO simulator did not take the charge", e);
        }
        String transactionId = answer == null ? "" : answer.path("transactionId").asString("");
        if (!ProviderReport.TRANSACTION_ID.matcher(transactionId).matches()) {
            throw new PaymentProvider.Unavailable("the DEMO simulator answered no valid transaction id", null);
        }
        return transactionId;
    }
}
