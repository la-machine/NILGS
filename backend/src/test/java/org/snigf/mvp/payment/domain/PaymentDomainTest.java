// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** References, standing, expiry as of now, fee definitions and outcomes (ADR-037). */
class PaymentDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void aReferenceCarriesTheYearEightDigitsAndTheCheckCharacter() {
        PaymentReference reference = PaymentReference.issue(2026, 42);
        assertThat(reference.value()).matches("^DEMO-PAY-2026-00000042-[0-9A-Z]$");
        assertThat(PaymentReference.isValid(reference.value())).isTrue();
        String mistyped = reference.value().replace("00000042", "00000043");
        assertThat(PaymentReference.isValid(mistyped)).isFalse();
        assertThat(PaymentReference.isValid("DEMO-PAY-0000-00000000-0")).isFalse();
        assertThatThrownBy(() -> PaymentReference.issue(2026, 100_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentReference("DEMO-APP-2026-000001-X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPendingRequestPastItsExpiryReadsExpiredBeforeAnySweep() {
        PaymentRequest pending = request(PaymentRequest.Status.PENDING, NOW.plusSeconds(1));
        assertThat(pending.statusAt(NOW)).isEqualTo(PaymentRequest.Status.PENDING);
        assertThat(pending.statusAt(NOW.plusSeconds(1))).isEqualTo(PaymentRequest.Status.EXPIRED);
        assertThat(request(PaymentRequest.Status.PAID, NOW.minusSeconds(60)).statusAt(NOW)).isEqualTo(PaymentRequest.Status.PAID);
    }

    @Test
    void theStandingFollowsTheRequests() {
        assertThat(Standing.of(false, List.of(), NOW)).isEqualTo(Standing.NOT_REQUIRED);
        assertThat(Standing.of(true, List.of(), NOW)).isEqualTo(Standing.DUE);
        assertThat(Standing.of(true, List.of(request(PaymentRequest.Status.PENDING, NOW.plusSeconds(60))), NOW))
                .isEqualTo(Standing.PENDING);
        assertThat(Standing.of(true, List.of(request(PaymentRequest.Status.PENDING, NOW.minusSeconds(60))), NOW))
                .as("expired: due again").isEqualTo(Standing.DUE);
        assertThat(Standing.of(true, List.of(request(PaymentRequest.Status.FAILED, NOW),
                request(PaymentRequest.Status.PAID, NOW)), NOW)).isEqualTo(Standing.PAID);
    }

    @Test
    void aFeeIsADemoChargeInXafSaidInBothLanguages() {
        Fee.Definition good = new Fee.Definition("DEMO_REGISTRATION", 25_000, "XAF",
                "Frais de service de la plateforme DÉMO — pas un droit administratif officiel",
                "DEMO platform service charge — not an official administrative fee");
        assertThat(good.problems()).isEmpty();
        assertThat(new Fee.Definition("DEMO_REGISTRATION", 0, "EUR", "Frais", "Official fee").problems())
                .hasSize(3);
        assertThat(new Fee.Definition("demo", 1, "XAF", "Frais DÉMO", "DEMO fee").problems()).hasSize(1);
    }

    @Test
    void onlyMoneyTakenWithoutAMatchingPendingRequestIsAnException() {
        assertThat(Reconciliation.Outcome.MATCHED.exception()).isFalse();
        assertThat(Reconciliation.Outcome.FAILED.exception()).isFalse();
        assertThat(Reconciliation.Outcome.NO_EFFECT.exception()).isFalse();
        assertThat(List.of(Reconciliation.Outcome.AMOUNT_MISMATCH, Reconciliation.Outcome.UNMATCHED_REFERENCE,
                Reconciliation.Outcome.AFTER_EXPIRY, Reconciliation.Outcome.ALREADY_SETTLED))
                .allMatch(Reconciliation.Outcome::exception);
    }

    @Test
    void aReportIsValidatedBeforeAnythingReadsIt() {
        assertThatThrownBy(() -> new ProviderReport(PaymentProvider.Kind.DEMO_SIMULATOR, "short", "DEMO-PAY-X", 1, "XAF",
                ProviderReport.Status.SUCCEEDED, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderReport(PaymentProvider.Kind.DEMO_SIMULATOR, "SIMTX-12345678", "DEMO-PAY-X", -1,
                "XAF", ProviderReport.Status.SUCCEEDED, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderReport(PaymentProvider.Kind.DEMO_SIMULATOR, "SIMTX-12345678", "DEMO-PAY-X", 1,
                "xaf", ProviderReport.Status.SUCCEEDED, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static PaymentRequest request(PaymentRequest.Status status, Instant expiresAt) {
        return new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), "DEMO-PAY-2026-00000001-X", 25_000, "XAF", status,
                Scenario.SUCCEED, null, expiresAt.minusSeconds(86_400), expiresAt, null);
    }
}
