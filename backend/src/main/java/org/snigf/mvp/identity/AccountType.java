// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

/** Kind of account, as in {@code app_user.account_type}. */
public enum AccountType {
    /** Self-registered member of the public; no second factor in slice 1, no back-office access. */
    APPLICANT,
    /** Back-office account; must complete a second factor before any business call (ADR-016). */
    STAFF,
    /**
     * External professional, notary or sworn surveyor (ADR-041): a second factor like staff, and no staff
     * right of any kind. Database permission functions for staff refuse it by construction.
     */
    PROFESSIONAL;

    /** Signs in with a password and security keys (ADR-016, ADR-041); applicants with a password only. */
    public boolean requiresSecondFactor() {
        return this == STAFF || this == PROFESSIONAL;
    }
}
