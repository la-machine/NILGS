// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.MismatchKind;
import org.snigf.mvp.keys.Signature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-AC5 and C7 at the domain level: every way to alter history is detected at its
 * position, and a rewrite that keeps the chain consistent is caught by a signed checkpoint.
 */
class ChainVerifierTest {

    private final KeyPair key = ed25519();
    private final Predicate<Checkpoint> signedByUs = c -> verifies(c, key);

    @Test
    void anUntouchedChainVerifies() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);

        ChainVerification v = new ChainVerifier(chain).verify(null, null, List.of());

        assertThat(v.verified()).isTrue();
        assertThat(v.checkedFrom()).isEqualTo(1);
        assertThat(v.checkedTo()).isEqualTo(5);
        assertThat(v.rowsChecked()).isEqualTo(5);
        assertThat(v.firstMismatchPos()).isNull();
    }

    @Test
    void anEmptyChainVerifies() {
        ChainVerification v = new ChainVerifier(new InMemoryChain()).verify(null, null, List.of());

        assertThat(v.verified()).isTrue();
        assertThat(v.checkedTo()).isZero();
    }

    @Test
    void anEditedEventIsDetectedAtItsOwnPosition() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        chain.edit(3, "{\"n\": \"rewritten\"}");

        assertFailure(new ChainVerifier(chain).verify(null, null, List.of()), 3, MismatchKind.CONTENT_HASH);
    }

    @Test
    void anEditedEventWithARecomputedHashBreaksTheNextLink() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        chain.editAndRehash(3, "{\"n\": \"rewritten\"}");

        ChainVerification v = new ChainVerifier(chain).verify(null, null, List.of());

        assertFailure(v, 4, MismatchKind.PREV_HASH);
        assertThat(v.checkedTo()).isEqualTo(3);
    }

    @Test
    void aDeletedEventIsAGap() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        chain.delete(2);

        assertFailure(new ChainVerifier(chain).verify(null, null, List.of()), 2, MismatchKind.GAP);
    }

    @Test
    void aConsistentRewriteIsCaughtBySignedCheckpoints() {
        InMemoryChain chain = new InMemoryChain().withEvents(6);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 4));
        chain.rewriteFrom(2, "{\"n\": \"rewritten\"}");

        ChainVerification v = new ChainVerifier(chain).verify(null, null, evidence);

        assertThat(v.rowsChecked()).as("the chain alone is consistent again").isEqualTo(6);
        assertFailure(v, 4, MismatchKind.CHECKPOINT_MISMATCH);
        assertThat(v.checkpoints()).singleElement().satisfies(c -> assertThat(c.matches()).isFalse());
    }

    @Test
    void deletingTheNewestEventsIsCaughtByACheckpointBeyondTheEnd() {
        InMemoryChain chain = new InMemoryChain().withEvents(6);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 6));
        chain.delete(6);
        chain.delete(5);

        assertFailure(new ChainVerifier(chain).verify(null, null, evidence), 6, MismatchKind.TRUNCATED);
    }

    @Test
    void aForgedCheckpointIsRejectedNotTrusted() {
        InMemoryChain chain = new InMemoryChain().withEvents(4);
        Checkpoint forged = sign(chain, 3, ed25519());   // signed with another key

        ChainVerification v = new ChainVerifier(chain).verify(null, null, evidence(forged));

        assertFailure(v, 3, MismatchKind.CHECKPOINT_SIGNATURE);
        assertThat(v.checkpoints().getFirst().signatureValid()).isFalse();
    }

    @Test
    void databaseAndArchiveCopiesMustAgree() {
        InMemoryChain chain = new InMemoryChain().withEvents(4);
        Checkpoint genuine = checkpoint(chain, 3);
        Checkpoint altered = new Checkpoint(genuine.id(), genuine.chainPos(), new byte[32], genuine.createdAt(),
                genuine.signature());

        List<CheckpointEvidence> evidence = CheckpointEvidence.merge(List.of(altered), List.of(genuine), List.of(),
                signedByUs);

        assertThat(evidence).singleElement().satisfies(e -> {
            assertThat(e.inDatabase()).isTrue();
            assertThat(e.inFile()).isTrue();
            assertThat(e.trusted()).isFalse();
        });
        assertFailure(new ChainVerifier(chain).verify(null, null, evidence), 3, MismatchKind.CHECKPOINT_SIGNATURE);
    }

    @Test
    void anUnreadableArchiveFileIsReported() {
        InMemoryChain chain = new InMemoryChain().withEvents(4);
        List<CheckpointEvidence> evidence = CheckpointEvidence.merge(List.of(), List.of(),
                List.of(new UnreadableCheckpoint("checkpoint-2.json", 2, "JacksonException")), signedByUs);

        assertFailure(new ChainVerifier(chain).verify(null, null, evidence), 2, MismatchKind.CHECKPOINT_UNREADABLE);
    }

    @Test
    void theLowestProblemIsReportedFirst() {
        InMemoryChain chain = new InMemoryChain().withEvents(6);
        List<CheckpointEvidence> evidence = evidence(sign(chain, 5, ed25519()));
        chain.edit(2, "{\"n\": \"rewritten\"}");

        assertFailure(new ChainVerifier(chain).verify(null, null, evidence), 2, MismatchKind.CONTENT_HASH);
    }

    @Test
    void aRangeStartsFromTheStoredHashBeforeIt() {
        InMemoryChain chain = new InMemoryChain().withEvents(8);
        chain.edit(2, "{\"n\": \"outside the range\"}");

        ChainVerification v = new ChainVerifier(chain).verify(4L, 6L, List.of());

        assertThat(v.verified()).isTrue();
        assertThat(v.checkedFrom()).isEqualTo(4);
        assertThat(v.checkedTo()).isEqualTo(6);
        assertThat(v.rowsChecked()).isEqualTo(3);
    }

    @Test
    void aRangeDoesNotReportCheckpointsBeyondItsEndAsTruncation() {
        InMemoryChain chain = new InMemoryChain().withEvents(8);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 8));

        ChainVerification v = new ChainVerifier(chain).verify(1L, 4L, evidence);

        assertThat(v.verified()).isTrue();
        assertThat(v.checkpoints().getFirst().matches()).isNull();
    }

    @Test
    void sinceTheLastCheckpointStartsFromItsSignedHead() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 3));
        chain.add("{\"n\": \"after the checkpoint\"}");

        ChainVerification v = new ChainVerifier(chain).verifySinceLastCheckpoint(evidence);

        assertThat(v.verified()).isTrue();
        assertThat(v.checkedFrom()).isEqualTo(3);
        assertThat(v.rowsChecked()).as("only events after the checkpoint are recomputed").isEqualTo(3);
        assertThat(v.lastCheckpoint()).hasValueSatisfying(c -> assertThat(c.matches()).isTrue());
    }

    @Test
    void sinceTheLastCheckpointDetectsARewriteOfTheCheckpointedHead() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 3));
        chain.rewriteFrom(1, "{\"n\": \"rewritten\"}");

        assertFailure(new ChainVerifier(chain).verifySinceLastCheckpoint(evidence), 3, MismatchKind.CHECKPOINT_MISMATCH);
    }

    @Test
    void sinceTheLastCheckpointDetectsEditsAfterIt() {
        InMemoryChain chain = new InMemoryChain().withEvents(6);
        List<CheckpointEvidence> evidence = evidence(checkpoint(chain, 3));
        chain.edit(5, "{\"n\": \"rewritten\"}");

        assertFailure(new ChainVerifier(chain).verifySinceLastCheckpoint(evidence), 5, MismatchKind.CONTENT_HASH);
    }

    @Test
    void sinceTheLastCheckpointIgnoresForgedAnchors() {
        InMemoryChain chain = new InMemoryChain().withEvents(5);
        chain.rewriteFrom(2, "{\"n\": \"rewritten\"}");
        // An attacker signs the rewritten head with a key of their own: it is no anchor.
        List<CheckpointEvidence> evidence = evidence(sign(chain, 5, ed25519()));

        assertFailure(new ChainVerifier(chain).verifySinceLastCheckpoint(evidence), 5, MismatchKind.CHECKPOINT_SIGNATURE);
    }

    private static void assertFailure(ChainVerification v, long pos, MismatchKind kind) {
        assertThat(v.verified()).isFalse();
        assertThat(v.firstMismatchPos()).isEqualTo(pos);
        assertThat(v.mismatchKind()).isEqualTo(kind);
    }

    private List<CheckpointEvidence> evidence(Checkpoint... checkpoints) {
        return CheckpointEvidence.merge(List.of(checkpoints), List.of(), List.of(), signedByUs);
    }

    private Checkpoint checkpoint(InMemoryChain chain, long pos) {
        return sign(chain, pos, key);
    }

    private static Checkpoint sign(InMemoryChain chain, long pos, KeyPair signer) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-21T09:00:00.000001Z");
        byte[] head = chain.hashAt(pos).orElseThrow();
        byte[] payload = org.snigf.mvp.audit.AuditEncoding.checkpointPayload(id, pos, head, at);
        try {
            java.security.Signature ed = java.security.Signature.getInstance("Ed25519");
            ed.initSign(signer.getPrivate());
            ed.update(payload);
            return new Checkpoint(id, pos, head, at, new Signature(1, ed.sign()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verifies(Checkpoint c, KeyPair key) {
        try {
            java.security.Signature ed = java.security.Signature.getInstance("Ed25519");
            ed.initVerify(key.getPublic());
            ed.update(c.payload());
            return ed.verify(c.signature().value());
        } catch (Exception e) {
            return false;
        }
    }

    private static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
