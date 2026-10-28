// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The only kinds of file kept (Baseline, "Documents"): PDF, JPEG and PNG, recognised from their
 * bytes. A name's extension must agree with what the bytes are, so a file is never served as
 * something it is not.
 */
public enum DocumentType {
    PDF("application/pdf", Set.of("pdf")),
    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    PNG("image/png", Set.of("png"));

    private final String mediaType;
    private final Set<String> extensions;

    DocumentType(String mediaType, Set<String> extensions) {
        this.mediaType = mediaType;
        this.extensions = extensions;
    }

    public String mediaType() {
        return mediaType;
    }

    public static Optional<DocumentType> ofMediaType(String mediaType) {
        return Arrays.stream(values()).filter(t -> t.mediaType.equals(mediaType)).findFirst();
    }

    /** Whether the name ends with an extension of this type (case ignored). */
    public boolean named(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
