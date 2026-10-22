// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.List;
import java.util.Optional;

import org.snigf.mvp.keys.SigningPublicKey;

/**
 * Verification and checkpoints of the audit trail (S1-AC5, C7). The auditor's API (E-06)
 * calls these after its own permission check; scheduled jobs call them daily.
 */
public interface AuditTrail {

    /**
     * Recomputes every hash between two positions (inclusive; null means from the start or
     * to the end) and compares the chain with every signed checkpoint. A full verification
     * (both null) also detects deleted newest events. Records {@code AUDIT_VERIFIED}.
     */
    ChainVerification verify(Long fromPos, Long toPos);

    /**
     * Signs the current head of the chain, after verifying every event since the last
     * checkpoint, so a tampered chain is never signed. Stores the checkpoint in the database
     * and as a file in the checkpoint directory, and records {@code AUDIT_CHECKPOINT_CREATED}.
     *
     * @throws AuditIntegrityException if the chain since the last checkpoint does not verify
     * @throws IllegalStateException   if the chain is empty or another checkpoint is being made
     */
    Checkpoint createCheckpoint();

    /**
     * Searches the events, newest first. The search itself is audited
     * ({@code AUDIT_EVENTS_READ}, with the criteria): reading audit data is audited.
     */
    AuditEventView.Page events(AuditEventFilter filter);

    /** Every checkpoint in the database, oldest first. */
    List<Checkpoint> checkpoints();

    /** A checkpoint file's exact bytes, for download by the auditor. */
    Optional<byte[]> checkpointFile(long chainPos);

    /** Public keys of the checkpoint signing key, every version, for offline verification. */
    List<SigningPublicKey> checkpointPublicKeys();
}
