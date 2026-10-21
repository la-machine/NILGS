// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

/** Result of the audited attempt. */
public enum AuditOutcome {
    SUCCESS,
    /** Refused by an access rule. */
    DENIED,
    /** Attempted and rejected or failed (validation, wrong credentials, error). */
    FAILED
}
