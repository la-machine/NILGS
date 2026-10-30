// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

/** Ed25519 signing keys, one per purpose (ADR-018). */
public enum SigningKey {
    /** Signs audit-chain checkpoints (ADR-007). */
    AUDIT_CHECKPOINT,
    /** Server-side signature of issued records, alongside the officer's own (ADR-019). */
    RECORD,
    /** Signs the manifest of every document SNIGF issues (ADR-039). */
    DOCUMENT_ISSUANCE
}
