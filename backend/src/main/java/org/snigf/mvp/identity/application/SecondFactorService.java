// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.domain.LockoutPolicy;
import org.snigf.mvp.identity.domain.RecoveryCode;
import org.snigf.mvp.identity.domain.RecoveryCodeHasher;
import org.snigf.mvp.identity.domain.RegisteredKey;
import org.snigf.mvp.identity.domain.SecondFactorRepository;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the staff second factor (ADR-016, ADR-023): register authenticators until
 * enrolment is complete, prove one at each login, or use a one-time recovery code.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only staff have a second factor; applicants are refused ({@link NotAllowed}).</li>
 *   <li>Before enrolment is complete, a password session may register authenticators.
 *       Once complete, adding one requires a fully authenticated session, so a stolen
 *       password alone can never add an attacker's key.</li>
 *   <li>Enrolment completes when {@code minAuthenticators} are registered: the account is
 *       marked enrolled, recovery codes are issued once, and the session becomes FULL.</li>
 *   <li>A wrong assertion or recovery code counts toward the lockout (P6) like a wrong
 *       password, and is audited; the caller ends the session when the account locks.</li>
 * </ul>
 */
@Service
public class SecondFactorService {

    /** The operation does not apply to this session (wrong account type or level). */
    public static class NotAllowed extends RuntimeException {
        public NotAllowed(String reason) {
            super(reason);
        }
    }

    /** The key is not one of the caller's keys. */
    public static class KeyNotFound extends RuntimeException {
        public KeyNotFound() {
            super("not one of your keys");
        }
    }

    /** Removing it would leave no key: the account would be enrolled without a second factor. */
    public static class LastKey extends RuntimeException {
        public LastKey() {
            super("the last key cannot be removed");
        }
    }

    /**
     * Result of a registration: still enrolling, or complete with the upgraded principal and
     * the recovery codes (shown once, never stored in clear).
     */
    public record Enrolment(int registered, int required, boolean complete, AuthenticatedUser upgraded,
                            List<String> recoveryCodes) {
    }

    /** Result of a second-factor proof. {@code upgraded} is null on failure. */
    public record Verification(AuthenticatedUser upgraded, boolean locked, Integer remainingRecoveryCodes) {

        public boolean succeeded() {
            return upgraded != null;
        }
    }

    private final WebAuthnCeremonies webauthn;
    private final UserRepository users;
    private final SecondFactorRepository factors;
    private final RecoveryCodeHasher codes;
    private final LockoutPolicy lockout;
    private final AuditRecorder audit;
    private final Clock clock;
    private final int minAuthenticators;
    private final int recoveryCodeCount;

    public SecondFactorService(WebAuthnCeremonies webauthn, UserRepository users, SecondFactorRepository factors,
                               RecoveryCodeHasher codes, LockoutPolicy lockout, AuditRecorder audit, Clock clock,
                               IdentityProperties properties) {
        this.webauthn = webauthn;
        this.users = users;
        this.factors = factors;
        this.codes = codes;
        this.lockout = lockout;
        this.audit = audit;
        this.clock = clock;
        this.minAuthenticators = properties.webauthn().minAuthenticators();
        this.recoveryCodeCount = properties.webauthn().recoveryCodes();
    }

    // --- The owner's keys (ADR-028) -----------------------------------------------------------

    /** The caller's keys, once every factor is proven. */
    @Transactional(readOnly = true)
    public List<RegisteredKey> keys(AuthenticatedUser user) {
        fullStaff(user);
        return factors.keys(user.userId());
    }

