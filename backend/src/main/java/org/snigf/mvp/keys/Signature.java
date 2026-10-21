// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.util.Arrays;
import java.util.Base64;

/**
 * An Ed25519 signature and the key version that made it.
 *
 * @param keyVersion version of the signing key, starting at 1
 * @param value      the 64-byte signature
 */
public record Signature(int keyVersion, byte[] value) {

    private static final String PREFIX = "snigf:v";

    public Signature {
        if (keyVersion < 1) {
            throw new IllegalArgumentException("key version must be >= 1");
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    /** Storage form: {@code snigf:v<version>:<base64>}. */
    public String encoded() {
        return PREFIX + keyVersion + ":" + Base64.getEncoder().encodeToString(value);
    }

    /** Parses {@link #encoded()}. */
    public static Signature parse(String encoded) {
        Versioned v = Versioned.parse(PREFIX, encoded);
        return new Signature(v.version(), v.value());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Signature other && keyVersion == other.keyVersion && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * keyVersion + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return encoded();
    }
}
