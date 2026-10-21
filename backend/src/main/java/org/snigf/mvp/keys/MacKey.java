// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

/** HMAC-SHA-256 keys, one per purpose (ADR-018). */
public enum MacKey {
    /** Authenticates payment-provider callbacks. */
    PAYMENT_CALLBACK,
    /** Peppers passwords before hashing; never rotated automatically. */
    PASSWORD_PEPPER,
    /** Blind index for equality lookups on encrypted values; never rotated automatically. */
    BLIND_INDEX
}
