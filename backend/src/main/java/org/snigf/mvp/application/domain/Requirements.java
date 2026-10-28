// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an application type asks for (C4: configurable, labelled as demonstration requirements):
 * the form's fields and the document categories. Defined in configuration, stored as one version
 * of the type (V13), and checked here, both when a definition is loaded and when a form is saved
 * or submitted. Every value of a form is text.
 */
public record Requirements(List<Field> fields, List<DocumentCategory> documents) {

    /** A field's key, a document category's code, a choice's code. */
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{1,39}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    /** Bounds of a definition, so a configuration mistake cannot make the form unusable. */
    public static final int MAX_FIELDS = 30;
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_LABEL = 200;

    public enum Kind { TEXT, LONG_TEXT, CHOICE }

    /** A labelled choice of a CHOICE field. */
    public record Option(String code, String labelFr, String labelEn) {
    }

    /** One field of the form; {@code maxLength} for text, {@code options} for a choice. */
    public record Field(String key, Kind kind, boolean required, int maxLength, List<Option> options,
                        String labelFr, String labelEn, String hintFr, String hintEn) {
    }

    /** A category of supporting document (uploads come with E-13); DEMO in its code. */
    public record DocumentCategory(String code, boolean required, String labelFr, String labelEn) {
    }

    public Requirements {
        fields = fields == null ? List.of() : List.copyOf(fields);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /** What is wrong with this definition, as readable reasons (empty when it can be used). */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (fields.size() > MAX_FIELDS) {
            problems.add("more than " + MAX_FIELDS + " fields");
        }
        if (documents.size() > MAX_DOCUMENTS) {
            problems.add("more than " + MAX_DOCUMENTS + " document categories");
        }
        Set<String> keys = new HashSet<>();
        for (Field f : fields) {
            String where = "field " + f.key();
            if (f.key() == null || !KEY.matcher(f.key()).matches()) {
                problems.add(where + ": key must be camelCase letters and digits (2 to 40)");
            } else if (!keys.add(f.key())) {
                problems.add(where + ": duplicate key");
            }
            if (f.kind() == null) {
                problems.add(where + ": kind is required (TEXT, LONG_TEXT, CHOICE)");
            } else if (f.kind() == Kind.CHOICE) {
                if (f.options() == null || f.options().size() < 2 || f.options().size() > 30) {
                    problems.add(where + ": a choice has 2 to 30 options");
                } else {
                    Set<String> codes = new HashSet<>();
                    for (Option o : f.options()) {
                        if (o.code() == null || !CODE.matcher(o.code()).matches() || !codes.add(o.code())) {
                            problems.add(where + ": option codes are unique UPPER_SNAKE_CASE");
                        }
                        labels(problems, where + " option " + o.code(), o.labelFr(), o.labelEn());
                    }
                }
            } else if (f.maxLength() < 1 || f.maxLength() > MAX_TEXT) {
                problems.add(where + ": maxLength must be 1 to " + MAX_TEXT);
            }
            labels(problems, where, f.labelFr(), f.labelEn());
            optional(problems, where + " hint", f.hintFr(), f.hintEn());
        }
        Set<String> categories = new HashSet<>();
        for (DocumentCategory d : documents) {
            String where = "document " + d.code();
            if (d.code() == null || !CODE.matcher(d.code()).matches() || !d.code().startsWith("DEMO_")) {
                problems.add(where + ": code must be UPPER_SNAKE_CASE starting with DEMO_ (C4)");
            } else if (!categories.add(d.code())) {
                problems.add(where + ": duplicate code");
            }
            labels(problems, where, d.labelFr(), d.labelEn());
        }
        return problems;
    }

    /**
     * Checks a form: unknown fields, values too long, choices not offered, and (for a
     * submission) required fields left empty. Stable codes such as {@code MISSING:statement} or
     * {@code TOO_LONG:representativeName}, in the definition's order.
     */
    public List<String> check(Map<String, String> data, boolean submitting) {
        List<String> problems = new ArrayList<>();
        Map<String, Field> byKey = new LinkedHashMap<>();
        fields.forEach(f -> byKey.put(f.key(), f));
        for (String key : data.keySet()) {
            if (!byKey.containsKey(key)) {
                problems.add("UNKNOWN_FIELD:" + key);
            }
        }
        for (Field f : fields) {
            String value = data.get(f.key());
            boolean empty = value == null || value.isBlank();
            if (empty) {
                if (submitting && f.required()) {
                    problems.add("MISSING:" + f.key());
                }
                continue;
            }
            if (f.kind() == Kind.CHOICE) {
                if (f.options().stream().noneMatch(o -> o.code().equals(value))) {
                    problems.add("NOT_AN_OPTION:" + f.key());
                }
            } else if (value.strip().length() > f.maxLength()) {
                problems.add("TOO_LONG:" + f.key());
            }
        }
        return problems;
    }

    private static void labels(List<String> problems, String where, String fr, String en) {
        if (fr == null || fr.isBlank() || fr.length() > MAX_LABEL || en == null || en.isBlank() || en.length() > MAX_LABEL) {
            problems.add(where + ": labels in French and English are required (at most " + MAX_LABEL + " characters)");
        }
    }

    private static void optional(List<String> problems, String where, String fr, String en) {
        if ((fr == null) != (en == null) || (fr != null && (fr.length() > MAX_LABEL || en.length() > MAX_LABEL))) {
            problems.add(where + ": give both languages or neither (at most " + MAX_LABEL + " characters)");
        }
    }
}
