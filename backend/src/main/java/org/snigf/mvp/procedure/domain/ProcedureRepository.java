// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads under row-level security and the functions of V32 and V33; the only writes are {@code procedure_stage_record_add},
 * {@code procedure_objection_add} and {@code procedure_objection_decide}.
 */
public interface ProcedureRepository {

    /** Whether the actor bound to the transaction may read the case's procedure. */
    boolean readable(UUID applicationId, UUID actor);

    Optional<Procedure.CaseHeader> caseHeader(UUID applicationId);

    /** The stages of the case's procedure, in rank order; empty for a type without a procedure. */
    List<Procedure.StageDefinition> stagesOf(UUID applicationId);

    /** Every record of every stage of the case, oldest first. */
    List<Procedure.StageRecord> records(UUID applicationId);

    /**
     * Each stage's status as the database reads it (V33): {@code NOT_STARTED}, {@code NOT_SATISFIED}, {@code COMPLETED},
     * {@code IN_PROGRESS} (a publication whose period is open) or {@code OUTDATED} (to be recorded again).
     */
    java.util.Map<String, String> statuses(UUID applicationId);

    /** What stops the case's approval. */
    List<String> gate(UUID applicationId);

    /** Whether the actor may record this stage now (the screens' hint; the function decides). */
    boolean mayRecord(UUID applicationId, String stage);

    UUID add(UUID applicationId, String stage, Procedure.NewRecord record, List<Procedure.KeptFile> files);

    Optional<Procedure.FileRef> file(UUID applicationId, UUID fileId);

    List<Procedure.QueueEntry> queue(int limit, int offset);

    // --- Publication and objections (V33) ---

    /** The case's notices, newest first; the first is the current one when the latest publication opened it. */
    List<Procedure.Notice> notices(UUID applicationId);

    /** The case's objections, oldest first, never with the objector's identity. */
    List<Procedure.Objection> objections(UUID applicationId);

    /** Whether the actor may record an objection to the case now (the screens' hint; the function decides). */
    boolean mayRecordObjection(UUID applicationId);

    /** Objections still waiting for a decision. */
    int objectionsOpen(UUID applicationId);

    Procedure.ObjectionWritten addObjection(UUID applicationId, Procedure.NewObjection objection, List<Procedure.KeptFile> files, UUID clientKey);

    UUID decideObjection(UUID objectionId, String outcome, String grounds, List<String> reviewStages, String applicantInstructions);

    /** The objector's identity, refused by the database unless the actor receives or decides objections at the office. */
    Procedure.ObjectorIdentity objectorIdentity(UUID objectionId);

    /** The case an objection belongs to, if the actor may read it. */
    Optional<UUID> objectionCase(UUID objectionId);

    /** The objection's number, for the audit trail. */
    Optional<String> objectionNumber(UUID objectionId);

    Optional<Procedure.ObjectionFileRef> objectionFile(UUID applicationId, UUID objectionId, UUID fileId);

    List<Procedure.ObjectionQueueEntry> objectionQueue(int limit, int offset);

    /** The notices whose period is open, for anyone. */
    List<Procedure.PublicNotice> publicNotices(int limit, int offset);

    // --- Reconsideration (V36) ---

    /** The case's reconsiderations, by round. */
    List<Procedure.Reconsideration> reconsiderations(UUID applicationId);

    /** Why the actor bound to the transaction may not open a reconsideration now, or empty when they may. */
    Optional<String> reconsiderProblem(UUID applicationId, UUID actor);

    /** The case's open deposit and its duty receipts, for whoever records procedure B's stages (V37); empty otherwise. */
    Optional<Procedure.CaseDeposit> caseDeposit(UUID applicationId);

    /** Opens the next round; the database checks everything again, under the case's lock. */
    UUID reconsider(UUID applicationId, long expectedVersion, List<String> addedStages, String justification);
}
