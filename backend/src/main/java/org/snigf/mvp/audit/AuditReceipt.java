// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.UUID;

/**
 * Proof that an event was appended.
 *
 * @param id       the event's identifier
 * @param chainPos its position in the chain (final once the transaction commits)
 */
public record AuditReceipt(UUID id, long chainPos) {
}
