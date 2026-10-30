// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.config.SnigfProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-018: the KeyService contract, exercised through the provider shared logic. */
class EphemeralKeyServiceTest {

    static final SnigfProperties.KeyNames NAMES = new SnigfProperties.KeyNames(
            "audit-checkpoint", "record-signing", "data-encryption", "payment-callback", "password-pepper", "blind-index", "document-issuance");

    private final EphemeralKeyService keys = new EphemeralKeyService(NAMES);
    private final byte[] message = "parcel CM-LT-0001 decision".getBytes(StandardCharsets.UTF_8);
    private final byte[] context = "application 0190d1f2".getBytes(StandardCharsets.UTF_8);

    @Test
    void signaturesVerifyOfflineAndRejectTampering() {
        Signature signature = keys.sign(SigningKey.AUDIT_CHECKPOINT, message);

        assertThat(keys.verify(SigningKey.AUDIT_CHECKPOINT, message, signature)).isTrue();
        assertThat(keys.verify(SigningKey.AUDIT_CHECKPOINT, "other".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        // Another purpose has another key.
        assertThat(keys.verify(SigningKey.RECORD, message, signature)).isFalse();
        // Unknown version: false, not an exception.
        assertThat(keys.verify(SigningKey.AUDIT_CHECKPOINT, message, new Signature(7, signature.value()))).isFalse();

        List<SigningPublicKey> published = keys.publicKeys(SigningKey.AUDIT_CHECKPOINT);
        assertThat(published).singleElement().satisfies(k -> assertThat(k.verify(message, signature.value())).isTrue());
    }

    @Test
    void signatureStorageFormRoundTrips() {
        Signature signature = keys.sign(SigningKey.RECORD, message);

        assertThat(signature.encoded()).startsWith("snigf:v1:");
        assertThat(Signature.parse(signature.encoded())).isEqualTo(signature);
        assertThatThrownBy(() -> Signature.parse("vault:v1:AAAA")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeOpensOnlyWithTheSameContextAndUnalteredData() {
        SealedData sealed = keys.seal(EncryptionKey.DATA, message, context);

        assertThat(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(message, StandardCharsets.ISO_8859_1));
        assertThat(keys.open(EncryptionKey.DATA, sealed, context)).isEqualTo(message);

        assertThatThrownBy(() -> keys.open(EncryptionKey.DATA, sealed, "application other".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(KeyServiceException.class);
        byte[] altered = sealed.ciphertext();
        altered[0] ^= 1;
        assertThatThrownBy(() -> keys.open(EncryptionKey.DATA, new SealedData(sealed.wrappedKey(), sealed.nonce(), altered), context))
                .isInstanceOf(KeyServiceException.class);
        assertThatThrownBy(() -> keys.seal(EncryptionKey.DATA, message, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachSealUsesAFreshDataKeyAndNonce() {
        SealedData first = keys.seal(EncryptionKey.DATA, message, context);
        SealedData second = keys.seal(EncryptionKey.DATA, message, context);

        assertThat(first.wrappedKey()).isNotEqualTo(second.wrappedKey());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void macsAreDeterministicPerKeyAndVerifyInConstantTime() {
        Mac mac = keys.mac(MacKey.BLIND_INDEX, message);

        assertThat(keys.mac(MacKey.BLIND_INDEX, message)).isEqualTo(mac);
        assertThat(keys.mac(MacKey.PASSWORD_PEPPER, message)).isNotEqualTo(mac);
        assertThat(keys.verifyMac(MacKey.BLIND_INDEX, message, mac)).isTrue();
        assertThat(keys.verifyMac(MacKey.BLIND_INDEX, context, mac)).isFalse();
        assertThat(mac.toString()).doesNotContain(java.util.Base64.getEncoder().encodeToString(mac.value()));
        assertThat(Mac.parse(mac.encoded())).isEqualTo(mac);
    }

    @Test
    void selfTestPassesOnAWorkingProvider() {
        new KeyServiceSelfTest(keys);
    }
}
