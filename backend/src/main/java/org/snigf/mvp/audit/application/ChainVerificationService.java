// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.util.List;

import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.domain.AuditEventRepository;
import org.snigf.mvp.audit.domain.ChainVerifier;
import org.snigf.mvp.audit.domain.CheckpointEvidence;
import org.snigf.mvp.audit.domain.CheckpointRepository;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.SigningKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the domain {@link ChainVerifier} on one consistent snapshot: a read-only REPEATABLE
 * READ transaction, so events and database checkpoints come from the same instant. The
 * evidence merges the database and archive copies, each checked with the checkpoint key.
 */
@Service
public class ChainVerificationService {

    private final ChainVerifier verifier;
    private final CheckpointRepository checkpoints;
    private final CheckpointArchive archive;
    private final KeyService keys;
    private final TransactionTemplate snapshot;

    public ChainVerificationService(AuditEventRepository events, CheckpointRepository checkpoints,
                                    CheckpointArchive archive, KeyService keys,
                                    PlatformTransactionManager transactions) {
        this.verifier = new ChainVerifier(events);
        this.checkpoints = checkpoints;
        this.archive = archive;
        this.keys = keys;
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ChainVerification verify(Long fromPos, Long toPos) {
        return snapshot.execute(tx -> verifier.verify(fromPos, toPos, evidence()));
    }

    /** Joins the caller's transaction when there is one (checkpoint creation holds its lock). */
    public ChainVerification verifySinceLastCheckpoint() {
        return snapshot.execute(tx -> verifier.verifySinceLastCheckpoint(evidence()));
    }

    private List<CheckpointEvidence> evidence() {
        CheckpointArchive.Contents archived = archive.readAll();
        return CheckpointEvidence.merge(checkpoints.all(), archived.checkpoints(), archived.unreadable(),
                c -> keys.verify(SigningKey.AUDIT_CHECKPOINT, c.payload(), c.signature()));
    }
}
