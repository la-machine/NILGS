// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

import java.util.List;

/**
 * Cryptographic operations with keys held in custody (ADR-018). Each operation takes the
 * purpose of the key, never a key name or key material, so a key cannot be used for
 * another purpose and callers cannot pick a key.
 *
 * <p>Long-term keys never enter the application. Envelope encryption generates a fresh
 * data key per call, uses it locally and erases it; only its wrapped form is stored.
 * Every method throws {@link KeyServiceException} when custody is unavailable: callers
 * must fail the operation, never fall back to unprotected data.
 */
public interface KeyService {

    /** Signs {@code message} with the current version of the key (Ed25519). */
    Signature sign(SigningKey key, byte[] message);

    /**
     * Verifies a signature locally against the public key of the version that made it.
     * Returns false for an invalid signature or an unknown key version.
     */
    boolean verify(SigningKey key, byte[] message, Signature signature);

    /** Public keys of every version of a signing key, oldest first, for offline verifiers. */
    List<SigningPublicKey> publicKeys(SigningKey key);

    /**
     * Encrypts with AES-256-GCM under a new data key. The data key is wrapped by the
     * custody key derived for {@code context}, and {@code context} is also bound as
     * associated data: the result opens only with the same context (for example the
     * record identifier), so ciphertexts cannot be swapped between records.
     */
    SealedData seal(EncryptionKey key, byte[] plaintext, byte[] context);

    /** Reverses {@link #seal}; fails if the data, the wrapped key or the context was altered. */
    byte[] open(EncryptionKey key, SealedData sealed, byte[] context);

    /** HMAC-SHA-256 with the current version of the key. */
    Mac mac(MacKey key, byte[] message);

    /**
     * HMAC-SHA-256 with one given version of the key, for values that must be recomputed
     * exactly as they were first made (a peppered password hash records its pepper version).
     * Throws {@link KeyServiceException} for an unknown version.
     */
    Mac mac(MacKey key, byte[] message, int keyVersion);

    /** Recomputes the HMAC with the version recorded in {@code mac} and compares in constant time. */
    boolean verifyMac(MacKey key, byte[] message, Mac mac);
}
