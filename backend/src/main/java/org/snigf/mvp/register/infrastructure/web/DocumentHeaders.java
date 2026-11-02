// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The headers of ADR-033 for a deposit's file: always an attachment, never rendered or guessed by a browser. */
final class DocumentHeaders {

    private DocumentHeaders() {
    }

    static HttpHeaders attachment(String mediaType, long sizeBytes, String name, String sha256) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentLength(sizeBytes);
        headers.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Repr-Digest", "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)) + ":");
        return headers;
    }
}
