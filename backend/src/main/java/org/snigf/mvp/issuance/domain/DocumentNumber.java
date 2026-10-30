// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The public number of an issued document: {@code SNIGF-} and 26 characters of Crockford base 32
 * (no I, L, O or U), 128 random bits. It identifies; it never proves: authenticity comes from the
 * registry and the signed manifest (ADR-039). Not guessable, so the public lookup cannot be used to
 * enumerate documents.
 */
public record DocumentNumber(String value) {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final Pattern FORMAT = Pattern.compile("^SNIGF-[0-9A-HJKMNP-TV-Z]{26}$");

    public DocumentNumber {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("not a document number");
        }
    }

    public static DocumentNumber random(SecureRandom random) {
        byte[] bits = new byte[16];
        random.nextBytes(bits);
        StringBuilder out = new StringBuilder("SNIGF-");
        // 128 bits in 26 groups of 5, most significant first (the first group has 3 bits).
        java.math.BigInteger n = new java.math.BigInteger(1, bits);
        char[] digits = new char[26];
        for (int i = 25; i >= 0; i--) {
            digits[i] = ALPHABET.charAt(n.intValue() & 31);
            n = n.shiftRight(5);
        }
        return new DocumentNumber(out.append(digits).toString());
    }

    /** Upper-cases and drops spaces, as a person may type a printed number; never throws. */
    public static String normalise(String typed) {
        return typed == null ? "" : typed.strip().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
    }

    public static boolean isValid(String candidate) {
        return candidate != null && FORMAT.matcher(candidate).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
