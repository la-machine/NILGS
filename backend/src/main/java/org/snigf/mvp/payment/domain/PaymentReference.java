// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import org.snigf.mvp.shared.id.CheckCharacter;

/**
 * A payment reference, {@code DEMO-PAY-{yyyy}-{seq8}-{check}} (MVP-08), issued when the applicant
 * starts paying. The check character is the one of every DEMO reference ({@link CheckCharacter}).
 * Unique by constraint, never reused; gaps are possible.
 */
public record PaymentReference(String value) {

    static final String PREFIX = "DEMO-PAY";
    private static final Pattern FORMAT = Pattern.compile("^DEMO-PAY-[0-9]{4}-[0-9]{8}-[0-9A-Z]$");

    public PaymentReference {
        if (!isValid(value)) {
            throw new IllegalArgumentException("not a valid payment reference");
        }
    }

    public static PaymentReference issue(int year, long sequence) {
        if (sequence < 1 || sequence > 99_999_999 || year < 2000 || year > 2999) {
            throw new IllegalArgumentException("sequence or year out of range");
        }
        String body = PREFIX + "-" + year + "-" + String.format(Locale.ROOT, "%08d", sequence);
        return new PaymentReference(body + "-" + CheckCharacter.of(body));
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
