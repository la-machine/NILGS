// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.util.Base64;

/** Parses {@code <prefix><version>:<base64>} storage forms. */
record Versioned(int version, byte[] value) {

    static Versioned parse(String prefix, String encoded) {
        if (encoded == null || !encoded.startsWith(prefix)) {
            throw new IllegalArgumentException("not a versioned value");
        }
        int colon = encoded.indexOf(':', prefix.length());
        if (colon < 0) {
            throw new IllegalArgumentException("not a versioned value");
        }
        try {
            int version = Integer.parseInt(encoded.substring(prefix.length(), colon));
            return new Versioned(version, Base64.getDecoder().decode(encoded.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a versioned value", e);
        }
    }
}
