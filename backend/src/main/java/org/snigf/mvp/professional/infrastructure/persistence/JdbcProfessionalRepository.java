// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.professional.domain.Professional;
import org.snigf.mvp.professional.domain.ProfessionalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads under row-level security (V24), writes only through the database's functions. */
@Repository
class JdbcProfessionalRepository implements ProfessionalRepository {

    private static final String PROFILE = """
            SELECT p.*, u.email, u.display_name,
                   coalesce((SELECT array_agg(o.code ORDER BY o.code) FROM professional_office x JOIN office o ON o.id = x.office_id
                              WHERE x.user_id = p.user_id AND x.removed_at IS NULL), '{}') AS offices
              FROM professional_profile p JOIN app_user u ON u.id = p.user_id
            """;

    private static final String REQUEST = """
            SELECT r.*, pu.display_name AS professional_name, ru.display_name AS requested_name, du.display_name AS decided_name,
                   coalesce((SELECT array_agg(o.code ORDER BY o.code) FROM office o WHERE o.id = ANY (r.offices_add)), '{}') AS add_codes,
                   coalesce((SELECT array_agg(o.code ORDER BY o.code) FROM office o WHERE o.id = ANY (r.offices_remove)), '{}') AS remove_codes
              FROM professional_request r
              JOIN app_user pu ON pu.id = r.user_id
              JOIN app_user ru ON ru.id = r.requested_by
              LEFT JOIN app_user du ON du.id = r.decided_by
            """;

    private final JdbcClient jdbc;

    JdbcProfessionalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createProfile(UUID userId, String profession, String chamber, String registrationNumber, String jurisdiction) {
        jdbc.sql("SELECT professional_profile_create(:user, :profession, :chamber, :registration, :jurisdiction)")
                .param("user", userId).param("profession", profession).param("chamber", chamber).param("registration", registrationNumber)
                .param("jurisdiction", jurisdiction)
                .query((rs, n) -> 1).single();
    }

    @Override
    public Optional<Professional.Profile> profile(UUID userId) {
        return jdbc.sql(PROFILE + " WHERE p.user_id = :user").param("user", userId).query((rs, n) -> profile(rs)).optional();
    }

    /**
     * The directory's conditions (D20). Every value is a parameter. The search uses position() on search_fold (V28), so
     * case and accents are ignored and '%' or '_' match only themselves. Read under row-level security, as before.
     */
    private static final String DIRECTORY = """
             WHERE (CAST(:status AS text) IS NULL OR p.status = :status)
               AND (CAST(:profession AS text) IS NULL OR p.profession = :profession)
               AND (CAST(:office AS text) IS NULL
                    OR EXISTS (SELECT 1 FROM professional_office x JOIN office o ON o.id = x.office_id
                                WHERE x.user_id = p.user_id AND x.removed_at IS NULL AND o.code = :office)
                    OR EXISTS (SELECT 1 FROM professional_request r JOIN office o ON o.id = ANY (r.offices_add)
                                WHERE r.user_id = p.user_id AND r.status = 'PENDING' AND r.requested_at > now() - CAST(:ttl AS interval) AND o.code = :office))
               AND (CAST(:pending AS text) IS NULL
                    OR (:pending = 'VERIFICATION' AND p.status = 'REQUESTED')
                    OR (:pending IN ('ACTIVATION', 'CHANGE')
                        AND EXISTS (SELECT 1 FROM professional_request r
                                     WHERE r.user_id = p.user_id AND r.status = 'PENDING' AND r.requested_at > now() - CAST(:ttl AS interval)
                                       AND (r.kind = 'ACTIVATION') = (:pending = 'ACTIVATION'))))
               AND (CAST(:text AS text) IS NULL
                    OR position(search_fold(:text) IN search_fold(u.email)) > 0
                    OR position(search_fold(:text) IN search_fold(u.display_name)) > 0
                    OR position(search_fold(:text) IN search_fold(p.chamber)) > 0
                    OR position(search_fold(:text) IN search_fold(p.registration_number)) > 0)
            """;

