// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.id;

/**
 * The check character of every DEMO reference (Baseline Task D): ISO/IEC 7064 MOD 37,36
 * (hybrid system, M = 36) over the characters 0-9 A-Z, hyphens ignored. It detects every single
 * wrong character and every swap of two adjacent characters, so a mistyped reference is refused
 * before any lookup.
 */
public final class CheckCharacter {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private CheckCharacter() {
    }

    /** The check character of {@code body} (the reference without its last hyphen and character). */
    public static char of(String body) {
        int m = ALPHABET.length();
        int p = m;
        for (char c : body.toCharArray()) {
            if (c == '-') {
                continue;
            }
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("character outside 0-9 A-Z");
            }
            int s = (p + value) % m;
            if (s == 0) {
                s = m;
            }
            p = (2 * s) % (m + 1);
        }
        return ALPHABET.charAt((m + 1 - p) % m);
    }

    /** Whether {@code reference} ends with "-" and the right check character; never throws. */
    public static boolean matches(String reference) {
        if (reference == null || reference.length() < 3 || reference.charAt(reference.length() - 2) != '-') {
            return false;
        }
        try {
            return reference.charAt(reference.length() - 1) == of(reference.substring(0, reference.length() - 2));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
