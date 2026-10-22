// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.AccountAdministrationService.Refused;
import org.snigf.mvp.identity.domain.AuthorizationRepository;
import org.snigf.mvp.identity.domain.RoleChangeRequest;
import org.snigf.mvp.identity.domain.RoleDefinition;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom roles defined by administrators (ADR-025): one administrator requests the
 * creation, change or retirement of a role; another approves or rejects it.
 *
 * <ul>
 *   <li>Baseline (system) roles never change.</li>
 *   <li>A role combines catalogue permissions only, each with a scope the catalogue allows,
 *       and never administration with audit (separation of duties).</li>
 *   <li>The approver is not the requester, does not hold the role being changed (no one
 *       widens their own rights), and the change must not give any current holder
 *       administration and audit together.</li>
 *   <li>A role still held cannot be retired. Requests expire after
 *       {@code SNIGF_ADMIN_REQUEST_TTL}. Everything is audited.</li>
 * </ul>
 */
@Service
public class RoleAdministrationService {

    static final String ENTITY_ROLE = "ROLE_DEFINITION";

    private final AuthorizationRepository authorization;
    private final AuthorizationService effective;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration requestTimeToLive;

    public RoleAdministrationService(AuthorizationRepository authorization, AuthorizationService effective,
                                     AuditRecorder audit, Clock clock, IdentityProperties properties) {
        this.authorization = authorization;
        this.effective = effective;
        this.audit = audit;
        this.clock = clock;
        this.requestTimeToLive = properties.administration().requestTimeToLive();
    }

    @Transactional(readOnly = true)
    public List<RoleDefinition> roles() {
        return authorization.roles();
    }

    @Transactional(readOnly = true)
    public List<RoleChangeRequest> requests(RoleChangeRequest.Status status) {
        return authorization.roleRequests(status, 200);
    }

