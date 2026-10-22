// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One-time recovery codes, the fallback when a staff member has lost their authenticators
 * (ADR-016, ADR-023): 16 characters of Crockford base32, shown as {@code XXXX-XXXX-XXXX-XXXX},
 * 80 random bits each. Typing is forgiving: case, spaces and dashes are ignored, and the
 * look-alikes O, I and L read as 0, 1 and 1.
 */
public final class RecoveryCode {

    /** Crockford base32: no I, L, O or U, so codes read back unambiguously. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCode() {
    }

    /** {@code count} new codes in display form. */
    public static List<String> generate(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder code = new StringBuilder(LENGTH + 3);
            for (int c = 0; c < LENGTH; c++) {
                if (c > 0 && c % 4 == 0) {
                    code.append('-');
                }
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** The canonical 16 characters, or empty if the input cannot be a code. */
    public static Optional<String> normalise(String typed) {
        if (typed == null || typed.length() > 64) {
            return Optional.empty();
        }
        String compact = typed.toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        if (compact.length() != LENGTH) {
            return Optional.empty();
        }
        for (char c : compact.toCharArray()) {
            if (new String(ALPHABET).indexOf(c) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(compact);
    }
}
