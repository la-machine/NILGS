// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A document SNIGF issued, as the registry holds it (V20). Everything but the status is fixed at
 * issuance; the status only leaves {@code VALID}, once.
 *
 * @param printed     what the document prints (reference, type, parcel, office, holder, date), for a re-issue
 * @param sha256      the SHA-256 of the exact PDF issued
 * @param manifest    the canonical bytes that were signed ({@link Manifest})
 * @param signature   {@code snigf:v<key version>:<base64>}
 * @param replacement the number of the document that replaced it; null unless superseded
 */
public record IssuedDocument(UUID id, String number, DocumentType type, String templateVersion, String subjectType,
                             UUID subjectId, String subjectRef, String officeCode, UUID issuedTo, Instant issuedAt,
                             Map<String, String> printed, byte[] sha256, byte[] manifest, String signature,
                             int keyVersion, Status status, String statusReason, Instant statusChangedAt,
                             String replacement) {

    public enum Status { VALID, SUPERSEDED, REVOKED, CANCELLED }

    public IssuedDocument {
        printed = Map.copyOf(printed);
        sha256 = sha256.clone();
        manifest = manifest.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    @Override
    public byte[] manifest() {
        return manifest.clone();
    }
}
