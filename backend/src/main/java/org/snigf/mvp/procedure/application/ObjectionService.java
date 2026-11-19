// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.application;

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
 * Objections to a published case (ADR-042 step 4, P2). An objection is received at the counter only: an officer holding
 * {@code procedure.objection.record} at the case's office records it for a named objector, with a statement and files,
 * under an {@code Idempotency-Key} so a retry gives back the first objection. One received after the period is recorded
 * and marked late; nothing is lost. A supervisor holding {@code procedure.objection.decide} decides it once, with
 * grounds; never its recorder, the applicant, the case's reviewer or decision authority, nor an officer of one of its
 * stages. An upheld objection names the stages examined again and never refuses the case. While an objection is open,
 * no recommendation or decision is possible. The database (V33) decides all of this; every write and refusal is
 * audited, the objector's identity never in the trail.
 */
@Service
public class ObjectionService {

    /** The objection itself, and the reader's view of its case after the write. */
    public record Written(UUID objectionId, ProcedureService.ProcedureView view) {
    }

    /** A file to download, with its bytes. */
    public record FileDownload(Procedure.ObjectionFileRef file, java.io.InputStream content) {
    }

    private final ProcedureRepository procedures;
    private final ProcedureService stages;
    private final DocumentFiles files;
    private final DatabaseActor actor;
    private final AuditRecorder audit;
    private final ProcedureProperties settings;

    public ObjectionService(ProcedureRepository procedures, ProcedureService stages, DocumentFiles files, DatabaseActor actor,
                            AuditRecorder audit, ProcedureProperties settings) {
        this.procedures = procedures;
        this.stages = stages;
        this.files = files;
        this.actor = actor;
        this.audit = audit;
        this.settings = settings;
    }

