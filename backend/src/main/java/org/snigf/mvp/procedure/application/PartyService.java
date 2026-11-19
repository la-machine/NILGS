// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.application;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.procedure.domain.Parties;
import org.snigf.mvp.procedure.domain.PartyRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The persons of a case at intake (ADR-043 step 5a): an officer scans the documents presented at the counter and
 * transcribes each identity, the ownership statement and each mandate from them; a second officer checks each version,
 * never their own. Every write is one call to V35's functions, which decide; each is audited without the identity
 * numbers themselves, and every refusal is audited on its own. Nothing here verifies an identity: a check says the entry
 * corresponds to the document presented (D39).
 */
@Service
public class PartyService {

    private final PartyRepository parties;
    private final ProcedureService procedures;
    private final DocumentFiles files;
    private final DatabaseActor actor;
    private final AuditRecorder audit;

    public PartyService(PartyRepository parties, ProcedureService procedures, DocumentFiles files, DatabaseActor actor, AuditRecorder audit) {
        this.parties = parties;
        this.procedures = procedures;
        this.files = files;
        this.actor = actor;
        this.audit = audit;
    }

    /** A file to send, already checked against its hash. */
    public record FileDownload(Parties.File file, InputStream content) {
    }

    @Transactional(readOnly = true)
    public Parties.View read(AuthenticatedUser staff, UUID applicationId) {
        actor.bind(staff.userId());
        return view(applicationId);
    }

    /** Keeps a document presented at the counter (ADR-033 checks), for the transcriptions to cite. */
    @Transactional
    public Parties.View addFile(AuthenticatedUser officer, UUID applicationId, String purpose, String givenName, InputStream content) {
        actor.bind(officer.userId());
        if (purpose == null || !Parties.PURPOSES.contains(purpose)) {
            throw refused(applicationId, "ADD_PARTY_FILE", ProcedureRefused.of("PURPOSE_INVALID"));
        }
        String key = null;
        try (DocumentFiles.Received received = files.receive(content, givenName)) {
            key = received.keep();
            Parties.KeptFile kept = new Parties.KeptFile(key, received.sha256(), received.sizeBytes(), received.mediaType(), received.name());
            UUID id = guarded(applicationId, "ADD_PARTY_FILE", () -> parties.addFile(applicationId, purpose, kept));
            audited(AuditAction.CASE_PARTY_FILE_ADDED, applicationId, Map.of("file", id.toString(), "purpose", purpose, "sha256", kept.sha256()));
            return view(applicationId);
        } catch (DocumentFiles.Refused e) {
            throw refused(applicationId, "ADD_PARTY_FILE", ProcedureRefused.of(e.code()));
        } catch (RuntimeException e) {
            if (key != null) {
                files.forget(key);
            }
            throw e;
        }
    }

    /** Transcribes a person's identity from a cited document; a correction names the version it replaces, with a reason. */
    @Transactional
    public Parties.View recordIdentity(AuthenticatedUser officer, UUID applicationId, UUID personId, Parties.NewIdentity identity, UUID sourceFile,
                                       UUID sourceVersion, UUID supersedes, String reason) {
        actor.bind(officer.userId());
        requirePerson(applicationId, personId);
        UUID id = guarded(applicationId, "RECORD_IDENTITY",
                () -> parties.recordIdentity(personId, identity, sourceFile, sourceVersion, supersedes, ProcedureService.truncate(reason)));
        boolean correction = reason != null && !reason.isBlank();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("person", personId.toString());
        details.put("version", id.toString());
        details.put("verification", "UNVERIFIED_DEMO");
        AuditEvent event = AuditEvent.of(correction ? AuditAction.CASE_PARTY_IDENTITY_CORRECTED : AuditAction.CASE_PARTY_IDENTITY_RECORDED,
                AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId).withDetails(details);
        audit.record(correction ? event.withReason(ProcedureService.truncate(reason)) : event);
        return view(applicationId);
    }

