// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A version of the DEMO fee of an application type (V18): never edited, closed once when the
 * catalogue changes. Every fee is a DEMO platform service charge, never an official
 * administrative fee, and says so in both languages.
 */
public record Fee(UUID id, String typeCode, long amount, String currency, String labelFr, String labelEn,
                  Instant validFrom, Instant validTo) {

    public static final String CURRENCY = "XAF";
    public static final long MAX_AMOUNT = 10_000_000;
    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    /** A fee as configured, before it is stored as a version. */
    public record Definition(String typeCode, long amount, String currency, String labelFr, String labelEn) {

        /** What is wrong with it, as readable reasons. */
        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            if (typeCode == null || !TYPE.matcher(typeCode).matches()) {
                problems.add("fee " + typeCode + ": the type code must be UPPER_SNAKE_CASE");
            }
            if (amount <= 0 || amount > MAX_AMOUNT) {
                problems.add("fee " + typeCode + ": amount from 1 to " + MAX_AMOUNT);
            }
            if (!CURRENCY.equals(currency)) {
                problems.add("fee " + typeCode + ": currency must be " + CURRENCY);
            }
            if (labelFr == null || labelEn == null || labelFr.strip().length() < 3 || labelEn.strip().length() < 3
                    || labelFr.length() > 200 || labelEn.length() > 200
                    || !labelFr.contains("DÉMO") || !labelEn.contains("DEMO")) {
                problems.add("fee " + typeCode + ": labels of 3 to 200 characters, saying DÉMO (fr) and DEMO (en)");
            }
            return problems;
        }
    }

    /** Whether this version already says what the definition says. */
    public boolean sameAs(Definition d) {
        return typeCode.equals(d.typeCode()) && amount == d.amount() && currency.equals(d.currency())
                && labelFr.equals(d.labelFr()) && labelEn.equals(d.labelEn());
    }
}
