// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * The signed form of an issued document (ADR-039, encoding {@value #VERSION}):
 * <pre>
 *   snigf-issued-document-v1|f(number)|f(type)|f(template)|f(subject type)|f(subject reference)
 *                           |f(office)|f(issued at, UTC microseconds)|f(SHA-256 of the PDF, hex)
 * </pre>
 * {@code f} writes {@code <UTF-8 byte length>:<value>}, or {@code ~} for none, as the audit chain
 * does, so no value can pass for a separator. It binds the exact PDF (its fingerprint) to what it
 * is, for whom it was issued, by which office, and when.
 */
public final class Manifest {

    public static final String VERSION = "snigf-issued-document-v1";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Manifest() {
    }

    public static byte[] canonical(String number, DocumentType type, String templateVersion, String subjectType,
                                   String subjectRef, String officeCode, Instant issuedAt, byte[] sha256) {
        return String.join("|", VERSION, field(number), field(type.name()), field(templateVersion), field(subjectType),
                field(subjectRef), field(officeCode), field(TIME.format(issuedAt)), field(HexFormat.of().formatHex(sha256)))
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String field(String value) {
        return value == null ? "~" : value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
