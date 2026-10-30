// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.ApplicationDirectory;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.issuance.domain.DocumentNumber;
import org.snigf.mvp.issuance.domain.DocumentRenderer;
import org.snigf.mvp.issuance.domain.DocumentType;
import org.snigf.mvp.issuance.domain.IssuanceRepository;
import org.snigf.mvp.issuance.domain.IssuedDocument;
import org.snigf.mvp.issuance.domain.Manifest;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issuing SNIGF documents (ADR-039). DEMO only: the submission acknowledgment, which establishes no
 * right.
 * <ul>
 *   <li><b>Once per operation.</b> The operation key is locked, then looked up: a retry, or a
 *       concurrent attempt, returns the document already issued; the database's unique key is the
 *       last word.</li>
 *   <li><b>Signed in custody.</b> The manifest binds the SHA-256 of the exact PDF to the number,
 *       type, template, subject, office and time; it is signed with the issuance key in OpenBao and
 *       the signature checked before anything is stored.</li>
 *   <li><b>Statuses.</b> The document officer revokes, cancels or re-issues (the old document then
 *       names its replacement); never their own document; every status is final.</li>
 * </ul>
 * Every issuance, refusal and status change is audited, on the application the document belongs to.
 */
@Service
public class IssuanceService {

    static final String ENTITY = "ISSUED_DOCUMENT";
    static final DocumentType ACK = DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT;

