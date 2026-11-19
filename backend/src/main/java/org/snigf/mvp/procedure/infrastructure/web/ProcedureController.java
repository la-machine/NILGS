// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.procedure.application.ObjectionService;
import org.snigf.mvp.procedure.application.ProcedureRefused;
import org.snigf.mvp.procedure.application.ProcedureService;
import org.snigf.mvp.procedure.domain.Procedure;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A case's procedure (ADR-042 steps 3 and 4). Read by the case's readers at its office, its assignees and its stage
 * officers; a stage recorded with the permission the stage names, at the case's office; attached files served as
 * attachments only. An objection is recorded at the counter ({@code procedure.objection.record}) under an
 * {@code Idempotency-Key}, and decided by a supervisor ({@code procedure.objection.decide}). An approved case is reopened
 * by a supervisor holding {@code procedure.case.reopen} (ADR-043 step 5b). The service and the database (V32, V33, V36)
 * decide the rest.
 */
@RestController
@RequestMapping("/api/v1/procedures")
class ProcedureController {

    /**
     * Readers: the case's readers at its office (every stage officer holds {@code application.read} there; a designated
     * member of a commission receives it by special grant with the commission's report), and its assignees. The database
     * narrows it to the case (V32, {@code procedure_readable}).
     */
    static final String READ = "@access.canAt(authentication, 'application.read', @caseOffices.of(#id))"
            + " or @access.canAssigned(authentication, 'application.read')";

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProcedureService procedures;
    private final ObjectionService objections;

    ProcedureController(ProcedureService procedures, ObjectionService objections) {
        this.procedures = procedures;
        this.objections = objections;
    }

    /**
     * What an officer records. {@code details} by stage: none for {@code CADASTRAL_REVIEW}; {@code visitedOn} and
     * {@code members} ({@code name}, {@code capacity}) for {@code FIELD_VISIT}; {@code reportReference}, {@code reportDate}
     * and {@code signatories} for {@code COMMISSION_REPORT}. A correction names the record it replaces and says why.
     */
    record RecordBody(@Size(max = 20) String outcome, @Size(max = 4000) String finding, Map<String, Object> details,
                      @Size(max = 20) List<UUID> evidenceVersions, UUID supersedes, @Size(max = 2000) String correctionReason) {
    }

    record FileResponse(UUID id, String originalName, String mediaType, long sizeBytes, String sha256, Instant uploadedAt) {
    }

    record RecordResponse(UUID id, String outcome, String finding, Map<String, Object> details, List<UUID> evidenceVersions,
                          UUID supersedes, String correctionReason, String recordedByName, boolean recordedBySelf, Instant recordedAt,
                          List<FileResponse> files) {
    }

    record StageResponse(String code, int rank, String requires, String permission, String labelEn, String labelFr, String status,
                         boolean mayRecord, List<RecordResponse> records) {
    }

    /** A notice a publication opened; {@code current} for the latest publication's, {@code periodOpen} while objections are awaited. */
    record NoticeResponse(UUID id, String number, String parcelReference, String locality, String period, Instant opensAt, Instant closesAt,
                          UUID replaces, boolean current, boolean periodOpen) {
    }

    /** The objector, never with the identity: {@code identityVisible} says whether this reader may ask for it. */
    record ObjectorResponse(String name, String interest, boolean identityVisible) {
    }

    /** The identity document, one objection at a time, each read audited. */
    record ObjectorIdentityResponse(String identityKind, String identityNumber) {
    }

    record ObjectionDecisionResponse(String outcome, String grounds, List<String> reviewStages, String applicantInstructions, String decidedByName,
                                     boolean decidedBySelf, Instant decidedAt) {
    }

    record ObjectionResponse(UUID id, String number, String noticeNumber, ObjectorResponse objector, String statement, Instant receivedAt,
                             boolean late, String recordedByName, boolean recordedBySelf, ObjectionDecisionResponse decision, boolean mayDecide,
                             List<FileResponse> files) {
    }

    /**
     * {@code procedure} is {@code A}, {@code B}, or null for a type without one; {@code gate} what stops an approval;
     * {@code notices} newest first; {@code mayRecordObjection} whether the reader may record one at the counter now.
     */
    /** A reconsideration (V36): {@code roundOutcome} is null while its round's decision is not made. */
    record ReconsiderationResponse(UUID id, int round, String reconsideredOutcome, List<String> objectionNumbers, List<String> stages,
                                   List<String> addedStages, String justification, String openedByName, boolean openedBySelf, Instant openedAt,
                                   String roundOutcome) {
    }

