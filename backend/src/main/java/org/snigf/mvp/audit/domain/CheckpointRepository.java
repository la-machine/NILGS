// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.util.List;

import org.snigf.mvp.audit.Checkpoint;

/**
 * The database copy of the checkpoints: a convenience, never trusted on its own. A
 * checkpoint counts only if its signature verifies ({@link CheckpointEvidence}).
 */
public interface CheckpointRepository {

    /** Takes the checkpoint lock until the transaction ends; false if someone else holds it. */
    boolean tryLock();

    void add(Checkpoint checkpoint);

    /** Every checkpoint, oldest first. */
    List<Checkpoint> all();
}
