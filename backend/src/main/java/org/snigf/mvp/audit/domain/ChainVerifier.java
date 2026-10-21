// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.CheckpointCheck;
import org.snigf.mvp.audit.MismatchKind;

/**
 * Domain service that recomputes the chain and confronts it with the checkpoint evidence
 * (S1-AC5). It trusts neither the database's verification functions nor its checkpoint
 * table: hashes are recomputed by {@link ChainedEvent}, and a checkpoint counts only when
 * its {@link CheckpointEvidence} is trusted.
 *
 * <p>Findings, each reported at its position, the lowest one first:
 * {@code GAP}, {@code PREV_HASH}, {@code CONTENT_HASH} from the walk;
 * {@code CHECKPOINT_UNREADABLE}, {@code CHECKPOINT_SIGNATURE}, {@code CHECKPOINT_MISMATCH}
 * from the evidence; {@code TRUNCATED} when a full verification finds a checkpoint beyond
 * the end of the chain. The caller provides one consistent snapshot of the chain.
 */
public final class ChainVerifier {

    private final AuditEventRepository chain;

    public ChainVerifier(AuditEventRepository chain) {
        this.chain = chain;
    }

    /**
     * Verifies {@code [fromPos, toPos]} (null = start or end of the chain). Range checks start
     * from the stored hash before {@code fromPos}; only a full check starts from genesis.
     */
    public ChainVerification verify(Long fromPos, Long toPos, List<CheckpointEvidence> evidence) {
        long head = chain.head().map(ChainHead::chainPos).orElse(0L);
        long from = Math.max(fromPos == null ? 1 : fromPos, 1);
        long to = Math.min(toPos == null ? head : toPos, head);
        boolean full = fromPos == null && toPos == null;
        Map<CheckpointEvidence, Boolean> matches = new IdentityHashMap<>();
        Optional<byte[]> anchor = from == 1 ? Optional.of(ChainedEvent.GENESIS) : chain.hashAt(from - 1);
        Walk walk = anchor.isPresent()
                ? walk(from, to, anchor.get(), evidence, matches)
                : Walk.brokenAt(from - 1, MismatchKind.GAP);
        return conclude(from, to, head, full, walk, evidence, matches);
    }

    /**
     * Verifies the events after the newest trusted checkpoint, starting from its signed head
     * rather than from the database. Run before a new checkpoint is signed.
     */
    public ChainVerification verifySinceLastCheckpoint(List<CheckpointEvidence> evidence) {
        long head = chain.head().map(ChainHead::chainPos).orElse(0L);
        Map<CheckpointEvidence, Boolean> matches = new IdentityHashMap<>();
        Optional<CheckpointEvidence> newest = evidence.stream()
                .filter(e -> e.trusted() && e.chainPos() <= head)
                .max(Comparator.comparingLong(CheckpointEvidence::chainPos));
        if (newest.isEmpty()) {
            return conclude(1, head, head, true, walk(1, head, ChainedEvent.GENESIS, evidence, matches),
                    evidence, matches);
        }
        Checkpoint anchor = newest.get().checkpoint();
        byte[] stored = chain.hashAt(anchor.chainPos()).orElse(null);
        boolean anchorHolds = stored != null && MessageDigest.isEqual(stored, anchor.headHash());
        matches.put(newest.get(), anchorHolds);
        Walk walk = anchorHolds
                ? walk(anchor.chainPos() + 1, head, anchor.headHash(), evidence, matches)
                : Walk.brokenAt(anchor.chainPos() - 1, MismatchKind.CHECKPOINT_MISMATCH, anchor.chainPos());
        return conclude(anchor.chainPos(), head, head, true, walk, evidence, matches);
    }

    /** Recomputes every event of the range, stopping at the first break. */
    private Walk walk(long from, long to, byte[] anchor, List<CheckpointEvidence> evidence,
                      Map<CheckpointEvidence, Boolean> matches) {
        Map<Long, List<CheckpointEvidence>> byPos = new HashMap<>();
        evidence.stream().filter(CheckpointEvidence::readable)
                .forEach(e -> byPos.computeIfAbsent(e.chainPos(), p -> new ArrayList<>()).add(e));
        Walk walk = new Walk(from - 1, anchor);
        if (from > to) {
            return walk;
        }
        chain.forEach(from, to, event -> {
            if (walk.failPos != null) {
                return;   // nothing after a break is trusted
            }
            walk.rows++;
            long expectedPos = walk.lastGood + 1;
            if (event.chainPos() != expectedPos) {
                walk.fail(expectedPos, MismatchKind.GAP);
            } else if (!MessageDigest.isEqual(event.prevHash(), walk.expectedPrev)) {
                walk.fail(event.chainPos(), MismatchKind.PREV_HASH);
            } else if (!MessageDigest.isEqual(event.hash(), event.computeHash())) {
                walk.fail(event.chainPos(), MismatchKind.CONTENT_HASH);
            } else {
                walk.lastGood = event.chainPos();
                walk.expectedPrev = event.hash();
                for (CheckpointEvidence e : byPos.getOrDefault(event.chainPos(), List.of())) {
                    matches.put(e, MessageDigest.isEqual(e.checkpoint().headHash(), event.hash()));
                }
            }
        });
        if (walk.failPos == null && walk.lastGood < to) {
            walk.fail(walk.lastGood + 1, MismatchKind.GAP);   // rows missing at the end of the range
        }
        return walk;
    }

    private static ChainVerification conclude(long from, long to, long head, boolean full, Walk walk,
                                              List<CheckpointEvidence> evidence,
                                              Map<CheckpointEvidence, Boolean> matches) {
        Long failPos = walk.failPos;
        MismatchKind failKind = walk.failKind;
        List<CheckpointCheck> checks = new ArrayList<>();
        for (CheckpointEvidence e : evidence) {
            Boolean match = matches.get(e);
            MismatchKind problem = null;
            if (!e.readable()) {
                problem = MismatchKind.CHECKPOINT_UNREADABLE;
            } else if (!e.signatureValid()) {
                problem = MismatchKind.CHECKPOINT_SIGNATURE;
            } else if (full && e.chainPos() > head) {
                match = false;
                problem = MismatchKind.TRUNCATED;
            } else if (Boolean.FALSE.equals(match)) {
                problem = MismatchKind.CHECKPOINT_MISMATCH;
            }
            if (problem != null && (failPos == null || e.chainPos() < failPos)) {
                failPos = e.chainPos();
                failKind = problem;
            }
            checks.add(new CheckpointCheck(e.id(), e.chainPos(), e.inDatabase(), e.inFile(), e.trusted(), match));
        }
        long checkedTo = walk.failPos == null ? Math.max(to, from - 1) : walk.lastGood;
        return new ChainVerification(failPos == null, from, checkedTo, walk.rows, failPos, failKind, checks);
    }

    /** Progress of a walk: last position that verified, and the first break. */
    private static final class Walk {
        long lastGood;
        byte[] expectedPrev;
        long rows;
        Long failPos;
        MismatchKind failKind;

        Walk(long lastGood, byte[] expectedPrev) {
            this.lastGood = lastGood;
            this.expectedPrev = expectedPrev;
        }

        static Walk brokenAt(long pos, MismatchKind kind) {
            return brokenAt(pos - 1, kind, pos);
        }

        static Walk brokenAt(long lastGood, MismatchKind kind, long pos) {
            Walk w = new Walk(lastGood, null);
            w.fail(pos, kind);
            return w;
        }

        void fail(long pos, MismatchKind kind) {
            failPos = pos;
            failKind = kind;
        }
    }
}
