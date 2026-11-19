// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.procedure.domain.Procedure;
import org.snigf.mvp.procedure.domain.ProcedureRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads under row-level security (V32: {@code procedure_readable}; V33: the objector's identity through
 * {@code procedure_objections}); writes through the functions of V32 and V33.
 */
@Repository
class JdbcProcedureRepository implements ProcedureRepository {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcProcedureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean readable(UUID applicationId, UUID actor) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT procedure_readable(:id, :actor)").param("id", applicationId).param("actor", actor)
                .query(Boolean.class).single());
    }

    @Override
    public Optional<Procedure.CaseHeader> caseHeader(UUID applicationId) {
        // The case's procedure readers (procedure_readable), a conservator recording procedure B's stages included, who holds
        // no application.read at the office (V37): the header only, through the database's function.
        return jdbc.sql("SELECT * FROM procedure_case_header(:id)")
                .param("id", applicationId)
                .query((rs, n) -> new Procedure.CaseHeader(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("state"),
                        rs.getString("type_code"), rs.getString("office_code"), rs.getLong("version"), rs.getInt("review_round")))
                .optional();
    }

    @Override
    public List<Procedure.StageDefinition> stagesOf(UUID applicationId) {
        return jdbc.sql("""
                        SELECT ps.* FROM jsonb_array_elements_text(coalesce(application_procedure(:id) -> 'stages', '[]')) s
                          JOIN procedure_stage ps ON ps.code = s ORDER BY ps.rank""")
                .param("id", applicationId)
                .query((rs, n) -> new Procedure.StageDefinition(rs.getString("code"), rs.getString("procedure"), rs.getInt("rank"),
                        rs.getString("requires"), rs.getString("permission"), rs.getString("label_en"), rs.getString("label_fr")))
                .list();
    }

    @Override
    public List<Procedure.StageRecord> records(UUID applicationId) {
        Map<UUID, List<Procedure.StageFile>> filesByRecord = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT f.* FROM procedure_stage_file f JOIN procedure_stage_record r ON r.id = f.record_id
                         WHERE r.application_id = :id ORDER BY f.uploaded_at, f.id""")
                .param("id", applicationId)
                .query((rs, n) -> {
                    filesByRecord.computeIfAbsent(rs.getObject("record_id", UUID.class), k -> new ArrayList<>())
                            .add(new Procedure.StageFile(rs.getObject("id", UUID.class), rs.getString("original_name"), rs.getString("detected_type"),
                                    rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "uploaded_at")));
                    return null;
                }).list();
        return jdbc.sql("""
                        SELECT r.*, r.details::text AS details_json, u.display_name
                          FROM procedure_stage_record r JOIN app_user u ON u.id = r.recorded_by
                         WHERE r.application_id = :id ORDER BY r.recorded_at, r.id""")
                .param("id", applicationId)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Procedure.StageRecord(id, rs.getString("stage"), rs.getString("outcome"), rs.getString("finding"),
                            details(rs.getString("details_json")), uuids(rs.getArray("evidence_versions")),
                            rs.getObject("supersedes_id", UUID.class), rs.getString("correction_reason"), rs.getObject("recorded_by", UUID.class),
                            rs.getString("display_name"), instant(rs, "recorded_at"), filesByRecord.getOrDefault(id, List.of()));
                })
                .list();
    }

    @Override
    public Map<String, String> statuses(UUID applicationId) {
        Map<String, String> statuses = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s AS stage, procedure_stage_status(:id, s) AS status
                          FROM jsonb_array_elements_text(coalesce(application_procedure(:id) -> 'stages', '[]')) s""")
                .param("id", applicationId)
                .query((rs, n) -> statuses.put(rs.getString("stage"), rs.getString("status"))).list();
        return statuses;
    }

    @Override
    public List<String> gate(UUID applicationId) {
        return jdbc.sql("SELECT unnest(procedure_gate_problems(:id))").param("id", applicationId).query(String.class).list();
    }

    @Override
    public boolean mayRecord(UUID applicationId, String stage) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT procedure_may_record(:id, :stage)").param("id", applicationId).param("stage", stage)
                .query(Boolean.class).single());
    }

    @Override
    public UUID add(UUID applicationId, String stage, Procedure.NewRecord record, List<Procedure.KeptFile> files) {
        List<Map<String, Object>> kept = keptFiles(files);
        UUID[] evidence = record.evidence() == null ? new UUID[0] : record.evidence().toArray(UUID[]::new);
        return jdbc.sql("""
                        SELECT procedure_stage_record_add(:id, :stage, :outcome, :finding, CAST(:details AS jsonb), CAST(:evidence AS uuid[]), CAST(:supersedes AS uuid),
                                                          :reason, CAST(:files AS jsonb))""")
                .param("id", applicationId).param("stage", stage).param("outcome", record.outcome()).param("finding", record.finding())
                .param("details", json.writeValueAsString(record.details() == null ? Map.of() : record.details()))
                .param("evidence", evidence).param("supersedes", record.supersedes()).param("reason", record.correctionReason())
                .param("files", json.writeValueAsString(kept))
                .query(UUID.class).single();
    }

    @Override
    public Optional<Procedure.FileRef> file(UUID applicationId, UUID fileId) {
        return jdbc.sql("""
                        SELECT f.*, r.stage FROM procedure_stage_file f JOIN procedure_stage_record r ON r.id = f.record_id
                         WHERE f.id = :file AND r.application_id = :id""")
                .param("file", fileId).param("id", applicationId)
                .query((rs, n) -> new Procedure.FileRef(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class), rs.getString("stage"),
                        rs.getString("storage_key"), rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("detected_type"),
                        rs.getString("original_name")))
                .optional();
    }

    @Override
    public List<Procedure.QueueEntry> queue(int limit, int offset) {
        return jdbc.sql("SELECT * FROM procedure_queue(:limit, :offset)").param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Procedure.QueueEntry(rs.getObject("application_id", UUID.class), rs.getString("reference"),
                        rs.getString("office_code"), rs.getString("type_code"), rs.getString("stage"), rs.getString("stage_status"),
                        instant(rs, "submitted_at")))
                .list();
    }

    @Override
    public List<Procedure.Notice> notices(UUID applicationId) {
        return jdbc.sql("""
                        SELECT n.*, n.period::text AS period_text FROM procedure_notice n
                         WHERE n.application_id = :id ORDER BY n.opens_at DESC, n.id DESC""")
                .param("id", applicationId)
                .query((rs, n) -> new Procedure.Notice(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("record_id", UUID.class),
                        rs.getString("parcel_reference"), rs.getString("locality"), rs.getString("period_text"), instant(rs, "opens_at"),
                        instant(rs, "closes_at"), rs.getObject("replaces_id", UUID.class)))
                .list();
    }

    @Override
    public List<Procedure.Objection> objections(UUID applicationId) {
        Map<UUID, List<Procedure.ObjectionFile>> filesByObjection = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT f.* FROM procedure_objection_file f JOIN procedure_objection o ON o.id = f.objection_id
                         WHERE o.application_id = :id ORDER BY f.uploaded_at, f.id""")
                .param("id", applicationId)
                .query((rs, n) -> {
                    filesByObjection.computeIfAbsent(rs.getObject("objection_id", UUID.class), k -> new ArrayList<>())
                            .add(new Procedure.ObjectionFile(rs.getObject("id", UUID.class), rs.getString("original_name"), rs.getString("detected_type"),
                                    rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "uploaded_at")));
                    return null;
                }).list();
        return jdbc.sql("SELECT * FROM procedure_objections(:id)")
                .param("id", applicationId)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String outcome = rs.getString("outcome");
                    Procedure.ObjectionDecision decision = outcome == null ? null : new Procedure.ObjectionDecision(outcome, rs.getString("grounds"),
                            texts(rs.getArray("review_stages")), rs.getString("applicant_instructions"), rs.getObject("decided_by", UUID.class),
                            rs.getString("decided_by_name"), instant(rs, "decided_at"));
                    return new Procedure.Objection(id, rs.getString("number"), rs.getString("notice_number"), rs.getString("objector_name"),
                            rs.getString("objector_interest"), rs.getBoolean("identity_visible"),
                            rs.getString("statement"), instant(rs, "received_at"), rs.getBoolean("late"), rs.getObject("recorded_by", UUID.class),
                            rs.getString("recorded_by_name"), decision, rs.getBoolean("may_decide"), filesByObjection.getOrDefault(id, List.of()));
                })
                .list();
    }

    @Override
    public boolean mayRecordObjection(UUID applicationId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT procedure_may_record_objection(:id)").param("id", applicationId).query(Boolean.class).single());
    }

    @Override
    public List<Procedure.Reconsideration> reconsiderations(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, round, reconsidered_decision, reconsidered_outcome, objection_numbers, stages, added_stages, justification,
                               opened_by, opened_by_name, opened_at, round_outcome
                          FROM procedure_reconsiderations(:id)""")
                .param("id", applicationId)
                .query((rs, n) -> new Procedure.Reconsideration(rs.getObject("id", UUID.class), rs.getInt("round"),
                        rs.getObject("reconsidered_decision", UUID.class), rs.getString("reconsidered_outcome"), texts(rs.getArray("objection_numbers")),
                        texts(rs.getArray("stages")), texts(rs.getArray("added_stages")), rs.getString("justification"),
                        rs.getObject("opened_by", UUID.class), rs.getString("opened_by_name"),
                        rs.getObject("opened_at", java.time.OffsetDateTime.class).toInstant(), rs.getString("round_outcome")))
                .list();
    }

    @Override
    public Optional<String> reconsiderProblem(UUID applicationId, UUID actor) {
        return jdbc.sql("SELECT procedure_reconsider_problem(:id, :actor)").param("id", applicationId).param("actor", actor)
                .query(String.class).optional();
    }

    @Override
    public Optional<Procedure.CaseDeposit> caseDeposit(UUID applicationId) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM procedure_case_deposit(:id)").param("id", applicationId).query().listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> first = rows.getFirst();
        List<Procedure.Receipt> receipts = rows.stream().filter(r -> r.get("receipt_id") != null)
                .map(r -> new Procedure.Receipt((UUID) r.get("receipt_id"), (String) r.get("receipt_name"))).toList();
        Object at = first.get("deposited_at");
        java.time.Instant depositedAt = at instanceof java.sql.Timestamp ts ? ts.toInstant()
                : at instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
        return Optional.of(new Procedure.CaseDeposit((UUID) first.get("deposit_id"), (String) first.get("status"), (String) first.get("channel"),
                depositedAt, (String) first.get("title_number"), receipts));
    }

    @Override
    public UUID reconsider(UUID applicationId, long expectedVersion, List<String> addedStages, String justification) {
        return jdbc.sql("SELECT procedure_reconsider(:id, :version, CAST(:added AS text[]), :justification)")
                .param("id", applicationId).param("version", expectedVersion).param("added", addedStages.toArray(String[]::new))
                .param("justification", justification).query(UUID.class).single();
    }

    @Override
    public int objectionsOpen(UUID applicationId) {
        return jdbc.sql("SELECT procedure_objections_open(:id)").param("id", applicationId).query(Integer.class).single();
    }

    @Override
    public Procedure.ObjectionWritten addObjection(UUID applicationId, Procedure.NewObjection objection, List<Procedure.KeptFile> files,
                                                   UUID clientKey) {
        Map<String, Object> objector = new LinkedHashMap<>();
        Procedure.Objector o = objection.objector();
        if (o != null) {
            objector.put("name", o.name());
            objector.put("identityKind", o.identityKind());
            objector.put("identityNumber", o.identityNumber());
            objector.put("interest", o.interest());
        }
        return jdbc.sql("""
                        SELECT * FROM procedure_objection_add(:id, CAST(:objector AS jsonb), :statement, CAST(:files AS jsonb), CAST(:key AS uuid))""")
                .param("id", applicationId).param("objector", json.writeValueAsString(objector)).param("statement", objection.statement())
                .param("files", json.writeValueAsString(keptFiles(files))).param("key", clientKey)
                .query((rs, n) -> new Procedure.ObjectionWritten(rs.getObject("objection_id", UUID.class), rs.getBoolean("replayed")))
                .single();
    }

    @Override
    public UUID decideObjection(UUID objectionId, String outcome, String grounds, List<String> reviewStages, String applicantInstructions) {
        String[] stages = reviewStages == null ? new String[0] : reviewStages.toArray(String[]::new);
        return jdbc.sql("SELECT procedure_objection_decide(:id, :outcome, :grounds, CAST(:stages AS text[]), :instructions)")
                .param("id", objectionId).param("outcome", outcome).param("grounds", grounds).param("stages", stages)
                .param("instructions", applicantInstructions)
                .query(UUID.class).single();
    }

    @Override
    public Procedure.ObjectorIdentity objectorIdentity(UUID objectionId) {
        return jdbc.sql("SELECT * FROM procedure_objector_identity(:id)").param("id", objectionId)
                .query((rs, n) -> new Procedure.ObjectorIdentity(rs.getString("identity_kind"), rs.getString("identity_number")))
                .single();
    }

    @Override
    public Optional<UUID> objectionCase(UUID objectionId) {
        return jdbc.sql("SELECT application_id FROM procedure_objection WHERE id = :id").param("id", objectionId).query(UUID.class).optional();
    }

    @Override
    public Optional<String> objectionNumber(UUID objectionId) {
        return jdbc.sql("SELECT number FROM procedure_objection WHERE id = :id").param("id", objectionId).query(String.class).optional();
    }

    @Override
    public Optional<Procedure.ObjectionFileRef> objectionFile(UUID applicationId, UUID objectionId, UUID fileId) {
        return jdbc.sql("""
                        SELECT f.* FROM procedure_objection_file f JOIN procedure_objection o ON o.id = f.objection_id
                         WHERE f.id = :file AND o.id = :objection AND o.application_id = :id""")
                .param("file", fileId).param("objection", objectionId).param("id", applicationId)
                .query((rs, n) -> new Procedure.ObjectionFileRef(rs.getObject("id", UUID.class), rs.getObject("objection_id", UUID.class),
                        rs.getString("storage_key"), rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("detected_type"),
                        rs.getString("original_name")))
                .optional();
    }

    @Override
    public List<Procedure.ObjectionQueueEntry> objectionQueue(int limit, int offset) {
        return jdbc.sql("SELECT * FROM procedure_objection_queue(:limit, :offset)").param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Procedure.ObjectionQueueEntry(rs.getObject("objection_id", UUID.class), rs.getString("number"),
                        rs.getObject("application_id", UUID.class), rs.getString("reference"), rs.getString("office_code"),
                        instant(rs, "received_at"), rs.getBoolean("late")))
                .list();
    }

    @Override
    public List<Procedure.PublicNotice> publicNotices(int limit, int offset) {
        return jdbc.sql("SELECT * FROM procedure_public_notices(:limit, :offset)").param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Procedure.PublicNotice(rs.getString("number"), rs.getString("procedure"), rs.getString("parcel_reference"),
                        rs.getString("locality"), rs.getString("office_code"), rs.getString("office_name_fr"), rs.getString("office_name_en"),
                        instant(rs, "opens_at"), instant(rs, "closes_at")))
                .list();
    }

    private static List<Map<String, Object>> keptFiles(List<Procedure.KeptFile> files) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Procedure.KeptFile f : files) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("storageKey", f.storageKey());
            file.put("sha256", f.sha256());
            file.put("size", f.sizeBytes());
            file.put("type", f.mediaType());
            file.put("name", f.name());
            kept.add(file);
        }
        return kept;
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private Map<String, Object> details(String text) {
        return text == null ? Map.of() : json.readValue(text, MAP);
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
