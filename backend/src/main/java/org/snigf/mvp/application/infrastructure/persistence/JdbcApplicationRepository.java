// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationState;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.application.domain.CompletenessRecord;
import org.snigf.mvp.application.domain.Requirements;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applications under row-level security (V13): every statement sees only the rows of the actor
 * the use case named. The type catalogue is insert-only; the database numbers nothing itself but
 * refuses a version that does not follow the last one.
 */
@Repository
class JdbcApplicationRepository implements ApplicationRepository {

    private static final String TYPE = """
            SELECT id, code, version_no, available, name_fr, name_en, requirements::text AS requirements, effective_from
              FROM application_type""";
    private static final String APPLICATION = """
            SELECT a.id, a.reference, a.applicant_id, a.type_code, a.type_version_id, a.state, a.parcel_reference,
                   o.code AS office_code, a.data::text AS data, a.created_at, a.updated_at, a.submitted_at, a.version
              FROM application a
              LEFT JOIN office o ON o.id = a.office_id
            """;
    private static final String CASE = """
            SELECT a.id, a.reference, a.applicant_id, a.type_code, a.type_version_id, a.state, a.parcel_reference,
                   o.code AS office_code, a.data::text AS data, a.created_at, a.updated_at, a.submitted_at, a.version,
                   u.display_name AS applicant_name
              FROM application a
              JOIN office o ON o.id = a.office_id
              JOIN app_user u ON u.id = a.applicant_id
            """;
    private static final TypeReference<Map<String, String>> FORM = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApplicationType> availableTypes() {
        return jdbc.sql("""
                        SELECT DISTINCT ON (code) id, code, version_no, available, name_fr, name_en,
                               requirements::text AS requirements, effective_from
                          FROM application_type ORDER BY code, version_no DESC""")
                .query((rs, n) -> type(rs)).list().stream().filter(ApplicationType::available).toList();
    }

    @Override
    public Optional<ApplicationType> latestType(String code) {
        return jdbc.sql(TYPE + " WHERE code = :code ORDER BY version_no DESC LIMIT 1").param("code", code)
                .query((rs, n) -> type(rs)).optional();
    }

    @Override
    public Optional<ApplicationType> type(UUID versionId) {
        return jdbc.sql(TYPE + " WHERE id = :id").param("id", versionId).query((rs, n) -> type(rs)).optional();
    }

    @Override
    public void addTypeVersion(ApplicationType.Definition definition, int versionNo, boolean available) {
        jdbc.sql("""
                        INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements)
                        VALUES (:code, :version, :available, :fr, :en, CAST(:requirements AS jsonb))""")
                .param("code", definition.code()).param("version", versionNo).param("available", available)
                .param("fr", definition.nameFr()).param("en", definition.nameEn())
                .param("requirements", json.writeValueAsString(definition.requirements()))
                .update();
    }

    @Override
    public List<String> typeCodes() {
        return jdbc.sql("SELECT DISTINCT code FROM application_type ORDER BY code").query(String.class).list();
    }

    @Override
    public UUID createDraft(UUID applicant, String typeCode, UUID typeVersionId, String parcelReference,
                            Map<String, String> data) {
        return jdbc.sql("""
                        INSERT INTO application (applicant_id, type_code, type_version_id, parcel_reference, data)
                        VALUES (:applicant, :type, :version, :parcel, CAST(:data AS jsonb))
                        RETURNING id""")
                .param("applicant", applicant).param("type", typeCode).param("version", typeVersionId)
                .param("parcel", parcelReference).param("data", json.writeValueAsString(data))
                .query(UUID.class).single();
    }

    @Override
    public int draftCount(UUID applicant) {
        return jdbc.sql("SELECT count(*) FROM application WHERE applicant_id = :applicant AND state = 'DRAFT'")
                .param("applicant", applicant).query(Integer.class).single();
    }

    @Override
    public List<Application> ofApplicant(UUID applicant, int limit, int offset) {
        return jdbc.sql(APPLICATION + """
                         WHERE a.applicant_id = :applicant
                         ORDER BY a.updated_at DESC, a.id DESC LIMIT :limit OFFSET :offset""")
                .param("applicant", applicant).param("limit", limit).param("offset", offset)
                .query((rs, n) -> application(rs)).list();
    }

    @Override
    public Optional<Application> find(UUID id) {
        return jdbc.sql(APPLICATION + " WHERE a.id = :id").param("id", id).query((rs, n) -> application(rs)).optional();
    }

