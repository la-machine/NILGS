// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.util.Arrays;

/**
 * Envelope-encrypted data: AES-256-GCM ciphertext under a one-time data key, and that
 * data key wrapped by the custody key. All three parts are stored; none is secret on its own.
 *
 * @param wrappedKey the data key, wrapped by custody (opaque, carries the key version)
 * @param nonce      the 12-byte GCM nonce
 * @param ciphertext ciphertext followed by the 16-byte GCM tag
 */
public record SealedData(String wrappedKey, byte[] nonce, byte[] ciphertext) {

    public SealedData {
        if (wrappedKey == null || wrappedKey.isBlank()) {
            throw new IllegalArgumentException("wrapped key is required");
        }
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SealedData other && wrappedKey.equals(other.wrappedKey)
                && Arrays.equals(nonce, other.nonce) && Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * wrappedKey.hashCode() + Arrays.hashCode(nonce)) + Arrays.hashCode(ciphertext);
    }

    @Override
    public String toString() {
        return "SealedData[" + ciphertext.length + " bytes]";
    }
}
