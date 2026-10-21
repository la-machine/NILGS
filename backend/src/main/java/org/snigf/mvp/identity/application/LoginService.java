// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.domain.EmailAddress;
import org.snigf.mvp.identity.domain.LockoutPolicy;
import org.snigf.mvp.identity.domain.PasswordHasher;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases "log in with a password", "restore a session's principal" and "log out".
 *
 * <p>Every failure looks the same to the caller (unknown address, wrong password, locked or
 * disabled account): {@link LoginResult#failed()}, after the same work. Every attempt is
 * audited with its real reason, and wrong passwords count toward the lockout (P6).
 */
@Service
public class LoginService {

    /** What the client must do next (API {@code nextStep}). */
    public enum NextStep {
        /** Authenticated; the session is complete. */
        DONE,
        /** Staff without a registered second factor: register one first. */
        MFA_ENROLMENT,
        /** Staff with a second factor: prove it. */
        MFA_CHALLENGE
    }

    /** Outcome of a login attempt; a failure carries no detail by design. */
    public record LoginResult(AuthenticatedUser user, NextStep nextStep) {

        static LoginResult failed() {
            return new LoginResult(null, null);
        }

        public boolean succeeded() {
            return user != null;
        }
    }

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final LockoutPolicy lockout;
    private final AuditRecorder audit;
    private final Clock clock;

    public LoginService(UserRepository users, PasswordHasher hasher, LockoutPolicy lockout, AuditRecorder audit,
                        Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.lockout = lockout;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public LoginResult login(String rawEmail, String password) {
        Instant now = clock.instant();
        Optional<UserAccount> found;
        try {
            found = users.findByEmail(EmailAddress.of(rawEmail));
        } catch (IllegalArgumentException e) {
            found = Optional.empty();
        }
        if (found.isEmpty()) {
            hasher.spendEquivalentTime(password);
            failed(null, "UNKNOWN_ACCOUNT", Map.of());
            return LoginResult.failed();
        }
        UserAccount user = found.get();
        if (!user.enabled() || user.lockedAt(now)) {
            hasher.spendEquivalentTime(password);
            failed(user.id(), user.enabled() ? "LOCKED" : "DISABLED", Map.of());
            return LoginResult.failed();
        }
        if (!hasher.matches(password, user.passwordHash())) {
            UserRepository.Failure failure = users.recordFailedLogin(user.id(), lockout, now);
            failed(user.id(), "WRONG_PASSWORD", Map.of("failedLogins", failure.failedLogins()));
            if (failure.lockedNow()) {
                audit.record(AuditEvent.of(AuditAction.ACCOUNT_LOCKED, AuditOutcome.SUCCESS)
                        .withEntity(RegistrationService.ENTITY_USER, user.id())
                        .withDetails(Map.of("lockedUntil", failure.lockedUntil().toString(),
                                "afterFailures", lockout.maxFailures())));
            }
            return LoginResult.failed();
        }

        users.recordSuccessfulLogin(user.id());
        if (hasher.needsRehash(user.passwordHash())) {
            users.updatePasswordHash(user.id(), hasher.hash(password));
        }
        AuthLevel level = user.accountType() == AccountType.STAFF ? AuthLevel.PASSWORD : AuthLevel.FULL;
        NextStep next = user.accountType() != AccountType.STAFF ? NextStep.DONE
                : user.secondFactorReady() ? NextStep.MFA_CHALLENGE : NextStep.MFA_ENROLMENT;
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.displayName(), user.accountType(),
                users.activeRoles(user.id()), level, now);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("factor", "PASSWORD");
        details.put("level", level.name());
        details.put("nextStep", next.name());
        // The user is the actor of their own login.
        actingAs(principal, () -> audit.record(AuditEvent.of(AuditAction.LOGIN_SUCCEEDED, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, user.id())
                .withDetails(details)));
        return new LoginResult(principal, next);
    }

    /**
     * Rebuilds the principal of a session from the database. Empty when the account no
     * longer exists or is disabled: the session must then end.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> restore(UUID userId, AuthLevel level, Instant authenticatedAt) {
        return users.findById(userId)
                .filter(UserAccount::enabled)
                .map(u -> new AuthenticatedUser(u.id(), u.displayName(), u.accountType(), users.activeRoles(u.id()),
                        u.accountType() == AccountType.STAFF ? level : AuthLevel.FULL, authenticatedAt));
    }

    /** Records the logout of the current principal; the caller ends the session. */
    @Transactional
    public void recordLogout(AuthenticatedUser user) {
        audit.record(AuditEvent.of(AuditAction.LOGOUT, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, user.userId()));
    }

    private void failed(UUID userId, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("reason", reason);
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILED)
                .withEntity(userId == null ? null : RegistrationService.ENTITY_USER, userId)
                .withDetails(details));
    }

    private static <T> T actingAs(AuthenticatedUser user, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, Set.of()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
