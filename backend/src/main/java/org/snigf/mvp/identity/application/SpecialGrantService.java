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
import org.snigf.mvp.identity.application.AccountAdministrationService.Refused;
import org.snigf.mvp.identity.domain.AuthorizationRepository;
import org.snigf.mvp.identity.domain.SpecialGrant;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.snigf.mvp.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Special grants (ADR-025): one permission to one staff member for a task, requested by one
 * administrator and approved by another, neither of them the beneficiary.
 *
 * <ul>
 *   <li>Only delegable catalogue permissions (never administration), everywhere or at one
 *       office, as the catalogue's scopes allow.</li>
 *   <li>Limits: an end date, a maximum number of uses, both, or neither (permanent).</li>
 *   <li>Approval refuses grants that would give the person administration and audit
 *       together, and requests older than {@code SNIGF_ADMIN_REQUEST_TTL}.</li>
 *   <li>Each use is taken atomically before the action and given back if the action fails;
 *       every use is audited ({@code PERMISSION_GRANT_USED}).</li>
 * </ul>
 */
@Service
public class SpecialGrantService {

    static final String ENTITY_GRANT = "PERMISSION_GRANT";

    private final AuthorizationRepository authorization;
    private final AuthorizationService effective;
    private final UserRepository users;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration requestTimeToLive;
    private final TransactionTemplate writing;

