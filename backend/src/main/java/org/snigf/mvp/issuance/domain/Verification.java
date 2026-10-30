// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What a presented document proves (ADR-039). Two channels:
 * <ul>
 *   <li><b>a file</b>: its exact bytes are compared with the registry. Only an identical file is
 *       {@code AUTHENTIC}; a file naming a known number with other bytes is {@code ALTERED};
 *       a file claiming to be from SNIGF that nothing matches is {@code UNVERIFIED} and suspicious;
 *       a file with no SNIGF claim at all is simply not one of ours ({@code UNVERIFIED}, no incident);</li>
 *   <li><b>a number</b> (QR code, printed or typed): it confirms that a record exists and its
 *       status ({@code RECORD_FOUND}), never that a paper is unaltered.</li>
 * </ul>
 * Whatever matches is first checked against its own signature: a registry row that no longer
 * agrees with its signed manifest is never trusted ({@code REGISTRY_INTEGRITY}).
 */
public final class Verification {

    private Verification() {
    }

    public enum Channel { NUMBER, FILE }

    public enum Outcome { AUTHENTIC, RECORD_FOUND, ALTERED, UNVERIFIED, REVOKED, SUPERSEDED, CANCELLED }

    /** Why an incident is opened; none for an honest "not found". */
    public enum IncidentKind { ALTERED, FABRICATED, REGISTRY_INTEGRITY }

    /**
     * What the registry holds about a number, as verification needs it. The subject reference is
     * kept only to rebuild the signed manifest; it is never shown to the public.
     */
    public record Evidence(UUID id, String number, DocumentType type, String templateVersion, String subjectType,
                           String subjectRef, Instant issuedAt, String officeCode, String officeNameFr,
                           String officeNameEn, byte[] sha256, byte[] manifest, String signature, int keyVersion,
                           IssuedDocument.Status status, String replacement) {

        /** The stored manifest is exactly the one these fields produce: nothing was rewritten beside it. */
        public boolean consistent() {
            return MessageDigest.isEqual(manifest, Manifest.canonical(number, type, templateVersion, subjectType,
                    subjectRef, officeCode, issuedAt, sha256));
        }
    }

    /** What a presented PDF says about itself: whether it claims SNIGF, and the number it names. */
    public record Claim(boolean pdf, boolean claimsSnigf, String number) {
    }

    /**
     * @param evidence the issuance it matched, if any; null otherwise
     * @param incident why it must be reviewed; null when nothing is suspicious
     */
    public record Result(Outcome outcome, String reason, Evidence evidence, IncidentKind incident) {
    }

    /** {@code signed} recomputes the signature of the evidence's manifest in key custody. */
    public static Result ofNumber(Optional<Evidence> found, Predicate<Evidence> signed) {
        if (found.isEmpty()) {
            return new Result(Outcome.UNVERIFIED, "UNKNOWN_NUMBER", null, null);
        }
        Evidence e = found.get();
        if (!trusted(e, signed)) {
            return new Result(Outcome.UNVERIFIED, "REGISTRY_INTEGRITY", e, IncidentKind.REGISTRY_INTEGRITY);
        }
        return e.status() == IssuedDocument.Status.VALID
                ? new Result(Outcome.RECORD_FOUND, "RECORD_FOUND", e, null)
                : new Result(statusOutcome(e.status()), "STATUS_" + e.status().name(), e, null);
    }

    /**
     * @param presentedSha256 the SHA-256 of the presented bytes
     * @param bySha           the issuance with exactly these bytes, if any
     * @param byNumber        the issuance the file's claimed number names, if any
     */
    public static Result ofFile(byte[] presentedSha256, Claim claim, Optional<Evidence> bySha, Optional<Evidence> byNumber,
                                Predicate<Evidence> signed) {
        if (bySha.isPresent()) {
            Evidence e = bySha.get();
            if (!trusted(e, signed)) {
                return new Result(Outcome.UNVERIFIED, "REGISTRY_INTEGRITY", e, IncidentKind.REGISTRY_INTEGRITY);
            }
            return e.status() == IssuedDocument.Status.VALID
                    ? new Result(Outcome.AUTHENTIC, "EXACT_MATCH", e, null)
                    : new Result(statusOutcome(e.status()), "EXACT_MATCH_STATUS_" + e.status().name(), e, null);
        }
        if (!claim.pdf()) {
            return new Result(Outcome.UNVERIFIED, "NOT_A_PDF", null, null);
        }
        if (byNumber.isPresent()) {
            Evidence e = byNumber.get();
            if (!trusted(e, signed)) {
                return new Result(Outcome.UNVERIFIED, "REGISTRY_INTEGRITY", e, IncidentKind.REGISTRY_INTEGRITY);
            }
            // A known number on other bytes: what was issued is not what is presented.
            return new Result(Outcome.ALTERED, "CONTENT_DIFFERS", e, IncidentKind.ALTERED);
        }
        if (claim.claimsSnigf() || claim.number() != null) {
            return new Result(Outcome.UNVERIFIED, "NO_ISSUANCE_RECORD", null, IncidentKind.FABRICATED);
        }
        return new Result(Outcome.UNVERIFIED, "NO_SNIGF_EVIDENCE", null, null);
    }

    private static boolean trusted(Evidence e, Predicate<Evidence> signed) {
        return e.consistent() && signed.test(e);
    }

    private static Outcome statusOutcome(IssuedDocument.Status status) {
        return switch (status) {
            case VALID -> Outcome.AUTHENTIC;
            case SUPERSEDED -> Outcome.SUPERSEDED;
            case REVOKED -> Outcome.REVOKED;
            case CANCELLED -> Outcome.CANCELLED;
        };
    }
}
