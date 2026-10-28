// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applications and their type catalogue. Every read and write runs under row-level security
 * for the actor the use case named (V13): a row of someone else is simply not there.
 */
public interface ApplicationRepository {

    /** The versions in force of every available type. */
    List<ApplicationType> availableTypes();

    /** The latest version of a type (available or not); empty if it never existed. */
    Optional<ApplicationType> latestType(String code);

    Optional<ApplicationType> type(UUID versionId);

    /** Stores a new version of a type, following its latest one. */
    void addTypeVersion(ApplicationType.Definition definition, int versionNo, boolean available);

    /** Every type code that has a version. */
    List<String> typeCodes();

    UUID createDraft(UUID applicant, String typeCode, UUID typeVersionId, String parcelReference, Map<String, String> data);

    /** How many drafts the applicant holds. */
    int draftCount(UUID applicant);

    /** The actor's own applications, most recently changed first. */
    List<Application> ofApplicant(UUID applicant, int limit, int offset);

    Optional<Application> find(UUID id);

    /**
     * Changes a draft still at {@code expectedVersion}, moving it to the type version in force;
     * false when it changed meanwhile.
     */
    boolean updateDraft(UUID id, long expectedVersion, UUID typeVersionId, String parcelReference, Map<String, String> data);

    /** Deletes a draft still at {@code expectedVersion}; false when it changed or is gone. */
    boolean deleteDraft(UUID id, long expectedVersion);

    /** What a submission writes; the database checks it again (V13). */
    record Submission(UUID typeVersionId, UUID parcelId, String parcelReference, UUID officeId,
                      ApplicationReference reference, UUID actor) {
    }

    boolean submit(UUID id, long expectedVersion, Submission submission);

    List<Application.Transition> transitions(UUID id);

    long nextSequence(int year);

    // --- Completeness (V15) -------------------------------------------------------------------

    /** What the database finds missing now: {@code MISSING:field}, {@code MISSING_DOCUMENT:CATEGORY}. */
    List<String> missingItems(UUID id);

    /** Adds a completeness record; the database computes what was missing and checks who may. */
    void record(UUID id, CompletenessRecord.Kind kind, List<String> requested, String message, UUID actor);

    List<CompletenessRecord> completeness(UUID id);

    /**
     * Moves the state if the application is still at {@code expectedVersion} and {@code from};
     * {@code actor} null only for the automatic check. False when it changed meanwhile.
     */
    boolean move(UUID id, long expectedVersion, ApplicationState from, ApplicationState to, UUID actor, String reason);

    /** A submitted application as staff of its office see it, with its applicant's name. */
    record Case(Application application, String applicantName) {
    }

    /** Applications of an office (row-level security: staff reading there), oldest waiting first. */
    List<Case> ofOffice(String officeCode, ApplicationState state, int limit, int offset);

    Optional<Case> findCase(UUID id);

    /** The office of an application, for the endpoints' permission checks; empty when unknown or a draft. */
    Optional<String> officeCode(UUID id);
}
