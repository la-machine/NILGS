// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.application;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.procedure.domain.Procedure;
import org.snigf.mvp.procedure.domain.ProcedureRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stage records of a case's procedure (ADR-042 step 3). An officer records the stage their permission names, at the
 * case's office, while the case is under review; never the applicant, never the case's reviewer or decision authority;
 * the commission's report never by the officer of the visit it reports on (D26). A record is never changed: a correction
 * is a new record naming the latest one, with a reason, and both stay (PROCEDURE_STAGE_CORRECTED). The database decides
 * all of this (V32) and refuses with a reason code; each refusal is audited on its own ({@code PROCEDURE_REFUSED}).
 * Retries add nothing: a second first record, or a correction of a record already corrected, is refused.
 *
 * <p>Publication (step 4, V33): the officer gives the finding (where and how the notice was posted) and no details; the
 * objection period is the deployment's setting ({@code SNIGF_PROCEDURE_OBJECTION_PERIOD}), never the officer's, and
 * the database records the parcel and locality, opens the notice, and bounds the period again
 * ({@code PROCEDURE_NOTICE_PUBLISHED}). A stage's status is the database's: a record that no longer holds is
 * {@code OUTDATED}, a publication whose period is open {@code IN_PROGRESS}.
 */
@Service
public class ProcedureService {

    static final String ENTITY = "APPLICATION";
    static final String PUBLICATION = "PUBLICATION";
    public static final int PAGE_SIZE = 50;
    public static final int MAX_FILES = 5;

    /** A stage of the case with its records, newest first, and whether the reader may record it now. */
    public record StageView(Procedure.StageDefinition definition, String status, boolean mayRecord, List<Procedure.StageRecord> records) {
    }

    /**
     * A case's procedure: its stages, what still stops its approval, its notices (newest first), its objections, and
     * whether the reader may record an objection now.
     */
    public record ProcedureView(Procedure.CaseHeader header, String procedure, List<StageView> stages, List<String> gate,
                                List<Procedure.Notice> notices, List<Procedure.Objection> objections, boolean mayRecordObjection,
                                List<Procedure.Reconsideration> reconsiderations, String reconsiderProblem, Procedure.CaseDeposit caseDeposit) {
    }

    /** A file as received, before its checks. */
    public record Upload(String givenName, InputStream content) {
    }

    /** A file to download, with its bytes. */
    public record FileDownload(Procedure.FileRef file, InputStream content) {
    }

    private final ProcedureRepository procedures;
    private final DocumentFiles files;
    private final DatabaseActor actor;
    private final AuditRecorder audit;
    private final ProcedureProperties settings;

