// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.conflict.domain.ConflictAlert;
import org.snigf.mvp.conflict.domain.ConflictAlertRepository;
import org.snigf.mvp.conflict.domain.ConflictPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Alerts as V11 raised them, read with both parcels' references, offices, compared version
 * numbers, areas and authors. The application's account can read alerts, resolve an open one
 * (resolution columns only, checked again by the V12 guard) and adjust the policy row; it
 * cannot insert an alert, change its evidence or delete it.
 */
@Repository
class JdbcConflictAlertRepository implements ConflictAlertRepository {

    private static final String SELECT = """
            SELECT c.id, c.alert_type, c.status,
                   c.parcel_a_id, pa.reference AS reference_a, oa.code AS office_a, c.version_a_id,
                   va.version_no AS version_no_a, pa.current_version_no AS current_a, va.created_by AS author_a,
                   ua.display_name AS author_name_a, va.area_m2 AS area_a, c.share_a,
                   c.parcel_b_id, pb.reference AS reference_b, ob.code AS office_b, c.version_b_id,
                   vb.version_no AS version_no_b, pb.current_version_no AS current_b, vb.created_by AS author_b,
                   ub.display_name AS author_name_b, vb.area_m2 AS area_b, c.share_b,
                   c.intersection_m2, ST_AsGeoJSON(c.intersection_geom, 7, 0) AS intersection,
                   c.normalized_reference, c.raised_by_version_id, c.raised_at,
                   c.resolution, c.resolution_note, c.resolved_by, ur.display_name AS resolved_by_name, c.resolved_at,
                   c.superseded_by_version_id, c.superseded_at, c.version
              FROM conflict_alert c
              JOIN parcel pa ON pa.id = c.parcel_a_id
              JOIN office oa ON oa.id = pa.office_id
              JOIN parcel_version va ON va.id = c.version_a_id
              JOIN app_user ua ON ua.id = va.created_by
              JOIN parcel pb ON pb.id = c.parcel_b_id
              JOIN office ob ON ob.id = pb.office_id
              JOIN parcel_version vb ON vb.id = c.version_b_id
              JOIN app_user ub ON ub.id = vb.created_by
              LEFT JOIN app_user ur ON ur.id = c.resolved_by
            """;

    private final JdbcClient jdbc;

    JdbcConflictAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ConflictAlert> raisedBy(UUID versionId) {
        return jdbc.sql(SELECT + " WHERE c.raised_by_version_id = :version ORDER BY c.id")
                .param("version", versionId).query((rs, n) -> alert(rs)).list();
    }

    @Override
    public List<ConflictAlert> supersededBy(UUID versionId) {
        return jdbc.sql(SELECT + " WHERE c.superseded_by_version_id = :version ORDER BY c.id")
                .param("version", versionId).query((rs, n) -> alert(rs)).list();
    }

    @Override
    public List<ConflictAlert> forParcel(String reference) {
        return jdbc.sql(SELECT + """
                         WHERE pa.reference = :reference OR pb.reference = :reference
                         ORDER BY (c.status = 'OPEN') DESC, c.raised_at DESC, c.id DESC""")
                .param("reference", reference).query((rs, n) -> alert(rs)).list();
    }

