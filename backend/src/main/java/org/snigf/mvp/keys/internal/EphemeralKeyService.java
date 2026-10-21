// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.config.SnigfProperties;

/**
 * AUTOMATED TESTS ONLY: random keys generated in memory per process and lost at exit,
 * with the same formats and checks as OpenBao. EnvironmentGuard refuses this provider
 * in every environment except test.
 */
class EphemeralKeyService extends CustodyKeyService {

    private static final String WRAP_PREFIX = "ephemeral:v1:";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, KeyPair> signing = new ConcurrentHashMap<>();
    private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();

    EphemeralKeyService(SnigfProperties.KeyNames names) {
        super(names);
    }

    @Override
    Signature signWith(String keyName, byte[] message) {
        try {
            java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
            signer.initSign(keyPair(keyName).getPrivate());
            signer.update(message);
            return new Signature(1, signer.sign());
        } catch (GeneralSecurityException e) {
            throw new KeyServiceException("signing failed", e);
        }
    }

    @Override
    List<SigningPublicKey> fetchPublicKeys(String keyName) {
        byte[] spki = keyPair(keyName).getPublic().getEncoded();
        return List.of(new SigningPublicKey(1, Arrays.copyOfRange(spki, spki.length - 32, spki.length)));
    }

    @Override
    DataKey newDataKey(String keyName, byte[] context) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        random.nextBytes(dataKey);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, secret(keyName), nonce, context, dataKey);
        byte[] stored = Arrays.copyOf(nonce, NONCE_BYTES + wrapped.length);
        System.arraycopy(wrapped, 0, stored, NONCE_BYTES, wrapped.length);
        return new DataKey(dataKey, WRAP_PREFIX + Base64.getEncoder().encodeToString(stored));
    }

    @Override
    byte[] unwrapDataKey(String keyName, String wrapped, byte[] context) {
        if (!wrapped.startsWith(WRAP_PREFIX)) {
            throw new KeyServiceException("unexpected wrapped key format");
        }
        byte[] stored = Base64.getDecoder().decode(wrapped.substring(WRAP_PREFIX.length()));
        return gcm(Cipher.DECRYPT_MODE, secret(keyName), Arrays.copyOf(stored, NONCE_BYTES), context,
                Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
    }

    @Override
    Mac macWith(String keyName, byte[] message, Integer version) {
        if (version != null && version != 1) {
            throw new KeyServiceException("unknown key version " + version);
        }
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret(keyName), "HmacSHA256"));
            return new Mac(1, hmac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new KeyServiceException("HMAC failed", e);
        }
    }

    @Override
    void verifyCustody() {
        // Nothing persists; nothing can be exported.
    }

    @Override
    void checkAvailable() {
        // Always available in-process.
    }

    private KeyPair keyPair(String keyName) {
        return signing.computeIfAbsent(keyName, n -> {
            try {
                return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new KeyServiceException("Ed25519 is unavailable", e);
            }
        });
    }

    private byte[] secret(String keyName) {
        return secrets.computeIfAbsent(keyName, n -> {
            byte[] key = new byte[DATA_KEY_BYTES];
            random.nextBytes(key);
            return key;
        });
    }
}
