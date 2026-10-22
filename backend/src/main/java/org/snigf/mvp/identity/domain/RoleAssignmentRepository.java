// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.RoleGrant;

/**
 * Role assignments, their requests, and the account directory used by administrators. The
 * database keeps the rules: no self-grant or self-revoke, office scope, one active
 * assignment per (user, role, office), final revocation (V1); an assignment only through an
 * approved request, decided by someone else who holds the approval permission (V8).
 */
public interface RoleAssignmentRepository {

    /** The same role is already active for the user at that office. */
    class AlreadyActive extends RuntimeException {
        public AlreadyActive() {
            super("role already active");
        }
    }

    /** The same role, person and office already have an open request. */
    class OpenRequestExists extends RuntimeException {
        public OpenRequestExists() {
            super("an open request exists");
        }
    }

    /** An active or revoked assignment. */
    record Assignment(UUID id, UUID userId, String role, String officeCode, boolean active) {
    }

    /** An account as administrators see it. */
    record AccountSummary(UUID id, String email, String displayName, AccountType accountType, boolean enabled,
                          boolean secondFactorReady, boolean locked, List<AssignmentView> assignments) {
    }

    /** An active assignment, with the id needed to revoke it. */
    record AssignmentView(UUID id, String role, String officeCode) {

        public RoleGrant grant() {
            return new RoleGrant(role, officeCode);
        }
    }

    Optional<UUID> officeId(String officeCode);

    /** @throws OpenRequestExists if the same role, person and office already have an open request */
    void createRequest(UUID id, UUID userId, String role, UUID officeId, UUID requestedBy, String reason);

    Optional<RoleAssignmentRequest> request(UUID id);

    /** Whether the role is active for the person at that office. */
    boolean active(UUID userId, String role, UUID officeId);

    /** Requests with a status (or any when null), newest first. */
    List<RoleAssignmentRequest> requests(RoleAssignmentRequest.Status status, int limit);

    /**
     * Records the decision atomically; on approval the database creates the assignment (with
     * the request's id) in the same statement. False if the request was no longer pending.
     *
     * @throws AlreadyActive if the same role is already active at that office
     */
    boolean decide(UUID id, RoleAssignmentRequest.Status decision, UUID decidedBy, String reason);

    Optional<Assignment> find(UUID assignmentId);

    /** Revokes atomically; false if the assignment is not active for that user. */
    boolean revoke(UUID assignmentId, UUID userId, UUID revokedBy, String reason, Instant at);

    /** Accounts whose e-mail or name contains {@code text} (case-insensitive), newest first. */
    List<AccountSummary> search(String text, int limit, int offset, Instant now);
}
