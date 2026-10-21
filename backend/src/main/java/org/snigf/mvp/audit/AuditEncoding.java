// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Byte encodings of the audit chain and its checkpoints. They match {@code audit_field}
 * in V3__audit.sql: every field is {@code <UTF-8 byte length>:<value>}, or {@code ~} for
 * NULL, and fields are joined with {@code |}, so a value can never be mistaken for a
 * separator. Offline verifiers rebuild the same bytes (docs/audit.md).
 */
public final class AuditEncoding {

    /** Version tag of the checkpoint payload; a new encoding gets a new tag. */
    public static final String CHECKPOINT_VERSION = "snigf-audit-checkpoint-v1";

    /** UTC with microseconds, as {@code to_char(... 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')}. */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditEncoding() {
    }

    /**
     * {@code snigf-audit-checkpoint-v1|f(id)|f(chainPos)|f(hex(headHash))|f(createdAt)} in
     * UTF-8, where {@code f} is {@link #field} and the hash is lower-case hex.
     */
    public static byte[] checkpointPayload(UUID id, long chainPos, byte[] headHash, Instant createdAt) {
        return String.join("|",
                CHECKPOINT_VERSION,
                field(id.toString()),
                field(Long.toString(chainPos)),
                field(HexFormat.of().formatHex(headHash)),
                field(TIMESTAMP.format(createdAt))).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code <UTF-8 byte length>:<value>}, or {@code ~} for null. */
    public static String field(String value) {
        return value == null ? "~" : value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
