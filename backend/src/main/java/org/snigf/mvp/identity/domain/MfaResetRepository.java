// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Second-factor reset requests (V10). The database checks again who may ask and who may
 * confirm, and performs the reset itself when a request is confirmed.
 */
public interface MfaResetRepository {

    /** The person already has an open reset request. */
    class OpenRequestExists extends RuntimeException {
        public OpenRequestExists() {
            super("an open reset request exists");
        }
    }

    /** @throws OpenRequestExists if the person already has a pending request */
    void create(UUID id, UUID userId, String reason, UUID requestedBy);

    Optional<MfaResetRequest> find(UUID id);

    /** Requests with a status (or any when null), newest first. */
    List<MfaResetRequest> list(MfaResetRequest.Status status, int limit);

    /**
     * Records the decision atomically; a confirmation makes the database remove every key,
     * invalidate the unused recovery codes and clear the enrolment in the same statement.
     * False if the request was no longer pending.
     */
    boolean decide(UUID id, MfaResetRequest.Status decision, UUID decidedBy, String identityCheck, String reason);
}
