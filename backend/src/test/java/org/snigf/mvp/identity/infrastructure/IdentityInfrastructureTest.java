// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.infrastructure.config.IdentityConfigurationProbe;
import org.snigf.mvp.identity.infrastructure.crypto.PepperedArgon2PasswordHasher;
import org.snigf.mvp.identity.infrastructure.session.SessionSerializationProbe;
import org.snigf.mvp.keys.EncryptionKey;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.keys.SealedData;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.config.DeploymentEnvironment;
import org.snigf.mvp.support.PostgisDatabase.RepositoryPaths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapters of the identity module that need no database: hashing, session values, settings. */
class IdentityInfrastructureTest {

    /** Pepper key with versions 1 and 2, the way OpenBao answers after a rotation. */
    static final class TwoVersionPepper implements KeyService {
        int current = 1;

        @Override
        public Mac mac(MacKey key, byte[] message) {
            return mac(key, message, current);
        }

        @Override
        public Mac mac(MacKey key, byte[] message, int keyVersion) {
            if (keyVersion < 1 || keyVersion > current) {
                throw new KeyServiceException("unknown key version " + keyVersion);
            }
            try {
                javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
                hmac.init(new SecretKeySpec(("pepper-v" + keyVersion).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return new Mac(keyVersion, hmac.doFinal(message));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public boolean verifyMac(MacKey key, byte[] message, Mac mac) { throw new UnsupportedOperationException(); }
        @Override public Signature sign(SigningKey key, byte[] message) { throw new UnsupportedOperationException(); }
        @Override public boolean verify(SigningKey key, byte[] message, Signature s) { throw new UnsupportedOperationException(); }
        @Override public List<SigningPublicKey> publicKeys(SigningKey key) { throw new UnsupportedOperationException(); }
        @Override public SealedData seal(EncryptionKey key, byte[] p, byte[] c) { throw new UnsupportedOperationException(); }
        @Override public byte[] open(EncryptionKey key, SealedData s, byte[] c) { throw new UnsupportedOperationException(); }
    }

    static IdentityProperties properties(int memoryKib, int iterations, int minLength, String sessionCookie,
                                         String csrfCookie, String sameSite) {
        return new IdentityProperties(
                new IdentityProperties.Password(minLength, 128, memoryKib, iterations, 1, 2, Duration.ofSeconds(1)),
                new IdentityProperties.Lockout(5, Duration.ofMinutes(15)),
                new IdentityProperties.Session(Duration.ofHours(8), sessionCookie, sameSite),
                new IdentityProperties.Csrf(csrfCookie, "X-XSRF-TOKEN"),
                new IdentityProperties.RateLimit(20, 10, 1000),
                new IdentityProperties.WebAuthn("localhost", "SNIGF test", java.util.List.of("https://localhost:47000"),
                        Duration.ofMinutes(2), "none", 2, 10),
                new IdentityProperties.Administration(Duration.ofDays(7)));
    }

    static final IdentityProperties SAFE = properties(19_456, 2, 12, "__Host-snigf-session", "__Host-snigf-xsrf", "Strict");

    @Test
    void hashesArePepperedArgon2idAndVerifyOnlyTheRightPassword() {
        TwoVersionPepper keys = new TwoVersionPepper();
        PepperedArgon2PasswordHasher hasher = new PepperedArgon2PasswordHasher(keys,
                properties(1024, 1, 12, "__Host-a", "__Host-b", "Strict"));

        String stored = hasher.hash("Correct-Horse-Battery-42");

        assertThat(stored).startsWith("$snigf-pepper$v=1$$argon2id$v=19$m=1024,t=1,p=1$");
        assertThat(hasher.hash("Correct-Horse-Battery-42")).as("random salt").isNotEqualTo(stored);
        assertThat(hasher.matches("Correct-Horse-Battery-42", stored)).isTrue();
        assertThat(hasher.matches("Correct-Horse-Battery-43", stored)).isFalse();
        assertThat(hasher.matches("Correct-Horse-Battery-42", "plain-text")).as("malformed never matches").isFalse();
        assertThat(hasher.needsRehash(stored)).isFalse();

        keys.current = 2;   // the pepper key was rotated
        hasher.hash("anything at all");
        assertThat(hasher.matches("Correct-Horse-Battery-42", stored)).as("old version still verifies").isTrue();
        assertThat(hasher.needsRehash(stored)).as("upgraded at next login").isTrue();

        PepperedArgon2PasswordHasher stronger = new PepperedArgon2PasswordHasher(keys,
                properties(2048, 2, 12, "__Host-a", "__Host-b", "Strict"));
        assertThat(stronger.needsRehash(stronger.hash("x".repeat(12)))).isFalse();
        assertThat(stronger.needsRehash(stored)).as("weaker parameters are upgraded").isTrue();
    }

    @Test
    void anUnknownAccountCostsAHashToo() {
        PepperedArgon2PasswordHasher hasher = new PepperedArgon2PasswordHasher(new TwoVersionPepper(),
                properties(1024, 1, 12, "__Host-a", "__Host-b", "Strict"));
        hasher.spendEquivalentTime("any password");   // must not throw, must not match anything
        hasher.spendEquivalentTime(null);
    }

    @Test
    void sessionsHoldOnlyPlainValues() throws Exception {
        for (Object ok : new Object[] {"user", 42L, 7, true}) {
            assertThat(SessionSerializationProbe.roundTrip(ok)).isEqualTo(ok);
        }
        assertThatThrownBy(() -> SessionSerializationProbe.serialize(new HashMap<>()))
                .hasMessageContaining("only strings, numbers and booleans");

        ByteArrayOutputStream gadget = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(gadget)) {
            out.writeObject(new HashMap<>(java.util.Map.of("k", "v")));
        }
        assertThatThrownBy(() -> SessionSerializationProbe.deserialize(gadget.toByteArray()))
                .hasMessageContaining("refused");

        assertThatThrownBy(() -> SessionSerializationProbe.deserialize(
                SessionSerializationProbe.serialize("x".repeat(10_000)))).as("size limit").hasMessageContaining("refused");
    }

    @Test
    void identitySettingsAreCheckedOutsideTests() {
        Duration idle = Duration.ofMinutes(30);
        for (DeploymentEnvironment env : DeploymentEnvironment.values()) {
            assertThat(IdentityConfigurationProbe.check(SAFE, env, idle)).as(env.name()).isEmpty();
        }
        DeploymentEnvironment prod = DeploymentEnvironment.PROD;
        assertThat(IdentityConfigurationProbe.check(properties(19_456, 2, 12, "SESSION", "__Host-x", "Strict"), prod, idle))
                .singleElement().asString().contains("SNIGF_SESSION_COOKIE_NAME");
        assertThat(IdentityConfigurationProbe.check(properties(19_456, 2, 12, "__Host-s", "XSRF-TOKEN", "Strict"), prod, idle))
                .singleElement().asString().contains("SNIGF_CSRF_COOKIE_NAME");
        assertThat(IdentityConfigurationProbe.check(properties(19_456, 2, 12, "__Host-s", "__Host-x", "None"), prod, idle))
                .singleElement().asString().contains("SAME_SITE");
        assertThat(IdentityConfigurationProbe.check(properties(4096, 1, 12, "__Host-s", "__Host-x", "Strict"), prod, idle))
                .singleElement().asString().contains("Argon2id");
        assertThat(IdentityConfigurationProbe.check(properties(19_456, 2, 8, "__Host-s", "__Host-x", "Strict"), prod, idle))
                .singleElement().asString().contains("MIN_LENGTH");
        assertThat(IdentityConfigurationProbe.check(SAFE, prod, Duration.ofHours(9)))
                .singleElement().asString().contains("IDLE_TIMEOUT");
        assertThat(IdentityConfigurationProbe.check(properties(1024, 1, 8, "SESSION", "XSRF", "Strict"),
                DeploymentEnvironment.TEST, idle)).as("cheap settings allowed in tests").isEmpty();
    }

    @Test
    void webAuthnOriginsMustMatchTheRelyingPartyAndUseHttps() {
        Duration idle = Duration.ofMinutes(30);
        DeploymentEnvironment prod = DeploymentEnvironment.PROD;
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("snigf.example.cm",
                List.of("https://snigf.example.cm", "https://app.snigf.example.cm:47000"), 2), prod, idle)).isEmpty();
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("snigf.example.cm",
                List.of("https://evil.example"), 2), prod, idle)).singleElement().asString().contains("RP_ID");
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("snigf.example.cm",
                List.of("https://notsnigf.example.cm"), 2), prod, idle)).as("suffix, not substring")
                .singleElement().asString().contains("RP_ID");
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("localhost",
                List.of("http://localhost:47000"), 2), prod, idle)).singleElement().asString().contains("https");
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("localhost",
                List.of("https://localhost:47000/path"), 2), prod, idle)).singleElement().asString().contains("scheme://host");
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("localhost",
                List.of("https://localhost:47000"), 1), prod, idle)).singleElement().asString().contains("MIN_AUTHENTICATORS");
        assertThat(IdentityConfigurationProbe.check(withWebAuthn("localhost",
                List.of("http://localhost:47000"), 1), DeploymentEnvironment.TEST, idle)).isEmpty();
    }

    @Test
    void recoveryCodeHashesAreKeyedDomainSeparatedAndVersioned() {
        TwoVersionPepper keys = new TwoVersionPepper();
        org.snigf.mvp.identity.infrastructure.crypto.KeyedRecoveryCodeHasher hasher =
                new org.snigf.mvp.identity.infrastructure.crypto.KeyedRecoveryCodeHasher(keys);
        String stored = hasher.hash("ABCDEFGHJKMNPQRS");

        assertThat(stored).matches("v1:[0-9a-f]{64}");
        assertThat(hasher.matches("ABCDEFGHJKMNPQRS", stored)).isTrue();
        assertThat(hasher.matches("ABCDEFGHJKMNPQRT", stored)).isFalse();
        assertThat(hasher.matches("ABCDEFGHJKMNPQRS", "garbage")).isFalse();
        assertThat(stored.substring(3)).as("never equal to the password MAC of the same text").isNotEqualTo(
                HexFormat.of().formatHex(keys.mac(MacKey.PASSWORD_PEPPER,
                        "ABCDEFGHJKMNPQRS".getBytes(StandardCharsets.UTF_8)).value()));
        keys.current = 2;
        assertThat(hasher.matches("ABCDEFGHJKMNPQRS", stored)).as("old version still checks").isTrue();
    }

    static IdentityProperties withWebAuthn(String rpId, List<String> origins, int minAuthenticators) {
        return new IdentityProperties(SAFE.password(), SAFE.lockout(), SAFE.session(), SAFE.csrf(), SAFE.rateLimit(),
                new IdentityProperties.WebAuthn(rpId, "SNIGF", origins, Duration.ofMinutes(2), "none", minAuthenticators, 10),
                SAFE.administration());
    }

    /** common-passwords.NOTICE.md: the bundled list cannot change unnoticed. */
    @Test
    void theCommonPasswordListIsTheDocumentedOne() throws Exception {
        byte[] list = java.nio.file.Files.readAllBytes(
                RepositoryPaths.file("backend/src/main/resources/identity/common-passwords.txt"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(list)))
                .isEqualTo("01e70dd6c848616ec19ed6bbb6461133d2131fd80d7755a85e9800ed24d1b630");
        assertThat(IdentityConfigurationProbe.commonPasswords()).hasSize(29_982).contains("q1w2e3r4t5y6");
    }
}
