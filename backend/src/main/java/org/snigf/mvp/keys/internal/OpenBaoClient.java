// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.snigf.mvp.keys.KeyServiceException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Minimal OpenBao HTTP client (ADR-018). The {@link RestClient} it receives carries the
 * mutual-TLS 1.3 connection; this class adds certificate login and token handling.
 *
 * <p>The application logs in with its TLS client certificate ({@code auth/<mount>/login});
 * no password, AppRole secret or token is stored anywhere. The token is kept in memory and
 * replaced after two thirds of its lifetime, or at once if OpenBao rejects it (revoked,
 * expired, OpenBao restarted). Error messages carry the status and path only, never
 * request or response bodies, which may contain key material.
 */
class OpenBaoClient {

    static final String TOKEN_HEADER = "X-Vault-Token";

    private final RestClient rest;
    private final String authMount;
    private final String role;
    private final Clock clock;

    private record Token(String value, Instant renewAfter) {
    }

    private volatile Token token;

    OpenBaoClient(RestClient rest, String authMount, String role, Clock clock) {
        this.rest = rest;
        this.authMount = authMount;
        this.role = role;
        this.clock = clock;
    }

    /** POST with the application token; retries once with a fresh login if the token was rejected. */
    JsonNode post(String path, Map<String, ?> body) {
        return withToken(path, t -> rest.post().uri(path)
                .header(TOKEN_HEADER, t)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** GET with the application token; retries once with a fresh login if the token was rejected. */
    JsonNode get(String path) {
        return withToken(path, t -> rest.get().uri(path)
                .header(TOKEN_HEADER, t)
                .retrieve()
                .body(JsonNode.class));
    }

    private interface Call {
        JsonNode run(String token);
    }

    private JsonNode withToken(String path, Call call) {
        try {
            try {
                return call.run(token());
            } catch (HttpClientErrorException.Forbidden e) {
                token = null;   // revoked or expired: log in again once
                return call.run(token());
            }
        } catch (HttpClientErrorException e) {
            throw new KeyServiceException("OpenBao refused " + path + " (HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            throw new KeyServiceException("OpenBao unavailable for " + path, e);
        }
    }

    private String token() {
        Token current = token;
        if (current != null && clock.instant().isBefore(current.renewAfter())) {
            return current.value();
        }
        synchronized (this) {
            current = token;
            if (current == null || !clock.instant().isBefore(current.renewAfter())) {
                current = login();
                token = current;
            }
            return current.value();
        }
    }

    private Token login() {
        String path = "auth/" + authMount + "/login";
        JsonNode response;
        try {
            response = rest.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", role))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new KeyServiceException("OpenBao refused the certificate login (HTTP "
                                + resp.getStatusCode().value() + ")");
                    })
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new KeyServiceException("OpenBao unavailable for the certificate login", e);
        }
        JsonNode auth = response == null ? null : response.path("auth");
        String value = auth == null ? "" : auth.path("client_token").asString("");
        long lease = auth == null ? 0 : auth.path("lease_duration").asLong(0);
        if (value.isEmpty() || lease <= 0) {
            throw new KeyServiceException("OpenBao certificate login returned no usable token");
        }
        return new Token(value, clock.instant().plus(Duration.ofSeconds(lease * 2 / 3)));
    }
}
