// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.application.WebAuthnCeremonies;
import org.snigf.mvp.identity.domain.SigningChallenge;
import org.snigf.mvp.identity.infrastructure.webauthn.SpringWebAuthnCeremonies;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-040 plan, step 1: an officer signs a record with their own security key, in a ceremony
 * whose challenge is set by the server and separate from sign-in. The authenticators are
 * webauthn4j emulators, so the server verifies genuine WebAuthn signatures.
 */
class OfficerSignatureIT extends IdentityIntegrationTest {

    private static final byte[] CANONICAL = "snigf-inscription-v1 record".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private OfficerSignatures signatures;

    @Autowired
    private JdbcOperations jdbcOperations;

    @Autowired
    private IdentityProperties properties;

    @Test
    void anOfficerSignsARecordAndAnyoneCanVerifyItFromTheStoredEvidence() throws Exception {
        Enrolled officer = enrol();
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);

        JsonNode options = json.readTree(started.optionsJson());
        assertThat(options.path("userVerification").asString("")).isEqualTo("required");
        assertThat(options.path("allowCredentials")).extracting(c -> c.path("id").asString(""))
                .as("the officer's own keys only")
                .containsExactlyInAnyOrder(officer.first().credentialId, officer.second().credentialId);

        OfficerSignature signature = signatures.finish(started.ceremonyId(), officer.id(), CANONICAL,
                officer.first().get(started.optionsJson()));

