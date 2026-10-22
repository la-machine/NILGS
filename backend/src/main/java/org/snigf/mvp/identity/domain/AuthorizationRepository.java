// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Role definitions, role change requests and special grants (V7). Changes are atomic
 * statements; the database enforces four-eyes, system-role immutability, delegability and
 * use limits again.
 */
public interface AuthorizationRepository {

    // --- Roles ----------------------------------------------------------------------------

    List<RoleDefinition> roles();

    Optional<RoleDefinition> role(String code);

    /** Accounts that currently hold the role, at any office. */
    List<UUID> activeHolders(String roleCode);

    // --- Role change requests --------------------------------------------------------------

    /** The role already has an open request. */
    class OpenRequestExists extends RuntimeException {
        public OpenRequestExists() {
            super("an open request exists for this role");
        }
    }

    /** @throws OpenRequestExists if the role already has a pending request */
    void createRoleRequest(RoleChangeRequest request);

    Optional<RoleChangeRequest> roleRequest(UUID id);

    List<RoleChangeRequest> roleRequests(RoleChangeRequest.Status status, int limit);

    /**
     * Records the decision atomically; false if the request was no longer pending. On
     * approval the database applies the change in the same statement (V8): the application
     * cannot write role definitions itself.
     */
    boolean decideRoleRequest(UUID id, RoleChangeRequest.Status decision, UUID decidedBy, String reason, Instant at);

    // --- Special grants --------------------------------------------------------------------

    void createGrant(SpecialGrant grant);

    Optional<SpecialGrant> grant(UUID id);

    /** Grants of a user (or of everyone when null), with a status (or any when null), newest first. */
    List<SpecialGrant> grants(UUID userId, SpecialGrant.Status status, int limit);

    /** Approved grants of a user that are in force at {@code now}. */
    List<SpecialGrant> grantsInForce(UUID userId, Instant now);

    boolean decideGrant(UUID id, SpecialGrant.Status decision, UUID decidedBy, String reason, Instant at);

    boolean revokeGrant(UUID id, UUID revokedBy, String reason, Instant at);

    /**
     * Takes one use atomically, if the grant is approved, not expired and not used up.
     * Returns false otherwise: the action must then be refused.
     */
    boolean reserveUse(UUID grantId, Instant now);

    /** Gives back a use taken for an action that then failed. */
    void releaseUse(UUID grantId);

    /** Office id by code. */
    Optional<UUID> officeId(String officeCode);
}
