// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

/**
 * What the DEMO provider does with a charge (ADR-037). It exists only because the provider is a
 * simulator: the applicant picks it, it is stored with the request and audited.
 */
public enum Scenario {
    /** One success callback with the amount asked. */
    SUCCEED,
    /** One failure callback. */
    FAIL,
    /** No callback: the request stays pending until it expires. */
    NO_RESPONSE,
    /** The same success callback twice (same transaction id). */
    DUPLICATE,
    /** A success callback reporting another amount. */
    WRONG_AMOUNT,
    /** A success callback naming a reference nobody issued. */
    UNKNOWN_REFERENCE
}