    @Override
    public List<Professional.Profile> directory(Professional.DirectoryFilter filter, Duration requestTtl, int limit, int offset) {
        return directoryParams(jdbc.sql(PROFILE + DIRECTORY + " ORDER BY p.requested_at DESC, p.user_id DESC LIMIT :limit OFFSET :offset"), filter,
                requestTtl)
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> profile(rs)).list();
    }

    @Override
    public long directoryCount(Professional.DirectoryFilter filter, Duration requestTtl) {
        return directoryParams(jdbc.sql("SELECT count(*) FROM professional_profile p JOIN app_user u ON u.id = p.user_id" + DIRECTORY), filter, requestTtl)
                .query(Long.class).single();
    }

    /**
     * The dashboard's counts (D21), exact, for the bound actor. A count the actor may not act on is NULL, never zero; the
     * actor's own requests and verifications are not waiting for them (four eyes). A request past its time to live is no
     * longer pending, as in the directory.
     */
    @Override
    public Professional.Counts counts(Duration requestTtl) {
        return jdbc.sql("""
                        WITH me AS (SELECT current_actor() AS id,
                                           holds_permission(current_actor(), 'admin.professional.verify') AS verifies,
                                           holds_permission(current_actor(), 'admin.professional.approve') AS approves,
                                           holds_permission(current_actor(), 'admin.professional.suspend') AS suspends),
                             waiting AS (SELECT r.* FROM professional_request r
                                          WHERE r.status = 'PENDING' AND r.requested_at > now() - CAST(:ttl AS interval))
                        SELECT CASE WHEN me.verifies THEN (SELECT count(*) FROM professional_profile p WHERE p.status = 'REQUESTED') END AS requests,
                               CASE WHEN me.approves THEN (SELECT count(*) FROM waiting r
                                                             JOIN professional_profile p ON p.user_id = r.user_id AND p.status = 'VERIFIED'
                                                             LEFT JOIN professional_verification v ON v.id = r.verification_id
                                                            WHERE r.kind = 'ACTIVATION' AND r.requested_by <> me.id
                                                              AND v.verified_by IS DISTINCT FROM me.id) END AS activations,
                               CASE WHEN me.approves THEN (SELECT count(*) FROM waiting r
                                                            WHERE r.kind <> 'ACTIVATION' AND r.requested_by <> me.id) END AS changes,
                               CASE WHEN me.verifies OR me.suspends THEN (SELECT count(*) FROM waiting r WHERE r.requested_by = me.id) END AS mine
                          FROM me""")
                .param("ttl", requestTtl.toSeconds() + " seconds")
                .query((rs, n) -> new Professional.Counts(rs.getObject("requests", Long.class), rs.getObject("activations", Long.class),
                        rs.getObject("changes", Long.class), rs.getObject("mine", Long.class)))
                .single();
    }

    private static JdbcClient.StatementSpec directoryParams(JdbcClient.StatementSpec sql, Professional.DirectoryFilter f, Duration requestTtl) {
        return sql.param("status", f.status()).param("profession", f.profession()).param("office", f.office()).param("pending", f.pending())
                .param("text", f.text()).param("ttl", requestTtl.toSeconds() + " seconds");
    }

    private static Professional.Profile profile(ResultSet rs) throws SQLException {
        return new Professional.Profile(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("profession"), rs.getString("chamber"), rs.getString("registration_number"), rs.getString("jurisdiction"),
                rs.getString("status"), instant(rs, "requested_at"), instant(rs, "status_changed_at"), texts(rs.getArray("offices")));
    }

    @Override
    public List<Professional.Request> requests(UUID userId) {
        return jdbc.sql(REQUEST + " WHERE r.user_id = :user ORDER BY r.requested_at DESC, r.id").param("user", userId).query((rs, n) -> request(rs)).list();
    }

    @Override
    public List<Professional.Request> pendingRequests(int limit, int offset) {
        return jdbc.sql(REQUEST + " WHERE r.status = 'PENDING' ORDER BY r.requested_at, r.id LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query((rs, n) -> request(rs)).list();
    }

    @Override
    public Optional<Professional.Request> request(UUID requestId) {
        return jdbc.sql(REQUEST + " WHERE r.id = :id").param("id", requestId).query((rs, n) -> request(rs)).optional();
    }

    private static Professional.Request request(ResultSet rs) throws SQLException {
        return new Professional.Request(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("professional_name"),
                rs.getString("kind"), texts(rs.getArray("add_codes")), texts(rs.getArray("remove_codes")), rs.getString("reason"),
                rs.getObject("requested_by", UUID.class), rs.getString("requested_name"), instant(rs, "requested_at"), rs.getString("status"),
                rs.getString("decided_name"), instant(rs, "decided_at"), rs.getString("decision_reason"));
    }

    @Override
    public Optional<Professional.Verification> verification(UUID userId) {
        return jdbc.sql("""
                        SELECT v.*, u.display_name FROM professional_verification v JOIN app_user u ON u.id = v.verified_by
                         WHERE v.user_id = :user ORDER BY v.verified_at DESC LIMIT 1""")
                .param("user", userId)
                .query((rs, n) -> new Professional.Verification(rs.getObject("id", UUID.class), rs.getObject("verified_by", UUID.class),
                        rs.getString("display_name"), rs.getString("body"), rs.getString("reference"), rs.getObject("checked_on", LocalDate.class),
                        rs.getString("note"), instant(rs, "verified_at"), List.of()))
                .optional()
                .map(v -> new Professional.Verification(v.id(), v.verifiedBy(), v.verifiedByName(), v.body(), v.reference(), v.checkedOn(), v.note(),
                        v.verifiedAt(), jdbc.sql(EVIDENCE + " WHERE d.verification_id = :id ORDER BY d.uploaded_at, d.id").param("id", v.id())
                                .query((rs, n) -> evidence(rs)).list()));
    }

    private static final String EVIDENCE = """
            SELECT d.*, u.display_name FROM professional_verification_document d JOIN app_user u ON u.id = d.uploaded_by
            """;

    @Override
    public Optional<Professional.Evidence> evidence(UUID userId, UUID documentId) {
        return jdbc.sql(EVIDENCE + " JOIN professional_verification v ON v.id = d.verification_id WHERE v.user_id = :user AND d.id = :id")
                .param("user", userId).param("id", documentId).query((rs, n) -> evidence(rs)).optional();
    }

    private static Professional.Evidence evidence(ResultSet rs) throws SQLException {
        return new Professional.Evidence(rs.getObject("id", UUID.class), rs.getString("storage_key"), rs.getString("sha256"), rs.getLong("size_bytes"),
                rs.getString("detected_type"), rs.getString("original_name"), rs.getString("display_name"), instant(rs, "uploaded_at"));
    }

    @Override
    public List<Professional.HistoryEntry> history(UUID userId) {
        return jdbc.sql("""
                        SELECT h.event, u.display_name, h.reason, h.at FROM professional_history h LEFT JOIN app_user u ON u.id = h.actor_id
                         WHERE h.user_id = :user ORDER BY h.at, h.id""")
                .param("user", userId)
                .query((rs, n) -> new Professional.HistoryEntry(rs.getString("event"), rs.getString("display_name"), rs.getString("reason"),
                        instant(rs, "at")))
                .list();
    }

    @Override
    public List<UUID> officeIds(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM office WHERE code = ANY (CAST(:codes AS text[])) ORDER BY code")
                .param("codes", codes.toArray(String[]::new)).query(UUID.class).list();
    }

    @Override
    public UUID verify(UUID userId, String body, String reference, LocalDate checkedOn, String note, List<UUID> offices) {
        return jdbc.sql("SELECT professional_verify(:user, :body, :reference, :checked, :note, CAST(:offices AS uuid[]))")
                .param("user", userId).param("body", body).param("reference", reference).param("checked", checkedOn).param("note", note)
                .param("offices", offices.toArray(UUID[]::new))
                .query(UUID.class).single();
    }

    @Override
    public UUID addEvidence(UUID verificationId, String storageKey, String sha256, long sizeBytes, String mediaType, String name) {
        return jdbc.sql("SELECT professional_evidence_add(:verification, :key, :sha256, CAST(:size AS bigint), :type, :name)")
                .param("verification", verificationId).param("key", storageKey).param("sha256", sha256).param("size", sizeBytes)
                .param("type", mediaType).param("name", name)
                .query(UUID.class).single();
    }

    @Override
    public void suspend(UUID userId, String reason) {
        jdbc.sql("SELECT professional_suspend(:user, :reason)").param("user", userId).param("reason", reason).query((rs, n) -> 1).single();
    }

    @Override
    public UUID requestChange(UUID userId, String kind, List<UUID> add, List<UUID> remove, String reason) {
        return jdbc.sql("SELECT professional_request_change(:user, :kind, CAST(:add AS uuid[]), CAST(:remove AS uuid[]), :reason)")
                .param("user", userId).param("kind", kind).param("add", add.toArray(UUID[]::new)).param("remove", remove.toArray(UUID[]::new))
                .param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public void decide(UUID requestId, boolean approve, String reason) {
        jdbc.sql("SELECT professional_decide(:id, :approve, :reason)").param("id", requestId).param("approve", approve).param("reason", reason)
                .query((rs, n) -> 1).single();
    }

    @Override
    public boolean expire(UUID requestId, Duration ttl) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT professional_request_expire(:id, CAST(:ttl AS interval))")
                .param("id", requestId).param("ttl", ttl.toSeconds() + " seconds").query(Boolean.class).single());
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
