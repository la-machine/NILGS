// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A service request (MVP-02): owned by its applicant, who alone sees and edits it while it is a
 * draft. The reference, the parcel (resolved) and the office (the parcel's) exist only once it is
 * submitted; from then on, what was submitted never changes (V13).
 *
 * @param parcelReference the parcel as the applicant named it (optional in a draft)
 * @param officeCode      the parcel's office, from submission
 * @param version         optimistic lock
 */
public record Application(UUID id, String reference, UUID applicantId, String typeCode, UUID typeVersionId,
                          ApplicationState state, String parcelReference, String officeCode, Map<String, String> data,
                          Instant createdAt, Instant updatedAt, Instant submittedAt, long version) {

    public Application {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public boolean ownedBy(UUID user) {
        return applicantId.equals(user);
    }

    /**
     * One state change, as the database recorded it; for staff. The applicant reads it through
     * {@link Tracking}, without the actor's name or the reason.
     *
     * @param actorId null for the automatic check
     */
    public record Transition(ApplicationState from, ApplicationState to, UUID actorId, String actorName, String reason,
                             Instant at) {
    }
}
