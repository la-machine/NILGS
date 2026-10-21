// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

/** Kind of account, as in {@code app_user.account_type}. */
public enum AccountType {
    /** Self-registered member of the public; no second factor in slice 1, no back-office access. */
    APPLICANT,
    /** Back-office account; must complete a second factor before any business call (ADR-016). */
    STAFF
}
