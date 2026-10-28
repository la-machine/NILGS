// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.parcel.domain.GeometryCheck;
import org.snigf.mvp.parcel.domain.Parcel;
import org.snigf.mvp.parcel.domain.ParcelReference;
import org.snigf.mvp.parcel.domain.ParcelRepository;
import org.snigf.mvp.parcel.domain.ParcelVersion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code parcel} and {@code parcel_version} (V2), as {@code snigf_app}. Every stored geometry
 * comes out of {@code normalize_parcel_geometry} inside the INSERT itself, so what is stored
 * is exactly what PostGIS validated, never a value assembled by the application. GeoJSON is
 * written with 7 decimals (about 1 cm, the stored precision); the display geometry is
 * transformed by PostGIS into the UTM zone of the parcel's centroid (ADR-029).
 */
@Repository
public class JdbcParcelRepository implements ParcelRepository {

    /** The UTM zone of a geometry's centroid: the same rule as {@code ParcelRules.displaySrid}. */
    private static final String DISPLAY_SRID = "(CASE WHEN ST_X(ST_Centroid(v.geom)) < 12 THEN 32632 ELSE 32633 END)";

    private static final String VERSION_COLUMNS = """
            v.version_no, ST_AsGeoJSON(v.geom, 7) AS geometry, %1$s AS display_srid,
            ST_AsGeoJSON(ST_Transform(v.geom, %1$s), 2) AS display_geometry, v.area_m2, v.source_srid, v.locality,
            v.external_reference, v.verification_status, v.change_reason, v.created_by, v.created_at"""
            .formatted(DISPLAY_SRID);

    private final JdbcClient jdbc;

    public JdbcParcelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GeometryCheck check(String geoJson, int srid) {
        return jdbc.sql("SELECT (r).ok, (r).error_code, (r).error_detail, (r).area_m2"
                        + " FROM (SELECT normalize_parcel_geometry(:json, :srid) AS r) s")
                .param("json", geoJson).param("srid", srid)
                .query((rs, n) -> rs.getBoolean(1) ? GeometryCheck.accepted(rs.getBigDecimal(4))
                        : GeometryCheck.refused(rs.getString(2), rs.getString(3)))
                .single();
    }

    @Override
    public Optional<UUID> officeId(String officeCode) {
        return jdbc.sql("SELECT id FROM office WHERE code = :code").param("code", officeCode).query(UUID.class).optional();
    }

    @Override
    public long nextSequence(String officeCode, int year) {
        return jdbc.sql("SELECT next_reference_number('PCL', :office, :year)")
                .param("office", officeCode).param("year", year).query(Long.class).single();
    }

    @Override
    public void create(UUID parcelId, ParcelReference reference, UUID officeId, NewVersion first) {
        jdbc.sql("""
                        INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
                        VALUES (:id, :reference, :office, 1, :by)""")
                .param("id", parcelId).param("reference", reference.value()).param("office", officeId)
                .param("by", first.createdBy())
                .update();
        insertVersion(parcelId, first);
    }

    @Override
    public void correct(UUID parcelId, int expectedCurrent, NewVersion next) {
        try {
            insertVersion(parcelId, next);
        } catch (DuplicateKeyException e) {
            throw new StaleVersion();   // another correction took this version number
        }
        int moved = jdbc.sql("""
                        UPDATE parcel SET current_version_no = :next, version = version + 1
                         WHERE id = :id AND current_version_no = :expected""")
                .param("next", next.versionNo()).param("id", parcelId).param("expected", expectedCurrent)
                .update();
        if (moved != 1) {
            throw new StaleVersion();
        }
    }

    private void insertVersion(UUID parcelId, NewVersion v) {
        int inserted = jdbc.sql("""
                        INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid,
                                                    source_geojson_sha256, locality, external_reference, change_reason,
                                                    created_by)
                        SELECT :id, :parcel, :no, (n.r).geom, (n.r).area_m2, :srid, :sha, :locality, :external, :reason, :by
                          FROM (SELECT normalize_parcel_geometry(:json, :srid) AS r) n
                         WHERE (n.r).ok""")
                .param("id", v.versionId()).param("parcel", parcelId).param("no", v.versionNo())
                .param("srid", v.srid()).param("sha", v.sourceSha256()).param("locality", v.locality())
                .param("external", v.externalReference()).param("reason", v.changeReason()).param("by", v.createdBy())
                .param("json", v.geoJson())
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("the geometry was refused by PostGIS at insert time");
        }
    }

    @Override
    public Optional<Parcel> find(ParcelReference reference) {
        return jdbc.sql("""
                        SELECT p.id, p.reference, o.code, p.status, p.current_version_no, p.created_by, p.created_at
                          FROM parcel p JOIN office o ON o.id = p.office_id WHERE p.reference = :reference""")
                .param("reference", reference.value())
                .query((rs, n) -> new Parcel(rs.getObject(1, UUID.class), new ParcelReference(rs.getString(2)),
                        rs.getString(3), Parcel.Status.valueOf(rs.getString(4)), rs.getInt(5),
                        rs.getObject(6, UUID.class), instant(rs, 7)))
                .optional();
    }

    @Override
    public Optional<String> officeOf(ParcelReference reference) {
        return jdbc.sql("SELECT o.code FROM parcel p JOIN office o ON o.id = p.office_id WHERE p.reference = :reference")
                .param("reference", reference.value()).query(String.class).optional();
    }

    @Override
    public Optional<ParcelVersion> version(UUID parcelId, int versionNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM parcel_version v WHERE v.parcel_id = :parcel AND v.version_no = :no")
                .param("parcel", parcelId).param("no", versionNo)
                .query((rs, n) -> version(rs)).optional();
    }

    @Override
    public List<ParcelVersion> versions(UUID parcelId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM parcel_version v WHERE v.parcel_id = :parcel ORDER BY v.version_no")
                .param("parcel", parcelId)
                .query((rs, n) -> version(rs)).list();
    }

    @Override
    public List<ParcelSummary> search(String officeCode, String text, int limit, int offset) {
        return jdbc.sql("""
                        SELECT p.reference, o.code, p.status, p.current_version_no, v.locality, v.area_m2, v.created_at
                          FROM parcel p
                          JOIN office o ON o.id = p.office_id
                          JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                         WHERE o.code = :office
                           AND (:text = '' OR position(lower(:text) IN lower(p.reference)) > 0
                                OR position(lower(:text) IN lower(v.locality)) > 0)
                         ORDER BY p.created_at DESC, p.id
                         LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("text", text).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ParcelSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getBigDecimal(6), instant(rs, 7)))
                .list();
    }

    private static ParcelVersion version(ResultSet rs) throws SQLException {
        return new ParcelVersion(rs.getInt("version_no"), rs.getString("geometry"),
                new ParcelVersion.Display(rs.getInt("display_srid"), rs.getString("display_geometry")),
                rs.getBigDecimal("area_m2"), rs.getInt("source_srid"), rs.getString("locality"),
                rs.getString("external_reference"), rs.getString("verification_status"), rs.getString("change_reason"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