    public SpecialGrantService(AuthorizationRepository authorization, AuthorizationService effective, UserRepository users,
                               AuditRecorder audit, Clock clock, IdentityProperties properties,
                               org.springframework.transaction.PlatformTransactionManager transactions) {
        this.authorization = authorization;
        this.effective = effective;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.requestTimeToLive = properties.administration().requestTimeToLive();
        this.writing = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    public List<SpecialGrant> grants(UUID userId, SpecialGrant.Status status) {
        return authorization.grants(userId, status, 200);
    }

    @Transactional
    public UUID request(AuthenticatedUser admin, UUID userId, String permissionCode, String officeCode, String reason,
                        Instant validUntil, Integer maxUses) {
        if (reason == null || reason.strip().length() < 10 || reason.length() > 500) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
        if (admin.userId().equals(userId)) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_ASSIGNMENT");
        }
        Permission permission = Permission.ofCode(permissionCode)
                .orElseThrow(() -> new Refused(Refused.Kind.INVALID, "PERMISSION_UNKNOWN"));
        if (!permission.delegable()) {
            throw new Refused(Refused.Kind.INVALID, "PERMISSION_NOT_DELEGABLE");
        }
        String office = officeCode == null || officeCode.isBlank() ? null : officeCode.strip();
        Permission.Scope scope = office == null ? Permission.Scope.ANY : Permission.Scope.OFFICE;
        if (!permission.scopes().contains(scope)) {
            throw new Refused(Refused.Kind.INVALID, "SCOPE_NOT_ALLOWED");
        }
        if (office != null && authorization.officeId(office).isEmpty()) {
            throw new Refused(Refused.Kind.NOT_FOUND, "OFFICE_NOT_FOUND");
        }
        Instant now = clock.instant();
        if (validUntil != null && !validUntil.isAfter(now)) {
            throw new Refused(Refused.Kind.INVALID, "VALID_UNTIL_IN_PAST");
        }
        if (maxUses != null && (maxUses < 1 || maxUses > 100_000)) {
            throw new Refused(Refused.Kind.INVALID, "MAX_USES_INVALID");
        }
        UserAccount target = users.findById(userId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "USER_NOT_FOUND"));
        if (target.accountType() != AccountType.STAFF) {
            throw new Refused(Refused.Kind.INVALID, "ACCOUNT_NOT_STAFF");
        }
        UUID id = UuidV7.next();
        authorization.createGrant(new SpecialGrant(id, userId, permission.code(), office, reason.strip(), validUntil,
                maxUses, 0, SpecialGrant.Status.PENDING, admin.userId(), now, null, null, null, null, null, null));
        audit.record(AuditEvent.of(AuditAction.PERMISSION_GRANT_REQUESTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_GRANT, id).withReason(reason.strip())
                .withDetails(details(userId, permission.code(), office, validUntil, maxUses)));
        return id;
    }

    @Transactional
    public void approve(AuthenticatedUser admin, UUID grantId, String reason) {
        requireDecisionReason(reason);
        SpecialGrant grant = pending(grantId);
        if (grant.requestedBy().equals(admin.userId()) || grant.userId().equals(admin.userId())) {
            audit.recordIndependently(AuditEvent.of(AuditAction.PERMISSION_GRANT_APPROVED, AuditOutcome.DENIED)
                    .withEntity(ENTITY_GRANT, grantId).withReason("four-eyes: requester or beneficiary cannot approve"));
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        Instant now = clock.instant();
        if (grant.requestedAt().plus(requestTimeToLive).isBefore(now)
                || (grant.validUntil() != null && !grant.validUntil().isAfter(now))) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_EXPIRED");
        }
        if (effective.wouldConflict(grant.userId(), List.of(), List.of(grant.permission()))) {
            throw new Refused(Refused.Kind.CONFLICT, "SOD_CONFLICT");
        }
        if (!authorization.decideGrant(grantId, SpecialGrant.Status.APPROVED, admin.userId(), reason.strip(), now)) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        audit.record(AuditEvent.of(AuditAction.PERMISSION_GRANT_APPROVED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_GRANT, grantId).withReason(reason.strip())
                .withDetails(details(grant.userId(), grant.permission(), grant.officeCode(), grant.validUntil(),
                        grant.maxUses())));
    }

    @Transactional
    public void reject(AuthenticatedUser admin, UUID grantId, String reason) {
        requireDecisionReason(reason);
        SpecialGrant grant = pending(grantId);
        if (grant.userId().equals(admin.userId())) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        if (!authorization.decideGrant(grantId, SpecialGrant.Status.REJECTED, admin.userId(), reason.strip(),
                clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        audit.record(AuditEvent.of(AuditAction.PERMISSION_GRANT_REJECTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_GRANT, grantId).withReason(reason.strip()));
    }

    @Transactional
    public void revoke(AuthenticatedUser admin, UUID grantId, String reason) {
        requireDecisionReason(reason);
        SpecialGrant grant = authorization.grant(grantId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "GRANT_NOT_FOUND"));
        if (grant.userId().equals(admin.userId())) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_ASSIGNMENT");
        }
        if (!authorization.revokeGrant(grantId, admin.userId(), reason.strip(), clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "GRANT_NOT_ACTIVE");
        }
        audit.record(AuditEvent.of(AuditAction.PERMISSION_GRANT_REVOKED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_GRANT, grantId).withReason(reason.strip())
                .withDetails(Map.of("userId", grant.userId(), "permission", grant.permission(), "uses", grant.uses())));
    }

    // --- Uses (called by the access check and when the request completes) ------------------

    /** Takes one use before the action; false if the grant is no longer in force. */
    public boolean reserve(UUID grantId) {
        return authorization.reserveUse(grantId, clock.instant());
    }

    /** The action succeeded: the use stays taken, and is audited. */
    public void confirm(UUID grantId, String permission, String method, String path) {
        recordUse(grantId, permission, method, path, AuditOutcome.SUCCESS);
    }

    /** The action failed: the use is given back, and the attempt audited. */
    public void release(UUID grantId, String permission, String method, String path) {
        authorization.releaseUse(grantId);
        recordUse(grantId, permission, method, path, AuditOutcome.FAILED);
    }

    private void recordUse(UUID grantId, String permission, String method, String path, AuditOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("permission", permission);
        details.put("method", method);
        details.put("path", path.length() > 200 ? path.substring(0, 200) : path);
        AuditEvent event = AuditEvent.of(AuditAction.PERMISSION_GRANT_USED, outcome).withEntity(ENTITY_GRANT, grantId)
                .withDetails(details);
        if (outcome == AuditOutcome.SUCCESS) {
            writing.executeWithoutResult(tx -> audit.record(event));
        } else {
            audit.recordIndependently(event.withReason("the action failed; the use was given back"));
        }
    }

    private SpecialGrant pending(UUID grantId) {
        SpecialGrant grant = authorization.grant(grantId)
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "GRANT_NOT_FOUND"));
        if (grant.status() != SpecialGrant.Status.PENDING) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        return grant;
    }

    private static Map<String, Object> details(UUID userId, String permission, String office, Instant validUntil,
                                               Integer maxUses) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", userId);
        details.put("permission", permission);
        details.put("office", office == null ? "*" : office);
        details.put("validUntil", validUntil == null ? "none" : validUntil.toString());
        details.put("maxUses", maxUses == null ? "unlimited" : maxUses);
        return details;
    }

    private static void requireDecisionReason(String reason) {
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
    }
}