        assertThat(b64(options.path("challenge").asString("")))
                .as("the challenge is derived from the record and the nonce")
                .isEqualTo(SigningChallenge.of(CANONICAL, signature.nonce()));
        assertThat(signature.nonce()).hasSize(32);
        assertThat(signature.credentialId()).isEqualTo(officer.first().credentialId);
        assertThat(signatures.verify(CANONICAL, signature)).isTrue();
    }

    @Test
    void theStoredEvidenceStopsVerifyingWhenAnythingInItChanges() throws Exception {
        Enrolled officer = enrol();
        OfficerSignature good = sign(officer, CANONICAL);

        assertThat(signatures.verify("another record".getBytes(StandardCharsets.UTF_8), good)).isFalse();
        assertThat(signatures.verify(CANONICAL, new OfficerSignature(good.credentialId(), good.publicKeyCose(),
                new byte[32], good.authenticatorData(), good.clientDataJson(), good.signature())))
                .as("another nonce").isFalse();
        byte[] flippedSignature = good.signature();
        flippedSignature[flippedSignature.length - 1] ^= 1;
        assertThat(signatures.verify(CANONICAL, new OfficerSignature(good.credentialId(), good.publicKeyCose(),
                good.nonce(), good.authenticatorData(), good.clientDataJson(), flippedSignature)))
                .as("altered signature").isFalse();
        byte[] noUserVerification = good.authenticatorData();
        noUserVerification[32] &= ~0x04;
        assertThat(signatures.verify(CANONICAL, new OfficerSignature(good.credentialId(), good.publicKeyCose(),
                good.nonce(), noUserVerification, good.clientDataJson(), good.signature())))
                .as("user verification flag cleared").isFalse();
        OfficerSignature others = sign(enrol(), CANONICAL);
        assertThat(signatures.verify(CANONICAL, new OfficerSignature(good.credentialId(), others.publicKeyCose(),
                good.nonce(), good.authenticatorData(), good.clientDataJson(), good.signature())))
                .as("another officer's public key").isFalse();
        assertThat(signatures.verify(CANONICAL, new OfficerSignature(good.credentialId(), new byte[] {1, 2, 3},
                good.nonce(), good.authenticatorData(), good.clientDataJson(), good.signature())))
                .as("an unreadable key verifies nothing").isFalse();
    }

    @Test
    void aSignatureStaysVerifiableAfterTheKeyIsRemoved() throws Exception {
        Enrolled officer = enrol();
        OfficerSignature signature = sign(officer, CANONICAL);

        jdbc.update("DELETE FROM user_credentials WHERE credential_id = ?", officer.first().credentialId);

        assertThat(signatures.verify(CANONICAL, signature)).isTrue();
    }

    @Test
    void aTamperedRecordIsRefusedEvenWithAValidResponse() throws Exception {
        Enrolled officer = enrol();
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);
        String response = officer.first().get(started.optionsJson());
        byte[] tampered = Arrays.copyOf(CANONICAL, CANONICAL.length);
        tampered[0] ^= 1;

        assertThatThrownBy(() -> signatures.finish(started.ceremonyId(), officer.id(), tampered, response))
                .isInstanceOf(OfficerSignatures.Refused.class).hasMessageContaining("changed");
    }

    @Test
    void anotherOfficersKeyIsRefused() throws Exception {
        Enrolled officer = enrol();
        Enrolled intruder = enrol();
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);
        String signedByIntruder = intruder.first().get(started.optionsJson(), intruder.first().credentialId);

        assertThatThrownBy(() -> signatures.finish(started.ceremonyId(), officer.id(), CANONICAL, signedByIntruder))
                .isInstanceOf(OfficerSignatures.Refused.class);
    }

    @Test
    void anotherOfficerCannotFinishTheCeremony() throws Exception {
        Enrolled officer = enrol();
        Enrolled other = enrol();
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);
        String response = officer.first().get(started.optionsJson());

        assertThatThrownBy(() -> signatures.finish(started.ceremonyId(), other.id(), CANONICAL, response))
                .isInstanceOf(OfficerSignatures.Refused.class);
    }

    @Test
    void aReplayedResponseIsRefused() throws Exception {
        Enrolled officer = enrol();
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);
        String response = officer.first().get(started.optionsJson());
        signatures.finish(started.ceremonyId(), officer.id(), CANONICAL, response);

        assertThatThrownBy(() -> signatures.finish(started.ceremonyId(), officer.id(), CANONICAL, response))
                .as("the same ceremony").isInstanceOf(OfficerSignatures.Refused.class);
        OfficerSignatures.Started next = signatures.begin(officer.id(), CANONICAL);
        assertThatThrownBy(() -> signatures.finish(next.ceremonyId(), officer.id(), CANONICAL, response))
                .as("a new ceremony has a new nonce, so the old response no longer answers it")
                .isInstanceOf(OfficerSignatures.Refused.class);
    }

    @Test
    void anExpiredChallengeIsRefused() throws Exception {
        Enrolled officer = enrol();
        MovableClock clock = new MovableClock();
        WebAuthnCeremonies ceremonies = new SpringWebAuthnCeremonies(jdbcOperations, properties, clock);
        WebAuthnCeremonies.Started started = ceremonies.beginSigning(officer.id(), CANONICAL);
        String response = officer.first().get(started.optionsJson());
        clock.now = clock.now.plus(properties.webauthn().timeout()).plus(Duration.ofSeconds(1));

        assertThatThrownBy(() -> ceremonies.finishSigning(started.ceremonyId(), officer.id(), CANONICAL, response))
                .isInstanceOf(WebAuthnCeremonies.Rejected.class);
    }

    @Test
    void aSignInCeremonyCannotBeUsedToSignARecord() throws Exception {
        Enrolled officer = enrol();
        Browser browser = new Browser();
        browser.login(officer.email(), PASSWORD);
        String signIn = options(browser, "assertion");
        String response = officer.first().get(signIn);
        OfficerSignatures.Started started = signatures.begin(officer.id(), CANONICAL);

        assertThatThrownBy(() -> signatures.finish(started.ceremonyId(), officer.id(), CANONICAL, response))
                .as("its challenge is not the record's").isInstanceOf(OfficerSignatures.Refused.class);
    }

    @Test
    void applicantsDoNotSign() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Applicant Only", PASSWORD);

        assertThatThrownBy(() -> signatures.begin(userId(email), CANONICAL))
                .isInstanceOf(OfficerSignatures.Refused.class);
    }

    private OfficerSignature sign(Enrolled officer, byte[] canonical) {
        OfficerSignatures.Started started = signatures.begin(officer.id(), canonical);
        return signatures.finish(started.ceremonyId(), officer.id(), canonical, officer.first().get(started.optionsJson()));
    }

    /** A clock the test moves forward. */
    private static final class MovableClock extends Clock {
        Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