    /**
     * Removes one of the caller's keys, typically a lost one after a recovery-code sign-in.
     * The last key stays (the database refuses it too, V10). Returns how many keys remain;
     * below the required number, the interface asks for a new one. Audited.
     */
    @Transactional
    public int removeKey(AuthenticatedUser user, String credentialId) {
        fullStaff(user);
        List<RegisteredKey> keys = factors.keys(user.userId());
        RegisteredKey key = keys.stream().filter(k -> k.id().equals(credentialId)).findFirst()
                .orElseThrow(KeyNotFound::new);
        if (keys.size() <= 1) {
            throw new LastKey();
        }
        try {
            if (!factors.removeKey(user.userId(), credentialId)) {
                throw new KeyNotFound();
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new LastKey();
        }
        int remaining = keys.size() - 1;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("label", key.label());
        details.put("remaining", remaining);
        audit.record(AuditEvent.of(AuditAction.MFA_KEY_REMOVED, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, user.userId()).withDetails(details));
        return remaining;
    }

    public int requiredKeys() {
        return minAuthenticators;
    }

    private static void fullStaff(AuthenticatedUser user) {
        if (user.accountType() != AccountType.STAFF || !user.fullyAuthenticated()) {
            throw new NotAllowed("key management needs a staff session with every factor proven");
        }
    }

    // --- Registration ---------------------------------------------------------------------

    /** Writable: the first registration creates the account's WebAuthn user entity. */
    @Transactional
    public WebAuthnCeremonies.Started beginRegistration(AuthenticatedUser user) {
        UserAccount account = mayRegister(user);
        return webauthn.beginRegistration(account.id(), account.displayName());
    }

    @Transactional
    public Enrolment finishRegistration(AuthenticatedUser user, String ceremonyId, String responseJson, String label) {
        UserAccount account = mayRegister(user);
        WebAuthnCeremonies.Registered registered;
        try {
            registered = webauthn.finishRegistration(ceremonyId, account.id(), responseJson, label);
        } catch (WebAuthnCeremonies.Rejected e) {
            audit.recordIndependently(AuditEvent.of(AuditAction.MFA_ENROLLED, AuditOutcome.FAILED)
                    .withEntity(RegistrationService.ENTITY_USER, account.id())
                    .withReason("the authenticator's registration did not verify")
                    .withDetails(Map.of("method", "WEBAUTHN")));
            throw e;
        }
        int count = webauthn.countCredentials(account.id());
        boolean completesNow = !account.secondFactorReady() && count >= minAuthenticators;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", "WEBAUTHN");
        details.put("label", registered.label());
        details.put("authenticators", count);
        details.put("enrolmentComplete", account.secondFactorReady() || completesNow);
        audit.record(AuditEvent.of(AuditAction.MFA_ENROLLED, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, account.id())
                .withDetails(details));

        if (!completesNow) {
            return new Enrolment(count, minAuthenticators, account.secondFactorReady(), null, List.of());
        }
        Instant now = clock.instant();
        factors.completeEnrolment(account.id(), now);
        List<String> issued = issueRecoveryCodes(account.id(), now);
        return new Enrolment(count, minAuthenticators, true, upgrade(user), issued);
    }

    // --- Verification at login ------------------------------------------------------------

    @Transactional(readOnly = true)
    public WebAuthnCeremonies.Started beginAssertion(AuthenticatedUser user) {
        UserAccount account = mustProve(user);
        return webauthn.beginAssertion(account.id());
    }

    @Transactional
    public Verification finishAssertion(AuthenticatedUser user, String ceremonyId, String responseJson) {
        UserAccount account = mustProve(user);
        if (account.lockedAt(clock.instant())) {
            return failed(account, "LOCKED");
        }
        try {
            webauthn.finishAssertion(ceremonyId, account.id(), responseJson);
        } catch (WebAuthnCeremonies.Rejected e) {
            return failed(account, "SECOND_FACTOR_INVALID");
        }
        users.recordSuccessfulLogin(account.id());
        audit.record(AuditEvent.of(AuditAction.MFA_VERIFIED, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, account.id())
                .withDetails(Map.of("method", "WEBAUTHN")));
        return new Verification(upgrade(user), false, null);
    }

    @Transactional
    public Verification recover(AuthenticatedUser user, String typedCode) {
        UserAccount account = mustProve(user);
        Instant now = clock.instant();
        if (account.lockedAt(now)) {
            return failed(account, "LOCKED");
        }
        Optional<String> code = RecoveryCode.normalise(typedCode);
        List<SecondFactorRepository.StoredRecoveryCode> unused = factors.unusedRecoveryCodes(account.id());
        Optional<SecondFactorRepository.StoredRecoveryCode> match = code.flatMap(c -> unused.stream()
                .filter(stored -> codes.matches(c, stored.hash())).findFirst());
        if (match.isEmpty() || !factors.consumeRecoveryCode(match.get().id(), now)) {
            return failed(account, "RECOVERY_CODE_INVALID");
        }
        users.recordSuccessfulLogin(account.id());
        int remaining = unused.size() - 1;
        audit.record(AuditEvent.of(AuditAction.MFA_RECOVERY_CODE_USED, AuditOutcome.SUCCESS)
                .withEntity(RegistrationService.ENTITY_USER, account.id())
                .withDetails(Map.of("remainingCodes", remaining)));
        return new Verification(upgrade(user), false, remaining);
    }

    // --- Rules ----------------------------------------------------------------------------

    private UserAccount mayRegister(AuthenticatedUser user) {
        UserAccount account = staffAccount(user);
        if (account.secondFactorReady() && user.level() != AuthLevel.FULL) {
            throw new NotAllowed("prove the existing second factor before adding an authenticator");
        }
        return account;
    }

    private UserAccount mustProve(AuthenticatedUser user) {
        UserAccount account = staffAccount(user);
        if (!account.secondFactorReady()) {
            throw new NotAllowed("no second factor is registered yet");
        }
        if (user.level() != AuthLevel.PASSWORD) {
            throw new NotAllowed("the second factor is already proven for this session");
        }
        return account;
    }

    private UserAccount staffAccount(AuthenticatedUser user) {
        UserAccount account = users.findById(user.userId()).filter(UserAccount::enabled)
                .orElseThrow(() -> new NotAllowed("account unavailable"));
        if (account.accountType() != AccountType.STAFF) {
            throw new NotAllowed("only staff accounts have a second factor");
        }
        return account;
    }

    private Verification failed(UserAccount account, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        boolean locked = account.lockedAt(clock.instant());
        if (!locked) {
            UserRepository.Failure failure = users.recordFailedLogin(account.id(), lockout, clock.instant());
            details.put("failedLogins", failure.failedLogins());
            locked = failure.lockedNow();
            if (locked) {
                audit.record(AuditEvent.of(AuditAction.ACCOUNT_LOCKED, AuditOutcome.SUCCESS)
                        .withEntity(RegistrationService.ENTITY_USER, account.id())
                        .withDetails(Map.of("lockedUntil", failure.lockedUntil().toString(),
                                "afterFailures", lockout.maxFailures())));
            }
        }
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILED)
                .withEntity(RegistrationService.ENTITY_USER, account.id())
                .withDetails(details));
        return new Verification(null, locked, null);
    }

    private List<String> issueRecoveryCodes(UUID userId, Instant now) {
        List<String> issued = RecoveryCode.generate(recoveryCodeCount);
        factors.replaceRecoveryCodes(userId, issued.stream()
                .map(c -> codes.hash(RecoveryCode.normalise(c).orElseThrow())).toList(), now);
        return issued;
    }

    private static AuthenticatedUser upgrade(AuthenticatedUser user) {
        return user.at(AuthLevel.FULL);
    }
}
