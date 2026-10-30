// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 (historical verification): a document signed before a rotation still verifies after it,
 * because verification uses the public key of the version that signed, and every version stays
 * available (OpenBao keeps them; {@code min_available_version} is never raised). Exercised on the
 * real {@link CustodyKeyService} verification, with a custody of two versions standing in for OpenBao.
 */
class SigningKeyRotationTest {

    /** A custody whose issuance key is rotated on demand; versions are never removed. */
    private static final class RotatingCustody extends CustodyKeyService {
        private final List<KeyPair> versions = new ArrayList<>();

        RotatingCustody() {
            super(EphemeralKeyServiceTest.NAMES);
            rotate();
        }

        void rotate() {
            try {
                versions.add(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        Signature signWith(String keyName, byte[] message) {
            try {
                java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
                signer.initSign(versions.getLast().getPrivate());
                signer.update(message);
                return new Signature(versions.size(), signer.sign());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        List<SigningPublicKey> fetchPublicKeys(String keyName) {
            List<SigningPublicKey> keys = new ArrayList<>();
            for (int i = 0; i < versions.size(); i++) {
                byte[] spki = versions.get(i).getPublic().getEncoded();
                keys.add(new SigningPublicKey(i + 1, Arrays.copyOfRange(spki, spki.length - 32, spki.length)));
            }
            return keys;
        }

        @Override
        DataKey newDataKey(String keyName, byte[] context) {
            throw new UnsupportedOperationException();
        }

        @Override
        byte[] unwrapDataKey(String keyName, String wrapped, byte[] context) {
            throw new UnsupportedOperationException();
        }

        @Override
        Mac macWith(String keyName, byte[] message, Integer version) {
            throw new UnsupportedOperationException();
        }

        @Override
        void verifyCustody() {
        }

        @Override
        void checkAvailable() {
        }
    }

    @Test
    void aManifestSignedBeforeARotationStillVerifiesAfterIt() {
        RotatingCustody custody = new RotatingCustody();
        byte[] manifest = "snigf-issued-document-v1|...".getBytes(StandardCharsets.UTF_8);
        Signature before = custody.sign(SigningKey.DOCUMENT_ISSUANCE, manifest);
        assertThat(before.keyVersion()).isEqualTo(1);
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, before)).isTrue();

        custody.rotate();
        Signature after = custody.sign(SigningKey.DOCUMENT_ISSUANCE, manifest);
        assertThat(after.keyVersion()).isEqualTo(2);
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, before)).as("the old version still verifies").isTrue();
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, after)).isTrue();
        assertThat(custody.publicKeys(SigningKey.DOCUMENT_ISSUANCE)).extracting(SigningPublicKey::version).containsExactly(1, 2);
    }

    @Test
    void aSignatureClaimingAnotherVersionOrAnUnknownOneDoesNotVerify() {
        RotatingCustody custody = new RotatingCustody();
        byte[] manifest = "snigf-issued-document-v1|...".getBytes(StandardCharsets.UTF_8);
        Signature v1 = custody.sign(SigningKey.DOCUMENT_ISSUANCE, manifest);
        custody.rotate();
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, new Signature(2, v1.value())))
                .as("made by version 1, claiming version 2").isFalse();
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, new Signature(7, v1.value())))
                .as("a version custody never had").isFalse();
        byte[] altered = "snigf-issued-document-v1|..!".getBytes(StandardCharsets.UTF_8);
        assertThat(custody.verify(SigningKey.DOCUMENT_ISSUANCE, altered, v1)).isFalse();
    }
}
