// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.payment.domain.Fee;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.ProviderReport;
import org.snigf.mvp.payment.domain.Reconciliation;
import org.snigf.mvp.payment.domain.Scenario;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Payments in PostgreSQL (V18). Requests are read under row-level security; the database's
 * guard fills what a request owes; reports, expiry, the gate and the finance reads are the
 * database's functions.
 */
@Repository
class JdbcPaymentRepository implements PaymentRepository {

    private static final String FEE = "SELECT id, application_type_code, amount, currency, label_fr, label_en, valid_from, valid_to"
            + " FROM fee_schedule ";
    private static final String REQUEST = "SELECT id, application_id, reference, amount, currency, status, scenario,"
            + " provider_charge_id, created_at, expires_at, settled_at FROM payment_request ";

    private final JdbcClient jdbc;

    JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Fee> feeInForce(String typeCode) {
        return jdbc.sql(FEE + "WHERE id = fee_in_force(:type, clock_timestamp())").param("type", typeCode)
                .query(JdbcPaymentRepository::fee).optional();
    }

    @Override
    public Optional<Fee> feeOf(UUID applicationId) {
        return jdbc.sql(FEE + "WHERE id = (SELECT fee_in_force(a.type_code, a.submitted_at) FROM application a"
                        + " WHERE a.id = :id AND a.submitted_at IS NOT NULL)")
                .param("id", applicationId).query(JdbcPaymentRepository::fee).optional();
    }

    @Override
    public List<String> feeTypeCodes() {
        return jdbc.sql("SELECT DISTINCT application_type_code FROM fee_schedule WHERE valid_to IS NULL ORDER BY 1")
                .query(String.class).list();
    }

    @Override
    public void closeFee(UUID feeId) {
        jdbc.sql("UPDATE fee_schedule SET valid_to = clock_timestamp() WHERE id = :id").param("id", feeId).update();
    }

    @Override
    public void addFee(Fee.Definition d) {
        jdbc.sql("""
                        INSERT INTO fee_schedule (application_type_code, amount, currency, label_fr, label_en, valid_from)
                        VALUES (:type, :amount, :currency, :fr, :en, clock_timestamp())""")
                .param("type", d.typeCode()).param("amount", d.amount()).param("currency", d.currency())
                .param("fr", d.labelFr()).param("en", d.labelEn()).update();
    }

