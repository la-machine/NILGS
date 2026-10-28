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
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.AccountAdministrationService.Refused;
import org.snigf.mvp.identity.domain.MfaResetRepository;
import org.snigf.mvp.identity.domain.MfaResetRequest;
import org.snigf.mvp.identity.domain.SecondFactorRepository;
import org.snigf.mvp.identity.domain.UserRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.snigf.mvp.shared.security.Permission;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supervised reset of a lost second factor (ADR-024, ADR-028), for a staff member who
 * lost every key and every recovery code:
 * <ol>
 *   <li>an administrator holding {@code admin.mfa.reset} requests it, with a reason;</li>
 *   <li>the person's supervisor, holding {@code identity.reset.confirm} at an office where the
 *       person holds a role, checks their identity out of band and confirms in the
 *       application, saying how ("in person, ID card checked"), or rejects it;</li>
 *   <li>the confirmation performs the reset in the database: every key removed, unused
 *       recovery codes invalidated, enrolment cleared; then every session ends.</li>
 * </ol>
 * Requester, confirmer and person are three different people; the database checks the same
 * rules again (V10). Everything is audited.
 */
@Service
public class SecondFactorResetService {

    static final String ENTITY_RESET = "MFA_RESET_REQUEST";

    private final MfaResetRepository resets;
    private final SecondFactorRepository factors;
    private final UserRepository users;
    private final SessionRevoker sessions;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration requestTimeToLive;

    public SecondFactorResetService(MfaResetRepository resets, SecondFactorRepository factors, UserRepository users,
                                    SessionRevoker sessions, AuditRecorder audit, Clock clock,
                                    IdentityProperties properties) {
        this.resets = resets;
        this.factors = factors;
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.requestTimeToLive = properties.administration().requestTimeToLive();
    }

    @Transactional
    public UUID request(AuthenticatedUser admin, UUID targetId, String reason) {
        if (reason == null || reason.strip().length() < 10 || reason.length() > 500
                || reason.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
        if (admin.userId().equals(targetId)) {
            audit.recordIndependently(AuditEvent.of(AuditAction.MFA_RESET_REQUESTED, AuditOutcome.DENIED)
                    .withEntity(RegistrationService.ENTITY_USER, targetId).withReason("self-reset refused (C6c)"));
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_SELF_ACTION");
        }
        var target = users.findById(targetId).orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "USER_NOT_FOUND"));
        if (target.accountType() != AccountType.STAFF) {
            throw new Refused(Refused.Kind.INVALID, "ACCOUNT_NOT_STAFF");
        }
        UUID id = UuidV7.next();
        try {
            resets.create(id, targetId, reason.strip(), admin.userId());
        } catch (MfaResetRepository.OpenRequestExists e) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_ALREADY_OPEN");
        }
        audit.record(AuditEvent.of(AuditAction.MFA_RESET_REQUESTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_RESET, id).withReason(reason.strip()).withDetails(Map.of("userId", targetId)));
        return id;
    }

    /** Every request, for administrators. */
    @Transactional(readOnly = true)
    public List<MfaResetRequest> requests(MfaResetRequest.Status status) {
        return resets.list(status, 200);
    }

    /** Pending requests the supervisor may decide: people with a role at one of their offices. */
    @Transactional(readOnly = true)
    public List<MfaResetRequest> toConfirm(AuthenticatedUser supervisor) {
        return resets.list(MfaResetRequest.Status.PENDING, 200).stream()
                .filter(r -> mayDecide(supervisor, r)).toList();
    }

    @Transactional
    public void confirm(AuthenticatedUser supervisor, UUID requestId, String identityCheck) {
        if (identityCheck == null || identityCheck.strip().length() < 3 || identityCheck.length() > 200
                || identityCheck.codePoints().anyMatch(Character::isISOControl)) {
            throw new Refused(Refused.Kind.INVALID, "IDENTITY_CHECK_REQUIRED");
        }
        MfaResetRequest request = decidable(supervisor, requestId, AuditAction.MFA_RESET);
        if (request.requestedAt().plus(requestTimeToLive).isBefore(clock.instant())) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_EXPIRED");
        }
        int keys = factors.keys(request.userId()).size();
        try {
            if (!resets.decide(requestId, MfaResetRequest.Status.CONFIRMED, supervisor.userId(), identityCheck.strip(), null)) {
                throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
            }
        } catch (DataAccessException e) {
            throw AccountAdministrationService.refusedByDatabase(e);
        }
        int revoked = sessions.revokeAll(request.userId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", request.userId());
        details.put("requestedBy", request.requestedBy());
        details.put("identityCheck", identityCheck.strip());
        details.put("authenticatorsRemoved", keys);
        details.put("sessionsRevoked", revoked);
        audit.record(AuditEvent.of(AuditAction.MFA_RESET, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_RESET, requestId).withReason(request.reason()).withDetails(details));
    }

    @Transactional
    public void reject(AuthenticatedUser supervisor, UUID requestId, String reason) {
        if (reason == null || reason.strip().length() < 3 || reason.length() > 500) {
            throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED");
        }
        MfaResetRequest request = decidable(supervisor, requestId, AuditAction.MFA_RESET_REJECTED);
        try {
            if (!resets.decide(requestId, MfaResetRequest.Status.REJECTED, supervisor.userId(), null, reason.strip())) {
                throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
            }
        } catch (DataAccessException e) {
            throw AccountAdministrationService.refusedByDatabase(e);
        }
        audit.record(AuditEvent.of(AuditAction.MFA_RESET_REJECTED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_RESET, requestId).withReason(reason.strip())
                .withDetails(Map.of("userId", request.userId())));
    }

    /**
     * A pending request this person may decide. A request outside their offices answers
     * "not found", so its existence is not revealed; requester and person never decide.
     */
    private MfaResetRequest decidable(AuthenticatedUser supervisor, UUID requestId, AuditAction action) {
        MfaResetRequest request = resets.find(requestId)
                .filter(r -> mayDecide(supervisor, r) || isParty(supervisor, r))
                .orElseThrow(() -> new Refused(Refused.Kind.NOT_FOUND, "REQUEST_NOT_FOUND"));
        if (isParty(supervisor, request)) {
            audit.recordIndependently(AuditEvent.of(action, AuditOutcome.DENIED).withEntity(ENTITY_RESET, requestId)
                    .withReason("four-eyes: the requester or the person cannot confirm"));
            throw new Refused(Refused.Kind.FORBIDDEN, "SOD_FOUR_EYES");
        }
        if (request.status() != MfaResetRequest.Status.PENDING) {
            throw new Refused(Refused.Kind.CONFLICT, "REQUEST_NOT_PENDING");
        }
        return request;
    }

    private static boolean isParty(AuthenticatedUser user, MfaResetRequest request) {
        return user.userId().equals(request.requestedBy()) || user.userId().equals(request.userId());
    }

    /** Holds identity.reset.confirm at one of the person's offices (role permissions only: never delegable). */
    private static boolean mayDecide(AuthenticatedUser supervisor, MfaResetRequest request) {
        String code = Permission.IDENTITY_RESET_CONFIRM.code();
        return supervisor.fullyAuthenticated() && !isParty(supervisor, request)
                && request.offices().stream().anyMatch(office -> supervisor.permissions().contains(code + "@" + office));
    }
}
