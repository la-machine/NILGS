// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.util.List;
import java.util.Optional;

import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.domain.UnreadableCheckpoint;
import org.snigf.mvp.keys.SigningPublicKey;

/**
 * Port: the off-database copy of every checkpoint, which the auditor downloads and keeps on
 * a separate machine (Slice 1 design §7). Entries are written once and never replaced.
 */
public interface CheckpointArchive {

    record Contents(List<Checkpoint> checkpoints, List<UnreadableCheckpoint> unreadable) {
    }

    /** @throws IllegalStateException if a checkpoint for that position is already archived */
    void store(Checkpoint checkpoint);

    /** Publishes the public keys that verify the checkpoints, every version. */
    void storePublicKeys(List<SigningPublicKey> keys);

    /** The archived document's exact bytes. */
    Optional<byte[]> read(long chainPos);

    Contents readAll();
}