    public ProcedureService(ProcedureRepository procedures, DocumentFiles files, DatabaseActor actor, AuditRecorder audit,
                            ProcedureProperties settings) {
        this.procedures = procedures;
        this.files = files;
        this.actor = actor;
        this.audit = audit;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProcedureView view(AuthenticatedUser staff, UUID applicationId) {
        actor.bind(staff.userId());
        return read(staff, applicationId);
    }

    /** The case's procedure for this reader, in the caller's transaction (public: also called through the proxy). */
    public ProcedureView read(AuthenticatedUser staff, UUID applicationId) {
        if (!procedures.readable(applicationId, staff.userId())) {
            throw ProcedureRefused.of("CASE_NOT_FOUND");
        }
        Procedure.CaseHeader header = procedures.caseHeader(applicationId).orElseThrow(() -> ProcedureRefused.of("CASE_NOT_FOUND"));
        List<Procedure.StageDefinition> stages = procedures.stagesOf(applicationId);
        List<Procedure.StageRecord> records = procedures.records(applicationId);
        Map<String, String> statuses = procedures.statuses(applicationId);
        List<StageView> views = new ArrayList<>();
        for (Procedure.StageDefinition stage : stages) {
            List<Procedure.StageRecord> chain = new ArrayList<>(records.stream().filter(r -> r.stage().equals(stage.code())).toList());
            java.util.Collections.reverse(chain);
            views.add(new StageView(stage, statuses.getOrDefault(stage.code(), "NOT_STARTED"), procedures.mayRecord(applicationId, stage.code()),
                    chain));
        }
        return new ProcedureView(header, stages.isEmpty() ? null : stages.getFirst().procedure(), views, procedures.gate(applicationId),
                procedures.notices(applicationId), procedures.objections(applicationId), procedures.mayRecordObjection(applicationId),
                procedures.reconsiderations(applicationId), procedures.reconsiderProblem(applicationId, staff.userId()).orElse(null),
                procedures.caseDeposit(applicationId).orElse(null));
    }

    /**
     * Opens the reconsideration of an approved case (ADR-043 step 5b, D41): the next round, the stages the objections upheld
     * after its decision named (and those added, never fewer), the publication anew, and the case under review again. The
     * database decides who may (V36) and refuses with a reason code; the opening is audited with the justification as its
     * reason ({@code CASE_RECONSIDERATION_OPENED}).
     */
    @Transactional
    public ProcedureView reconsider(AuthenticatedUser supervisor, UUID applicationId, long expectedVersion, List<String> addedStages,
                                    String justification) {
        actor.bind(supervisor.userId());
        List<String> added = addedStages == null ? List.of() : List.copyOf(addedStages);
        UUID id = guarded(applicationId, null, "RECONSIDER", () -> procedures.reconsider(applicationId, expectedVersion, added,
                justification == null ? null : justification.strip()));
        Procedure.Reconsideration opened = procedures.reconsiderations(applicationId).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> ProcedureRefused.of("CASE_NOT_FOUND"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reconsideration", id.toString());
        details.put("round", opened.round());
        details.put("reconsidered", opened.reconsideredDecision().toString());
        details.put("stages", opened.stages());
        details.put("addedStages", opened.addedStages());
        details.put("objections", opened.objectionNumbers());
        audit.record(AuditEvent.of(AuditAction.CASE_RECONSIDERATION_OPENED, AuditOutcome.SUCCESS).withEntity(ENTITY, applicationId)
                .withEntityRef(procedures.caseHeader(applicationId).map(Procedure.CaseHeader::reference).orElse(null))
                .withReason(truncate(justification)).withDetails(details));
        return read(supervisor, applicationId);
    }

    /**
     * Records a stage, or corrects its latest record when {@code record.supersedes()} names it. Files are checked and kept
     * first (ADR-033); if the record is refused, they are removed.
     */
    @Transactional
    public ProcedureView record(AuthenticatedUser staff, UUID applicationId, String stage, Procedure.NewRecord record, List<Upload> uploads) {
        actor.bind(staff.userId());
        List<Upload> given = uploads == null ? List.of() : uploads;
        if (given.size() > MAX_FILES) {
            throw refused(applicationId, stage, ProcedureRefused.of("TOO_MANY_FILES"));
        }
        if (record == null || record.outcome() == null || !Procedure.OUTCOMES.contains(record.outcome())) {
            throw refused(applicationId, stage, ProcedureRefused.of("OUTCOME_REQUIRED"));
        }
        if (PUBLICATION.equals(stage)) {
            // The period is the deployment's setting, never the officer's: any details from the request are refused.
            if (record.details() != null && !record.details().isEmpty()) {
                throw refused(applicationId, stage, ProcedureRefused.of("DETAILS_INVALID"));
            }
            record = new Procedure.NewRecord(record.outcome(), record.finding(), Map.of("objectionPeriod", settings.objectionPeriod().toString()),
                    record.evidence(), record.supersedes(), record.correctionReason());
        }
        Procedure.NewRecord accepted = record;
        List<String> keys = new ArrayList<>();
        try {
            List<Procedure.KeptFile> kept = new ArrayList<>();
            for (Upload upload : given) {
                try (DocumentFiles.Received received = files.receive(upload.content(), upload.givenName())) {
                    String key = received.keep();
                    keys.add(key);
                    kept.add(new Procedure.KeptFile(key, received.sha256(), received.sizeBytes(), received.mediaType(), received.name()));
                } catch (DocumentFiles.Refused e) {
                    throw refused(applicationId, stage, ProcedureRefused.of(e.code()));
                }
            }
            UUID id = guarded(applicationId, stage, () -> procedures.add(applicationId, stage, accepted, kept));
            String reference = procedures.caseHeader(applicationId).map(Procedure.CaseHeader::reference).orElse(null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("stage", stage);
            details.put("outcome", record.outcome());
            details.put("record", id.toString());
            details.put("files", kept.size());
            details.put("citedVersions", record.evidence() == null ? 0 : record.evidence().size());
            AuditEvent event;
            if (record.supersedes() == null) {
                event = AuditEvent.of(AuditAction.PROCEDURE_STAGE_RECORDED, AuditOutcome.SUCCESS);
            } else {
                details.put("supersedes", record.supersedes().toString());
                event = AuditEvent.of(AuditAction.PROCEDURE_STAGE_CORRECTED, AuditOutcome.SUCCESS).withReason(truncate(record.correctionReason()));
            }
            audit.record(event.withEntity(ENTITY, applicationId).withEntityRef(reference).withDetails(details));
            if (PUBLICATION.equals(stage)) {
                procedures.notices(applicationId).stream().filter(n -> n.recordId().equals(id)).findFirst().ifPresent(n -> {
                    Map<String, Object> notice = new LinkedHashMap<>();
                    notice.put("notice", n.number());
                    notice.put("record", id.toString());
                    notice.put("period", settings.objectionPeriod().toString());
                    notice.put("closesAt", n.closesAt().toString());
                    if (n.replacesId() != null) {
                        notice.put("replaces", n.replacesId().toString());
                    }
                    audit.record(AuditEvent.of(AuditAction.PROCEDURE_NOTICE_PUBLISHED, AuditOutcome.SUCCESS).withEntity(ENTITY, applicationId)
                            .withEntityRef(reference).withDetails(notice));
                });
            }
            return read(staff, applicationId);
        } catch (RuntimeException e) {
            // The transaction rolls back: a file kept for the record must not stay.
            keys.forEach(files::forget);
            throw e;
        }
    }

    /** An attached file, always as an attachment; its hash is checked first, and the read is audited. */
    @Transactional
    public FileDownload download(AuthenticatedUser staff, UUID applicationId, UUID fileId) {
        actor.bind(staff.userId());
        if (!procedures.readable(applicationId, staff.userId())) {
            throw ProcedureRefused.of("CASE_NOT_FOUND");
        }
        Procedure.FileRef file = procedures.file(applicationId, fileId).orElseThrow(() -> ProcedureRefused.of("FILE_NOT_FOUND"));
        if (!files.intact(file.storageKey(), file.sha256(), file.sizeBytes())) {
            throw refused(applicationId, file.stage(), "DOWNLOAD_FILE", ProcedureRefused.of("FILE_INTEGRITY_FAILED"));
        }
        String reference = procedures.caseHeader(applicationId).map(Procedure.CaseHeader::reference).orElse(null);
        audit.record(AuditEvent.of(AuditAction.PROCEDURE_STAGE_FILE_DOWNLOADED, AuditOutcome.SUCCESS).withEntity(ENTITY, applicationId)
                .withEntityRef(reference).withDetails(Map.of("record", file.recordId().toString(), "file", file.id().toString(),
                        "stage", file.stage())));
        return new FileDownload(file, files.open(file.storageKey()));
    }

    /** The stages waiting for this officer at their offices, oldest case first. */
    @Transactional(readOnly = true)
    public List<Procedure.QueueEntry> queue(AuthenticatedUser staff, int page) {
        actor.bind(staff.userId());
        if (page < 0 || page > 10_000) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return procedures.queue(PAGE_SIZE, page * PAGE_SIZE);
    }

    private <T> T guarded(UUID applicationId, String stage, Supplier<T> write) {
        return guarded(applicationId, stage, "RECORD_STAGE", write);
    }

    private <T> T guarded(UUID applicationId, String stage, String operation, Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            throw refused(applicationId, stage, operation, ProcedureRefused.translated(e));
        }
    }

    private ProcedureRefused refused(UUID applicationId, String stage, ProcedureRefused refusal) {
        return refused(applicationId, stage, "RECORD_STAGE", refusal);
    }

    /** Audits a refusal on its own (it survives the rollback) and returns it to throw. */
    public ProcedureRefused refused(UUID applicationId, String stage, String operation, ProcedureRefused refusal) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", refusal.code());
        details.put("operation", operation);
        if (stage != null) {
            details.put("stage", stage.length() > 40 ? stage.substring(0, 40) : stage);
        }
        audit.recordIndependently(AuditEvent.of(AuditAction.PROCEDURE_REFUSED, AuditOutcome.FAILED).withEntity(ENTITY, applicationId)
                .withReason(refusal.code()).withDetails(details));
        return refusal;
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() > 1000 ? t.substring(0, 999) + "…" : t;
    }
}
