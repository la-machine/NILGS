// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.snigf.mvp.audit.AuditEncoding.TIMESTAMP;
import static org.snigf.mvp.audit.AuditEncoding.field;

/**
 * An event as stored in the chain, and the Java implementation of {@code audit_canonical}
 * (V3__audit.sql, encoding {@value #VERSION}).
 *
 * <p>Verification recomputes hashes here rather than with the database's functions, which
 * an administrator could replace. Two values are kept in the database's text form, because
 * that form is what the database hashed: {@code host(source_ip)} and {@code details::text}.
 * {@code CanonicalHashParityIT} proves both sides produce identical bytes.
 */
public record ChainedEvent(
        long chainPos,
        UUID id,
        Instant occurredAt,
        String actorType,
        UUID actorId,
        String actorLabel,
        List<String> actorRoles,
        String action,
        String entityType,
        UUID entityId,
        String entityRef,
        String outcome,
        String reason,
        UUID correlationId,
        String sourceIp,
        String details,
        byte[] prevHash,
        byte[] hash) {

    public static final String VERSION = "snigf-audit-v1";

    /** {@code prev_hash} of the first event. */
    public static final byte[] GENESIS = new byte[32];

    /** The bytes {@code audit_canonical(e)} returns for this event. */
    public byte[] canonical() {
        return String.join("|",
                VERSION,
                field(Long.toString(chainPos)),
                field(id.toString()),
                field(TIMESTAMP.format(occurredAt)),
                field(actorType),
                field(actorId == null ? null : actorId.toString()),
                field(actorLabel),
                field(jsonArray(actorRoles)),
                field(action),
                field(entityType),
                field(entityId == null ? null : entityId.toString()),
                field(entityRef),
                field(outcome),
                field(reason),
                field(correlationId.toString()),
                field(sourceIp),
                field(details)).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code sha256(prev_hash || audit_canonical(e))}. */
    public byte[] computeHash() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(prevHash);
            return sha256.digest(canonical());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** {@code array_to_json(text[])::text}: no spaces, PostgreSQL's JSON string escaping. */
    static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String value = values.get(i);
            if (value == null) {
                out.append("null");
            } else {
                appendJsonString(out, value);
            }
        }
        return out.append(']').toString();
    }

    /** As PostgreSQL's {@code escape_json}: short escapes, other control characters as a 4-digit hex escape. */
    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