    @Override
    public boolean applyGate(boolean gate) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT payment_policy_apply(:gate)").param("gate", gate)
                .query(Boolean.class).single());
    }

    @Override
    public long nextSequence(int year) {
        // Payments are numbered nationally: the counter's office column holds a fixed key.
        return jdbc.sql("SELECT next_reference_number('PAY', 'ALL', :year)").param("year", year)
                .query(Long.class).single();
    }

    @Override
    public UUID open(UUID applicationId, String reference, Scenario scenario, UUID applicant, Duration ttl,
                     PaymentProvider.Kind provider) {
        // The guard fills the fee, amount and currency, and keeps the time to live from its own clock.
        return jdbc.sql("""
                        WITH now AS (SELECT clock_timestamp() AS at)
                        INSERT INTO payment_request (application_id, reference, provider, scenario, requested_by,
                                                     created_at, expires_at)
                        SELECT :application, :reference, :provider, :scenario, :applicant,
                               now.at, now.at + make_interval(secs => :ttl)
                          FROM now
                        RETURNING id""")
                .param("application", applicationId).param("reference", reference).param("provider", provider.name())
                .param("scenario", scenario.name()).param("applicant", applicant).param("ttl", (double) ttl.toSeconds())
                .query(UUID.class).single();
    }

    @Override
    public Optional<PaymentRequest> find(UUID requestId) {
        return jdbc.sql(REQUEST + "WHERE id = :id").param("id", requestId).query(JdbcPaymentRepository::request).optional();
    }

    @Override
    public List<PaymentRequest> ofApplication(UUID applicationId) {
        return jdbc.sql(REQUEST + "WHERE application_id = :id ORDER BY created_at DESC, id DESC").param("id", applicationId)
                .query(JdbcPaymentRepository::request).list();
    }

    @Override
    public boolean recordCharge(UUID requestId, String chargeId) {
        return jdbc.sql("UPDATE payment_request SET provider_charge_id = :charge WHERE id = :id AND provider_charge_id IS NULL"
                        + " AND status = 'PENDING'")
                .param("charge", chargeId).param("id", requestId).update() == 1;
    }

    @Override
    public boolean blocksAcceptance(UUID applicationId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT payment_blocks_acceptance(:id)").param("id", applicationId)
                .query(Boolean.class).single());
    }

    @Override
    public Reconciliation.Applied apply(ProviderReport r) {
        return jdbc.sql("""
                        SELECT record_id, outcome, duplicate, expired_now, request_id, request_reference, application_id,
                               expected_amount
                          FROM payment_callback_apply(:provider, :tx, :reference, :amount, :currency, :status, :occurred, :key)""")
                .param("provider", r.provider().name()).param("tx", r.transactionId()).param("reference", r.reference())
                .param("amount", r.amount()).param("currency", r.currency()).param("status", r.status().name())
                .param("occurred", r.occurredAt() == null ? null : OffsetDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC))
                .param("key", r.keyVersion())
                .query((rs, n) -> new Reconciliation.Applied(rs.getObject("record_id", UUID.class),
                        Reconciliation.Outcome.valueOf(rs.getString("outcome")), rs.getBoolean("duplicate"),
                        rs.getBoolean("expired_now"), rs.getObject("request_id", UUID.class),
                        rs.getString("request_reference"), rs.getObject("application_id", UUID.class),
                        rs.getObject("expected_amount", Long.class)))
                .single();
    }

    @Override
    public List<Expired> expireDue() {
        return jdbc.sql("SELECT request_id, reference, application_id FROM payment_expire_due()")
                .query((rs, n) -> new Expired(rs.getObject("request_id", UUID.class), rs.getString("reference"),
                        rs.getObject("application_id", UUID.class)))
                .list();
    }

    @Override
    public List<Reconciliation.FinanceRequest> financeRequests(PaymentRequest.Status status, int limit, int offset) {
        return jdbc.sql("SELECT * FROM payment_requests_for_finance(CAST(:status AS text), :limit, :offset)")
                .param("status", status == null ? null : status.name()).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Reconciliation.FinanceRequest(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("application_reference"), rs.getLong("amount"), rs.getString("currency"),
                        PaymentRequest.Status.valueOf(rs.getString("status")), instant(rs, "created_at"),
                        instant(rs, "expires_at"), instant(rs, "settled_at"), rs.getString("transaction_id")))
                .list();
    }

    @Override
    public List<Reconciliation.FinanceException> financeExceptions(Boolean resolved, int limit, int offset) {
        return jdbc.sql("SELECT * FROM payment_exceptions_for_finance(CAST(:resolved AS boolean), :limit, :offset)")
                .param("resolved", resolved).param("limit", limit).param("offset", offset)
                .query((rs, n) -> {
                    String resolution = rs.getString("resolution");
                    return new Reconciliation.FinanceException(rs.getObject("id", UUID.class), rs.getString("transaction_id"),
                            rs.getString("reported_reference"), rs.getLong("reported_amount"),
                            rs.getString("reported_currency"), Reconciliation.Outcome.valueOf(rs.getString("outcome")),
                            instant(rs, "received_at"), rs.getString("request_reference"),
                            rs.getObject("expected_amount", Long.class), rs.getString("request_status"),
                            rs.getString("application_reference"),
                            resolution == null ? null : Reconciliation.Resolution.valueOf(resolution),
                            rs.getString("resolution_note"), rs.getString("resolved_by_name"), instant(rs, "resolved_at"));
                })
                .list();
    }

    @Override
    public Optional<Reconciliation.Summary> financeSummary() {
        return jdbc.sql("SELECT pending, paid, failed, expired, open_exceptions FROM payment_finance_summary()")
                .query((rs, n) -> new Reconciliation.Summary(rs.getLong("pending"), rs.getLong("paid"), rs.getLong("failed"),
                        rs.getLong("expired"), rs.getLong("open_exceptions")))
                .optional();
    }

    @Override
    public UUID resolve(UUID recordId, Reconciliation.Resolution resolution, String note) {
        return jdbc.sql("SELECT payment_exception_resolve(:record, :resolution, :note)")
                .param("record", recordId).param("resolution", resolution.name()).param("note", note)
                .query(UUID.class).single();
    }

    private static Fee fee(ResultSet rs, int n) throws SQLException {
        return new Fee(rs.getObject("id", UUID.class), rs.getString("application_type_code"), rs.getLong("amount"),
                rs.getString("currency"), rs.getString("label_fr"), rs.getString("label_en"), instant(rs, "valid_from"),
                instant(rs, "valid_to"));
    }

    private static PaymentRequest request(ResultSet rs, int n) throws SQLException {
        return new PaymentRequest(rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class),
                rs.getString("reference"), rs.getLong("amount"), rs.getString("currency"),
                PaymentRequest.Status.valueOf(rs.getString("status")), Scenario.valueOf(rs.getString("scenario")),
                rs.getString("provider_charge_id"), instant(rs, "created_at"), instant(rs, "expires_at"),
                instant(rs, "settled_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
