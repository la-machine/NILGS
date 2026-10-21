// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.snigf.mvp.keys.Signature;

/**
 * A signed statement that the audit chain's event at {@code chainPos} has hash
 * {@code headHash}, signed with the audit checkpoint key (Ed25519, held by OpenBao).
 * The signed bytes are {@link AuditEncoding#checkpointPayload}; docs/audit.md describes
 * them for offline verifiers.
 *
 * @param id        identifier
 * @param chainPos  position of the event the checkpoint covers
 * @param headHash  SHA-256 chain hash of that event (32 bytes)
 * @param createdAt creation time, microsecond precision
 * @param signature Ed25519 signature over the payload, with its key version
 */
public record Checkpoint(UUID id, long chainPos, byte[] headHash, Instant createdAt, Signature signature) {

    public Checkpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(signature, "signature");
        if (chainPos < 1) {
            throw new IllegalArgumentException("chain position must be >= 1");
        }
        if (headHash.length != 32) {
            throw new IllegalArgumentException("head hash must have 32 bytes");
        }
        headHash = headHash.clone();
    }

    @Override
    public byte[] headHash() {
        return headHash.clone();
    }

    public String headHashHex() {
        return HexFormat.of().formatHex(headHash);
    }

    /** The bytes the signature covers. */
    public byte[] payload() {
        return AuditEncoding.checkpointPayload(id, chainPos, headHash, createdAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Checkpoint other && id.equals(other.id) && chainPos == other.chainPos
                && Arrays.equals(headHash, other.headHash) && createdAt.equals(other.createdAt)
                && signature.equals(other.signature);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Checkpoint[id=" + id + ", chainPos=" + chainPos + ", headHash=" + headHashHex()
                + ", createdAt=" + createdAt + ", signature=" + signature + "]";
    }
}
