// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.springframework.stereotype.Component;

/**
 * Startup self-test of key custody. Runs while the context is created, before the web
 * server accepts requests: if a key is missing, exportable, deletable or of the wrong
 * type, or any operation does not round-trip (and reject tampering), the application
 * refuses to start rather than run without working signatures or encryption.
 */
@Component
class KeyServiceSelfTest {

    private static final Logger log = LoggerFactory.getLogger(KeyServiceSelfTest.class);
    private static final byte[] MESSAGE = "SNIGF key custody self-test".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTEXT = "self-test".getBytes(StandardCharsets.UTF_8);

    KeyServiceSelfTest(CustodyKeyService keys) {
        keys.verifyCustody();
        byte[] tampered = MESSAGE.clone();
        tampered[0] ^= 1;

        for (SigningKey key : SigningKey.values()) {
            Signature signature = keys.sign(key, MESSAGE);
            require(keys.verify(key, MESSAGE, signature), "signature of " + key + " does not verify");
            require(!keys.verify(key, tampered, signature), "signature of " + key + " verifies altered data");
        }

        for (EncryptionKey key : EncryptionKey.values()) {
            SealedData sealed = keys.seal(key, MESSAGE, CONTEXT);
            require(Arrays.equals(keys.open(key, sealed, CONTEXT), MESSAGE), "envelope " + key + " does not round-trip");
            require(fails(() -> keys.open(key, sealed, tampered)), "envelope " + key + " opens with another context");
        }

        for (MacKey key : MacKey.values()) {
            Mac mac = keys.mac(key, MESSAGE);
            require(keys.verifyMac(key, MESSAGE, mac), "MAC " + key + " does not verify");
            require(!keys.verifyMac(key, tampered, mac), "MAC " + key + " verifies altered data");
        }
        log.info("Key custody self-test passed: {} signing, {} encryption, {} MAC keys",
                SigningKey.values().length, EncryptionKey.values().length, MacKey.values().length);
    }

    private static void require(boolean condition, String failure) {
        if (!condition) {
            throw new KeyServiceException("key custody self-test failed: " + failure);
        }
    }

    private static boolean fails(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (KeyServiceException e) {
            return true;
        }
    }
}
