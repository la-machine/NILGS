// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.ProviderReport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The callback authentication of the DEMO simulator (ADR-037): identity, headers, freshness and
 * signature in that order, the body read only after; nothing of a rejected callback is parsed.
 */
class DemoSimulatorProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final String SUBJECT = "CN=snigf-payment-sim,O=FLYT";
    private static final String BODY = "{\"provider\":\"DEMO_SIMULATOR\",\"transactionId\":\"SIMTX-0001-test\","
            + "\"reference\":\"DEMO-PAY-2026-00000001-X\",\"amount\":25000,\"currency\":\"XAF\",\"status\":\"SUCCEEDED\","
            + "\"occurredAt\":\"2026-10-27T11:59:58Z\",\"demo\":true}";

    private final FakeKeys keys = new FakeKeys();
    private final DemoSimulatorProvider provider = new DemoSimulatorProvider(order -> "SIMTX-unused", keys,
            java.util.Set.of("snigf-payment-sim"), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aSignedFreshCallbackFromTheSimulatorIsRead() {
        ProviderReport report = provider.authenticate(signed(BODY, NOW));
        assertThat(report.transactionId()).isEqualTo("SIMTX-0001-test");
        assertThat(report.reference()).isEqualTo("DEMO-PAY-2026-00000001-X");
        assertThat(report.amount()).isEqualTo(25_000);
        assertThat(report.status()).isEqualTo(ProviderReport.Status.SUCCEEDED);
        assertThat(report.keyVersion()).isEqualTo(1);
    }

    @Test
    void theProviderMustBeVouchedForByTheProxy() {
        PaymentProvider.Callback good = signed(BODY, NOW);
        assertReason(new PaymentProvider.Callback(null, good.headers(), good.body()), "PROVIDER_NOT_RECOGNISED");
        assertReason(new PaymentProvider.Callback("CN=snigf-web,O=FLYT", good.headers(), good.body()), "PROVIDER_NOT_RECOGNISED");
        assertReason(new PaymentProvider.Callback("not a subject", good.headers(), good.body()), "PROVIDER_NOT_RECOGNISED");
        assertThat(DemoSimulatorProvider.commonName("O=FLYT,CN=snigf-payment-sim")).contains("snigf-payment-sim");
    }

    @Test
    void headersMustBePresentAndWellFormed() {
        PaymentProvider.Callback good = signed(BODY, NOW);
        assertReason(new PaymentProvider.Callback(SUBJECT, Map.of(), good.body()), "SIGNATURE_MISSING");
        Map<String, String> noVersion = new HashMap<>(good.headers());
        noVersion.put(DemoSimulatorProvider.SIGNATURE_HEADER, good.headers().get(DemoSimulatorProvider.SIGNATURE_HEADER).substring(3));
        assertReason(new PaymentProvider.Callback(SUBJECT, noVersion, good.body()), "SIGNATURE_MALFORMED");
        Map<String, String> badTime = new HashMap<>(good.headers());
        badTime.put(DemoSimulatorProvider.TIMESTAMP_HEADER, "yesterday");
        assertReason(new PaymentProvider.Callback(SUBJECT, badTime, good.body()), "SIGNATURE_MALFORMED");
    }

    @Test
    void aStaleOrFutureTimestampIsRefusedAtTheEdgeOfTheWindow() {
        assertThat(provider.authenticate(signed(BODY, NOW.minusSeconds(300))).amount()).isEqualTo(25_000);
        assertReason(signed(BODY, NOW.minusSeconds(301)), "TIMESTAMP_OUT_OF_WINDOW");
        assertReason(signed(BODY, NOW.plusSeconds(301)), "TIMESTAMP_OUT_OF_WINDOW");
    }

    @Test
    void anyChangeToTheBodyOrTheTimestampBreaksTheSignature() {
        PaymentProvider.Callback good = signed(BODY, NOW);
        byte[] tampered = BODY.replace("25000", "25001").getBytes(StandardCharsets.UTF_8);
        assertReason(new PaymentProvider.Callback(SUBJECT, good.headers(), tampered), "SIGNATURE_INVALID");
        Map<String, String> moved = new HashMap<>(good.headers());
        moved.put(DemoSimulatorProvider.TIMESTAMP_HEADER, String.valueOf(NOW.getEpochSecond() + 1));
        assertReason(new PaymentProvider.Callback(SUBJECT, moved, good.body()), "SIGNATURE_INVALID");
        Map<String, String> otherVersion = new HashMap<>(good.headers());
        otherVersion.put(DemoSimulatorProvider.SIGNATURE_HEADER, "v2=" + good.headers().get(DemoSimulatorProvider.SIGNATURE_HEADER).substring(3));
        assertReason(new PaymentProvider.Callback(SUBJECT, otherVersion, good.body()), "SIGNATURE_INVALID");
    }

    @Test
    void anOversizedBodyIsNotEvenVerified() {
        String big = BODY.replace("\"demo\":true", "\"demo\":true,\"pad\":\"" + "x".repeat(17_000) + "\"");
        assertReason(signed(big, NOW), "BODY_TOO_LARGE");
        assertThat(keys.calls).isZero();
    }

    @Test
    void anAuthenticBodyOutsideTheDemoProtocolIsMalformed() {
        for (String body : List.of(
                BODY.replace("\"demo\":true", "\"demo\":false"),
                BODY.replace("DEMO_SIMULATOR", "REAL_BANK"),
                BODY.replace("\"demo\":true", "\"demo\":true,\"extra\":1"),
                BODY.replace("25000", "\"25000\""),
                BODY.replace("SUCCEEDED", "REFUNDED"),
                "[]",
                "not json")) {
            assertThatThrownBy(() -> provider.authenticate(signed(body, NOW))).as(body)
                    .isInstanceOf(PaymentProvider.Malformed.class);
        }
    }

    @Test
    void keyCustodyUnreachableIsNotTheSendersFault() {
        keys.down = true;
        assertThatThrownBy(() -> provider.authenticate(signed(BODY, NOW))).isInstanceOf(PaymentProvider.Unavailable.class);
    }

    private PaymentProvider.Callback signed(String body, Instant at) {
        String timestamp = String.valueOf(at.getEpochSecond());
        Mac mac = keys.compute((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = Map.of(DemoSimulatorProvider.TIMESTAMP_HEADER, timestamp,
                DemoSimulatorProvider.SIGNATURE_HEADER, "v" + mac.keyVersion() + "=" + Base64.getEncoder().encodeToString(mac.value()));
        return new PaymentProvider.Callback(SUBJECT, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private void assertReason(PaymentProvider.Callback callback, String reason) {
        assertThatThrownBy(() -> provider.authenticate(callback)).isInstanceOf(PaymentProvider.Rejected.class)
                .extracting(e -> ((PaymentProvider.Rejected) e).reason()).isEqualTo(reason);
    }

    /** HMAC-SHA-256 with one version (1) of a fixed key; unknown versions refused as custody does. */
    private static final class FakeKeys implements KeyService {
        private final byte[] secret = "test-payment-callback-key-32-bytes!".getBytes(StandardCharsets.US_ASCII);
        /** Verifications asked of custody. */
        int calls;
        boolean down;

        Mac compute(byte[] message) {
            try {
                javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
                hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return new Mac(1, hmac.doFinal(message));
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean verifyMac(MacKey key, byte[] message, Mac mac) {
            calls++;
            if (down) {
                throw new KeyServiceException("OpenBao unavailable", new java.io.IOException("connection refused"));
            }
            if (mac.keyVersion() != 1) {
                throw new KeyServiceException("unknown key version " + mac.keyVersion());
            }
            return key == MacKey.PAYMENT_CALLBACK && MessageDigest.isEqual(compute(message).value(), mac.value());
        }

        @Override
        public Mac mac(MacKey key, byte[] message) {
            return compute(message);
        }

        @Override
        public Mac mac(MacKey key, byte[] message, int keyVersion) {
            return compute(message);
        }

        @Override
        public Signature sign(SigningKey key, byte[] message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean verify(SigningKey key, byte[] message, Signature signature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SigningPublicKey> publicKeys(SigningKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SealedData seal(EncryptionKey key, byte[] plaintext, byte[] context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] open(EncryptionKey key, SealedData sealed, byte[] context) {
            throw new UnsupportedOperationException();
        }
    }
}