    /** Records an objection received at the counter. Files are checked and kept first (ADR-033), removed if refused. */
    @Transactional
    public Written record(AuthenticatedUser officer, UUID applicationId, Procedure.NewObjection objection, List<ProcedureService.Upload> uploads,
                          UUID clientKey) {
        actor.bind(officer.userId());
        List<ProcedureService.Upload> given = uploads == null ? List.of() : uploads;
        if (clientKey == null) {
            throw refused(applicationId, "RECORD_OBJECTION", ProcedureRefused.of("IDEMPOTENCY_KEY_REQUIRED"));
        }
        if (given.size() > ProcedureService.MAX_FILES) {
            throw refused(applicationId, "RECORD_OBJECTION", ProcedureRefused.of("TOO_MANY_FILES"));
        }
        if (objection == null || objection.objector() == null) {
            throw refused(applicationId, "RECORD_OBJECTION", ProcedureRefused.of("OBJECTOR_INVALID"));
        }
        List<String> keys = new ArrayList<>();
        try {
            List<Procedure.KeptFile> kept = new ArrayList<>();
            for (ProcedureService.Upload upload : given) {
                try (DocumentFiles.Received received = files.receive(upload.content(), upload.givenName())) {
                    String key = received.keep();
                    keys.add(key);
                    kept.add(new Procedure.KeptFile(key, received.sha256(), received.sizeBytes(), received.mediaType(), received.name()));
                } catch (DocumentFiles.Refused e) {
                    throw refused(applicationId, "RECORD_OBJECTION", ProcedureRefused.of(e.code()));
                }
            }
            Procedure.ObjectionWritten written = guarded(applicationId, "RECORD_OBJECTION",
                    () -> procedures.addObjection(applicationId, objection, kept, clientKey));
            if (written.replayed()) {
                // A retry: the first objection stands with its own files; this request's files are not kept.
                keys.forEach(files::forget);
                keys.clear();
            } else {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("objection", written.id().toString());
                procedures.objectionNumber(written.id()).ifPresent(n -> details.put("number", n));
                details.put("interest", objection.objector().interest());
                details.put("files", kept.size());
                procedures.objections(applicationId).stream().filter(o -> o.id().equals(written.id())).findFirst()
                        .ifPresent(o -> details.put("late", o.late()));
                audit.record(AuditEvent.of(AuditAction.PROCEDURE_OBJECTION_RECORDED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                        .withEntityRef(reference(applicationId)).withDetails(details));
            }
            return new Written(written.id(), stages.read(officer, applicationId));
        } catch (RuntimeException e) {
            keys.forEach(files::forget);
            throw e;
        }
    }

    /**
     * Decides an objection of the case: upheld (naming the stages examined again, and what the applicant must do, if
     * anything) or dismissed, with grounds. It writes nothing on the case: an upheld objection never refuses it.
     */
    @Transactional
    public ProcedureService.ProcedureView decide(AuthenticatedUser supervisor, UUID applicationId, UUID objectionId, String outcome, String grounds,
                                                 List<String> reviewStages, String applicantInstructions) {
        actor.bind(supervisor.userId());
        UUID caseId = procedures.objectionCase(objectionId).orElse(null);
        if (caseId == null || !caseId.equals(applicationId)) {
            throw refused(applicationId, "DECIDE_OBJECTION", ProcedureRefused.of("OBJECTION_NOT_FOUND"));
        }
        if (outcome == null || !Procedure.OBJECTION_OUTCOMES.contains(outcome)) {
            throw refused(applicationId, "DECIDE_OBJECTION", ProcedureRefused.of("OUTCOME_REQUIRED"));
        }
        List<String> named = reviewStages == null ? List.of() : List.copyOf(reviewStages);
        String instructions = applicantInstructions == null || applicantInstructions.isBlank() ? null : applicantInstructions;
        guarded(applicationId, "DECIDE_OBJECTION", () -> procedures.decideObjection(objectionId, outcome, grounds, named, instructions));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("objection", objectionId.toString());
        procedures.objectionNumber(objectionId).ifPresent(n -> details.put("number", n));
        details.put("outcome", outcome);
        details.put("reviewStages", named);
        details.put("applicantInstructed", instructions != null);
        audit.record(AuditEvent.of(AuditAction.PROCEDURE_OBJECTION_DECIDED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withEntityRef(reference(applicationId)).withReason(ProcedureService.truncate(grounds)).withDetails(details));
        return stages.read(supervisor, applicationId);
    }

    /**
     * The objector's identity document, one objection at a time, to the officers who receive or decide objections at the
     * case's office (decision 1): refused to anyone else by the database, and every read audited, without the identity.
     */
    @Transactional
    public Procedure.ObjectorIdentity objectorIdentity(AuthenticatedUser staff, UUID applicationId, UUID objectionId) {
        actor.bind(staff.userId());
        UUID caseId = procedures.objectionCase(objectionId).orElse(null);
        if (caseId == null || !caseId.equals(applicationId)) {
            throw refused(applicationId, "READ_OBJECTOR_IDENTITY", ProcedureRefused.of("OBJECTION_NOT_FOUND"));
        }
        Procedure.ObjectorIdentity identity = guarded(applicationId, "READ_OBJECTOR_IDENTITY", () -> procedures.objectorIdentity(objectionId));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("objection", objectionId.toString());
        procedures.objectionNumber(objectionId).ifPresent(n -> details.put("number", n));
        audit.record(AuditEvent.of(AuditAction.PROCEDURE_OBJECTOR_IDENTITY_READ, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withEntityRef(reference(applicationId)).withDetails(details));
        return identity;
    }

    /** A file received with an objection, always as an attachment; its hash is checked first, and the read is audited. */
    @Transactional
    public FileDownload download(AuthenticatedUser staff, UUID applicationId, UUID objectionId, UUID fileId) {
        actor.bind(staff.userId());
        if (!procedures.readable(applicationId, staff.userId())) {
            throw ProcedureRefused.of("CASE_NOT_FOUND");
        }
        Procedure.ObjectionFileRef file = procedures.objectionFile(applicationId, objectionId, fileId)
                .orElseThrow(() -> ProcedureRefused.of("FILE_NOT_FOUND"));
        if (!files.intact(file.storageKey(), file.sha256(), file.sizeBytes())) {
            throw refused(applicationId, "DOWNLOAD_OBJECTION_FILE", ProcedureRefused.of("FILE_INTEGRITY_FAILED"));
        }
        audit.record(AuditEvent.of(AuditAction.PROCEDURE_OBJECTION_FILE_DOWNLOADED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withEntityRef(reference(applicationId)).withDetails(Map.of("objection", objectionId.toString(), "file", file.id().toString())));
        return new FileDownload(file, files.open(file.storageKey()));
    }

    /** The objections waiting for this supervisor at their offices, oldest first. */
    @Transactional(readOnly = true)
    public List<Procedure.ObjectionQueueEntry> queue(AuthenticatedUser supervisor, int page) {
        actor.bind(supervisor.userId());
        if (page < 0 || page > 10_000) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return procedures.objectionQueue(settings.pageSize(), page * settings.pageSize());
    }

    /** The notices whose objection period is open, for anyone (no actor bound). */
    @Transactional(readOnly = true)
    public List<Procedure.PublicNotice> publicNotices(int page) {
        if (page < 0 || page > 10_000) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return procedures.publicNotices(settings.pageSize(), page * settings.pageSize());
    }

    private String reference(UUID applicationId) {
        return procedures.caseHeader(applicationId).map(Procedure.CaseHeader::reference).orElse(null);
    }

    private <T> T guarded(UUID applicationId, String operation, Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            throw refused(applicationId, operation, ProcedureRefused.translated(e));
        }
    }

    private ProcedureRefused refused(UUID applicationId, String operation, ProcedureRefused refusal) {
        return stages.refused(applicationId, null, operation, refusal);
    }
}