    @Override
    public boolean updateDraft(UUID id, long expectedVersion, UUID typeVersionId, String parcelReference,
                               Map<String, String> data) {
        return jdbc.sql("""
                        UPDATE application SET type_version_id = :type, parcel_reference = :parcel,
                               data = CAST(:data AS jsonb), version = version + 1
                         WHERE id = :id AND state = 'DRAFT' AND version = :expected""")
                .param("type", typeVersionId).param("parcel", parcelReference).param("data", json.writeValueAsString(data))
                .param("id", id).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public boolean deleteDraft(UUID id, long expectedVersion) {
        return jdbc.sql("DELETE FROM application WHERE id = :id AND state = 'DRAFT' AND version = :expected")
                .param("id", id).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public boolean submit(UUID id, long expectedVersion, Submission s) {
        return jdbc.sql("""
                        UPDATE application
                           SET state = 'SUBMITTED', type_version_id = :type, parcel_id = :parcel, parcel_reference = :parcelRef,
                               office_id = :office, reference = :reference, state_changed_by = :actor,
                               version = version + 1
                         WHERE id = :id AND state = 'DRAFT' AND version = :expected""")
                .param("type", s.typeVersionId()).param("parcel", s.parcelId()).param("parcelRef", s.parcelReference())
                .param("office", s.officeId()).param("reference", s.reference().value()).param("actor", s.actor())
                .param("id", id).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public List<Application.Transition> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT t.from_state, t.to_state, t.actor_id, u.display_name, t.reason, t.at
                          FROM application_transition t LEFT JOIN app_user u ON u.id = t.actor_id
                         WHERE t.application_id = :id ORDER BY t.at, t.id""")
                .param("id", id)
                .query((rs, n) -> new Application.Transition(ApplicationState.valueOf(rs.getString("from_state")),
                        ApplicationState.valueOf(rs.getString("to_state")), rs.getObject("actor_id", UUID.class),
                        rs.getString("display_name"), rs.getString("reason"), instant(rs, "at")))
                .list();
    }

    @Override
    public long nextSequence(int year) {
        // Applications are numbered nationally: the counter's office column holds a fixed key.
        return jdbc.sql("SELECT next_reference_number('APP', 'ALL', :year)").param("year", year)
                .query(Long.class).single();
    }

    @Override
    public List<String> missingItems(UUID id) {
        return List.of((String[]) jdbc.sql("SELECT application_missing_items(:id)").param("id", id)
                .query((rs, n) -> rs.getArray(1).getArray()).single());
    }

    @Override
    public void record(UUID id, CompletenessRecord.Kind kind, List<String> requested, String message, UUID actor) {
        jdbc.sql("""
                        INSERT INTO completeness_record (application_id, kind, requested, message, actor_id)
                        VALUES (:id, :kind, CAST(:requested AS text[]), :message, :actor)""")
                .param("id", id).param("kind", kind.name())
                .param("requested", requested == null ? null : requested.toArray(String[]::new))
                .param("message", message).param("actor", actor)
                .update();
    }

    @Override
    public List<CompletenessRecord> completeness(UUID id) {
        return jdbc.sql("""
                        SELECT r.id, r.kind, r.missing, r.requested, r.message, u.display_name, r.at
                          FROM completeness_record r LEFT JOIN app_user u ON u.id = r.actor_id
                         WHERE r.application_id = :id ORDER BY r.at, r.id""")
                .param("id", id)
                .query((rs, n) -> new CompletenessRecord(rs.getObject("id", UUID.class),
                        CompletenessRecord.Kind.valueOf(rs.getString("kind")), strings(rs, "missing"),
                        strings(rs, "requested"), rs.getString("message"), rs.getString("display_name"), instant(rs, "at")))
                .list();
    }

    @Override
    public boolean move(UUID id, long expectedVersion, ApplicationState from, ApplicationState to, UUID actor, String reason) {
        return jdbc.sql("""
                        UPDATE application SET state = :to, state_changed_by = :actor, state_reason = :reason,
                               version = version + 1
                         WHERE id = :id AND state = :from AND version = :expected""")
                .param("to", to.name()).param("actor", actor).param("reason", reason)
                .param("id", id).param("from", from.name()).param("expected", expectedVersion).update() == 1;
    }

    @Override
    public List<Case> ofOffice(String officeCode, ApplicationState state, int limit, int offset) {
        return jdbc.sql(CASE + """
                         WHERE o.code = :office AND (CAST(:state AS text) IS NULL OR a.state = :state)
                         ORDER BY a.updated_at, a.id LIMIT :limit OFFSET :offset""")
                .param("office", officeCode).param("state", state == null ? null : state.name())
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Case(application(rs), rs.getString("applicant_name"))).list();
    }

    @Override
    public Optional<Case> findCase(UUID id) {
        return jdbc.sql(CASE + " WHERE a.id = :id AND a.state <> 'DRAFT'").param("id", id)
                .query((rs, n) -> new Case(application(rs), rs.getString("applicant_name"))).optional();
    }

    @Override
    public Optional<String> officeCode(UUID id) {
        return jdbc.sql("SELECT application_office_code(:id)").param("id", id).query(String.class).optional();
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private ApplicationType type(ResultSet rs) throws SQLException {
        return new ApplicationType(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("version_no"),
                rs.getBoolean("available"), rs.getString("name_fr"), rs.getString("name_en"),
                json.readValue(rs.getString("requirements"), Requirements.class), instant(rs, "effective_from"));
    }

    private Application application(ResultSet rs) throws SQLException {
        Map<String, String> data = new LinkedHashMap<>(json.readValue(rs.getString("data"), FORM));
        return new Application(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getObject("applicant_id", UUID.class), rs.getString("type_code"),
                rs.getObject("type_version_id", UUID.class), ApplicationState.valueOf(rs.getString("state")),
                rs.getString("parcel_reference"), rs.getString("office_code"), data, instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "submitted_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
