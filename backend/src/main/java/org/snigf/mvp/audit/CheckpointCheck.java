// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.UUID;

/**
 * The result of checking one checkpoint against the recomputed chain.
 *
 * @param id             checkpoint identifier (null for an unreadable file)
 * @param chainPos       position it covers (0 for an unreadable file)
 * @param inDatabase     a copy exists in the {@code audit_checkpoint} table
 * @param inFile         a copy exists in the checkpoint directory
 * @param signatureValid every copy verifies with the checkpoint public keys, and the
 *                       copies agree
 * @param matches        the recomputed chain hash equals the signed head; null when the
 *                       position was outside the verified range
 */
public record CheckpointCheck(UUID id, long chainPos, boolean inDatabase, boolean inFile,
                              boolean signatureValid, Boolean matches) {
}