    private final IssuanceRepository registry;
    private final ApplicationDirectory applications;
    private final DocumentRenderer renderer;
    private final KeyService keys;
    private final DatabaseActor actor;
    private final AuditRecorder audit;
    private final IssuanceProperties settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public IssuanceService(IssuanceRepository registry, ApplicationDirectory applications, DocumentRenderer renderer,
                           KeyService keys, DatabaseActor actor, AuditRecorder audit, IssuanceProperties settings,
                           Clock clock, PlatformTransactionManager transactions) {
        this.registry = registry;
        this.applications = applications;
        this.renderer = renderer;
        this.keys = keys;
        this.actor = actor;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** A document and its exact bytes. */
    public record Artifact(IssuedDocument document, byte[] pdf) {
    }

    /**
     * Issues the acknowledgment of the applicant's submitted application, or returns the one already
     * issued. Runs in its own transaction, as the applicant.
     *
     * @throws IssuanceRefused NOT_FOUND (not their submitted application), UNAVAILABLE (key custody)
     */
    public IssuedDocument issueSubmissionAcknowledgment(UUID applicant, UUID applicationId) {
        try {
            return transaction.execute(tx -> {
                actor.bind(applicant);
                ApplicationDirectory.SubmittedApplication app = applications.submitted(applicant, applicationId)
                        .filter(a -> a.applicantId().equals(applicant))
                        .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND"));
                String operation = ACK.operationKey(app.id());
                registry.lockOperation(operation);
                Optional<IssuedDocument> existing = registry.findByOperation(operation);
                if (existing.isPresent()) {
                    return existing.get();
                }
                IssuedDocument issued = issue(ACK, app.id(), app.reference(), app.officeCode(), applicant,
                        printed(app), operation, null);
                Map<String, Object> facts = facts(issued);
                facts.put("trigger", "SUBMISSION");
                audit.record(AuditEvent.of(AuditAction.DOCUMENT_ISSUED, AuditOutcome.SUCCESS)
                        .withEntity(ENTITY, issued.id()).withEntityRef(issued.number()).withDetails(facts));
                return issued;
            });
        } catch (KeyServiceException e) {
            audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_ISSUED, AuditOutcome.FAILED)
                    .withEntity("APPLICATION", applicationId).withReason("KEY_CUSTODY_UNAVAILABLE")
                    .withDetails(Map.of("type", ACK.name())));
            throw new IssuanceRefused(IssuanceRefused.Kind.UNAVAILABLE, "ISSUANCE_UNAVAILABLE");
        }
    }

    /** The acknowledgment of an application the actor may read (its applicant, staff of its office); empty if none yet. */
    public Optional<IssuedDocument> acknowledgment(UUID reader, UUID applicationId) {
        return transaction.execute(tx -> {
            actor.bind(reader);
            visible(reader, applicationId);
            return registry.ofSubject(applicationId, ACK).stream().findFirst();
        });
    }

    /** The exact bytes of the latest acknowledgment, its fingerprint checked before it is served. */
    public Artifact acknowledgmentFile(UUID reader, UUID applicationId) {
        return transaction.execute(tx -> {
            actor.bind(reader);
            visible(reader, applicationId);
            IssuedDocument document = registry.ofSubject(applicationId, ACK).stream().findFirst()
                    .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "NOT_ISSUED"));
            byte[] pdf = registry.artifact(document.id())
                    .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "NOT_ISSUED"));
            if (!MessageDigest.isEqual(Manifest.sha256(pdf), document.sha256())) {
                audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_ISSUED, AuditOutcome.FAILED)
                        .withEntity(ENTITY, document.id()).withEntityRef(document.number()).withReason("ARTIFACT_INTEGRITY"));
                throw new IssuanceRefused(IssuanceRefused.Kind.BROKEN, "ARTIFACT_INTEGRITY_FAILED");
            }
            return new Artifact(document, pdf);
        });
    }

    /** A document as the document officer reads it. */
    public IssuedDocument read(UUID officer, String number) {
        return transaction.execute(tx -> {
            actor.bind(officer);
            return found(number);
        });
    }

    public IssuedDocument revoke(UUID officer, String number, String reason) {
        return close(officer, number, IssuedDocument.Status.REVOKED, reason, AuditAction.DOCUMENT_REVOKED);
    }

    public IssuedDocument cancel(UUID officer, String number, String reason) {
        return close(officer, number, IssuedDocument.Status.CANCELLED, reason, AuditAction.DOCUMENT_CANCELLED);
    }

    /**
     * Re-issues a valid document with a new number and the same facts; the old one is superseded and
     * names it. Idempotent: re-issuing the same number again returns the same replacement.
     */
    public IssuedDocument reissue(UUID officer, String number, String reason) {
        String text = reason(number, reason);
        try {
            return transaction.execute(tx -> {
                actor.bind(officer);
                String operation = "REISSUE:" + DocumentNumber.normalise(number);
                registry.lockOperation(operation);
                Optional<IssuedDocument> done = registry.findByOperation(operation);
                if (done.isPresent()) {
                    return done.get();
                }
                IssuedDocument old = found(number);
                requireChangeable(officer, old);
                IssuedDocument replacement = issue(old.type(), old.subjectId(), old.subjectRef(), old.officeCode(),
                        old.issuedTo(), old.printed(), operation, old.id());
                if (!registry.changeStatus(old.id(), IssuedDocument.Status.SUPERSEDED, text, officer, replacement.id())) {
                    throw refusedStatus(old, "NOT_VALID", IssuanceRefused.Kind.CONFLICT);
                }
                Map<String, Object> facts = facts(replacement);
                facts.put("trigger", "REISSUE");
                facts.put("supersedes", old.number());
                audit.record(AuditEvent.of(AuditAction.DOCUMENT_ISSUED, AuditOutcome.SUCCESS)
                        .withEntity(ENTITY, replacement.id()).withEntityRef(replacement.number()).withDetails(facts));
                audit.record(AuditEvent.of(AuditAction.DOCUMENT_SUPERSEDED, AuditOutcome.SUCCESS)
                        .withEntity(ENTITY, old.id()).withEntityRef(old.number()).withReason(text)
                        .withDetails(Map.of("replacement", replacement.number())));
                return replacement;
            });
        } catch (KeyServiceException e) {
            throw new IssuanceRefused(IssuanceRefused.Kind.UNAVAILABLE, "ISSUANCE_UNAVAILABLE");
        }
    }

    private IssuedDocument close(UUID officer, String number, IssuedDocument.Status status, String reason,
                                 AuditAction action) {
        String text = reason(number, reason);
        return transaction.execute(tx -> {
            actor.bind(officer);
            IssuedDocument document = found(number);
            requireChangeable(officer, document);
            boolean changed;
            try {
                changed = registry.changeStatus(document.id(), status, text, officer, null);
            } catch (DataAccessException e) {
                throw "42501".equals(sqlState(e)) ? refusedStatus(document, "STATUS_REFUSED", IssuanceRefused.Kind.FORBIDDEN)
                        : refusedStatus(document, "NOT_VALID", IssuanceRefused.Kind.CONFLICT);
            }
            if (!changed) {
                throw refusedStatus(document, "NOT_VALID", IssuanceRefused.Kind.CONFLICT);
            }
            audit.record(AuditEvent.of(action, AuditOutcome.SUCCESS).withEntity(ENTITY, document.id())
                    .withEntityRef(document.number()).withReason(text).withDetails(Map.of("type", document.type().name())));
            return found(number);
        });
    }

    /** Draws, fingerprints, signs (checking the signature) and stores one document. */
    private IssuedDocument issue(DocumentType type, UUID subjectId, String subjectRef, String officeCode, UUID holder,
                                 Map<String, String> printed, String operation, UUID supersedes) {
        String number = DocumentNumber.random(random).value();
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        byte[] pdf = renderer.render(type, number, printed, issuedAt, settings.verifyUrl(number));
        byte[] sha256 = Manifest.sha256(pdf);
        byte[] manifest = Manifest.canonical(number, type, type.templateVersion(), "APPLICATION", subjectRef, officeCode,
                issuedAt, sha256);
        Signature signature = keys.sign(SigningKey.DOCUMENT_ISSUANCE, manifest);
        if (!keys.verify(SigningKey.DOCUMENT_ISSUANCE, manifest, signature)) {
            throw new KeyServiceException("the issuance signature does not verify");
        }
        IssuedDocument draft = new IssuedDocument(null, number, type, type.templateVersion(), "APPLICATION", subjectId,
                subjectRef, officeCode, holder, issuedAt, printed, sha256, manifest, signature.encoded(),
                signature.keyVersion(), IssuedDocument.Status.VALID, null, null, null);
        UUID id = registry.insert(draft, operation, supersedes, pdf);
        return registry.findByNumber(number).filter(d -> d.id().equals(id)).orElseThrow();
    }

    private void visible(UUID reader, UUID applicationId) {
        if (applications.submitted(reader, applicationId).isEmpty()) {
            throw new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND");
        }
    }

    private IssuedDocument found(String number) {
        String n = DocumentNumber.normalise(number);
        if (!DocumentNumber.isValid(n)) {
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "INVALID_NUMBER");
        }
        return registry.findByNumber(n).orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "DOCUMENT_NOT_FOUND"));
    }

    private void requireChangeable(UUID officer, IssuedDocument document) {
        if (document.issuedTo().equals(officer)) {
            throw refusedStatus(document, "OWN_DOCUMENT", IssuanceRefused.Kind.FORBIDDEN);
        }
        if (document.status() != IssuedDocument.Status.VALID) {
            throw refusedStatus(document, "NOT_VALID", IssuanceRefused.Kind.CONFLICT);
        }
    }

    private String reason(String number, String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 1000) {
            audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_STATUS_REFUSED, AuditOutcome.FAILED)
                    .withEntityRef(DocumentNumber.normalise(number).length() <= 64 ? DocumentNumber.normalise(number) : null)
                    .withReason("REASON_REQUIRED"));
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "REASON_REQUIRED");
        }
        return text;
    }

    private IssuanceRefused refusedStatus(IssuedDocument document, String code, IssuanceRefused.Kind kind) {
        audit.recordIndependently(AuditEvent.of(AuditAction.DOCUMENT_STATUS_REFUSED,
                        kind == IssuanceRefused.Kind.FORBIDDEN ? AuditOutcome.DENIED : AuditOutcome.FAILED)
                .withEntity(ENTITY, document.id()).withEntityRef(document.number()).withReason(code)
                .withDetails(Map.of("status", document.status().name())));
        return new IssuanceRefused(kind, code);
    }

    private static Map<String, String> printed(ApplicationDirectory.SubmittedApplication app) {
        Map<String, String> printed = new LinkedHashMap<>();
        printed.put("reference", app.reference());
        printed.put("typeFr", app.typeNameFr());
        printed.put("typeEn", app.typeNameEn());
        printed.put("parcel", app.parcelReference() == null ? "" : app.parcelReference());
        printed.put("office", app.officeCode() == null ? "" : app.officeCode());
        printed.put("holder", app.applicantName() == null ? "" : app.applicantName());
        printed.put("submittedAt", app.submittedAt().toString());
        return printed;
    }

    private static Map<String, Object> facts(IssuedDocument d) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("type", d.type().name());
        facts.put("templateVersion", d.templateVersion());
        facts.put("keyVersion", d.keyVersion());
        facts.put("sha256", java.util.HexFormat.of().formatHex(d.sha256()));
        facts.put("demo", true);
        return facts;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

}
