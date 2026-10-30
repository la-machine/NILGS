// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Numbers, the signed manifest and what a presented file or number proves (ADR-039). */
class IssuanceDomainTest {

    private static final Instant AT = Instant.parse("2026-10-29T10:00:00.123456Z");
    private static final byte[] SHA = Manifest.sha256("the exact PDF".getBytes(StandardCharsets.UTF_8));

    @Test
    void numbersAreRandomWellFormedAndNeverGuessedFromEachOther() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String n = DocumentNumber.random(random).value();
            assertThat(DocumentNumber.isValid(n)).as(n).isTrue();
            assertThat(seen.add(n)).isTrue();
        }
        assertThat(DocumentNumber.normalise(" snigf-0123456789abcdefghjkmnpqrs ")).isEqualTo("SNIGF-0123456789ABCDEFGHJKMNPQRS");
        assertThat(DocumentNumber.isValid("SNIGF-0123456789ABCDEFGHJKMNPQRI")).as("I is not in the alphabet").isFalse();
        assertThatThrownBy(() -> new DocumentNumber("DEMO-APP-2026-000001-X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theManifestBindsEveryFieldAndTheExactBytes() {
        byte[] m = Manifest.canonical("SNIGF-0123456789ABCDEFGHJKMNPQRS", DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT,
                "ack-demo-1", "APPLICATION", "DEMO-APP-2026-000001-X", "YDE1", AT, SHA);
        String text = new String(m, StandardCharsets.UTF_8);
        assertThat(text).startsWith("snigf-issued-document-v1|32:SNIGF-0123456789ABCDEFGHJKMNPQRS|30:DEMO_SUBMISSION_ACKNOWLEDGMENT|")
                .contains("|27:2026-10-29T10:00:00.123456Z|").endsWith("|64:" + java.util.HexFormat.of().formatHex(SHA));
        byte[] otherBytes = Manifest.canonical("SNIGF-0123456789ABCDEFGHJKMNPQRS", DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT,
                "ack-demo-1", "APPLICATION", "DEMO-APP-2026-000001-X", "YDE1", AT, Manifest.sha256(new byte[] {1}));
        byte[] otherHolder = Manifest.canonical("SNIGF-0123456789ABCDEFGHJKMNPQRS", DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT,
                "ack-demo-1", "APPLICATION", "DEMO-APP-2026-000002-X", "YDE1", AT, SHA);
        assertThat(otherBytes).isNotEqualTo(m);
        assertThat(otherHolder).isNotEqualTo(m);
    }

    @Test
    void onlyAnIdenticalFileIsAuthentic() {
        Verification.Evidence valid = evidence(IssuedDocument.Status.VALID);
        Verification.Claim claim = new Verification.Claim(true, true, valid.number());
        assertThat(Verification.ofFile(SHA, claim, Optional.of(valid), Optional.empty(), e -> true).outcome())
                .isEqualTo(Verification.Outcome.AUTHENTIC);
        Verification.Result altered = Verification.ofFile(Manifest.sha256(new byte[] {9}), claim, Optional.empty(),
                Optional.of(valid), e -> true);
        assertThat(altered.outcome()).isEqualTo(Verification.Outcome.ALTERED);
        assertThat(altered.incident()).isEqualTo(Verification.IncidentKind.ALTERED);
    }

    @Test
    void aFileClaimingSnigfThatNothingMatchesIsUnverifiedAndSuspiciousOtherFilesAreSimplyNotOurs() {
        Verification.Result fabricated = Verification.ofFile(SHA, new Verification.Claim(true, true, "SNIGF-0000000000000000000000000Z"
                .substring(0, 32)), Optional.empty(), Optional.empty(), e -> true);
        assertThat(fabricated.outcome()).isEqualTo(Verification.Outcome.UNVERIFIED);
        assertThat(fabricated.incident()).isEqualTo(Verification.IncidentKind.FABRICATED);
        Verification.Result external = Verification.ofFile(SHA, new Verification.Claim(true, false, null), Optional.empty(),
                Optional.empty(), e -> true);
        assertThat(external.outcome()).isEqualTo(Verification.Outcome.UNVERIFIED);
        assertThat(external.reason()).isEqualTo("NO_SNIGF_EVIDENCE");
        assertThat(external.incident()).as("not issued by SNIGF is not fraudulent").isNull();
        assertThat(Verification.ofFile(SHA, new Verification.Claim(false, false, null), Optional.empty(), Optional.empty(),
                e -> true).reason()).isEqualTo("NOT_A_PDF");
    }

    @Test
    void aNumberConfirmsTheRecordAndItsStatusNeverThePaper() {
        assertThat(Verification.ofNumber(Optional.of(evidence(IssuedDocument.Status.VALID)), e -> true).outcome())
                .isEqualTo(Verification.Outcome.RECORD_FOUND);
        assertThat(Verification.ofNumber(Optional.of(evidence(IssuedDocument.Status.REVOKED)), e -> true).outcome())
                .isEqualTo(Verification.Outcome.REVOKED);
        assertThat(Verification.ofNumber(Optional.of(evidence(IssuedDocument.Status.SUPERSEDED)), e -> true).outcome())
                .isEqualTo(Verification.Outcome.SUPERSEDED);
        Verification.Result unknown = Verification.ofNumber(Optional.empty(), e -> true);
        assertThat(unknown.outcome()).isEqualTo(Verification.Outcome.UNVERIFIED);
        assertThat(unknown.incident()).as("a typo is not evidence").isNull();
    }

    @Test
    void aRegistryRowThatNoLongerAgreesWithItsSignatureIsNeverTrusted() {
        Verification.Evidence valid = evidence(IssuedDocument.Status.VALID);
        Verification.Result badSignature = Verification.ofFile(SHA, new Verification.Claim(true, true, valid.number()),
                Optional.of(valid), Optional.empty(), e -> false);
        assertThat(badSignature.outcome()).isEqualTo(Verification.Outcome.UNVERIFIED);
        assertThat(badSignature.incident()).isEqualTo(Verification.IncidentKind.REGISTRY_INTEGRITY);
        Verification.Evidence rewritten = new Verification.Evidence(valid.id(), valid.number(), valid.type(),
                valid.templateVersion(), valid.subjectType(), "DEMO-APP-2026-000999-X", valid.issuedAt(), valid.officeCode(),
                null, null, valid.sha256(), valid.manifest(), valid.signature(), 1, IssuedDocument.Status.VALID, null);
        assertThat(rewritten.consistent()).as("a field rewritten beside its manifest").isFalse();
        assertThat(Verification.ofNumber(Optional.of(rewritten), e -> true).reason()).isEqualTo("REGISTRY_INTEGRITY");
    }

    private static Verification.Evidence evidence(IssuedDocument.Status status) {
        String number = "SNIGF-0123456789ABCDEFGHJKMNPQRS";
        byte[] manifest = Manifest.canonical(number, DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT, "ack-demo-1", "APPLICATION",
                "DEMO-APP-2026-000001-X", "YDE1", AT, SHA);
        return new Verification.Evidence(UUID.randomUUID(), number, DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT, "ack-demo-1",
                "APPLICATION", "DEMO-APP-2026-000001-X", AT, "YDE1", "Bureau", "Office", SHA, manifest, "snigf:v1:AA==", 1,
                status, status == IssuedDocument.Status.SUPERSEDED ? "SNIGF-ZZZZZZZZZZZZZZZZZZZZZZZZZZ" : null);
    }
}
