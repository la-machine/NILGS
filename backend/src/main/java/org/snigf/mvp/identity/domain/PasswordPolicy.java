// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password policy P7 (Slice 1 design): a minimum length, a maximum length (Argon2 cost is
 * paid per byte), no control characters, not a commonly used password, and not containing
 * the account's own name or e-mail local part.
 *
 * @param minLength minimum length in characters
 * @param maxLength maximum length in characters
 * @param common    commonly used passwords, in lower case
 */
public record PasswordPolicy(int minLength, int maxLength, Set<String> common) {

    /** What a password broke; the API reports these codes so the interface can explain them. */
    public enum Violation {
        TOO_SHORT,
        TOO_LONG,
        CONTROL_CHARACTER,
        COMMONLY_USED,
        CONTAINS_ACCOUNT_NAME
    }

    /** Below this, a name fragment is too short to be a meaningful match. */
    private static final int MIN_NAME_FRAGMENT = 4;

    public PasswordPolicy {
        if (minLength < 8 || maxLength < minLength) {
            throw new IllegalArgumentException("password length bounds are inconsistent");
        }
        common = Set.copyOf(common);
    }

    public List<Violation> check(String password, EmailAddress email, String displayName) {
        List<Violation> violations = new ArrayList<>();
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < minLength) {
            violations.add(Violation.TOO_SHORT);
        }
        if (length > maxLength) {
            violations.add(Violation.TOO_LONG);
        }
        if (password == null) {
            return violations;
        }
        if (password.codePoints().anyMatch(Character::isISOControl)) {
            violations.add(Violation.CONTROL_CHARACTER);
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (common.contains(lower)) {
            violations.add(Violation.COMMONLY_USED);
        }
        if (containsName(lower, email == null ? null : email.localPart())
                || containsName(lower, displayName)) {
            violations.add(Violation.CONTAINS_ACCOUNT_NAME);
        }
        return violations;
    }

    private static boolean containsName(String lowerPassword, String name) {
        if (name == null) {
            return false;
        }
        for (String part : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (part.length() >= MIN_NAME_FRAGMENT && lowerPassword.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
