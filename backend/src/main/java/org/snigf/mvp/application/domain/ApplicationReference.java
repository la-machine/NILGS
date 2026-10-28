// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import org.snigf.mvp.shared.id.CheckCharacter;

/**
 * An application reference, {@code DEMO-APP-{yyyy}-{seq6}-{check}} (Baseline Task D), issued
 * at submission, never at draft. The check character is the one of every DEMO reference
 * ({@link CheckCharacter}). Unique by constraint, never reused; gaps are possible.
 */
public record ApplicationReference(String value) {

    static final String PREFIX = "DEMO-APP";
    private static final Pattern FORMAT = Pattern.compile("^DEMO-APP-[0-9]{4}-[0-9]{6}-[0-9A-Z]$");

    public ApplicationReference {
        if (!isValid(value)) {
            throw new IllegalArgumentException("not a valid application reference");
        }
    }

    public static ApplicationReference issue(int year, long sequence) {
        if (sequence < 1 || sequence > 999_999 || year < 2000 || year > 2999) {
            throw new IllegalArgumentException("sequence or year out of range");
        }
        String body = PREFIX + "-" + year + "-" + String.format(Locale.ROOT, "%06d", sequence);
        return new ApplicationReference(body + "-" + CheckCharacter.of(body));
    }

    /** Well formed and with the right check character; never throws. */
    public static boolean isValid(String candidate) {
        return candidate != null && FORMAT.matcher(candidate).matches() && CheckCharacter.matches(candidate);
    }

    @Override
    public String toString() {
        return value;
    }
}
