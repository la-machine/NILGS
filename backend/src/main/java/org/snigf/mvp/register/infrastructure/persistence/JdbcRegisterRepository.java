// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.register.domain.Register;
import org.snigf.mvp.register.domain.RegisterRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads under row-level security (V21: {@code register.read} at the conservation), writes only
 * through the database's functions. Derived state comes from {@code title_version}, never computed here.
 */
@Repository
class JdbcRegisterRepository implements RegisterRepository {

    private static final String INSCRIPTION_COLUMNS = """
            SELECT i.*, o.code AS office_code, coalesce(i.title_id, i.new_title_id) AS effective_title, t.number AS title_number,
                   pu.display_name AS proposed_name, cu.display_name AS confirmed_name, ru.display_name AS registered_name,
                   xu.display_name AS closed_name,
                   (SELECT c.number FROM inscription c WHERE c.kind = 'CANCELLATION' AND c.status = 'REGISTERED' AND c.target_inscription_id = i.id) AS cancelled_by,
                   (SELECT max(c.number) FROM inscription c WHERE c.kind = 'CORRECTION' AND c.status = 'REGISTERED' AND c.target_inscription_id = i.id) AS corrected_by
              FROM inscription i
              JOIN office o ON o.id = i.office_id
              JOIN app_user pu ON pu.id = i.proposed_by
              LEFT JOIN land_title t ON t.id = i.title_id
              LEFT JOIN app_user cu ON cu.id = i.confirmed_by
              LEFT JOIN app_user ru ON ru.id = i.registered_by
              LEFT JOIN app_user xu ON xu.id = i.closed_by
            """;

    private static final String TRANSACTION_COLUMNS = """
            SELECT tx.*, t.number AS title_number, o.code AS office_code, dp.full_name AS depositor_name, sp.full_name AS submitted_name,
                   u.display_name AS received_name
              FROM pending_transaction tx
              JOIN land_title t ON t.id = tx.title_id
              JOIN office o ON o.id = tx.office_id
              JOIN register_party dp ON dp.id = tx.depositor_party_id
              LEFT JOIN register_party sp ON sp.id = tx.submitted_by_party_id
              JOIN app_user u ON u.id = tx.received_by
            """;

