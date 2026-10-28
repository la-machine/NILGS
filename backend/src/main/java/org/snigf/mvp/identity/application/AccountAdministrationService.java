// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.domain.AuthorizationRepository;
import org.snigf.mvp.identity.domain.RoleDefinition;
import org.snigf.mvp.identity.domain.RoleAssignmentRepository;
import org.snigf.mvp.identity.domain.RoleAssignmentRequest;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the system administrator (Slice 1 design §5 and §6, C6c; ADR-026): list
 * accounts, request, approve or reject office-scoped roles (four eyes), revoke them. The
 * reset of a lost second factor is {@link SecondFactorResetService} (ADR-028).
 *
 * <p>Separation of duties: nobody grants or revokes their own roles or resets their own
 * second factor. The service refuses first (and audits the attempt as DENIED); the
 * database refuses again for grants and revocations ({@code ck_no_self_grant},
 * {@code ck_no_self_revoke}). Back-office roles go to staff accounts only, never to a
 * self-registered applicant, and never so that one person holds administration and audit
 * together (ADR-025). Every change carries a written reason and is audited.
 */
@Service
public class AccountAdministrationService {

    /** A refused administrative action, with its stable code and kind. */
    public static class Refused extends RuntimeException {

        public enum Kind { FORBIDDEN, NOT_FOUND, CONFLICT, INVALID }

        private final String code;
        private final Kind kind;

        public Refused(Kind kind, String code) {
            super(code);
            this.kind = kind;
            this.code = code;
        }

        public String code() {
            return code;
        }

        public Kind kind() {
            return kind;
        }
    }

    static final String ENTITY_ASSIGNMENT = "ROLE_ASSIGNMENT";
    private static final Logger LOG = LoggerFactory.getLogger(AccountAdministrationService.class);
    private static final int PAGE_SIZE = 50;

    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final AuditRecorder audit;
    private final Clock clock;
    private final AuthorizationRepository authorization;
    private final AuthorizationService effective;
    private final Duration requestTimeToLive;

