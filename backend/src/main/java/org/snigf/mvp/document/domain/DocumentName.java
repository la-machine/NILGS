// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import java.text.Normalizer;

/**
 * The name a file was given, kept for display only (V14): never a path, never used to store or
 * to recognise the file. Directories are dropped, control and path characters removed, the
 * text normalised (NFC) and cut to 200 characters, keeping the extension.
 */
public final class DocumentName {

    public static final int MAX = 200;

    private DocumentName() {
    }

    /** A safe display name; empty when nothing usable remains. */
    public static String clean(String given) {
        if (given == null) {
            return "";
        }
        String name = given.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder kept = new StringBuilder();
        name.codePoints().filter(c -> !Character.isISOControl(c) && c != '/' && c != '\\'
                        && Character.getType(c) != Character.FORMAT)
                .forEach(kept::appendCodePoint);
        name = kept.toString().strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > MAX) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX - extension.length()) + extension;
        }
        return name;
    }
}
