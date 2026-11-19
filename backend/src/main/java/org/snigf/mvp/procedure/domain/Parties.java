// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The persons of a case (ADR-043): who applies, who represents whom, for whom the land is to be registered, and in what
 * shares. Every identity is DEMO data entered by hand and never independently verified ({@code UNVERIFIED_DEMO}); a check
 * {@code MATCHES} only says the entry corresponds to the document presented.
 */
public final class Parties {

    private Parties() {
    }

    public static final Set<String> PURPOSES = Set.of("IDENTITY_DOCUMENT", "MANDATE", "OWNERSHIP_EVIDENCE");
    public static final Set<String> CHECK_KINDS = Set.of("IDENTITY", "OWNERSHIP", "MANDATE");
    public static final Set<String> OUTCOMES = Set.of("MATCHES", "DOES_NOT_MATCH");

    /** A document presented at the counter and scanned by the officer. */
    public record File(UUID id, String purpose, String storageKey, String sha256, long sizeBytes, String mediaType, String originalName,
                       UUID uploadedById, String uploadedByName, Instant uploadedAt) {
    }

    /** One check of one recorded version, by someone other than its recorder. */
    public record Check(UUID id, String outcome, String note, UUID checkedById, String checkedByName, Instant checkedAt) {
    }

    /**
     * One version of a person's identity: DECLARED by the applicant, or RECORDED by an officer from a cited document.
     * {@code validUntil}: the document's own expiry, when it has one; {@code expiredAtTranscription}: it had passed on the day it
     * was transcribed. Validity, never the accuracy a check speaks of. {@code exception}: the supervisor's documented
     * exception under the DEMO policy, if any.
     */
    public record Identity(UUID id, int versionNo, String source, String fullName, String identityKind, String identityNumber, String phone,
                           String postalAddress, java.time.LocalDate validUntil, boolean expiredAtTranscription, UUID sourceFileId,
                           UUID sourceVersionId, UUID enteredById, String enteredByName, Instant enteredAt, UUID supersedesId,
                           String correctionReason, String verification, Check check, PolicyException exception) {
    }

    /** A documented exception under a defined DEMO policy (an expired identity document), by a supervisor who did not transcribe it. */
    public record PolicyException(UUID id, String policy, String reason, UUID authorisedById, String authorisedByName, Instant authorisedAt) {
    }

    public static final String EXPIRED_DOCUMENT_POLICY = "DEMO_EXPIRED_IDENTITY_DOCUMENT";

    /** A person of the case, their explicit roles, and their identity versions, newest first. */
    public record Person(UUID id, String ref, String kind, List<String> roles, boolean applicantAccount, List<Identity> identities) {
    }

    /** A right and its share: sole ownership, co-ownership, usufruct (with its term) or bare ownership. */
    public record Share(UUID personId, String personRef, String interest, int shareNum, int shareDen, String usufructTerm,
                        java.time.LocalDate usufructUntil, UUID basisFileId, UUID basisVersionId) {
    }

    /** One version of the ownership statement, newest first in the view. */
    public record Statement(UUID id, int versionNo, String source, UUID enteredById, String enteredByName, Instant enteredAt, UUID supersedesId,
                            String correctionReason, String verification, List<Share> shares, Check check) {
    }

    /** One version of a representative's mandate. */
    public record Mandate(UUID id, UUID representativeId, String representativeRef, int versionNo, String source, String scope,
                          List<String> represents, UUID documentFileId, UUID enteredById, String enteredByName, Instant enteredAt,
                          UUID supersedesId, String correctionReason, String verification, Check check) {
    }

    /** The persons of a case as an officer reads them, with what they still need ({@code problems}). */
    public record View(String capacity, List<Person> persons, List<Statement> statements, List<Mandate> mandates, List<File> files,
                       List<String> problems) {
    }

    public record NewIdentity(String fullName, String identityKind, String identityNumber, String phone, String postalAddress,
                              java.time.LocalDate validUntil) {
    }

    public record NewShare(UUID person, String interest, int shareNum, int shareDen, String usufructTerm, java.time.LocalDate usufructUntil,
                           UUID basisFile, UUID basisVersion) {
    }

    /** A file kept through ADR-033's checks, to record. */
    public record KeptFile(String storageKey, String sha256, long sizeBytes, String mediaType, String name) {
    }
}