    /** Records the ownership statement from the supporting documents, each share citing its own. */
    @Transactional
    public Parties.View recordOwnership(AuthenticatedUser officer, UUID applicationId, List<Parties.NewShare> shares, UUID supersedes, String reason) {
        actor.bind(officer.userId());
        UUID id = guarded(applicationId, "RECORD_OWNERSHIP",
                () -> parties.recordOwnership(applicationId, shares, supersedes, ProcedureService.truncate(reason)));
        AuditEvent event = AuditEvent.of(AuditAction.CASE_OWNERSHIP_RECORDED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withDetails(Map.of("statement", id.toString(), "shares", shares == null ? 0 : shares.size(), "verification", "UNVERIFIED_DEMO"));
        audit.record(reason == null || reason.isBlank() ? event : event.withReason(ProcedureService.truncate(reason)));
        return view(applicationId);
    }

    /** Records a representative's mandate from its document: whom they represent, and the scope. */
    @Transactional
    public Parties.View recordMandate(AuthenticatedUser officer, UUID applicationId, UUID representativeId, List<UUID> represents, String scope,
                                      UUID documentFile, UUID supersedes, String reason) {
        actor.bind(officer.userId());
        requirePerson(applicationId, representativeId);
        UUID id = guarded(applicationId, "RECORD_MANDATE",
                () -> parties.recordMandate(representativeId, represents, scope, documentFile, supersedes, ProcedureService.truncate(reason)));
        AuditEvent event = AuditEvent.of(AuditAction.CASE_MANDATE_RECORDED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withDetails(Map.of("mandate", id.toString(), "representative", representativeId.toString(), "represents",
                        represents == null ? 0 : represents.size(), "scope", String.valueOf(scope)));
        audit.record(reason == null || reason.isBlank() ? event : event.withReason(ProcedureService.truncate(reason)));
        return view(applicationId);
    }

    /** A second officer's check of one recorded version: whether it corresponds to the document presented. */
    @Transactional
    public Parties.View check(AuthenticatedUser officer, UUID applicationId, String targetKind, UUID targetId, String outcome, String note) {
        actor.bind(officer.userId());
        if (targetKind == null || !Parties.CHECK_KINDS.contains(targetKind)) {
            throw refused(applicationId, "CHECK_PARTY", ProcedureRefused.of("CHECK_TARGET_INVALID"));
        }
        requireTarget(applicationId, targetKind, targetId);
        UUID id = guarded(applicationId, "CHECK_PARTY", () -> parties.check(targetKind, targetId, outcome, ProcedureService.truncate(note)));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("check", id.toString());
        details.put("target", targetKind);
        details.put("version", targetId.toString());
        details.put("outcome", outcome);
        details.put("meaning", "MATCHES_PRESENTED_DOCUMENT_NOT_AUTHENTICATED");
        audit.record(AuditEvent.of(AuditAction.CASE_PARTY_CHECKED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withDetails(details));
        return view(applicationId);
    }

    /**
     * A supervisor's documented exception under the DEMO policy for an identity document that had expired when it was
     * transcribed: never the officer who transcribed it, once, with a written reason. Without it, or a replacement by a
     * valid document, the expired document holds the case. It changes nothing in the transcription or its check.
     */
    @Transactional
    public Parties.View authoriseException(AuthenticatedUser supervisor, UUID applicationId, UUID identityVersionId, String policy, String reason) {
        actor.bind(supervisor.userId());
        requireTarget(applicationId, "IDENTITY", identityVersionId);
        UUID id = guarded(applicationId, "AUTHORISE_EXCEPTION", () -> parties.authoriseException(identityVersionId, policy, ProcedureService.truncate(reason)));
        audit.record(AuditEvent.of(AuditAction.CASE_PARTY_EXCEPTION_AUTHORISED, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId)
                .withReason(ProcedureService.truncate(reason))
                .withDetails(Map.of("exception", id.toString(), "version", identityVersionId.toString(), "policy", String.valueOf(policy))));
        return view(applicationId);
    }

    /** A counter file, as an attachment; its hash is checked first, and the read is audited. */
    @Transactional
    public FileDownload download(AuthenticatedUser staff, UUID applicationId, UUID fileId) {
        actor.bind(staff.userId());
        Parties.File file = parties.file(applicationId, fileId).orElseThrow(() -> ProcedureRefused.of("FILE_NOT_FOUND"));
        if (!files.intact(file.storageKey(), file.sha256(), file.sizeBytes())) {
            throw refused(applicationId, "DOWNLOAD_PARTY_FILE", ProcedureRefused.of("FILE_INTEGRITY_FAILED"));
        }
        audited(AuditAction.CASE_PARTY_FILE_DOWNLOADED, applicationId, Map.of("file", file.id().toString(), "purpose", file.purpose()));
        return new FileDownload(file, files.open(file.storageKey()));
    }

    private Parties.View view(UUID applicationId) {
        return parties.view(applicationId).orElseThrow(() -> ProcedureRefused.of("CASE_NOT_FOUND"));
    }

    private void requirePerson(UUID applicationId, UUID personId) {
        if (personId == null || !parties.caseOfPerson(personId).map(applicationId::equals).orElse(false)) {
            throw refused(applicationId, "RECORD_PARTY", ProcedureRefused.of("PERSON_NOT_FOUND"));
        }
    }

    /** A check names a version of this case, never another's. */
    private void requireTarget(UUID applicationId, String kind, UUID targetId) {
        Parties.View v = view(applicationId);
        boolean ours = switch (kind) {
            case "IDENTITY" -> v.persons().stream().flatMap(p -> p.identities().stream()).anyMatch(i -> i.id().equals(targetId));
            case "OWNERSHIP" -> v.statements().stream().anyMatch(s -> s.id().equals(targetId));
            default -> v.mandates().stream().anyMatch(m -> m.id().equals(targetId));
        };
        if (!ours) {
            throw refused(applicationId, "CHECK_PARTY", ProcedureRefused.of("CHECK_TARGET_INVALID"));
        }
    }

    private void audited(AuditAction action, UUID applicationId, Map<String, Object> details) {
        audit.record(AuditEvent.of(action, AuditOutcome.SUCCESS).withEntity(ProcedureService.ENTITY, applicationId).withDetails(details));
    }

    private <T> T guarded(UUID applicationId, String operation, Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            throw refused(applicationId, operation, ProcedureRefused.translated(e));
        }
    }

    private ProcedureRefused refused(UUID applicationId, String operation, ProcedureRefused refusal) {
        return procedures.refused(applicationId, null, operation, refusal);
    }
}
