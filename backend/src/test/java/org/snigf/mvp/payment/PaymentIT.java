// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.payment.application.PaymentExpiry;
import org.snigf.mvp.payment.infrastructure.provider.InProcessSimulatorChannel;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The simulated payment end to end (MVP-08, E-17, ADR-037), through the real endpoints, the
 * callback signature check and the database's reconciliation, with the in-process simulator of
 * the test profile and callbacks signed with the callback key exactly as the simulator signs them.
 * <p>
 * The six outcomes: paid and matched, failed, pending then expired, duplicate callback, unmatched
 * reference, amount mismatch. Boundaries: a callback after expiry (swept or not), repeated and
 * concurrent callbacks, unsigned or badly signed callbacks, ownership, idempotency, the provider
 * unreachable, the gate before acceptance, and the finance officer's resolution.
 */
class PaymentIT extends CaseFlowIntegrationTest {

    @Autowired
    private PaymentExpiry expiry;

    @Test
    void paidAndMatchedOpensTheAcceptance() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));

        JsonNode due = view(applicant, id);
        assertThat(due.path("demo").asBoolean()).isTrue();
        assertThat(due.path("standing").asString("")).isEqualTo("DUE");
        assertThat(due.path("requiredBeforeAcceptance").asBoolean()).isTrue();
        assertThat(due.path("fee").path("amount").asLong()).isEqualTo(FEE);
        assertThat(due.path("fee").path("currency").asString("")).isEqualTo("XAF");
        assertThat(due.path("fee").path("labelEn").asString(""))
                .isEqualTo("DEMO platform service charge — not an official administrative fee");

        MockHttpServletResponse started = pay(applicant, id, "SUCCEED");
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(200);
        JsonNode pending = json.readTree(started.getContentAsString());
        String reference = pending.path("current").path("reference").asString("");
        assertThat(reference).matches("^DEMO-PAY-[0-9]{4}-[0-9]{8}-[0-9A-Z]$");
        assertThat(pending.path("standing").asString("")).isEqualTo("PENDING");
        assertThat(pending.path("current").path("amount").asLong()).isEqualTo(FEE);
        InProcessSimulatorChannel.Order order = simulator().last(reference);
        assertThat(order.order().amount()).isEqualTo(FEE);
        assertThat(order.order().scenario().name()).isEqualTo("SUCCEED");
        assertThat(chargeOf(reference)).isEqualTo(order.transactionId());

        assertThat(callback(order.transactionId(), reference, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        JsonNode paid = view(applicant, id);
        assertThat(paid.path("standing").asString("")).isEqualTo("PAID");
        assertThat(paid.path("current").path("status").asString("")).isEqualTo("PAID");
        assertThat(record(order.transactionId())).isEqualTo("MATCHED");

        // Paid once: no second payment.
        assertThat(code(pay(applicant, id, "SUCCEED"))).isEqualTo("ALREADY_PAID");
        accepted(signedIn("INTAKE_OFFICER"), id);
        assertThat(auditActions(reference)).contains("PAYMENT_REQUESTED", "PAYMENT_CALLBACK_ACCEPTED");
    }

    @Test
    void aFailedPaymentCanBePaidAgainUnderANewReference() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String first = reference(pay(applicant, id, "FAIL"));
        InProcessSimulatorChannel.Order order = simulator().last(first);
        assertThat(callback(order.transactionId(), first, FEE, "FAILED").getStatus()).isEqualTo(200);
        assertThat(record(order.transactionId())).isEqualTo("FAILED");
        JsonNode failed = view(applicant, id);
        assertThat(failed.path("standing").asString("")).isEqualTo("DUE");
        assertThat(failed.path("current").path("status").asString("")).isEqualTo("FAILED");
        assertThat(blocksAcceptance(id)).isTrue();

        String second = paid(applicant, id);
        assertThat(second).isNotEqualTo(first);
        assertThat(view(applicant, id).path("history").size()).isEqualTo(2);
        assertThat(blocksAcceptance(id)).isFalse();
    }

    @Test
    void aPendingPaymentExpiresAndALateSuccessIsAnExceptionNeverAPayment() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "NO_RESPONSE"));
        assertThat(view(applicant, id).path("standing").asString("")).isEqualTo("PENDING");
        assertThat(code(pay(applicant, id, "SUCCEED"))).isEqualTo("PAYMENT_PENDING");

        expireNow(reference);
        // Past its expiry, it reads expired before the sweep has run.
        assertThat(view(applicant, id).path("current").path("status").asString("")).isEqualTo("EXPIRED");
        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(status(reference)).isEqualTo("EXPIRED");
        assertThat(auditActions(reference)).contains("PAYMENT_EXPIRED");

        String tx = simulator().last(reference).transactionId();
        assertThat(callback(tx, reference, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        assertThat(record(tx)).isEqualTo("AFTER_EXPIRY");
        assertThat(status(reference)).isEqualTo("EXPIRED");
        assertThat(blocksAcceptance(id)).isTrue();
        assertThat(auditActions(reference)).contains("PAYMENT_EXCEPTION_RAISED");

        // The applicant pays again; the old request stays expired.
        String again = paid(applicant, id);
        assertThat(again).isNotEqualTo(reference);
        assertThat(blocksAcceptance(id)).isFalse();
    }

    @Test
    void aSuccessArrivingAfterTheExpiryExpiresTheRequestEvenBeforeTheSweep() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "SUCCEED"));
        expireNow(reference);
        String tx = simulator().last(reference).transactionId();
        assertThat(callback(tx, reference, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        assertThat(record(tx)).isEqualTo("AFTER_EXPIRY");
        assertThat(status(reference)).isEqualTo("EXPIRED");
        assertThat(view(applicant, id).path("standing").asString("")).isEqualTo("DUE");
        assertThat(auditActions(reference)).contains("PAYMENT_EXPIRED", "PAYMENT_EXCEPTION_RAISED");
    }

    @Test
    void aDuplicateCallbackChangesNothingAndIsRecordedAsSuch() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "DUPLICATE"));
        String tx = simulator().last(reference).transactionId();
        assertThat(callback(tx, reference, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        assertThat(callback(tx, reference, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        // Even a duplicate claiming the opposite changes nothing.
        assertThat(callback(tx, reference, FEE, "FAILED").getStatus()).isEqualTo(200);
        assertThat(records(reference)).isEqualTo(1);
        assertThat(status(reference)).isEqualTo("PAID");
        assertThat(auditActions(reference)).contains("PAYMENT_CALLBACK_DUPLICATE");
        assertThat(blocksAcceptance(id)).isFalse();
    }

    @Test
    void concurrentCopiesOfOneCallbackRecordItOnceAndPayOnce() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "DUPLICATE"));
        String tx = simulator().last(reference).transactionId();
        List<Integer> statuses = concurrently(8, () -> callback(tx, reference, FEE, "SUCCEEDED").getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(records(reference)).isEqualTo(1);
        assertThat(status(reference)).isEqualTo("PAID");
    }

    @Test
    void concurrentSuccessesUnderDifferentTransactionsPayOnceAndRaiseTheRestAsExceptions() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "SUCCEED"));
        List<Integer> statuses = concurrently(6, () -> callback("SIMTX-" + UUID.randomUUID(), reference, FEE, "SUCCEEDED").getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(records(reference)).isEqualTo(6);
        assertThat(outcomes(reference)).containsEntry("MATCHED", 1L).containsEntry("ALREADY_SETTLED", 5L);
        assertThat(status(reference)).isEqualTo("PAID");
    }

    @Test
    void anUnmatchedReferenceIsAnExceptionTheFinanceOfficerResolvesWithoutPayingAnything() throws Exception {
        String unknown = "DEMO-PAY-0000-00000000-0";
        String tx = "SIMTX-" + UUID.randomUUID();
        assertThat(callback(tx, unknown, FEE, "SUCCEEDED").getStatus()).isEqualTo(200);
        assertThat(record(tx)).isEqualTo("UNMATCHED_REFERENCE");

        SignedIn finance = signedIn("FINANCE_OFFICER");
        JsonNode open = json.readTree(finance.browser().send(get("/api/v1/payments/exceptions").param("resolved", "false"))
                .getContentAsString());
        JsonNode exception = find(open, "transactionId", tx);
        assertThat(exception.path("outcome").asString("")).isEqualTo("UNMATCHED_REFERENCE");
        assertThat(exception.path("requestReference").isNull()).isTrue();
        String recordId = exception.path("id").asString("");

        String key = key();
        MockHttpServletResponse resolved = resolve(finance, recordId, "REFUND_SIMULATED", "Simulated refund to the payer", key);
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(201);
        // The same key replays the same resolution; another resolution is refused.
        assertThat(json.readTree(resolve(finance, recordId, "REFUND_SIMULATED", "Simulated refund to the payer", key)
                .getContentAsString()).path("id").asString("")).isEqualTo(json.readTree(resolved.getContentAsString()).path("id").asString(""));
        assertThat(code(resolve(finance, recordId, "NO_ACTION", "Nothing more to do here", key()))).isEqualTo("ALREADY_RESOLVED");
        assertThat(code(resolve(finance, recordId, "NO_ACTION", "short", key()))).isEqualTo("NOTE_REQUIRED");
        assertThat(code(resolve(finance, recordId, "NO_ACTION", "Nothing more to do here", null))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        JsonNode closed = json.readTree(finance.browser().send(get("/api/v1/payments/exceptions").param("resolved", "true"))
                .getContentAsString());
        assertThat(find(closed, "transactionId", tx).path("resolution").asString("")).isEqualTo("REFUND_SIMULATED");
        assertThat(auditEntityActions(UUID.fromString(recordId))).contains("PAYMENT_EXCEPTION_RESOLVED");
    }

    @Test
    void anAmountMismatchStaysAnExceptionAndTheRequestStaysUnpaid() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = reference(pay(applicant, id, "WRONG_AMOUNT"));
        String tx = simulator().last(reference).transactionId();
        assertThat(callback(tx, reference, FEE - 1000, "SUCCEEDED").getStatus()).isEqualTo(200);
        assertThat(record(tx)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(status(reference)).isEqualTo("PENDING");
        assertThat(blocksAcceptance(id)).isTrue();

        SignedIn intake = signedIn("INTAKE_OFFICER");
        long version = json.readTree(intake.browser().send(get("/api/v1/cases/" + id)).getContentAsString()).path("version").asLong();
        MockHttpServletResponse refused = sendJson(intake.browser(), post("/api/v1/cases/" + id + "/acceptance"),
                "{\"expectedVersion\":" + version + "}");
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("PAYMENT_REQUIRED");

        SignedIn finance = signedIn("FINANCE_OFFICER");
        JsonNode exception = find(json.readTree(finance.browser().send(get("/api/v1/payments/exceptions"))
                .getContentAsString()), "transactionId", tx);
        assertThat(exception.path("reportedAmount").asLong()).isEqualTo(FEE - 1000);
        assertThat(exception.path("expectedAmount").asLong()).isEqualTo(FEE);
        assertThat(exception.path("requestReference").asString("")).isEqualTo(reference);
        // Resolving it never pays the request.
        assertThat(resolve(finance, exception.path("id").asString(""), "REFUND_SIMULATED", "Refund of the wrong amount",
                key()).getStatus()).isEqualTo(201);
        assertThat(status(reference)).isEqualTo("PENDING");
        assertThat(blocksAcceptance(id)).isTrue();
    }

    @Test
    void unsignedOrBadlySignedCallbacksAreRejectedAndNothingIsStored() throws Exception {
        String tx = "SIMTX-" + UUID.randomUUID();
        String body = callbackBody(tx, "DEMO-PAY-0000-00000000-0", FEE, "SUCCEEDED");
        String now = String.valueOf(Instant.now().getEpochSecond());
        String good = signature(now, body);

        assertThat(code(callback(body, null, null, SIMULATOR_SUBJECT))).isEqualTo("SIGNATURE_MISSING");
        assertThat(code(callback(body, now, "v1=AAAA", SIMULATOR_SUBJECT))).isEqualTo("SIGNATURE_MALFORMED");
        String forged = "v1=" + java.util.Base64.getEncoder().encodeToString(new byte[32]);
        MockHttpServletResponse invalid = callback(body, now, forged, SIMULATOR_SUBJECT);
        assertThat(invalid.getStatus()).isEqualTo(401);
        assertThat(code(invalid)).isEqualTo("SIGNATURE_INVALID");
        // A valid signature over another body, or another timestamp, does not pass.
        assertThat(code(callback(body.replace("25000", "25001"), now, good, SIMULATOR_SUBJECT))).isEqualTo("SIGNATURE_INVALID");
        String later = String.valueOf(Instant.now().getEpochSecond() + 1);
        assertThat(code(callback(body, later, good, SIMULATOR_SUBJECT))).isEqualTo("SIGNATURE_INVALID");
        // Old, correctly signed: a replay outside the window.
        String old = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
        assertThat(code(callback(body, old, signature(old, body), SIMULATOR_SUBJECT))).isEqualTo("TIMESTAMP_OUT_OF_WINDOW");
        // Correctly signed, but not vouched for by the provider listener, or another provider.
        assertThat(code(callback(body, now, good, null))).isEqualTo("PROVIDER_NOT_RECOGNISED");
        assertThat(code(callback(body, now, good, "CN=snigf-web,O=FLYT"))).isEqualTo("PROVIDER_NOT_RECOGNISED");
        // Correctly signed but not the DEMO protocol.
        String notDemo = body.replace("\"demo\":true", "\"demo\":false");
        MockHttpServletResponse malformed = callback(notDemo, now, signature(now, notDemo), SIMULATOR_SUBJECT);
        assertThat(malformed.getStatus()).isEqualTo(422);
        assertThat(code(malformed)).isEqualTo("CALLBACK_MALFORMED");

        assertThat(recordCount(tx)).isZero();
        assertThat(Long.parseLong(single("SELECT count(*)::text FROM snigf.audit_event WHERE action = ?"
                + " AND occurred_at > now() - interval '1 minute'", "PAYMENT_CALLBACK_REJECTED"))).isGreaterThanOrEqualTo(9);

        // The same body, signed and fresh: accepted.
        assertThat(callback(body, now, good, SIMULATOR_SUBJECT).getStatus()).isEqualTo(200);
        assertThat(recordCount(tx)).isEqualTo(1);
    }

    @Test
    void onlyTheApplicantPaysWithAnIdempotencyKey() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));

        Browser someoneElse = applicant("Someone Else");
        assertThat(someoneElse.send(get(APPS + "/" + id + "/payment")).getStatus()).isEqualTo(404);
        assertThat(pay(someoneElse, id, "SUCCEED").getStatus()).isEqualTo(404);
        SignedIn staff = signedIn("INTAKE_OFFICER");
        assertThat(staff.browser().send(get(APPS + "/" + id + "/payment")).getStatus()).isEqualTo(403);

        assertThat(code(pay(applicant, id, "SUCCEED", null))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        // An unknown scenario is unreadable: Boot's problem details answer 400 before any module handler.
        assertThat(pay(applicant, id, "NOT_A_SCENARIO").getStatus()).isEqualTo(400);

        String key = key();
        String first = reference(pay(applicant, id, "NO_RESPONSE", key));
        assertThat(reference(pay(applicant, id, "NO_RESPONSE", key))).isEqualTo(first);
        assertThat(code(pay(applicant, id, "SUCCEED", key))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(view(applicant, id).path("history").size()).isEqualTo(1);
        assertThat(simulator().orders().stream().filter(o -> o.order().reference().equals(first)).count()).isEqualTo(1);
    }

    @Test
    void aDraftOwesNothingYet() throws Exception {
        Browser applicant = applicant();
        String id = json.readTree(sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        assertThat(view(applicant, id).path("standing").asString("")).isEqualTo("NOT_REQUIRED");
        assertThat(code(pay(applicant, id, "SUCCEED"))).isEqualTo("APPLICATION_NOT_IN_PROGRESS");
    }

    @Test
    void whenTheProviderCannotBeReachedTheRequestWaitsAndPayingAgainStartsTheSameOne() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        simulator().setUnavailable(true);
        MockHttpServletResponse down;
        try {
            down = pay(applicant, id, "SUCCEED");
        } finally {
            simulator().setUnavailable(false);
        }
        assertThat(down.getStatus()).isEqualTo(503);
        assertThat(code(down)).isEqualTo("PROVIDER_UNAVAILABLE");
        JsonNode waiting = view(applicant, id);
        String reference = waiting.path("current").path("reference").asString("");
        assertThat(chargeOf(reference)).isNull();

        String again = reference(pay(applicant, id, "SUCCEED"));
        assertThat(again).isEqualTo(reference);
        assertThat(chargeOf(reference)).isEqualTo(simulator().last(reference).transactionId());
        assertThat(view(applicant, id).path("history").size()).isEqualTo(1);
    }

    @Test
    void theFinanceListsNeedPaymentReconcile() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        String reference = paid(applicant, id);

        SignedIn intake = signedIn("INTAKE_OFFICER");
        assertThat(intake.browser().send(get("/api/v1/payments")).getStatus()).isEqualTo(403);
        assertThat(intake.browser().send(get("/api/v1/payments/summary")).getStatus()).isEqualTo(403);

        SignedIn finance = signedIn("FINANCE_OFFICER");
        JsonNode requests = json.readTree(finance.browser().send(get("/api/v1/payments").param("status", "PAID"))
                .getContentAsString());
        JsonNode row = find(requests, "reference", reference);
        assertThat(row.path("status").asString("")).isEqualTo("PAID");
        assertThat(row.path("amount").asLong()).isEqualTo(FEE);
        assertThat(row.path("transactionId").asString("")).startsWith("SIMTX-");
        assertThat(row.has("data")).isFalse();
        JsonNode summary = json.readTree(finance.browser().send(get("/api/v1/payments/summary")).getContentAsString());
        assertThat(summary.path("demo").asBoolean()).isTrue();
        assertThat(summary.path("paid").asLong()).isGreaterThanOrEqualTo(1);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private JsonNode view(Browser applicant, String id) throws Exception {
        MockHttpServletResponse response = applicant.send(get(APPS + "/" + id + "/payment"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private String reference(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("current").path("reference").asString("");
    }

    private MockHttpServletResponse resolve(SignedIn finance, String recordId, String resolution, String note, String key)
            throws Exception {
        var request = post("/api/v1/payments/exceptions/" + recordId + "/resolution");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return sendJson(finance.browser(), request, json.writeValueAsString(Map.of("resolution", resolution, "note", note)));
    }

    private static JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (row.path(field).asString("").equals(value)) {
                return row;
            }
        }
        throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
    }

    /** Moves a pending request's expiry into the past, as time passing would (triggers off: superuser only). */
    private static void expireNow(String reference) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             Statement replica = su.createStatement();
             PreparedStatement update = su.prepareStatement("UPDATE snigf.payment_request"
                     + " SET created_at = clock_timestamp() - interval '25 hours', expires_at = clock_timestamp() - interval '1 hour'"
                     + " WHERE reference = ?")) {
            replica.execute("SET session_replication_role = replica");
            update.setString(1, reference);
            assertThat(update.executeUpdate()).isEqualTo(1);
            replica.execute("SET session_replication_role = origin");
            su.commit();
        }
    }

    private static String status(String reference) throws Exception {
        return single("SELECT status FROM snigf.payment_request WHERE reference = ?", reference);
    }

    private static String chargeOf(String reference) throws Exception {
        return single("SELECT provider_charge_id FROM snigf.payment_request WHERE reference = ?", reference);
    }

    private static String record(String transactionId) throws Exception {
        return single("SELECT outcome FROM snigf.payment_record WHERE provider_transaction_id = ?", transactionId);
    }

    private static long recordCount(String transactionId) throws Exception {
        return Long.parseLong(single("SELECT count(*)::text FROM snigf.payment_record WHERE provider_transaction_id = ?", transactionId));
    }

    private static long records(String reference) throws Exception {
        return Long.parseLong(single("SELECT count(*)::text FROM snigf.payment_record WHERE reference = ?", reference));
    }

    private static Map<String, Long> outcomes(String reference) throws Exception {
        Map<String, Long> counts = new java.util.HashMap<>();
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement query = su.prepareStatement("SELECT outcome, count(*) FROM snigf.payment_record"
                     + " WHERE reference = ? GROUP BY outcome")) {
            query.setString(1, reference);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return counts;
    }

    private static boolean blocksAcceptance(String id) throws Exception {
        return Boolean.parseBoolean(single("SELECT snigf.payment_blocks_acceptance(?::uuid)::text", id));
    }

    private static List<String> auditActions(String entityRef) throws Exception {
        List<String> actions = new ArrayList<>();
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement query = su.prepareStatement("SELECT action FROM snigf.audit_event WHERE entity_ref = ? ORDER BY chain_pos")) {
            query.setString(1, entityRef);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    actions.add(rs.getString(1));
                }
            }
        }
        return actions;
    }

    private static List<String> auditEntityActions(UUID entityId) throws Exception {
        List<String> actions = new ArrayList<>();
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement query = su.prepareStatement("SELECT action FROM snigf.audit_event WHERE entity_id = ? ORDER BY chain_pos")) {
            query.setObject(1, entityId);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    actions.add(rs.getString(1));
                }
            }
        }
        return actions;
    }

    private static String single(String sql, String parameter) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement query = su.prepareStatement(sql)) {
            query.setString(1, parameter);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static <T> List<T> concurrently(int copies, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
