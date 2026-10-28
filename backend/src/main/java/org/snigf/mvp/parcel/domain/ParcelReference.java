// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import org.snigf.mvp.shared.id.CheckCharacter;

/**
 * A parcel reference, {@code DEMO-PCL-{office}-{yyyy}-{seq6}-{check}} (Baseline Task D),
 * for example {@code DEMO-PCL-YDE1-2026-000042-1}. The server issues it; it is unique and
 * never reused.
 *
 * <p>The check character is ISO/IEC 7064 MOD 37,36 over the whole reference without
 * hyphens ({@code DEMOPCLYDE12026000042}): it detects every single wrong character and every
 * swap of two adjacent characters, so a mistyped reference is refused before any lookup.
 * (The Baseline's example ends in {@code K}, which no reading of the scheme produces: it was
 * illustrative.)
 */
public record ParcelReference(String value) {

    static final String PREFIX = "DEMO-PCL";
    private static final Pattern FORMAT =
            Pattern.compile("^DEMO-PCL-[A-Z0-9]{2,16}-[0-9]{4}-[0-9]{6}-[0-9A-Z]$");

    public ParcelReference {
        if (!isValid(value)) {
            throw new IllegalArgumentException("not a valid parcel reference");
        }
    }

    /** The reference for a sequence number at an office in a year. */
    public static ParcelReference issue(String officeCode, int year, long sequence) {
        if (sequence < 1 || sequence > 999_999 || year < 2000 || year > 2999) {
            throw new IllegalArgumentException("sequence or year out of range");
        }
        String body = PREFIX + "-" + officeCode + "-" + year + "-" + String.format(Locale.ROOT, "%06d", sequence);
        return new ParcelReference(body + "-" + checkCharacter(body));
    }

    /** Well formed and with the right check character; never throws. */
    public static boolean isValid(String candidate) {
        if (candidate == null || !FORMAT.matcher(candidate).matches()) {
            return false;
        }
        String body = candidate.substring(0, candidate.length() - 2);
        return candidate.charAt(candidate.length() - 1) == checkCharacter(body);
    }

    /** ISO/IEC 7064 MOD 37,36 over the characters, hyphens ignored ({@link CheckCharacter}). */
    static char checkCharacter(String body) {
        return CheckCharacter.of(body);
    }

    @Override
    public String toString() {
        return value;
    }
}
