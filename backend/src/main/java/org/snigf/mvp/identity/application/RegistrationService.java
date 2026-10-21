// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.domain.EmailAddress;
import org.snigf.mvp.identity.domain.PasswordHasher;
import org.snigf.mvp.identity.domain.PasswordPolicy;
import org.snigf.mvp.identity.domain.UserRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case "register an applicant" (S1, {@code POST /auth/register}). The answer is the
 * same whether or not the address was already registered, so the endpoint cannot be used
 * to discover accounts; the hash is computed in both cases so timing does not tell either.
 */
@Service
public class RegistrationService {

    static final String ENTITY_USER = "USER";
    private static final Set<String> LANGUAGES = Set.of("fr", "en");

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final AuditRecorder audit;

    public RegistrationService(UserRepository users, PasswordHasher hasher, PasswordPolicy policy, AuditRecorder audit) {
        this.users = users;
        this.hasher = hasher;
        this.policy = policy;
        this.audit = audit;
    }

    /**
     * @param email             as typed
     * @param displayName       1 to 120 characters, no control characters
     * @param password          checked against {@link PasswordPolicy}
     * @param preferredLanguage {@code fr} or {@code en}
     */
    public record Registration(String email, String displayName, String password, String preferredLanguage) {

        @Override
        public String toString() {
            return "Registration[<redacted>]";
        }
    }

    /** @throws RegistrationRejected when a rule is broken; nothing is stored then */
    @Transactional
    public void register(Registration registration) {
        List<String> problems = new ArrayList<>();
        EmailAddress email = null;
        try {
            email = EmailAddress.of(registration.email());
        } catch (IllegalArgumentException e) {
            problems.add("EMAIL_INVALID");
        }
        String displayName = registration.displayName() == null ? "" : registration.displayName().strip();
        if (displayName.isEmpty() || displayName.length() > 120 || displayName.codePoints().anyMatch(Character::isISOControl)) {
            problems.add("DISPLAY_NAME_INVALID");
        }
        if (!LANGUAGES.contains(registration.preferredLanguage())) {
            problems.add("LANGUAGE_UNSUPPORTED");
        }
        policy.check(registration.password(), email, displayName).forEach(v -> problems.add(v.name()));
        if (!problems.isEmpty()) {
            throw new RegistrationRejected(problems);
        }

        String hash = hasher.hash(registration.password());
        UUID id = UuidV7.next();
        Optional<UUID> created = users.createApplicant(id, email, displayName, hash, registration.preferredLanguage());
        if (created.isPresent()) {
            audit.record(AuditEvent.of(AuditAction.USER_REGISTERED, AuditOutcome.SUCCESS)
                    .withEntity(ENTITY_USER, id)
                    .withDetails(Map.of("accountType", "APPLICANT", "preferredLanguage", registration.preferredLanguage())));
        } else {
            UUID existing = users.findByEmail(email).map(u -> u.id()).orElse(null);
            audit.record(AuditEvent.of(AuditAction.USER_REGISTERED, AuditOutcome.FAILED)
                    .withEntity(ENTITY_USER, existing)
                    .withReason("e-mail address already registered"));
        }
    }
}