    public AccountAdministrationService(UserRepository users, RoleAssignmentRepository assignments,
                                        AuditRecorder audit,
                                        Clock clock, AuthorizationRepository authorization,
                                        AuthorizationService effective, IdentityProperties properties) {
        this.users = users;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
        this.authorization = authorization;
        this.effective = effective;
        this.requestTimeToLive = properties.administration().requestTimeToLive();
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentRepository.AccountSummary> accounts(String query, int page) {
        String text = query == null ? "" : query.strip();
        if (text.length() > 100) {
            throw new Refused(Refused.Kind.INVALID, "QUERY_TOO_LONG");
        }
        return assignments.search(text, PAGE_SIZE, Math.max(page, 0) * PAGE_SIZE, clock.instant());
    }

    /**
     * Requests a role for a staff member at an office (ADR-026). Nothing is granted yet: a
     * second administrator approves, and the database then creates the assignment.
     */
    @Transactional
    public UUID requestRole(AuthenticatedUser admin, UUID targetId, String roleName, String officeCode, String reason) {
        requireReason(reason, 3);
        RoleDefinition role = assignable(roleName);
        if (admin.userId().equals(targetId)) {
            denied(AuditAction.ROLE_ASSIGNMENT_REQUESTED, targetId, "self-assignment refused (C6c)");
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_ASSIGNMENT");
        }
        staff(targetId);
        UUID officeId = assignments.officeId(officeCode)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "OFFICE_NOT_FOUND"));
        if (assignments.active(targetId, role.code(), officeId)) {
            throw new Refused(Refused.Kind.CONFLICT, "ROLE_ALREADY_ACTIVE");
        }
        if (effective.wouldConflict(targetId, List.of(role.code()), List.of())) {
            throw new Refused(Refused.Kind.CONFLICT, "SOD_CONFLICT");
        }
        UUID id = UuidV7.next();
        try {
            assignments.createRequest(id, targetId, role.code(), officeId, admin.userId(), reason.strip());
        } catch (RoleAssignmentRepository.OpenRequestExists e) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_ALREADY_OPEN");
        }
        audit.record(AuditEvent.of(AuditAction.ROLE_ASSIGNMENT_REQUESTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ASSIGNMENT, id).withReason(reason.strip())
                .withDetails(assignmentDetails(targetId, role.code(), officeCode)));
        return id;
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentRequest> assignmentRequests(RoleAssignmentRequest.Status status) {
        return assignments.requests(status, 200);
    }

    /**
     * Approves a role request. Refused to the requester and to the beneficiary (four eyes),
     * after {@code SNIGF_ADMIN_REQUEST_TTL}, and when the person would then hold
     * administration and audit together. The database checks all of it again, including
     * that the approver holds {@code admin.assignment.approve}.
     */
    @Transactional
    public void approveAssignment(AuthenticatedUser admin, UUID requestId, String reason) {
        requireReason(reason, 3);
        RoleAssignmentRequest request = pendingAssignment(requestId);
        if (request.requestedBy().equals(admin.userId()) || request.userId().equals(admin.userId())) {
            audit.recordIndependently(AuditEvent.of(AuditAction.ROLE_GRANTED, AuditOutcome.DENIED)
                    .withEntity(ENTITY_ASSIGNMENT, requestId)
                    .withReason("four-eyes: the requester or the beneficiary cannot approve"));
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        if (request.requestedAt().plus(requestTimeToLive).isBefore(clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_EXPIRED");
        }
        assignable(request.role());
        if (effective.wouldConflict(request.userId(), List.of(request.role()), List.of())) {
            throw new Refused(Refused.Kind.CONFLICT, "SOD_CONFLICT");
        }
        try {
            if (!assignments.decide(requestId, RoleAssignmentRequest.Status.APPROVED, admin.userId(), reason.strip())) {
                throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
            }
        } catch (RoleAssignmentRepository.AlreadyActive e) {
            throw new Refused(Refused.Kind.CONFLICT, "ROLE_ALREADY_ACTIVE");
        } catch (DataAccessException e) {
            throw refusedByDatabase(e);
        }
        Map<String, Object> details = assignmentDetails(request.userId(), request.role(), request.officeCode());
        details.put("requestedBy", request.requestedBy());
        audit.record(AuditEvent.of(AuditAction.ROLE_GRANTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ASSIGNMENT, requestId).withReason(reason.strip()).withDetails(details));
    }

    @Transactional
    public void rejectAssignment(AuthenticatedUser admin, UUID requestId, String reason) {
        requireReason(reason, 3);
        RoleAssignmentRequest request = pendingAssignment(requestId);
        if (request.userId().equals(admin.userId())) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        try {
            if (!assignments.decide(requestId, RoleAssignmentRequest.Status.REJECTED, admin.userId(), reason.strip())) {
                throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
            }
        } catch (DataAccessException e) {
            throw refusedByDatabase(e);
        }
        audit.record(AuditEvent.of(AuditAction.ROLE_ASSIGNMENT_REJECTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ASSIGNMENT, requestId).withReason(reason.strip())
                .withDetails(assignmentDetails(request.userId(), request.role(), request.officeCode())));
    }

    private RoleAssignmentRequest pendingAssignment(UUID requestId) {
        RoleAssignmentRequest request = assignments.request(requestId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (request.status() != RoleAssignmentRequest.Status.PENDING) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        return request;
    }

    private RoleDefinition assignable(String roleName) {
        return authorization.role(roleName == null ? "" : roleName)
                .filter(r -> r.active() && r.assignable())
                .orElseThrow(() -> new Refused(Refused.Kind.INVALID, "ROLE_NOT_ASSIGNABLE"));
    }

    private static Map<String, Object> assignmentDetails(UUID userId, String role, String officeCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", userId);
        details.put("role", role);
        details.put("office", officeCode);
        return details;
    }

    /**
     * The database refused what the service had allowed: a race (another decision in
     * between) or an attempt the service did not foresee. Logged, never hidden.
     */
    static Refused refusedByDatabase(DataAccessException e) {
        LOG.warn("Administrative change refused by the database: {}", e.getMostSpecificCause().getMessage());
        return new Refused(Refused.Kind.CONFLICT, "REFUSED_BY_DATABASE");
    }

    @Transactional
    public void revokeRole(AuthenticatedUser admin, UUID targetId, UUID assignmentId, String reason) {
        requireReason(reason, 3);
        if (admin.userId().equals(targetId)) {
            denied(AuditAction.ROLE_REVOKED, targetId, "self-revocation refused (C6c)");
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_ASSIGNMENT");
        }
        RoleAssignmentRepository.Assignment assignment = assignments.find(assignmentId)
                .filter(a -> a.userId().equals(targetId))
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "ASSIGNMENT_NOT_FOUND"));
        try {
            if (!assignment.active() || !assignments.revoke(assignmentId, targetId, admin.userId(), reason.strip(),
                    clock.instant())) {
                throw new Refused(Refused.Kind.CONFLICT, "ASSIGNMENT_ALREADY_REVOKED");
            }
        } catch (DataAccessException e) {
            throw refusedByDatabase(e);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", targetId);
        details.put("role", assignment.role());
        details.put("office", assignment.officeCode() == null ? "" : assignment.officeCode());
        audit.record(AuditEvent.of(AuditAction.ROLE_REVOKED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ASSIGNMENT, assignmentId).withReason(reason.strip()).withDetails(details));
    }

    private UserAccount staff(UUID targetId) {
        UserAccount target = users.findById(targetId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "USER_NOT_FOUND"));
        if (target.accountType() != AccountType.STAFF) {
            throw new Refused(Refused.Kind.INVALID, "ACCOUNT_NOT_STAFF");
        }
        return target;
    }

    private static void requireReason(String reason, int min) {
        if (reason == null || reason.strip().length() < min || reason.length() > 500
                || reason.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
    }

    private void denied(AuditAction action, UUID targetId, String why) {
        audit.recordIndependently(AuditEvent.of(action, AuditOutcome.DENIED)
                .withEntity(RegistrationService.ENTITY_USER, targetId).withReason(why));
    }
}
