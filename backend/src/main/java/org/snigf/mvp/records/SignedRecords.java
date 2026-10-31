// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.records;

import java.util.UUID;

import org.snigf.mvp.identity.OfficerSignatures;

/**
 * Officer-signed records (ADR-019): the officer signs the canonical form with their security
 * key, then the system seals it with the {@code RECORD} key. If either fails, there is no record.
 */
public interface SignedRecords {

    /** Starts the officer's signature over {@code canonical}. */
    OfficerSignatures.Started begin(UUID officerId, byte[] canonical);

    /**
     * Finishes the officer's signature over {@code canonical} and seals the result. Throws
     * {@link OfficerSignatures.Refused} if the signature does not hold, and
     * {@link org.snigf.mvp.keys.KeyServiceException} if key custody is unavailable.
     */
    SignedRecord seal(String ceremonyId, UUID officerId, byte[] canonical, String responseJson);

    /** Verifies both signatures of a stored record, from the record alone and the public keys. */
    boolean verify(SignedRecord record);
}
