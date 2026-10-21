// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.util.Arrays;
import java.util.Base64;

/**
 * An HMAC-SHA-256 value and the key version that computed it.
 *
 * @param keyVersion version of the MAC key, starting at 1
 * @param value      the 32-byte MAC
 */
public record Mac(int keyVersion, byte[] value) {

    private static final String PREFIX = "snigf:v";

    public Mac {
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
    public static Mac parse(String encoded) {
        Versioned v = Versioned.parse(PREFIX, encoded);
        return new Mac(v.version(), v.value());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Mac other && keyVersion == other.keyVersion && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * keyVersion + Arrays.hashCode(value);
    }

    /** Never prints the value: a MAC of a password or identifier is itself sensitive. */
    @Override
    public String toString() {
        return "Mac[keyVersion=" + keyVersion + "]";
    }
}
