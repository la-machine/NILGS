// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.application.ApplicationDirectory;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.domain.DocumentName;
import org.snigf.mvp.document.domain.DocumentRepository;
import org.snigf.mvp.document.domain.DocumentVersion;
import org.snigf.mvp.document.domain.Inspection;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supporting documents of an application (MVP-02, E-13, ADR-033). An upload is:
 * <ol>
 *   <li>allowed only on the caller's own application while it takes documents, in a category of
 *       its type (404 for anyone else's application, as everywhere);</li>
 *   <li>staged: at most {@code SNIGF_DOCUMENT_MAX_BYTES}, counted and hashed (SHA-256) as it
 *       arrives, never trusted for its declared size or type;</li>
 *   <li>inspected from its bytes: PDF, JPEG or PNG only, the name's extension agreeing, no PDF
 *       active content or encryption, no oversized image;</li>
 *   <li>stored under a random key and recorded as a new version (numbered by the database).</li>
 * </ol>
 * A refusal is audited with its code and nothing is kept. A download checks the hash first and
 * is audited; bytes that no longer match are never served. Files nobody references (a crash
 * between storing and committing, a deleted draft) are removed by {@link #removeOrphans()}.
 * Malware scanning (ClamAV) is a pilot requirement (P-02).
 */
@Service
public class DocumentService {

    static final String ENTITY = "DOCUMENT";
    static final String UPLOAD_ENDPOINT = "POST /api/v1/applications/{id}/documents/{category}";
    private static final int CLEANUP_BATCH = 500;

    private final ApplicationDirectory applications;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final ContentInspector inspector;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final DocumentProperties settings;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public DocumentService(ApplicationDirectory applications, DocumentRepository documents, DocumentStorage storage,
                           ContentInspector inspector, IdempotencyStore idempotency, AuditRecorder audit,
                           DocumentProperties settings, Clock clock) {
        this.applications = applications;
        this.documents = documents;
        this.storage = storage;
        this.inspector = inspector;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** What an upload answers; stored for idempotent retries. */
    public record Uploaded(UUID id, String category, int versionNo, long sizeBytes, String type, String sha256) {
    }

    public record Outcome(Uploaded uploaded, boolean replayed) {
    }

    /** The categories of the application with their versions, newest first. */
    public record Listing(ApplicationDirectory.OwnApplication application, List<DocumentVersion> versions) {
    }

    /** A file to send: its stored version and its bytes, already checked against the hash. */
    public record Download(DocumentVersion version, InputStream content) {
    }

    @Transactional
    public Outcome upload(AuthenticatedUser user, UUID applicationId, String category, String givenName,
                          InputStream content, String idempotencyKey) {
        ApplicationDirectory.OwnApplication application = own(user, applicationId);
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new DocumentRefused(DocumentRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        if (application.category(category).isEmpty()) {
            throw new DocumentRefused(DocumentRefused.Kind.INVALID, "CATEGORY_UNKNOWN");
        }
        if (!application.acceptsDocuments()) {
            throw new DocumentRefused(DocumentRefused.Kind.CONFLICT, "DOCUMENTS_CLOSED");
        }
        if (!application.openCategories().contains(category)) {
            // During an information request, only the categories asked for take files (V15).
            throw new DocumentRefused(DocumentRefused.Kind.CONFLICT, "CATEGORY_NOT_REQUESTED");
        }
        String name = DocumentName.clean(givenName);
        if (name.isEmpty()) {
            throw refused(applicationId, category, "NAME_REQUIRED", 0, null);
        }

        DocumentStorage.Staged staged;
        try {
            staged = storage.stage(content, settings.maxBytes());
        } catch (DocumentStorage.TooLarge e) {
            audit.recordIndependently(rejection(applicationId, category, "FILE_TOO_LARGE", -1, null));
            throw new DocumentRefused(DocumentRefused.Kind.TOO_LARGE, "FILE_TOO_LARGE");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String key = null;
        try {
            if (staged.size() == 0) {
                throw refused(applicationId, category, "FILE_EMPTY", 0, null);
            }
            String requestHash = IdempotencyStore.sha256(applicationId + ":" + category + ":" + staged.sha256() + ":" + name);
            Optional<IdempotencyStore.Stored> earlier = idempotency.find(user.userId(), idempotencyKey);
            if (earlier.isPresent()) {
                if (!earlier.get().requestSha256().equals(requestHash)) {
                    throw new DocumentRefused(DocumentRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REUSED");
                }
                return new Outcome(json.readValue(earlier.get().body(), Uploaded.class), true);
            }
            Inspection inspection = inspector.inspect(staged.file(), name);
            if (!inspection.ok()) {
                throw refused(applicationId, category, inspection.code(), staged.size(), inspection.detected());
            }
            UUID documentId = documents.documentFor(applicationId, category);
            if (documents.versionCount(documentId) >= settings.maxVersions()) {
                throw new DocumentRefused(DocumentRefused.Kind.CONFLICT, "VERSION_LIMIT_REACHED");
            }
            key = storage.commit(staged);
            DocumentVersion version = documents.add(new DocumentRepository.NewVersion(documentId, key, staged.sha256(),
                    staged.size(), inspection.type(), name, user.userId()));
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("category", category);
            facts.put("versionNo", version.versionNo());
            facts.put("type", inspection.type().mediaType());
            facts.put("sizeBytes", staged.size());
            facts.put("sha256", staged.sha256());
            facts.put("application", applicationId.toString());
            audit.record(AuditEvent.of(AuditAction.DOCUMENT_UPLOADED, AuditOutcome.SUCCESS)
                    .withEntity(ENTITY, version.id()).withDetails(facts));
            Uploaded uploaded = new Uploaded(version.id(), category, version.versionNo(), staged.size(),
                    inspection.type().mediaType(), staged.sha256());
            try {
                idempotency.save(user.userId(), idempotencyKey, UPLOAD_ENDPOINT, requestHash, 201,
                        json.writeValueAsString(uploaded));
            } catch (IdempotencyStore.InProgress e) {
                throw new DocumentRefused(DocumentRefused.Kind.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
            }
            return new Outcome(uploaded, false);
        } catch (IOException e) {
            forget(key);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            // The transaction rolls back: a file stored for it must not stay (the cleanup would
            // remove it later anyway).
            forget(key);
            throw e;
        } finally {
            storage.discard(staged);
        }
    }

    @Transactional(readOnly = true)
    public Listing list(AuthenticatedUser user, UUID applicationId) {
        ApplicationDirectory.OwnApplication application = own(user, applicationId);
        return new Listing(application, documents.ofApplication(applicationId));
    }

    /** The documents of a submitted application, for staff reading at its office (E-14). */
    @Transactional(readOnly = true)
    public Listing listForStaff(AuthenticatedUser staff, UUID applicationId) {
        ApplicationDirectory.OwnApplication application = staffCase(staff, applicationId);
        return new Listing(application, documents.ofApplication(applicationId));
    }

    /** A stored version of a submitted application, for staff reading at its office; audited like any download. */
    @Transactional
    public Download downloadForStaff(AuthenticatedUser staff, UUID applicationId, UUID versionId) {
        staffCase(staff, applicationId);
        return send(applicationId, versionId);
    }

    /** The bytes of a stored version, checked against its hash before anything is sent. */
    @Transactional
    public Download download(AuthenticatedUser user, UUID applicationId, UUID versionId) {
        own(user, applicationId);
        return send(applicationId, versionId);
    }

    private Download send(UUID applicationId, UUID versionId) {
        DocumentVersion version = documents.version(applicationId, versionId)
                .filter(v -> v.status() == DocumentVersion.Status.STORED)
                .orElseThrow(() -> new DocumentRefused(DocumentRefused.Kind.NOT_FOUND, "DOCUMENT_NOT_FOUND"));
        if (!intact(version)) {
            audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_INTEGRITY_FAILED, AuditOutcome.FAILED)
                    .withEntity(ENTITY, version.id()).withReason("SHA256_MISMATCH")
                    .withDetails(Map.of("category", version.category(), "versionNo", version.versionNo())));
            throw new DocumentRefused(DocumentRefused.Kind.BROKEN, "DOCUMENT_INTEGRITY_FAILED");
        }
        audit.record(AuditEvent.of(AuditAction.DOCUMENT_DOWNLOADED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, version.id())
                .withDetails(Map.of("category", version.category(), "versionNo", version.versionNo())));
        try {
            return new Download(version, storage.open(version.storageKey()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes stored files no version names and staged leftovers, once older than the grace
     * period (an upload in progress is never touched). Returns the number of files removed.
     */
    @Transactional
    public int removeOrphans() {
        Instant before = clock.instant().minus(settings.orphanGrace());
        int removed = 0;
        try {
            List<DocumentStorage.StoredObject> old = storage.objectsOlderThan(before);
            for (int from = 0; from < old.size(); from += CLEANUP_BATCH) {
                List<String> keys = old.subList(from, Math.min(old.size(), from + CLEANUP_BATCH)).stream()
                        .map(DocumentStorage.StoredObject::key).toList();
                Set<String> known = documents.knownKeys(keys);
                for (String key : keys) {
                    if (!known.contains(key)) {
                        storage.delete(key);
                        removed++;
                    }
                }
            }
            int staging = storage.purgeStaging(before);
            if (removed + staging > 0) {
                audit.record(AuditEvent.of(AuditAction.DOCUMENT_ORPHANS_REMOVED, AuditOutcome.SUCCESS)
                        .withDetails(Map.of("stored", removed, "staged", staging, "olderThan", before.toString())));
            }
            return removed + staging;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ApplicationDirectory.OwnApplication staffCase(AuthenticatedUser staff, UUID applicationId) {
        return applications.forStaff(staff.userId(), applicationId)
                .orElseThrow(() -> new DocumentRefused(DocumentRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    private ApplicationDirectory.OwnApplication own(AuthenticatedUser user, UUID applicationId) {
        return applications.own(user.userId(), applicationId).orElseThrow(() -> {
            audit.recordIndependently(AuditEvent.of(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .withEntity("APPLICATION", applicationId).withReason("APPLICATION_NOT_FOUND"));
            return new DocumentRefused(DocumentRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND");
        });
    }

    private boolean intact(DocumentVersion version) {
        try (InputStream in = new DigestInputStream(storage.open(version.storageKey()), sha256())) {
            long size = in.transferTo(java.io.OutputStream.nullOutputStream());
            String actual = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
            return size == version.sizeBytes() && actual.equals(version.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    private DocumentRefused refused(UUID applicationId, String category, String code, long size, String detected) {
        audit.recordIndependently(rejection(applicationId, category, code, size, detected));
        return new DocumentRefused(DocumentRefused.Kind.INVALID, code);
    }

    private static AuditEvent rejection(UUID applicationId, String category, String code, long size, String detected) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("category", category);
        facts.put("code", code);
        if (size >= 0) {
            facts.put("sizeBytes", size);
        }
        if (detected != null) {
            facts.put("detected", detected);
        }
        return AuditEvent.of(AuditAction.DOCUMENT_REJECTED, AuditOutcome.FAILED)
                .withEntity("APPLICATION", applicationId).withReason(code).withDetails(facts);
    }

    private void forget(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (IOException ignored) {
            // The cleanup removes it once the grace period is over.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Kept for diagnostics: the categories an application still lacks a stored version for. */
    public static List<String> missingRequired(Listing listing) {
        List<String> missing = new ArrayList<>();
        for (ApplicationDirectory.DocumentCategory c : listing.application().categories()) {
            boolean present = listing.versions().stream()
                    .anyMatch(v -> v.category().equals(c.code()) && v.status() == DocumentVersion.Status.STORED);
            if (c.required() && !present) {
                missing.add(c.code());
            }
        }
        return missing;
    }
}
