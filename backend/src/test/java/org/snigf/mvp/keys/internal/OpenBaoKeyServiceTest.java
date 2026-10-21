// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ADR-018: the OpenBao provider speaks the transit API as the policy allows, logs in with
 * its certificate, renews its token, and never leaks response bodies into errors.
 */
class OpenBaoKeyServiceTest {

    private static final String BASE = "https://openbao.test/v1/";
    private static final Base64.Encoder B64 = Base64.getEncoder();

    private MockRestServiceServer server;
    private OpenBaoKeyService keys;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        keys = new OpenBaoKeyService(new OpenBaoClient(builder.build(), "cert", "snigf-app", clock), "transit",
                EphemeralKeyServiceTest.NAMES);
    }

    private void expectLogin(String token, long leaseSeconds) {
        server.expect(requestTo(BASE + "auth/cert/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("snigf-app"))
                .andRespond(withSuccess("{\"auth\":{\"client_token\":\"" + token + "\",\"lease_duration\":" + leaseSeconds + "}}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void signsInsideOpenBaoAndVerifiesLocallyWithItsPublicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] message = "checkpoint 42".getBytes(StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(message);
        byte[] sig = signer.sign();
        byte[] spki = pair.getPublic().getEncoded();
        String publicKey = B64.encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));

        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/sign/audit-checkpoint"))
                .andExpect(header(OpenBaoClient.TOKEN_HEADER, "t1"))
                .andExpect(jsonPath("$.input").value(B64.encodeToString(message)))
                .andRespond(withSuccess("{\"data\":{\"signature\":\"vault:v2:" + B64.encodeToString(sig) + "\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "transit/keys/audit-checkpoint"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"type\":\"ed25519\",\"keys\":{\"2\":{\"public_key\":\"" + publicKey + "\"}}}}",
                        MediaType.APPLICATION_JSON));

        Signature signature = keys.sign(SigningKey.AUDIT_CHECKPOINT, message);

        assertThat(signature.keyVersion()).isEqualTo(2);
        assertThat(keys.verify(SigningKey.AUDIT_CHECKPOINT, message, signature)).isTrue();
        // Second verification uses the cached public key: no further request.
        assertThat(keys.verify(SigningKey.AUDIT_CHECKPOINT, "tampered".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        server.verify();
    }

    @Test
    void envelopeUsesAOneTimeDataKeyBoundToTheContext() {
        byte[] dataKey = new byte[32];
        Arrays.fill(dataKey, (byte) 7);
        byte[] context = "record-1".getBytes(StandardCharsets.UTF_8);
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/datakey/plaintext/data-encryption"))
                .andExpect(jsonPath("$.context").value(B64.encodeToString(context)))
                .andExpect(jsonPath("$.bits").value(256))
                .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + B64.encodeToString(dataKey)
                        + "\",\"ciphertext\":\"vault:v1:wrapped\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "transit/decrypt/data-encryption"))
                .andExpect(jsonPath("$.ciphertext").value("vault:v1:wrapped"))
                .andExpect(jsonPath("$.context").value(B64.encodeToString(context)))
                .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + B64.encodeToString(dataKey) + "\"}}",
                        MediaType.APPLICATION_JSON));

        SealedData sealed = keys.seal(EncryptionKey.DATA, "secret".getBytes(StandardCharsets.UTF_8), context);

        assertThat(sealed.wrappedKey()).isEqualTo("vault:v1:wrapped");
        assertThat(keys.open(EncryptionKey.DATA, sealed, context)).asString(StandardCharsets.UTF_8).isEqualTo("secret");
        server.verify();
    }

    @Test
    void macVerificationRecomputesWithTheRecordedVersion() {
        byte[] value = new byte[32];
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(jsonPath("$.key_version").doesNotExist())
                .andRespond(withSuccess("{\"data\":{\"hmac\":\"vault:v3:" + B64.encodeToString(value) + "\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(jsonPath("$.key_version").value(3))
                .andRespond(withSuccess("{\"data\":{\"hmac\":\"vault:v3:" + B64.encodeToString(value) + "\"}}",
                        MediaType.APPLICATION_JSON));

        Mac mac = keys.mac(MacKey.BLIND_INDEX, new byte[] {1});

        assertThat(mac.keyVersion()).isEqualTo(3);
        assertThat(keys.verifyMac(MacKey.BLIND_INDEX, new byte[] {1}, mac)).isTrue();
        server.verify();
    }

    @Test
    void reusesTheTokenThenLogsInAgainBeforeItExpires() {
        String hmac = "{\"data\":{\"hmac\":\"vault:v1:" + B64.encodeToString(new byte[32]) + "\"}}";
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(header(OpenBaoClient.TOKEN_HEADER, "t1"))
                .andRespond(withSuccess(hmac, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(header(OpenBaoClient.TOKEN_HEADER, "t1"))
                .andRespond(withSuccess(hmac, MediaType.APPLICATION_JSON));
        expectLogin("t2", 900);
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(header(OpenBaoClient.TOKEN_HEADER, "t2"))
                .andRespond(withSuccess(hmac, MediaType.APPLICATION_JSON));

        keys.mac(MacKey.BLIND_INDEX, new byte[] {1});
        clock.advanceSeconds(599);
        keys.mac(MacKey.BLIND_INDEX, new byte[] {1});
        clock.advanceSeconds(2);   // past two thirds of 900 s
        keys.mac(MacKey.BLIND_INDEX, new byte[] {1});
        server.verify();
    }

    @Test
    void logsInAgainOnceWhenTheTokenIsRevoked() {
        String hmac = "{\"data\":{\"hmac\":\"vault:v1:" + B64.encodeToString(new byte[32]) + "\"}}";
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectLogin("t2", 900);
        server.expect(requestTo(BASE + "transit/hmac/blind-index/sha2-256"))
                .andExpect(header(OpenBaoClient.TOKEN_HEADER, "t2"))
                .andRespond(withSuccess(hmac, MediaType.APPLICATION_JSON));

        assertThat(keys.mac(MacKey.BLIND_INDEX, new byte[] {1}).keyVersion()).isEqualTo(1);
        server.verify();
    }

    @Test
    void errorsNameThePathAndStatusButNeverTheResponseBody() {
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/datakey/plaintext/data-encryption"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[\"SENSITIVE-DETAIL\"]}"));

        assertThatThrownBy(() -> keys.seal(EncryptionKey.DATA, new byte[] {1}, new byte[] {2}))
                .isInstanceOf(KeyServiceException.class)
                .hasMessageContaining("transit/datakey/plaintext/data-encryption")
                .hasMessageContaining("HTTP 400")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SENSITIVE-DETAIL"));
    }

    @Test
    void refusedCertificateLoginFailsClosed() {
        server.expect(requestTo(BASE + "auth/cert/login")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> keys.mac(MacKey.PASSWORD_PEPPER, new byte[] {1}))
                .isInstanceOf(KeyServiceException.class)
                .hasMessageContaining("certificate login");
    }

    @Test
    void custodyCheckRejectsAnExportableKey() {
        expectLogin("t1", 900);
        server.expect(requestTo(BASE + "transit/keys/audit-checkpoint"))
                .andRespond(withSuccess("{\"data\":{\"type\":\"ed25519\",\"exportable\":true,"
                        + "\"allow_plaintext_backup\":false,\"deletion_allowed\":false,\"derived\":false}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(keys::verifyCustody)
                .isInstanceOf(KeyServiceException.class)
                .hasMessageContaining("audit-checkpoint")
                .hasMessageContaining("exportable");
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
