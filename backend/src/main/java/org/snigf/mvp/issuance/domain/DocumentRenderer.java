// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Draws an issued document from its template, and reads back what a presented PDF claims. The
 * drawing carries the number in its metadata and in a QR code that points to the verification
 * page; neither proves anything by itself.
 */
public interface DocumentRenderer {

    /**
     * @param printed   the facts the template prints
     * @param verifyUrl the public verification address the QR code points to (the number, nothing personal)
     */
    byte[] render(DocumentType type, String number, Map<String, String> printed, Instant issuedAt, String verifyUrl);

    /** What a presented file says about itself; never throws on malformed input. */
    Verification.Claim read(byte[] presented);
}
