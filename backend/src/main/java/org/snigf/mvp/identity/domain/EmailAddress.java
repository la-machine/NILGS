// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A normalised e-mail address: trimmed, lower case, one {@code @}, no spaces or control
 * characters, at most 254 characters. Accounts are unique by this value
 * ({@code ux_app_user_email} on {@code lower(email)}).
 *
 * @param value the normalised address
 */
public record EmailAddress(String value) {

    private static final Pattern SHAPE = Pattern.compile("[^@\\s\\p{Cntrl}]{1,64}@[^@\\s\\p{Cntrl}]+\\.[^@\\s\\p{Cntrl}]{2,}");

    public EmailAddress {
        if (value == null || value.length() > 254 || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("not an e-mail address");
        }
    }

    /** Normalises then validates; throws {@link IllegalArgumentException} if invalid. */
    public static EmailAddress of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("not an e-mail address");
        }
        return new EmailAddress(raw.strip().toLowerCase(Locale.ROOT));
    }

    /** The part before {@code @}, for the password rule against account names. */
    public String localPart() {
        return value.substring(0, value.indexOf('@'));
    }

    @Override
    public String toString() {
        return "EmailAddress[<redacted>]";
    }
}