    private static final String CONFLICT_COLUMNS = """
            SELECT c.*, t.number AS title_number, o.code AS office_code, hp.full_name AS holding_depositor, h.status AS holding_status,
                   ap.full_name AS arriving_depositor, a.status AS arriving_status, h.received_by AS holding_received_by,
                   a.received_by AS arriving_received_by, u.display_name AS resolved_name
              FROM transaction_conflict c
              JOIN land_title t ON t.id = c.title_id
              JOIN office o ON o.id = c.office_id
              JOIN pending_transaction h ON h.id = c.holding_id
              JOIN register_party hp ON hp.id = h.depositor_party_id
              JOIN pending_transaction a ON a.id = c.arriving_id
              JOIN register_party ap ON ap.id = a.depositor_party_id
              LEFT JOIN app_user u ON u.id = c.resolved_by
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcRegisterRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<UUID> officeId(String officeCode) {
        return jdbc.sql("SELECT id FROM office WHERE code = :code").param("code", officeCode).query(UUID.class).optional();
    }

    @Override
    public List<Register.TitleSummary> titles(String officeCode, int limit, int offset) {
        return jdbc.sql("""
                        SELECT t.id, t.number, o.code, v.version_no, v.status,
                               (SELECT string_agg(p.full_name, ', ' ORDER BY p.full_name)
                                  FROM jsonb_array_elements(v.holders) h JOIN register_party p ON p.id::text = h->>'party') AS holder_names,
                               EXISTS (SELECT 1 FROM pending_transaction x WHERE x.title_id = t.id AND x.status = 'HOLDING') AS locked,
                               EXISTS (SELECT 1 FROM transaction_conflict x WHERE x.title_id = t.id AND x.status = 'OPEN') AS in_conflict
                          FROM land_title t
                          JOIN office o ON o.id = t.office_id
                          JOIN title_version v ON v.title_id = t.id
                               AND v.version_no = (SELECT max(m.version_no) FROM title_version m WHERE m.title_id = t.id)
                         WHERE o.code = :office ORDER BY t.number LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Register.TitleSummary(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("code"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("holder_names"), rs.getBoolean("locked"),
                        rs.getBoolean("in_conflict")))
                .list();
    }

    @Override
    public Optional<Register.Title> title(UUID id) {
        return jdbc.sql("""
                        SELECT t.id, t.number, o.code, t.origin, t.created_at, v.version_no, v.status, v.area_m2, v.parcel_version_id,
                               v.holders::text AS holders, v.charges::text AS charges, v.restrictions::text AS restrictions,
                               (SELECT p.reference FROM parcel_version pv JOIN parcel p ON p.id = pv.parcel_id WHERE pv.id = v.parcel_version_id) AS parcel_reference
                          FROM land_title t
                          JOIN office o ON o.id = t.office_id
                          JOIN title_version v ON v.title_id = t.id
                               AND v.version_no = (SELECT max(m.version_no) FROM title_version m WHERE m.title_id = t.id)
                         WHERE t.id = :id""")
                .param("id", id)
                .query((rs, n) -> {
                    Map<UUID, String> names = partyNames(rs.getString("holders"), rs.getString("charges"), rs.getString("restrictions"));
                    return new Register.Title(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("code"), rs.getString("origin"),
                            instant(rs, "created_at"), rs.getInt("version_no"), rs.getString("status"), rs.getBigDecimal("area_m2"),
                            rs.getObject("parcel_version_id", UUID.class), rs.getString("parcel_reference"),
                            holders(rs.getString("holders"), names), charges(rs.getString("charges"), names), charges(rs.getString("restrictions"), names));
                })
                .optional();
    }

    @Override
    public List<Register.Inscription> inscriptions(UUID titleId) {
        return loaded(jdbc.sql(INSCRIPTION_COLUMNS + " WHERE coalesce(i.title_id, i.new_title_id) = :title ORDER BY i.number NULLS LAST, i.proposed_at")
                .param("title", titleId).query((rs, n) -> inscription(rs, n)).list());
    }

    @Override
    public Optional<Register.Inscription> inscription(UUID id) {
        return jdbc.sql(INSCRIPTION_COLUMNS + " WHERE i.id = :id").param("id", id).query((rs, n) -> inscription(rs, n)).optional()
                .map(i -> loaded(List.of(i)).getFirst());
    }

    @Override
    public List<Register.Inscription> proposals(String officeCode, int limit, int offset) {
        return loaded(jdbc.sql(INSCRIPTION_COLUMNS + """
                         WHERE o.code = :office AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
                         ORDER BY i.proposed_at, i.id LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("limit", limit).param("offset", offset).query((rs, n) -> inscription(rs, n)).list());
    }

    private List<Register.Inscription> loaded(List<Register.Inscription> rows) {
        List<Register.Inscription> out = new ArrayList<>(rows.size());
        for (Register.Inscription i : rows) {
            List<Register.Holder> holders = jdbc.sql("""
                            SELECT h.party_id, p.full_name, h.interest, h.share_num, h.share_den
                              FROM inscription_holder h JOIN register_party p ON p.id = h.party_id
                             WHERE h.inscription_id = :id ORDER BY h.party_id, h.interest""")
                    .param("id", i.id())
                    .query((rs, n) -> new Register.Holder(rs.getObject("party_id", UUID.class), rs.getString("full_name"), rs.getString("interest"),
                            rs.getInt("share_num"), rs.getInt("share_den")))
                    .list();
            Register.Charge charge = jdbc.sql("""
                            SELECT c.charge_kind, c.beneficiary_id, p.full_name, c.amount, c.blocks_transfer, c.ends_on, c.note
                              FROM inscription_charge c LEFT JOIN register_party p ON p.id = c.beneficiary_id
                             WHERE c.inscription_id = :id""")
                    .param("id", i.id())
                    .query((rs, n) -> new Register.Charge(null, null, rs.getString("charge_kind"), rs.getObject("beneficiary_id", UUID.class),
                            rs.getString("full_name"), rs.getBigDecimal("amount"), rs.getBoolean("blocks_transfer"),
                            rs.getObject("ends_on", LocalDate.class), rs.getString("note")))
                    .optional().orElse(null);
            out.add(new Register.Inscription(i.id(), i.titleId(), i.officeId(), i.officeCode(), i.number(), i.kind(), i.status(), i.expectedVersion(),
                    i.authorityType(), i.authorityReference(), i.reason(), i.evidenceReferences(), i.evidenceVersions(), i.completesTransactionId(),
                    i.targetInscriptionId(), i.areaM2(), i.parcelVersionId(), i.proposedBy(), i.proposedByName(), i.proposedAt(), i.confirmedByName(),
                    i.registeredBy(), i.registeredByName(), i.registeredAt(), i.closedByName(), i.closedReason(), i.hash(), holders, charge,
                    i.cancelledByNumber(), i.correctedByNumber(), i.titleNumber()));
        }
        return out;
    }

    private Register.Inscription inscription(ResultSet rs, int n) throws SQLException {
        byte[] hash = rs.getBytes("hash");
        return new Register.Inscription(rs.getObject("id", UUID.class), rs.getObject("effective_title", UUID.class), rs.getObject("office_id", UUID.class),
                rs.getString("office_code"), (Integer) rs.getObject("number"), rs.getString("kind"), rs.getString("status"), rs.getInt("expected_version"),
                rs.getString("authority_type"), rs.getString("authority_reference"), rs.getString("reason"), texts(rs.getArray("evidence_references")),
                uuids(rs.getArray("evidence_versions")), rs.getObject("completes_transaction_id", UUID.class),
                rs.getObject("target_inscription_id", UUID.class), rs.getBigDecimal("area_m2"), rs.getObject("parcel_version_id", UUID.class),
                rs.getObject("proposed_by", UUID.class), rs.getString("proposed_name"), instant(rs, "proposed_at"), rs.getString("confirmed_name"),
                rs.getObject("registered_by", UUID.class), rs.getString("registered_name"), instant(rs, "registered_at"), rs.getString("closed_name"),
                rs.getString("closed_reason"), hash == null ? null : HexFormat.of().formatHex(hash), List.of(), null,
                (Integer) rs.getObject("cancelled_by"), (Integer) rs.getObject("corrected_by"), rs.getString("title_number"));
    }

    @Override
    public List<Register.Party> parties(String officeCode, int limit, int offset) {
        return jdbc.sql("""
                        SELECT p.* FROM register_party p JOIN office o ON o.id = p.office_id
                         WHERE o.code = :office ORDER BY p.full_name, p.id LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Register.Party(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("full_name"),
                        rs.getString("identity_kind"), rs.getString("identity_number"), rs.getString("chamber"), rs.getString("registration_number"),
                        rs.getString("jurisdiction"), rs.getObject("account_id", UUID.class)))
                .list();
    }

    @Override
    public Optional<Register.ParcelVersionRef> parcelVersion(String officeCode, String parcelReference) {
        return jdbc.sql("""
                        SELECT v.id, v.version_no, v.area_m2
                          FROM parcel p
                          JOIN office o ON o.id = p.office_id
                          JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                         WHERE o.code = :office AND p.reference = :reference""")
                .param("office", officeCode).param("reference", parcelReference)
                .query((rs, n) -> new Register.ParcelVersionRef(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getBigDecimal("area_m2")))
                .optional();
    }

    @Override
    public UUID createParty(UUID officeId, Register.NewParty party) {
        return jdbc.sql("""
                        SELECT register_party_create(:office, :kind, :name, :identityKind, :identityNumber, :chamber, :registration, :jurisdiction, :account)""")
                .param("office", officeId).param("kind", party.kind()).param("name", party.fullName()).param("identityKind", party.identityKind())
                .param("identityNumber", party.identityNumber()).param("chamber", party.chamber()).param("registration", party.registrationNumber())
                .param("jurisdiction", party.jurisdiction()).param("account", party.accountId())
                .query(UUID.class).single();
    }

    @Override
    public UUID propose(UUID officeId, Register.NewInscription p) {
        return jdbc.sql("""
                        SELECT register_propose(:office, :title, :kind, :expected, :authorityType, :authorityReference, :reason,
                                                CAST(:evidence AS text[]), CAST(:versions AS uuid[]), :transaction, :target,
                                                CAST(:area AS numeric), :parcelVersion, CAST(:holders AS jsonb), CAST(:charge AS jsonb))""")
                .param("office", officeId).param("title", p.titleId()).param("kind", p.kind()).param("expected", p.expectedVersion())
                .param("authorityType", p.authorityType()).param("authorityReference", p.authorityReference()).param("reason", p.reason())
                .param("evidence", p.evidenceReferences() == null ? new String[0] : p.evidenceReferences().toArray(String[]::new))
                .param("versions", p.evidenceVersions() == null ? new UUID[0] : p.evidenceVersions().toArray(UUID[]::new))
                .param("transaction", p.completesTransactionId()).param("target", p.targetInscriptionId())
                .param("area", p.areaM2()).param("parcelVersion", p.parcelVersionId())
                .param("holders", p.holders() == null ? null : json.writeValueAsString(p.holders().stream().map(JdbcRegisterRepository::holderJson).toList()))
                .param("charge", p.charge() == null ? null : json.writeValueAsString(chargeJson(p.charge())))
                .query(UUID.class).single();
    }

    private static Map<String, Object> holderJson(Register.NewHolder h) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("party", h.party());
        out.put("interest", h.interest());
        out.put("shareNum", h.shareNum());
        out.put("shareDen", h.shareDen());
        return out;
    }

    private static Map<String, Object> chargeJson(Register.NewCharge c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", c.kind());
        out.put("beneficiary", c.beneficiary());
        out.put("amount", c.amount());
        out.put("blocksTransfer", c.blocksTransfer());
        out.put("endsOn", c.endsOn() == null ? null : c.endsOn().toString());
        out.put("note", c.note());
        return out;
    }

    @Override
    public byte[] canonical(UUID inscriptionId) {
        return jdbc.sql("SELECT register_canonical(:id)").param("id", inscriptionId).query(byte[].class).single();
    }

    @Override
    public void register(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence) {
        signing("register_register", inscriptionId, canonical, evidence);
    }

    @Override
    public void requestConfirmation(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence) {
        signing("register_request_confirmation", inscriptionId, canonical, evidence);
    }

    @Override
    public void confirm(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence) {
        signing("register_confirm", inscriptionId, canonical, evidence);
    }

    private void signing(String function, UUID inscriptionId, byte[] canonical, SignatureEvidence e) {
        Base64.Encoder b64 = Base64.getEncoder();
        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("credentialId", e.credentialId());
        signature.put("publicKey", b64.encodeToString(e.publicKeyCose()));
        signature.put("nonce", b64.encodeToString(e.nonce()));
        signature.put("authenticatorData", b64.encodeToString(e.authenticatorData()));
        signature.put("clientData", b64.encodeToString(e.clientData()));
        signature.put("officerSignature", b64.encodeToString(e.officerSignature()));
        signature.put("serverSignature", e.serverSignature());
        jdbc.sql("SELECT " + function + "(:id, :canonical, CAST(:signature AS jsonb))")
                .param("id", inscriptionId).param("canonical", canonical).param("signature", json.writeValueAsString(signature))
                .query((rs, n) -> 1).single();
    }

    @Override
    public void closeProposal(UUID inscriptionId, String outcome, String reason) {
        jdbc.sql("SELECT register_close_proposal(:id, :outcome, :reason)")
                .param("id", inscriptionId).param("outcome", outcome).param("reason", reason)
                .query((rs, n) -> 1).single();
    }

    @Override
    public UUID deposit(Register.NewDeposit d, Duration reviewAfter) {
        return jdbc.sql("""
                        SELECT transaction_deposit(:title, :kind, :depositor, :submitted, CAST(:evidence AS text[]), CAST(:after AS interval))""")
                .param("title", d.titleId()).param("kind", d.kind()).param("depositor", d.depositorPartyId()).param("submitted", d.submittedByPartyId())
                .param("evidence", d.evidenceReferences() == null ? new String[0] : d.evidenceReferences().toArray(String[]::new))
                .param("after", reviewAfter.toSeconds() + " seconds")
                .query(UUID.class).single();
    }

    @Override
    public void closeTransaction(UUID transactionId, String outcome, String reason) {
        jdbc.sql("SELECT transaction_close(:id, :outcome, :reason)")
                .param("id", transactionId).param("outcome", outcome).param("reason", reason)
                .query((rs, n) -> 1).single();
    }

    @Override
    public Optional<Register.Transaction> transaction(UUID id) {
        return jdbc.sql(TRANSACTION_COLUMNS + " WHERE tx.id = :id").param("id", id).query((rs, n) -> transaction(rs, n)).optional();
    }

    @Override
    public List<Register.Transaction> transactions(String officeCode, boolean overdueOnly, int limit, int offset) {
        return jdbc.sql(TRANSACTION_COLUMNS + """
                         WHERE o.code = :office AND tx.status IN ('HOLDING', 'IN_CONFLICT')
                           AND (NOT :overdue OR (tx.status = 'HOLDING' AND tx.review_due_at <= clock_timestamp()))
                         ORDER BY tx.review_due_at, tx.id LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("overdue", overdueOnly).param("limit", limit).param("offset", offset)
                .query((rs, n) -> transaction(rs, n)).list();
    }

    @Override
    public List<Register.Transaction> titleTransactions(UUID titleId) {
        return jdbc.sql(TRANSACTION_COLUMNS + " WHERE tx.title_id = :title ORDER BY tx.deposited_at, tx.id")
                .param("title", titleId).query((rs, n) -> transaction(rs, n)).list();
    }

    private Register.Transaction transaction(ResultSet rs, int n) throws SQLException {
        Instant due = instant(rs, "review_due_at");
        return new Register.Transaction(rs.getObject("id", UUID.class), rs.getObject("title_id", UUID.class), rs.getString("title_number"),
                rs.getString("office_code"), rs.getString("kind"), rs.getString("channel"), rs.getObject("depositor_party_id", UUID.class),
                rs.getString("depositor_name"), rs.getString("submitted_name"), rs.getObject("received_by", UUID.class), rs.getString("received_name"),
                texts(rs.getArray("evidence_references")), rs.getString("status"), instant(rs, "deposited_at"), due,
                "HOLDING".equals(rs.getString("status")) && !due.isAfter(Instant.now()), rs.getString("closed_reason"));
    }

    @Override
    public Optional<UUID> conflictOf(UUID arrivingTransactionId) {
        return jdbc.sql("SELECT id FROM transaction_conflict WHERE arriving_id = :id").param("id", arrivingTransactionId).query(UUID.class).optional();
    }

    @Override
    public void resolveConflict(UUID conflictId, String outcome, String reason, List<String> evidence) {
        jdbc.sql("SELECT conflict_resolve(:id, :outcome, :reason, CAST(:evidence AS text[]))")
                .param("id", conflictId).param("outcome", outcome).param("reason", reason)
                .param("evidence", evidence == null ? new String[0] : evidence.toArray(String[]::new))
                .query((rs, n) -> 1).single();
    }

    @Override
    public Optional<Register.Conflict> conflict(UUID id) {
        return jdbc.sql(CONFLICT_COLUMNS + " WHERE c.id = :id").param("id", id).query((rs, n) -> conflict(rs, n)).optional();
    }

    @Override
    public List<Register.Conflict> conflicts(String officeCode, boolean openOnly, int limit, int offset) {
        return jdbc.sql(CONFLICT_COLUMNS + """
                         WHERE o.code = :office AND (NOT :open OR c.status = 'OPEN')
                         ORDER BY c.opened_at, c.id LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("open", openOnly).param("limit", limit).param("offset", offset)
                .query((rs, n) -> conflict(rs, n)).list();
    }

    @Override
    public List<Register.Conflict> titleConflicts(UUID titleId) {
        return jdbc.sql(CONFLICT_COLUMNS + " WHERE c.title_id = :title ORDER BY c.opened_at, c.id").param("title", titleId).query((rs, n) -> conflict(rs, n)).list();
    }

    private Register.Conflict conflict(ResultSet rs, int n) throws SQLException {
        return new Register.Conflict(rs.getObject("id", UUID.class), rs.getObject("title_id", UUID.class), rs.getString("title_number"),
                rs.getString("office_code"), rs.getObject("holding_id", UUID.class), rs.getString("holding_depositor"), rs.getString("holding_status"),
                rs.getObject("arriving_id", UUID.class), rs.getString("arriving_depositor"), rs.getString("arriving_status"),
                rs.getObject("holding_received_by", UUID.class), rs.getObject("arriving_received_by", UUID.class), rs.getString("status"),
                instant(rs, "opened_at"), rs.getString("outcome"), rs.getString("resolved_name"), instant(rs, "resolved_at"), rs.getString("resolution_reason"));
    }

    private static final String DOCUMENT_COLUMNS = """
            SELECT d.*, u.display_name, (SELECT r.id FROM deposit_document r WHERE r.replaces_id = d.id) AS replaced_by
              FROM deposit_document d JOIN app_user u ON u.id = d.uploaded_by
            """;

    @Override
    public UUID addDepositDocument(UUID transactionId, String category, String storageKey, String sha256, long sizeBytes, String mediaType, String name,
                                   UUID replaces, String reason) {
        return jdbc.sql("""
                        SELECT deposit_document_add(:transaction, :category, :key, :sha256, CAST(:size AS bigint), :type, :name, :replaces, :reason)""")
                .param("transaction", transactionId).param("category", category).param("key", storageKey).param("sha256", sha256)
                .param("size", sizeBytes).param("type", mediaType).param("name", name).param("replaces", replaces).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public List<Register.DepositDocument> depositDocuments(UUID transactionId) {
        return jdbc.sql(DOCUMENT_COLUMNS + " WHERE d.transaction_id = :transaction ORDER BY d.uploaded_at, d.id")
                .param("transaction", transactionId).query((rs, n) -> depositDocument(rs)).list();
    }

    @Override
    public Optional<Register.DepositDocument> depositDocument(UUID transactionId, UUID documentId) {
        return jdbc.sql(DOCUMENT_COLUMNS + " WHERE d.transaction_id = :transaction AND d.id = :id")
                .param("transaction", transactionId).param("id", documentId).query((rs, n) -> depositDocument(rs)).optional();
    }

    private static Register.DepositDocument depositDocument(ResultSet rs) throws SQLException {
        return new Register.DepositDocument(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class), rs.getString("category"),
                rs.getString("storage_key"), rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("detected_type"), rs.getString("original_name"),
                rs.getObject("uploaded_by", UUID.class), rs.getString("display_name"), instant(rs, "uploaded_at"), rs.getObject("replaces_id", UUID.class),
                rs.getString("replace_reason"), rs.getObject("replaced_by", UUID.class));
    }

    @Override
    public List<Register.BoundDocument> boundDocuments(UUID transactionId) {
        return jdbc.sql("SELECT id, sha256 FROM deposit_document WHERE transaction_id = :transaction ORDER BY id")
                .param("transaction", transactionId)
                .query((rs, n) -> new Register.BoundDocument(rs.getObject("id", UUID.class), rs.getString("sha256"))).list();
    }

    @Override
    public List<Register.StoredSignature> signatures(UUID inscriptionId) {
        // In the order the chain hash took them.
        return jdbc.sql("""
                        SELECT s.*, u.display_name FROM inscription_signature s JOIN app_user u ON u.id = s.signer_id
                         WHERE s.inscription_id = :id ORDER BY s.act""")
                .param("id", inscriptionId)
                .query((rs, n) -> new Register.StoredSignature(rs.getObject("inscription_id", UUID.class), rs.getString("act"),
                        rs.getObject("signer_id", UUID.class), rs.getString("display_name"), rs.getBytes("canonical"), rs.getString("credential_id"),
                        rs.getBytes("public_key_cose"), rs.getBytes("nonce"), rs.getBytes("authenticator_data"), rs.getBytes("client_data"),
                        rs.getBytes("officer_signature"), rs.getString("server_signature"), instant(rs, "signed_at")))
                .list();
    }

    @Override
    public Optional<byte[]> hashOf(UUID inscriptionId) {
        return jdbc.sql("SELECT hash FROM inscription WHERE id = :id").param("id", inscriptionId).query(byte[].class).optional();
    }

    @Override
    public List<Boolean> versionChecks(UUID titleId) {
        return jdbc.sql("SELECT matches FROM title_versions_check(:id) ORDER BY version_no").param("id", titleId).query(Boolean.class).list();
    }

    @Override
    public List<Register.OverdueLock> recordOverdueLocks() {
        return jdbc.sql("SELECT transaction_id, title_id, due_at FROM register_record_overdue_locks()")
                .query((rs, n) -> new Register.OverdueLock(rs.getObject("transaction_id", UUID.class), rs.getObject("title_id", UUID.class),
                        instant(rs, "due_at")))
                .list();
    }

    @Override
    public String officeCodeOf(String recordType, UUID id) {
        return jdbc.sql("SELECT register_office_code(:type, :id)").param("type", recordType).param("id", id).query(String.class).optional().orElse(null);
    }

    // --- Derived state, as the database wrote it ------------------------------------------------------

    private Map<UUID, String> partyNames(String... documents) {
        List<UUID> ids = new ArrayList<>();
        for (String document : documents) {
            for (JsonNode node : json.readTree(document)) {
                for (String field : List.of("party", "beneficiary")) {
                    String value = node.path(field).asString("");
                    if (!value.isEmpty()) {
                        ids.add(UUID.fromString(value));
                    }
                }
            }
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("SELECT id, full_name FROM register_party WHERE id = ANY(CAST(:ids AS uuid[]))")
                    .param("ids", ids.toArray(UUID[]::new))
                    .query((rs, n) -> names.put(rs.getObject("id", UUID.class), rs.getString("full_name")))
                    .list();
        }
        return names;
    }

    private List<Register.Holder> holders(String document, Map<UUID, String> names) {
        List<Register.Holder> out = new ArrayList<>();
        for (JsonNode h : json.readTree(document)) {
            UUID party = UUID.fromString(h.path("party").asString(""));
            out.add(new Register.Holder(party, names.get(party), h.path("interest").asString(""), h.path("shareNum").asInt(), h.path("shareDen").asInt()));
        }
        return out;
    }

    private List<Register.Charge> charges(String document, Map<UUID, String> names) {
        List<Register.Charge> out = new ArrayList<>();
        for (JsonNode c : json.readTree(document)) {
            String beneficiary = c.path("beneficiary").asString("");
            UUID party = beneficiary.isEmpty() ? null : UUID.fromString(beneficiary);
            String amount = c.path("amount").asString("");
            String ends = c.path("endsOn").asString("");
            String note = c.path("note").asString("");
            out.add(new Register.Charge(UUID.fromString(c.path("inscription").asString("")), c.path("number").asInt(), c.path("kind").asString(""),
                    party, party == null ? null : names.get(party), amount.isEmpty() ? null : new BigDecimal(amount),
                    c.path("blocksTransfer").asBoolean(), ends.isEmpty() ? null : LocalDate.parse(ends), note.isEmpty() ? null : note));
        }
        return out;
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
