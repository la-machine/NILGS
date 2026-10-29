// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditEncoding;
import org.snigf.mvp.audit.AuditEventFilter;
import org.snigf.mvp.audit.AuditEventView;
import org.snigf.mvp.audit.AuditIntegrityException;
import org.snigf.mvp.audit.AuditTrail;
import org.snigf.mvp.audit.AuditedPrincipal;
import org.snigf.mvp.audit.ChainVerification;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.application.AuditTrailService;

import org.snigf.mvp.keys.SigningPublicKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The auditor's API (Slice 1 design §5, S1-AC5): search events, read a case's or a parcel's
 * timeline, verify the chain, sign a checkpoint, list and download checkpoints and the public
 * keys that verify them. Every read and verification is itself audited.
 *
 * <p>Event search and the timeline need {@code audit.event.search} somewhere: held everywhere
 * (the auditor), every event; held at an office (the supervisor), the events whose subjects
 * belong to that office, as the database decides for each row (ADR-027, ADR-038). Every other
 * endpoint needs its permission everywhere.
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    private final AuditTrail trail;
    private final AuditTrailService service;
    private final JsonMapper json = JsonMapper.builder().build();

    AuditController(AuditTrail trail, AuditTrailService service) {
        this.trail = trail;
        this.service = service;
    }

    record VerifyRequest(Long fromPos, Long toPos) {
    }

    /**
     * An event with its details as JSON, not as a string.
     *
     * @param actorName the account's current name, for reading only (the event records its id)
     * @param subjects  what it is about, with each record's office at the time (ADR-038)
     * @param intact    its hash recomputes from its own row and stored link
     */
    record EventResponse(long chainPos, UUID id, Instant occurredAt, String actorType, UUID actorId, String actorLabel,
                         List<String> actorRoles, String action, String entityType, UUID entityId, String entityRef,
                         String outcome, String reason, UUID correlationId, String sourceIp, JsonNode details,
                         String prevHash, String hash, String actorName, List<AuditEventView.Subject> subjects,
                         boolean intact) {
    }

    record TimelineResponse(String subjectType, UUID subjectId, String reference, List<EventResponse> events,
                            boolean truncated) {
    }

    record CheckpointResponse(UUID id, long chainPos, String headHash, String createdAt, int keyVersion,
                              String signature, String file) {
    }

    @GetMapping("/events")
    @PreAuthorize("@access.canSomewhere(authentication, 'audit.event.search')")
    Map<String, Object> events(@AuthenticationPrincipal AuditedPrincipal reader,
                               @RequestParam(required = false) String entityType,
                               @RequestParam(required = false) UUID entityId,
                               @RequestParam(required = false) String subjectType,
                               @RequestParam(required = false) UUID subjectId,
                               @RequestParam(required = false) UUID actorId,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) Instant from,
                               @RequestParam(required = false) Instant to,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        AuditEventView.Page result = trail.events(reader.userId(), new AuditEventFilter(blankToNull(entityType), entityId,
                blankToNull(subjectType), subjectId, actorId, blankToNull(action), from, to, page, size));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", result.events().stream().map(this::response).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("more", result.more());
        return body;
    }

    /** {@code ?type=APPLICATION&reference=DEMO-APP-...}, or {@code &id=}; 404 when nothing is readable. */
    @GetMapping("/timeline")
    @PreAuthorize("@access.canSomewhere(authentication, 'audit.event.search')")
    ResponseEntity<?> timeline(@AuthenticationPrincipal AuditedPrincipal reader, @RequestParam String type,
                               @RequestParam(required = false) UUID id, @RequestParam(required = false) String reference) {
        return trail.timeline(reader.userId(), type, id, blankToNull(reference))
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(new TimelineResponse(t.subjectType(), t.subjectId(),
                        t.reference(), t.events().stream().map(this::response).toList(), t.truncated())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND, "TIMELINE_NOT_FOUND")));
    }

    @PostMapping("/verify")
    @PreAuthorize("@access.can(authentication, 'audit.chain.verify')")
    ChainVerification verify(@RequestBody(required = false) VerifyRequest body) {
        Long from = body == null ? null : body.fromPos();
        Long to = body == null ? null : body.toPos();
        if ((from != null && from < 1) || (to != null && to < 1) || (from != null && to != null && from > to)) {
            throw new IllegalArgumentException("invalid range");
        }
        return trail.verify(from, to);
    }

    @PostMapping("/checkpoints")
    @PreAuthorize("@access.can(authentication, 'audit.checkpoint.create')")
    ResponseEntity<CheckpointResponse> createCheckpoint() {
        Checkpoint checkpoint = trail.createCheckpoint();
        CheckpointResponse response = response(checkpoint);
        return ResponseEntity.created(URI.create(response.file())).body(response);
    }

    @GetMapping("/checkpoints")
    @PreAuthorize("@access.can(authentication, 'audit.checkpoint.list')")
    List<CheckpointResponse> checkpoints() {
        return trail.checkpoints().stream().map(AuditController::response).toList();
    }

    /** The archived file, byte for byte, as the auditor keeps it off-host. */
    @GetMapping("/checkpoints/{chainPos}/file")
    @PreAuthorize("@access.can(authentication, 'audit.checkpoint.download')")
    ResponseEntity<byte[]> checkpointFile(@PathVariable long chainPos) {
        return trail.checkpointFile(chainPos).map(bytes -> {
            service.recordCheckpointDownload(chainPos);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"checkpoint-" + chainPos + ".json\"")
                    .body(bytes);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Public keys of every version, as {@code checkpoint-keys.json} lists them. */
    @GetMapping("/checkpoint-keys")
    @PreAuthorize("@access.can(authentication, 'audit.key.read')")
    List<Map<String, Object>> checkpointKeys() {
        return trail.checkpointPublicKeys().stream().map(AuditController::key).toList();
    }

    @ExceptionHandler(AuditIntegrityException.class)
    ResponseEntity<ProblemDetail> broken(AuditIntegrityException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "AUDIT_CHAIN_BROKEN");
        problem.setProperty("firstMismatchPos", e.verification().firstMismatchPos());
        problem.setProperty("mismatchKind", e.verification().mismatchKind());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** The chain is empty, or another checkpoint is being made. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> unavailable() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, "CHECKPOINT_UNAVAILABLE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid() {
        return ResponseEntity.unprocessableContent().body(problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private EventResponse response(AuditEventView e) {
        return new EventResponse(e.chainPos(), e.id(), e.occurredAt(), e.actorType(), e.actorId(), e.actorLabel(),
                e.actorRoles(), e.action(), e.entityType(), e.entityId(), e.entityRef(), e.outcome(), e.reason(),
                e.correlationId(), e.sourceIp(), json.readTree(e.detailsJson()), e.prevHash(), e.hash(), e.actorName(),
                e.subjects(), e.intact());
    }

    private static CheckpointResponse response(Checkpoint c) {
        return new CheckpointResponse(c.id(), c.chainPos(), c.headHashHex(), AuditEncoding.TIMESTAMP.format(c.createdAt()),
                c.signature().keyVersion(), Base64.getEncoder().encodeToString(c.signature().value()),
                "/api/v1/audit/checkpoints/" + c.chainPos() + "/file");
    }

    private static Map<String, Object> key(SigningPublicKey key) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", key.version());
        entry.put("ed25519", Base64.getEncoder().encodeToString(key.raw()));
        entry.put("pem", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.toPublicKey().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        return entry;
    }

    private static ProblemDetail problem(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
