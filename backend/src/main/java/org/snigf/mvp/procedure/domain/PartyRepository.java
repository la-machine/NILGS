// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persons of a case (ADR-043, V35): read under row-level security ({@code case_party_readable}), written only through
 * V35's functions, which decide; a refusal is the database's PC001 with its reason.
 */
public interface PartyRepository {

    /** The persons, statements, mandates and files of the case, as the bound officer may read them; empty when they may not. */
    Optional<Parties.View> view(UUID applicationId);

    UUID addFile(UUID applicationId, String purpose, Parties.KeptFile file);

    UUID recordIdentity(UUID personId, Parties.NewIdentity identity, UUID sourceFile, UUID sourceVersion, UUID supersedes, String reason);

    UUID recordOwnership(UUID applicationId, List<Parties.NewShare> shares, UUID supersedes, String reason);

    UUID recordMandate(UUID representativeId, List<UUID> represents, String scope, UUID documentFile, UUID supersedes, String reason);

    UUID check(String targetKind, UUID targetId, String outcome, String note);

    UUID authoriseException(UUID identityVersionId, String policy, String reason);

    /** A counter file of this case, for its download. */
    Optional<Parties.File> file(UUID applicationId, UUID fileId);

    /** The case a person belongs to, if the bound officer reads it. */
    Optional<UUID> caseOfPerson(UUID personId);
}
