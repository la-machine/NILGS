// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Alerts as the database raised them (V11), their resolution (V12), and the detection policy. */
public interface ConflictAlertRepository {

    /** The two compared versions and the intersection, in one UTM zone (metres), for the review map. */
    record Display(int srid, String a, String b, String intersection) {
    }

    /** The alerts the writing of this version raised. */
    List<ConflictAlert> raisedBy(UUID versionId);

    /** The alerts the writing of this version closed as superseded. */
    List<ConflictAlert> supersededBy(UUID versionId);

    /** Every alert involving the parcel with this reference, open ones first, newest first. */
    List<ConflictAlert> forParcel(String reference);

    /**
     * Alerts involving a parcel of this office, newest first; {@code status} and {@code type}
     * narrow it when given.
     */
    List<ConflictAlert> forOffice(String officeCode, ConflictAlert.Status status, ConflictAlert.Type type,
                                  int limit, int offset);

    Optional<ConflictAlert> find(UUID id);

    Optional<Display> display(UUID id);

    /**
     * Resolves an open alert if it is still at {@code expectedVersion}; returns false when it
     * changed meanwhile. The database's guard checks the same rules again (V12).
     */
    boolean resolve(UUID id, long expectedVersion, ConflictAlert.Resolution resolution, String note, UUID resolver);

    /** The parcel's id, for a reference that exists. */
    Optional<UUID> parcelId(String reference);

    ConflictPolicy policy();

    void applyPolicy(ConflictPolicy policy);
}