    /**
     * Also: {@code version} and {@code round} of the case, its {@code reconsiderations} by round, and
     * {@code reconsiderProblem}: why the reader may not open one now, or null when they may.
     */
    record ProcedureResponse(UUID id, String reference, String state, String typeCode, String officeCode, String procedure,
                             List<StageResponse> stages, List<String> gate, List<NoticeResponse> notices, List<ObjectionResponse> objections,
                             boolean mayRecordObjection, long version, int round, List<ReconsiderationResponse> reconsiderations,
                             String reconsiderProblem, Procedure.CaseDeposit caseDeposit) {
    }

    /** A supervisor's reconsideration: the case's version as seen, any stage added to those the objections named, why. */
    record ReconsiderationBody(long expectedVersion, @Size(max = 12) List<@Size(max = 40) String> addedStages,
                               @Size(max = 4000) String justification) {
    }

    /** What the officer at the counter records: the objector, their interest, and why they object. */
    record ObjectionBody(@Size(max = 200) String objectorName, @Size(max = 40) String identityKind, @Size(max = 64) String identityNumber,
                         @Size(max = 40) String interest, @Size(max = 4000) String statement) {
    }

    /**
     * A supervisor's decision: {@code UPHELD} with the stages examined again and, if the applicant must do something,
     * instructions written for them; or {@code DISMISSED}. Grounds always.
     */
    record DecisionBody(@Size(max = 20) String outcome, @Size(max = 4000) String grounds, @Size(max = 6) List<@Size(max = 40) String> reviewStages,
                        @Size(max = 1000) String applicantInstructions) {
    }

    /** The objection written, and the case's procedure after it. */
    record ObjectionWrittenResponse(UUID objection, ProcedureResponse procedure) {
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    ProcedureResponse view(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return response(procedures.view(staff, id), staff);
    }

    // A record and up to five files ({@code files}), in one request; the record as a JSON part named {@code record}.
    // One endpoint per permission, each stage's as V31's catalogue names it; the database checks the same again.

    @PostMapping(path = "/{id}/stages/{stage:CADASTRAL_REVIEW|FIELD_VISIT}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'procedure.stage.cadastral', @caseOffices.of(#id))")
    ProcedureResponse recordCadastral(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable String stage,
                                      @Valid @RequestPart("record") RecordBody body,
                                      @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return record(staff, id, stage, body, files);
    }

    @PostMapping(path = "/{id}/stages/{stage:COMMISSION_REPORT}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'procedure.commission.report', @caseOffices.of(#id))")
    ProcedureResponse recordReport(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable String stage,
                                   @Valid @RequestPart("record") RecordBody body,
                                   @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return record(staff, id, stage, body, files);
    }

    @PostMapping(path = "/{id}/stages/{stage:PUBLICATION}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'procedure.stage.publish', @caseOffices.of(#id))")
    ProcedureResponse recordPublication(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable String stage,
                                        @Valid @RequestPart("record") RecordBody body,
                                        @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return record(staff, id, stage, body, files);
    }

    @PostMapping(path = "/{id}/stages/{stage:TITLE_VERIFICATION|DUTIES_EVIDENCE}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'procedure.stage.title', @caseOffices.of(#id))")
    ProcedureResponse recordTitle(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable String stage,
                                  @Valid @RequestPart("record") RecordBody body,
                                  @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        return record(staff, id, stage, body, files);
    }

