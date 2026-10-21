// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

/** Why a verification failed, at {@link ChainVerification#firstMismatchPos()}. */
public enum MismatchKind {
    /** A position is missing: an event was deleted. */
    GAP,
    /** An event does not link to the hash of the one before it. */
    PREV_HASH,
    /** An event's content no longer matches its own hash: it was edited. */
    CONTENT_HASH,
    /** A checkpoint's signature does not verify with the checkpoint public keys. */
    CHECKPOINT_SIGNATURE,
    /** The chain's hash at a checkpoint's position differs from the signed head: the chain was rewritten. */
    CHECKPOINT_MISMATCH,
    /** A checkpoint signs a position the chain no longer reaches: the newest events were deleted. */
    TRUNCATED,
    /** A checkpoint file cannot be read or parsed. */
    CHECKPOINT_UNREADABLE
}
