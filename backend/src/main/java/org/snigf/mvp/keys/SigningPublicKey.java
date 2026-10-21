// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The public half of one version of an Ed25519 signing key. Verifying needs nothing
 * else: auditors can check signatures offline with these values.
 *
 * @param version key version, starting at 1
 * @param raw     the 32-byte Ed25519 public key (RFC 8032)
 */
public record SigningPublicKey(int version, byte[] raw) {

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410) before the 32 key bytes. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public SigningPublicKey {
        if (raw.length != 32) {
            throw new IllegalArgumentException("an Ed25519 public key has 32 bytes, found " + raw.length);
        }
        raw = raw.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    /** The key as a JCA public key. */
    public PublicKey toPublicKey() {
        byte[] spki = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + raw.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is unavailable", e);
        }
    }

    /** Verifies an Ed25519 signature over {@code message} with this key. */
    public boolean verify(byte[] message, byte[] signature) {
        try {
            java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(toPublicKey());
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SigningPublicKey other && version == other.version && Arrays.equals(raw, other.raw);
    }

    @Override
    public int hashCode() {
        return 31 * version + Arrays.hashCode(raw);
    }

    @Override
    public String toString() {
        return "SigningPublicKey[version=" + version + ", raw=" + Base64.getEncoder().encodeToString(raw) + "]";
    }
}
