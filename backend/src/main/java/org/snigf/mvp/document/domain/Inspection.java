// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

/**
 * What a file turned out to be. Refused with a stable code: {@code TYPE_NOT_ALLOWED} (what the
 * bytes are is not PDF, JPEG or PNG), {@code TYPE_MISMATCH} (the name says otherwise),
 * {@code PDF_MALFORMED}, {@code PDF_ENCRYPTED}, {@code PDF_ACTIVE_CONTENT}, {@code IMAGE_MALFORMED},
 * {@code IMAGE_TOO_LARGE}.
 *
 * @param detected what the bytes were recognised as (a media type), for the record
 */
public record Inspection(DocumentType type, String code, String detected) {

    public static Inspection accepted(DocumentType type) {
        return new Inspection(type, null, type.mediaType());
    }

    public static Inspection refused(String code, String detected) {
        return new Inspection(null, code, detected);
    }

    public boolean ok() {
        return code == null;
    }
}