    private ProcedureResponse record(AuthenticatedUser staff, UUID id, String stage, RecordBody body, List<MultipartFile> files) {
        List<MultipartFile> given = files == null ? List.of() : files;
        if (given.size() > ProcedureService.MAX_FILES) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "TOO_MANY_FILES");
        }
        List<InputStream> opened = new ArrayList<>();
        try {
            List<ProcedureService.Upload> uploads = new ArrayList<>();
            for (MultipartFile file : given) {
                InputStream content = file.getInputStream();
                opened.add(content);
                uploads.add(new ProcedureService.Upload(file.getOriginalFilename(), content));
            }
            Procedure.NewRecord record = new Procedure.NewRecord(body.outcome(), body.finding(), body.details(), body.evidenceVersions(),
                    body.supersedes(), body.correctionReason());
            return response(procedures.record(staff, id, stage, record, uploads), staff);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (InputStream content : opened) {
                try {
                    content.close();
                } catch (IOException ignored) {
                    // The upload is spooled by the container, which removes it with the request.
                }
            }
        }
    }

    /** Always an attachment, with the headers of ADR-033 that forbid a browser from rendering or guessing it. */
    @GetMapping("/{id}/files/{fileId}")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> file(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable UUID fileId) {
        ProcedureService.FileDownload download = procedures.download(staff, id, fileId);
        Procedure.FileRef f = download.file();
        return ResponseEntity.ok().headers(attachment(f.mediaType(), f.sizeBytes(), f.originalName(), f.sha256()))
                .body(new InputStreamResource(download.content()));
    }

    /** The headers of ADR-033 that forbid a browser from rendering or guessing a file, and its digest. */
    static HttpHeaders attachment(String mediaType, long size, String name, String sha256) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentLength(size);
        headers.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Repr-Digest", "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)) + ":");
        return headers;
    }

    private static UUID uuid(String key) {
        if (key == null || key.isBlank()) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        try {
            return UUID.fromString(key.strip());
        } catch (IllegalArgumentException e) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    /** Records an objection received at the counter: a JSON part {@code objection} and up to five {@code files}. */
    @PostMapping(path = "/{id}/objections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'procedure.objection.record', @caseOffices.of(#id))")
    ResponseEntity<ObjectionWrittenResponse> recordObjection(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                             @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                             @Valid @RequestPart("objection") ObjectionBody body,
                                                             @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        UUID clientKey = uuid(key);
        List<MultipartFile> given = files == null ? List.of() : files;
        if (given.size() > ProcedureService.MAX_FILES) {
            throw new ProcedureRefused(ProcedureRefused.Kind.INVALID, "TOO_MANY_FILES");
        }
        List<InputStream> opened = new ArrayList<>();
        try {
            List<ProcedureService.Upload> uploads = new ArrayList<>();
            for (MultipartFile file : given) {
                InputStream content = file.getInputStream();
                opened.add(content);
                uploads.add(new ProcedureService.Upload(file.getOriginalFilename(), content));
            }
            Procedure.NewObjection objection = new Procedure.NewObjection(new Procedure.Objector(body.objectorName(), body.identityKind(),
                    body.identityNumber(), body.interest()), body.statement());
            ObjectionService.Written written = objections.record(staff, id, objection, uploads, clientKey);
            return ResponseEntity.status(201).body(new ObjectionWrittenResponse(written.objectionId(), response(written.view(), staff)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (InputStream content : opened) {
                try {
                    content.close();
                } catch (IOException ignored) {
                    // The upload is spooled by the container, which removes it with the request.
                }
            }
        }
    }

    /** Decides an objection of the case, once, with grounds. */
    @PostMapping("/{id}/objections/{objectionId}/decision")
    @PreAuthorize("@access.canAt(authentication, 'procedure.objection.decide', @caseOffices.of(#id))")
    ProcedureResponse decideObjection(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable UUID objectionId,
                                      @Valid @RequestBody DecisionBody body) {
        return response(objections.decide(staff, id, objectionId, body.outcome(), body.grounds(), body.reviewStages(),
                body.applicantInstructions()), staff);
    }

    /** Opens the reconsideration of an approved case after an objection upheld after its decision (ADR-043 step 5b). */
    @PostMapping("/{id}/reconsideration")
    @PreAuthorize("@access.canAt(authentication, 'procedure.case.reopen', @caseOffices.of(#id))")
    ProcedureResponse reconsider(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ReconsiderationBody body) {
        return response(procedures.reconsider(staff, id, body.expectedVersion(), body.addedStages(), body.justification()), staff);
    }

    /** The objector's identity document: to the officers who receive or decide objections at the office; audited. */
    @GetMapping("/{id}/objections/{objectionId}/objector")
    @PreAuthorize("@access.canAt(authentication, 'procedure.objection.record', @caseOffices.of(#id))"
            + " or @access.canAt(authentication, 'procedure.objection.decide', @caseOffices.of(#id))")
    ResponseEntity<ObjectorIdentityResponse> objector(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                      @PathVariable UUID objectionId) {
        Procedure.ObjectorIdentity identity = objections.objectorIdentity(staff, id, objectionId);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ObjectorIdentityResponse(identity.identityKind(), identity.identityNumber()));
    }

    /** A file received with an objection: always an attachment, as the stage files. */
    @GetMapping("/{id}/objections/{objectionId}/files/{fileId}")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> objectionFile(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                      @PathVariable UUID objectionId, @PathVariable UUID fileId) {
        ObjectionService.FileDownload download = objections.download(staff, id, objectionId, fileId);
        Procedure.ObjectionFileRef f = download.file();
        return ResponseEntity.ok().headers(attachment(f.mediaType(), f.sizeBytes(), f.originalName(), f.sha256()))
                .body(new InputStreamResource(download.content()));
    }

    /** The objections waiting for the caller's decision at their offices. */
    @GetMapping("/objection-queue")
    @PreAuthorize("@access.canSomewhere(authentication, 'procedure.objection.decide')")
    List<Procedure.ObjectionQueueEntry> objectionQueue(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam(defaultValue = "0") int page) {
        return objections.queue(staff, page);
    }

    /** The stages waiting for the caller at their offices. */
    @GetMapping("/queue")
    @PreAuthorize("@access.canSomewhere(authentication, 'procedure.stage.cadastral', 'procedure.commission.report', 'procedure.stage.publish',"
            + " 'procedure.stage.title')")
    List<Procedure.QueueEntry> queue(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam(defaultValue = "0") int page) {
        return procedures.queue(staff, page);
    }

    private static ProcedureResponse response(ProcedureService.ProcedureView view, AuthenticatedUser viewer) {
        List<StageResponse> stages = view.stages().stream().map(s -> new StageResponse(s.definition().code(), s.definition().rank(),
                s.definition().requires(), s.definition().permission(), s.definition().labelEn(), s.definition().labelFr(), s.status(),
                s.mayRecord(), s.records().stream().map(r -> new RecordResponse(r.id(), r.outcome(), r.finding(), r.details(),
                        r.evidenceVersions(), r.supersedesId(), r.correctionReason(), r.recordedByName(), r.recordedById().equals(viewer.userId()),
                        r.recordedAt(), r.files().stream().map(f -> new FileResponse(f.id(), f.originalName(), f.mediaType(), f.sizeBytes(),
                                f.sha256(), f.uploadedAt())).toList())).toList())).toList();
        Procedure.CaseHeader h = view.header();
        UUID latestPublication = view.stages().stream().filter(s -> s.definition().code().equals("PUBLICATION"))
                .flatMap(s -> s.records().stream().limit(1)).map(Procedure.StageRecord::id).findFirst().orElse(null);
        Instant now = Instant.now();
        List<NoticeResponse> notices = view.notices().stream().map(n -> new NoticeResponse(n.id(), n.number(), n.parcelReference(), n.locality(),
                n.period(), n.opensAt(), n.closesAt(), n.replacesId(), n.recordId().equals(latestPublication),
                n.recordId().equals(latestPublication) && n.closesAt().isAfter(now))).toList();
        List<ObjectionResponse> objections = view.objections().stream().map(o -> new ObjectionResponse(o.id(), o.number(), o.noticeNumber(),
                new ObjectorResponse(o.objectorName(), o.interest(), o.identityVisible()), o.statement(), o.receivedAt(), o.late(),
                o.recordedByName(), o.recordedById().equals(viewer.userId()),
                o.decision() == null ? null : new ObjectionDecisionResponse(o.decision().outcome(), o.decision().grounds(), o.decision().reviewStages(),
                        o.decision().applicantInstructions(), o.decision().decidedByName(), o.decision().decidedById().equals(viewer.userId()), o.decision().decidedAt()),
                o.mayDecide(), o.files().stream().map(f -> new FileResponse(f.id(), f.originalName(), f.mediaType(), f.sizeBytes(), f.sha256(),
                        f.uploadedAt())).toList())).toList();
        List<ReconsiderationResponse> reconsiderations = view.reconsiderations().stream().map(r -> new ReconsiderationResponse(r.id(), r.round(),
                r.reconsideredOutcome(), r.objectionNumbers(), r.stages(), r.addedStages(), r.justification(), r.openedByName(),
                r.openedById().equals(viewer.userId()), r.openedAt(), r.roundOutcome())).toList();
        return new ProcedureResponse(h.id(), h.reference(), h.state(), h.typeCode(), h.officeCode(), view.procedure(), stages, view.gate(),
                notices, objections, view.mayRecordObjection(), h.version(), h.round(), reconsiderations, view.reconsiderProblem(),
                view.caseDeposit());
    }
}