    @Override
    public List<ConflictAlert> forOffice(String officeCode, ConflictAlert.Status status, ConflictAlert.Type type,
                                         int limit, int offset) {
        return jdbc.sql(SELECT + """
                         WHERE (oa.code = :office OR ob.code = :office)
                           AND (CAST(:status AS text) IS NULL OR c.status = :status)
                           AND (CAST(:type AS text) IS NULL OR c.alert_type = :type)
                         ORDER BY c.raised_at DESC, c.id DESC
                         LIMIT :limit OFFSET :offset""")
                .param("office", officeCode)
                .param("status", status == null ? null : status.name())
                .param("type", type == null ? null : type.name())
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> alert(rs)).list();
    }

    @Override
    public Optional<ConflictAlert> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query((rs, n) -> alert(rs)).optional();
    }

    @Override
    public Optional<Display> display(UUID id) {
        // One zone for the three shapes, from the pair's centroid (the rule of ParcelRules.displaySrid).
        return jdbc.sql("""
                        WITH pair AS (
                            SELECT va.geom AS ga, vb.geom AS gb, c.intersection_geom AS gi,
                                   CASE WHEN ST_X(ST_Centroid(ST_Collect(va.geom, vb.geom))) < 12 THEN 32632 ELSE 32633 END AS srid
                              FROM conflict_alert c
                              JOIN parcel_version va ON va.id = c.version_a_id
                              JOIN parcel_version vb ON vb.id = c.version_b_id
                             WHERE c.id = :id)
                        SELECT srid, ST_AsGeoJSON(ST_Transform(ga, srid), 2) AS a, ST_AsGeoJSON(ST_Transform(gb, srid), 2) AS b,
                               CASE WHEN gi IS NULL THEN NULL ELSE ST_AsGeoJSON(ST_Transform(gi, srid), 2) END AS i
                          FROM pair""")
                .param("id", id)
                .query((rs, n) -> new Display(rs.getInt("srid"), rs.getString("a"), rs.getString("b"), rs.getString("i")))
                .optional();
    }

    @Override
    public boolean resolve(UUID id, long expectedVersion, ConflictAlert.Resolution resolution, String note, UUID resolver) {
        return jdbc.sql("""
                        UPDATE conflict_alert
                           SET status = 'RESOLVED', resolution = :resolution, resolution_note = :note,
                               resolved_by = :resolver, resolved_at = clock_timestamp(), version = version + 1
                         WHERE id = :id AND status = 'OPEN' AND version = :expected""")
                .param("resolution", resolution.name()).param("note", note).param("resolver", resolver)
                .param("id", id).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public Optional<UUID> parcelId(String reference) {
        return jdbc.sql("SELECT id FROM parcel WHERE reference = :reference").param("reference", reference)
                .query(UUID.class).optional();
    }

    @Override
    public ConflictPolicy policy() {
        return jdbc.sql("SELECT min_overlap_m2, duplicate_share FROM conflict_policy")
                .query((rs, n) -> new ConflictPolicy(rs.getBigDecimal(1), rs.getBigDecimal(2))).single();
    }

    @Override
    public void applyPolicy(ConflictPolicy policy) {
        jdbc.sql("UPDATE conflict_policy SET min_overlap_m2 = :min, duplicate_share = :share, updated_at = clock_timestamp()")
                .param("min", policy.minOverlapM2()).param("share", policy.duplicateShare()).update();
    }

    private static ConflictAlert alert(ResultSet rs) throws SQLException {
        String resolution = rs.getString("resolution");
        return new ConflictAlert(rs.getObject("id", UUID.class),
                ConflictAlert.Type.valueOf(rs.getString("alert_type")), ConflictAlert.Status.valueOf(rs.getString("status")),
                side(rs, "a"), side(rs, "b"),
                rs.getBigDecimal("intersection_m2"), rs.getString("intersection"), rs.getString("normalized_reference"),
                rs.getObject("raised_by_version_id", UUID.class), instant(rs, "raised_at"),
                resolution == null ? null : ConflictAlert.Resolution.valueOf(resolution), rs.getString("resolution_note"),
                rs.getObject("resolved_by", UUID.class), rs.getString("resolved_by_name"), instant(rs, "resolved_at"),
                rs.getObject("superseded_by_version_id", UUID.class), instant(rs, "superseded_at"), rs.getLong("version"));
    }

    private static ConflictAlert.Side side(ResultSet rs, String s) throws SQLException {
        return new ConflictAlert.Side(rs.getObject("parcel_" + s + "_id", UUID.class), rs.getString("reference_" + s),
                rs.getString("office_" + s), rs.getObject("version_" + s + "_id", UUID.class), rs.getInt("version_no_" + s),
                rs.getInt("current_" + s), rs.getObject("author_" + s, UUID.class), rs.getString("author_name_" + s),
                rs.getBigDecimal("area_" + s), rs.getBigDecimal("share_" + s));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
