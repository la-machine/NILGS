// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.config.SnigfProperties;

/**
 * What every provider shares: mapping purposes to key names, local signature
 * verification against cached public keys, and the local half of envelope encryption.
 * Providers implement only the operations that need the custody key.
 */
abstract class CustodyKeyService implements KeyService {

    static final int DATA_KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SnigfProperties.KeyNames names;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, List<SigningPublicKey>> publicKeys = new ConcurrentHashMap<>();

    /** A fresh data key: its plaintext (erase after use) and its wrapped form (store). */
    record DataKey(byte[] plaintext, String wrapped) {
    }

    CustodyKeyService(SnigfProperties.KeyNames names) {
        this.names = names;
    }

    // --- Provider operations ----------------------------------------------------------

    abstract Signature signWith(String keyName, byte[] message);

    abstract List<SigningPublicKey> fetchPublicKeys(String keyName);

    abstract DataKey newDataKey(String keyName, byte[] context);

    abstract byte[] unwrapDataKey(String keyName, String wrapped, byte[] context);

    /** HMAC-SHA-256 with {@code version}, or with the current version when null. */
    abstract Mac macWith(String keyName, byte[] message, Integer version);

    /** Fails unless every key exists with its expected type and custody settings. */
    abstract void verifyCustody();

    /** Fails if the provider cannot serve requests now. */
    abstract void checkAvailable();

    // --- KeyService -------------------------------------------------------------------

    @Override
    public Signature sign(SigningKey key, byte[] message) {
        return signWith(name(key), message);
    }

    @Override
    public boolean verify(SigningKey key, byte[] message, Signature signature) {
        SigningPublicKey publicKey = publicKey(name(key), signature.keyVersion());
        return publicKey != null && publicKey.verify(message, signature.value());
    }

    @Override
    public List<SigningPublicKey> publicKeys(SigningKey key) {
        return publicKeys.compute(name(key), (n, cached) -> fetchPublicKeys(n));
    }

    /** Public keys never change for a version, so the cache is refreshed only for an unknown version. */
    private SigningPublicKey publicKey(String keyName, int version) {
        SigningPublicKey found = find(publicKeys.computeIfAbsent(keyName, this::fetchPublicKeys), version);
        if (found == null) {
            found = find(publicKeys.compute(keyName, (n, cached) -> fetchPublicKeys(n)), version);
        }
        return found;
    }

    private static SigningPublicKey find(List<SigningPublicKey> keys, int version) {
        return keys.stream().filter(k -> k.version() == version).findFirst().orElse(null);
    }

    @Override
    public SealedData seal(EncryptionKey key, byte[] plaintext, byte[] context) {
        requireContext(context);
        DataKey dataKey = newDataKey(name(key), context);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            return new SealedData(dataKey.wrapped(), nonce, gcm(Cipher.ENCRYPT_MODE, dataKey.plaintext(), nonce, context, plaintext));
        } finally {
            Arrays.fill(dataKey.plaintext(), (byte) 0);
        }
    }

    @Override
    public byte[] open(EncryptionKey key, SealedData sealed, byte[] context) {
        requireContext(context);
        byte[] dataKey = unwrapDataKey(name(key), sealed.wrappedKey(), context);
        try {
            return gcm(Cipher.DECRYPT_MODE, dataKey, sealed.nonce(), context, sealed.ciphertext());
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public Mac mac(MacKey key, byte[] message) {
        return macWith(name(key), message, null);
    }

    @Override
    public Mac mac(MacKey key, byte[] message, int keyVersion) {
        if (keyVersion < 1) {
            throw new KeyServiceException("key version must be >= 1");
        }
        Mac mac = macWith(name(key), message, keyVersion);
        if (mac.keyVersion() != keyVersion) {
            throw new KeyServiceException("custody returned key version " + mac.keyVersion() + ", asked " + keyVersion);
        }
        return mac;
    }

    @Override
    public boolean verifyMac(MacKey key, byte[] message, Mac mac) {
        Mac expected = macWith(name(key), message, mac.keyVersion());
        return expected.keyVersion() == mac.keyVersion() && MessageDigest.isEqual(expected.value(), mac.value());
    }

    // --- Helpers ----------------------------------------------------------------------

    static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        if (key.length != DATA_KEY_BYTES) {
            throw new KeyServiceException("data key has the wrong length");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Includes AEADBadTagException: altered data, wrong key or wrong context.
            throw new KeyServiceException("authenticated encryption failed");
        }
    }

    private static void requireContext(byte[] context) {
        if (context == null || context.length == 0) {
            throw new IllegalArgumentException("a context (for example the record identifier) is required");
        }
    }

    String name(SigningKey key) {
        return switch (key) {
            case AUDIT_CHECKPOINT -> names.auditCheckpoint();
            case RECORD -> names.recordSigning();
        };
    }

    String name(EncryptionKey key) {
        return switch (key) {
            case DATA -> names.dataEncryption();
        };
    }

    String name(MacKey key) {
        return switch (key) {
            case PAYMENT_CALLBACK -> names.paymentCallback();
            case PASSWORD_PEPPER -> names.passwordPepper();
            case BLIND_INDEX -> names.blindIndex();
        };
    }
}