    @Transactional
    public UUID request(AuthenticatedUser admin, RoleChangeRequest.Kind kind, String roleCode,
                        RoleChangeRequest.Proposal proposal, String reason) {
        requireReason(reason);
        var existing = authorization.role(roleCode);
        switch (kind) {
            case CREATE -> {
                if (existing.isPresent()) {
                    throw new Refused(Refused.Kind.CONFLICT, "ROLE_EXISTS");
                }
                validate(roleCode, proposal);
            }
            case UPDATE -> {
                RoleDefinition role = custom(existing, roleCode);
                if (!role.active()) {
                    throw new Refused(Refused.Kind.CONFLICT, "ROLE_RETIRED");
                }
                validate(roleCode, proposal);
            }
            case RETIRE -> {
                RoleDefinition role = custom(existing, roleCode);
                if (!role.active()) {
                    throw new Refused(Refused.Kind.CONFLICT, "ROLE_RETIRED");
                }
                proposal = new RoleChangeRequest.Proposal(role.nameFr(), role.nameEn(), role.description(), Map.of());
            }
        }
        // Stored as the database will apply it, without the application touching it again.
        proposal = new RoleChangeRequest.Proposal(proposal.nameFr().strip(), proposal.nameEn().strip(),
                proposal.description() == null || proposal.description().isBlank() ? null : proposal.description().strip(),
                proposal.permissions());
        UUID id = UuidV7.next();
        try {
            authorization.createRoleRequest(new RoleChangeRequest(id, kind, roleCode, proposal, reason.strip(),
                    admin.userId(), clock.instant(), RoleChangeRequest.Status.PENDING, null, null, null));
        } catch (AuthorizationRepository.OpenRequestExists e) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_ALREADY_OPEN");
        }
        audit.record(AuditEvent.of(AuditAction.ROLE_DEFINITION_REQUESTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ROLE, id).withEntityRef(roleCode).withReason(reason.strip())
                .withDetails(details(kind, proposal)));
        return id;
    }

    @Transactional
    public void approve(AuthenticatedUser admin, UUID requestId, String reason) {
        requireDecisionReason(reason);
        RoleChangeRequest request = pending(requestId);
        if (request.requestedBy().equals(admin.userId())) {
            denied(request, "four-eyes: the requester cannot approve");
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        if (admin.roles().contains(request.roleCode())) {
            denied(request, "the approver holds the role being changed");
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_BENEFIT");
        }
        if (request.requestedAt().plus(requestTimeToLive).isBefore(clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_EXPIRED");
        }
        RoleChangeRequest.Proposal p = request.proposal();
        // Checked here for a clear answer; the database applies the change on approval and
        // checks it all again (ADR-026).
        switch (request.kind()) {
            case CREATE -> {
                if (authorization.role(request.roleCode()).isPresent()) {
                    throw new Refused(Refused.Kind.CONFLICT, "ROLE_EXISTS");
                }
                validate(request.roleCode(), p);
            }
            case UPDATE -> {
                custom(authorization.role(request.roleCode()), request.roleCode());
                validate(request.roleCode(), p);
                for (UUID holder : authorization.activeHolders(request.roleCode())) {
                    if (effective.wouldConflict(holder, List.of(), List.copyOf(p.permissions().keySet()))) {
                        throw new Refused(Refused.Kind.CONFLICT, "SOD_CONFLICT");
                    }
                }
            }
            case RETIRE -> {
                custom(authorization.role(request.roleCode()), request.roleCode());
                if (!authorization.activeHolders(request.roleCode()).isEmpty()) {
                    throw new Refused(Refused.Kind.CONFLICT, "ROLE_IN_USE");
                }
            }
        }
        try {
            if (!authorization.decideRoleRequest(requestId, RoleChangeRequest.Status.APPROVED, admin.userId(),
                    reason.strip(), clock.instant())) {
                throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
            }
        } catch (DataAccessException e) {
            throw AccountAdministrationService.refusedByDatabase(e);
        }
        audit.record(AuditEvent.of(AuditAction.ROLE_DEFINITION_APPROVED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ROLE, requestId).withEntityRef(request.roleCode()).withReason(reason.strip())
                .withDetails(details(request.kind(), p)));
    }

    @Transactional
    public void reject(AuthenticatedUser admin, UUID requestId, String reason) {
        requireDecisionReason(reason);
        RoleChangeRequest request = pending(requestId);
        if (!authorization.decideRoleRequest(requestId, RoleChangeRequest.Status.REJECTED, admin.userId(),
                reason.strip(), clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        audit.record(AuditEvent.of(AuditAction.ROLE_DEFINITION_REJECTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_ROLE, requestId).withEntityRef(request.roleCode()).withReason(reason.strip()));
    }

    private RoleChangeRequest pending(UUID requestId) {
        RoleChangeRequest request = authorization.roleRequest(requestId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (request.status() != RoleChangeRequest.Status.PENDING) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        return request;
    }

    private static RoleDefinition custom(java.util.Optional<RoleDefinition> role, String code) {
        RoleDefinition found = role.orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "ROLE_NOT_FOUND"));
        if (found.system()) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SYSTEM_ROLE_IMMUTABLE");
        }
        return found;
    }

    private static void validate(String code, RoleChangeRequest.Proposal p) {
        List<String> problems = RoleDefinition.problems(code, p.nameFr(), p.nameEn(), p.description(), p.permissions());
        if (!problems.isEmpty()) {
            throw new Refused(problems.contains("SOD_CONFLICT") ? Refused.Kind.CONFLICT : Refused.Kind.INVALID,
                    problems.getFirst());
        }
    }

    private void denied(RoleChangeRequest request, String why) {
        audit.recordIndependently(AuditEvent.of(AuditAction.ROLE_DEFINITION_APPROVED, AuditOutcome.DENIED)
                .withEntity(ENTITY_ROLE, request.id()).withEntityRef(request.roleCode()).withReason(why));
    }

    private static Map<String, Object> details(RoleChangeRequest.Kind kind, RoleChangeRequest.Proposal p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        Map<String, Object> permissions = new LinkedHashMap<>();
        p.permissions().forEach((code, scope) -> permissions.put(code, scope.name()));
        details.put("permissions", permissions);
        return details;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.strip().length() < 10 || reason.length() > 500) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
    }

    private static void requireDecisionReason(String reason) {
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
    }
}
