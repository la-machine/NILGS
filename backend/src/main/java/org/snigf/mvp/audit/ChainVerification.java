// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.List;
import java.util.Optional;

/**
 * Result of recomputing the audit chain in the application, independently of the
 * database's own functions, and comparing it with every signed checkpoint.
 *
 * @param verified         true when every event and every checkpoint checked out
 * @param checkedFrom      first position checked
 * @param checkedTo        last position checked (checkedFrom - 1 when the range was empty)
 * @param rowsChecked      events recomputed
 * @param firstMismatchPos lowest position at which a problem was found, or null
 * @param mismatchKind     what that problem is, or null
 * @param checkpoints      every checkpoint and its result, by position
 */
public record ChainVerification(
        boolean verified,
        long checkedFrom,
        long checkedTo,
        long rowsChecked,
        Long firstMismatchPos,
        MismatchKind mismatchKind,
        List<CheckpointCheck> checkpoints) {

    public ChainVerification {
        checkpoints = List.copyOf(checkpoints);
    }

    /** The newest checkpoint whose position was inside the verified range. */
    public Optional<CheckpointCheck> lastCheckpoint() {
        return checkpoints.stream().filter(c -> c.matches() != null).reduce((a, b) -> b);
    }
}
