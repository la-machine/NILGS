// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The {@code Idempotency-Key} of create endpoints (ADR-013), in {@code idempotency_record}
 * (V1): per user and key, the hash of the request and the response sent. A retry with the
 * same key and the same request gets the same response and creates nothing; the same key
 * with another request is refused. Call it inside the transaction that creates, so the
 * record and the creation commit together.
 */
@Component
public class IdempotencyStore {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

    /** A response already sent for this key. */
    public record Stored(String requestSha256, int status, String body) {
    }

    /** Another request with the same key is being processed at the same moment. */
    public static class InProgress extends RuntimeException {
        public InProgress() {
            super("a request with this idempotency key is in progress");
        }
    }

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Stored> find(UUID userId, String key) {
        return jdbc.sql("SELECT request_sha256, status_code, response_body::text FROM idempotency_record"
                        + " WHERE user_id = :user AND idem_key = :key")
                .param("user", userId).param("key", key)
                .query((rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .optional();
    }

    /** @throws InProgress if a concurrent request stored the same key first */
    public void save(UUID userId, String key, String endpoint, String requestSha256, int status, String body) {
        try {
            jdbc.sql("""
                            INSERT INTO idempotency_record (user_id, idem_key, endpoint, request_sha256, status_code, response_body)
                            VALUES (:user, :key, :endpoint, :hash, :status, CAST(:body AS jsonb))""")
                    .param("user", userId).param("key", key).param("endpoint", endpoint).param("hash", requestSha256)
                    .param("status", status).param("body", body)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new InProgress();
        }
    }
}
