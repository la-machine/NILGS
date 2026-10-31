// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.OfficerSignatures;
import org.snigf.mvp.register.application.RegisterRefused;
import org.snigf.mvp.register.application.RegisterService;
import org.snigf.mvp.register.domain.Register;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DEMO land register (ADR-040), for conservators of the office. Each signing act has two steps:
 * {@code signature/options} starts a ceremony over the inscription's canonical bytes, keeping only its
 * id in the session (ADR-022), and the act's endpoint takes the authenticator's response. No endpoint
 * issues a certificate: {@code /extract} is an on-screen extract labelled as not being one.
 */
@RestController
class RegisterController {

    static final String NOT_A_CERTIFICATE = "NOT A CERTIFICATE — DEMONSTRATION DATA";
    private static final String CEREMONY = "snigf.register.ceremony";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RegisterService register;

    RegisterController(RegisterService register) {
        this.register = register;
    }

    record PartyBody(@Size(max = 16) String officeCode, @Size(max = 20) String kind, @Size(max = 200) String fullName,
                     @Size(max = 40) String identityKind, @Size(max = 64) String identityNumber, @Size(max = 200) String chamber,
                     @Size(max = 64) String registrationNumber, @Size(max = 200) String jurisdiction, UUID accountId) {
    }

    record HolderBody(UUID party, @Size(max = 20) String interest, int shareNum, int shareDen) {
    }

    record ChargeBody(@Size(max = 20) String kind, UUID beneficiary, BigDecimal amount, boolean blocksTransfer,
                      java.time.LocalDate endsOn, @Size(max = 1000) String note) {
    }

    record ProposalBody(@Size(max = 16) String officeCode, UUID titleId, @Size(max = 20) String kind, int expectedVersion,
                        @Size(max = 20) String authorityType, @Size(max = 200) String authorityReference, @Size(max = 2000) String reason,
                        @Size(max = 50) List<@Size(max = 200) String> evidenceReferences, @Size(max = 50) List<UUID> evidenceVersions,
                        UUID completesTransactionId, UUID targetInscriptionId, BigDecimal areaM2, UUID parcelVersionId,
                        @Size(max = 50) List<HolderBody> holders, ChargeBody charge) {
    }

    record DepositBody(UUID titleId, @Size(max = 20) String kind, UUID depositorPartyId, UUID submittedByPartyId,
                       @Size(max = 50) List<@Size(max = 200) String> evidenceReferences) {
    }

    record ReasonBody(@Size(max = 2000) String reason) {
    }

    record ResolutionBody(@Size(max = 20) String outcome, @Size(max = 2000) String reason,
                          @Size(max = 50) List<@Size(max = 200) String> evidenceReferences) {
    }

    record Created(UUID id) {
    }

    record VerificationResponse(UUID titleId, boolean intact, int inscriptionsChecked, int signaturesChecked, int versionsChecked, int documentsChecked,
                                List<String> problems) {
    }

    /** {@code label} says what this is; never downloadable, printable or verifiable as a document. */
    record Extract(String label, String titleNumber, String officeCode, String status, int version, BigDecimal areaM2,
                   List<Register.Holder> holders, List<Register.Charge> charges, List<Register.Charge> restrictions) {
    }

    // --- Reading --------------------------------------------------------------------------------

    @GetMapping("/api/v1/register/titles")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    List<Register.TitleSummary> titles(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                                       @RequestParam(defaultValue = "0") int page) {
        return register.titles(staff, office, page);
    }

    @GetMapping("/api/v1/register/titles/{id}")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.title(#id))")
    RegisterService.TitleView title(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return register.title(staff, id);
    }

    @GetMapping("/api/v1/register/titles/{id}/extract")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.title(#id))")
    Extract extract(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        Register.Title t = register.title(staff, id).title();
        return new Extract(NOT_A_CERTIFICATE, t.number(), t.officeCode(), t.status(), t.version(), t.areaM2(), t.holders(), t.charges(), t.restrictions());
    }

    @GetMapping("/api/v1/register/titles/{id}/verification")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.title(#id))")
    VerificationResponse verification(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        Register.Verification v = register.verify(staff, id);
        return new VerificationResponse(v.titleId(), v.intact(), v.inscriptionsChecked(), v.signaturesChecked(), v.versionsChecked(), v.documentsChecked(), v.problems());
    }

    @GetMapping("/api/v1/register/inscriptions/{id}")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.inscription(#id))")
    Register.Inscription inscription(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return register.inscription(staff, id);
    }

    @GetMapping("/api/v1/register/proposals")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    List<Register.Inscription> proposals(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                                         @RequestParam(defaultValue = "0") int page) {
        return register.proposals(staff, office, page);
    }

    @GetMapping("/api/v1/register/parties")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    List<Register.Party> parties(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                                 @RequestParam(defaultValue = "0") int page) {
        return register.parties(staff, office, page);
    }

    @GetMapping("/api/v1/register/parcels/{reference}")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    Register.ParcelVersionRef parcelVersion(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable String reference,
                                            @RequestParam String office) {
        return register.parcelVersion(staff, office, reference);
    }

    @GetMapping("/api/v1/register/transactions")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    List<Register.Transaction> transactions(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                                            @RequestParam(defaultValue = "false") boolean overdue, @RequestParam(defaultValue = "0") int page) {
        return register.transactions(staff, office, overdue, page);
    }

    @GetMapping("/api/v1/register/conflicts")
    @PreAuthorize("@access.canAt(authentication, 'register.read', #office)")
    List<Register.Conflict> conflicts(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam String office,
                                      @RequestParam(defaultValue = "true") boolean open, @RequestParam(defaultValue = "0") int page) {
        return register.conflicts(staff, office, open, page);
    }

    // --- Writing ----------------------------------------------------------------------------------

    @PostMapping("/api/v1/register/parties")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', #body.officeCode())")
    ResponseEntity<Created> createParty(@AuthenticationPrincipal AuthenticatedUser staff, @Valid @RequestBody PartyBody body) {
        UUID id = register.createParty(staff, new Register.NewParty(body.officeCode(), body.kind(), body.fullName(), body.identityKind(),
                body.identityNumber(), body.chamber(), body.registrationNumber(), body.jurisdiction(), body.accountId()));
        return ResponseEntity.created(URI.create("/api/v1/register/parties/" + id)).body(new Created(id));
    }

    @PostMapping("/api/v1/register/inscriptions")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', #body.officeCode())")
    ResponseEntity<Created> propose(@AuthenticationPrincipal AuthenticatedUser staff, @Valid @RequestBody ProposalBody body,
                                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        List<Register.NewHolder> holders = body.holders() == null ? null : body.holders().stream()
                .map(h -> new Register.NewHolder(h.party(), h.interest(), h.shareNum(), h.shareDen())).toList();
        Register.NewCharge charge = body.charge() == null ? null : new Register.NewCharge(body.charge().kind(), body.charge().beneficiary(),
                body.charge().amount(), body.charge().blocksTransfer(), body.charge().endsOn(), body.charge().note());
        UUID id = register.propose(staff, new Register.NewInscription(body.officeCode(), body.titleId(), body.kind(), body.expectedVersion(),
                body.authorityType(), body.authorityReference(), body.reason(), body.evidenceReferences(), body.evidenceVersions(),
                body.completesTransactionId(), body.targetInscriptionId(), body.areaM2(), body.parcelVersionId(), holders, charge), key);
        return ResponseEntity.created(URI.create("/api/v1/register/inscriptions/" + id)).body(new Created(id));
    }

    /** Starts the officer's signature. Reading the register is enough: the act's endpoint checks the act's permission. */
    @PostMapping(path = "/api/v1/register/inscriptions/{id}/signature/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.inscription(#id))")
    String signatureOptions(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, HttpServletRequest request) {
        OfficerSignatures.Started started = register.beginSigning(staff, id);
        request.getSession().setAttribute(CEREMONY, started.ceremonyId());
        return started.optionsJson();
    }

    @PostMapping("/api/v1/register/inscriptions/{id}/register")
    @PreAuthorize("@access.canAt(authentication, 'register.inscribe', @registerOffices.inscription(#id))")
    Register.Inscription registerIt(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @RequestBody String response,
                                    HttpServletRequest request) {
        return register.sign(staff, id, Register.SigningAct.REGISTER, ceremony(request), response);
    }

    @PostMapping("/api/v1/register/inscriptions/{id}/request-confirmation")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.inscription(#id))")
    Register.Inscription requestConfirmation(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                             @RequestBody String response, HttpServletRequest request) {
        return register.sign(staff, id, Register.SigningAct.REQUEST_CONFIRMATION, ceremony(request), response);
    }

    @PostMapping("/api/v1/register/inscriptions/{id}/confirm")
    @PreAuthorize("@access.canAt(authentication, 'register.confirm', @registerOffices.inscription(#id))")
    Register.Inscription confirm(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @RequestBody String response,
                                 HttpServletRequest request) {
        return register.sign(staff, id, Register.SigningAct.CONFIRM, ceremony(request), response);
    }

    @PostMapping("/api/v1/register/inscriptions/{id}/withdraw")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.inscription(#id))")
    Register.Inscription withdraw(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return register.closeProposal(staff, id, "WITHDRAWN", body.reason());
    }

    @PostMapping("/api/v1/register/inscriptions/{id}/reject")
    @PreAuthorize("@access.canAt(authentication, 'register.inscribe', @registerOffices.inscription(#id))")
    Register.Inscription reject(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return register.closeProposal(staff, id, "REJECTED", body.reason());
    }

    @PostMapping("/api/v1/register/transactions")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.title(#body.titleId()))")
    ResponseEntity<Created> deposit(@AuthenticationPrincipal AuthenticatedUser staff, @Valid @RequestBody DepositBody body,
                                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        UUID id = register.deposit(staff, new Register.NewDeposit(body.titleId(), body.kind(), body.depositorPartyId(), body.submittedByPartyId(),
                body.evidenceReferences()), key);
        return ResponseEntity.created(URI.create("/api/v1/register/transactions/" + id)).body(new Created(id));
    }

    @PostMapping("/api/v1/register/transactions/{id}/withdraw")
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.transaction(#id))")
    Register.Transaction withdrawDeposit(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return register.closeTransaction(staff, id, "WITHDRAWN", body.reason());
    }

    @PostMapping("/api/v1/register/transactions/{id}/refuse")
    @PreAuthorize("@access.canAt(authentication, 'register.transaction.decide', @registerOffices.transaction(#id))")
    Register.Transaction refuseDeposit(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return register.closeTransaction(staff, id, "REFUSED", body.reason());
    }

    @PostMapping("/api/v1/register/conflicts/{id}/resolution")
    @PreAuthorize("@access.canAt(authentication, 'register.transaction.decide', @registerOffices.conflict(#id))")
    Register.Conflict resolve(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @Valid @RequestBody ResolutionBody body) {
        return register.resolveConflict(staff, id, body.outcome(), body.reason(), body.evidenceReferences());
    }

    // --- A deposit's documents. Storing one authorises nothing and inscribes nothing. ----------------------

    /** Never the storage key. {@code replacedById}: a correction replaced it, and it stays readable. */
    record DocumentResponse(UUID id, UUID transactionId, String category, String sha256, long sizeBytes, String mediaType, String originalName,
                            UUID uploadedBy, String uploadedByName, java.time.Instant uploadedAt, UUID replacesId, String replaceReason, UUID replacedById) {
    }

    @GetMapping("/api/v1/register/transactions/{id}/documents")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.transaction(#id))")
    List<DocumentResponse> depositDocuments(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return register.depositDocuments(staff, id).stream().map(d -> new DocumentResponse(d.id(), d.transactionId(), d.category(), d.sha256(), d.sizeBytes(),
                d.mediaType(), d.originalName(), d.uploadedBy(), d.uploadedByName(), d.uploadedAt(), d.replacesId(), d.replaceReason(), d.replacedById())).toList();
    }

    @PostMapping(path = "/api/v1/register/transactions/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.transaction(#id))")
    ResponseEntity<Created> addDocument(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @RequestParam String category,
                                        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                        @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return stored(staff, id, category, file, null, null, key);
    }

    /** A correction: the file named keeps its place in the record, and this one is shown as replacing it. */
    @PostMapping(path = "/api/v1/register/transactions/{id}/documents/{documentId}/replacement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'register.propose', @registerOffices.transaction(#id))")
    ResponseEntity<Created> replaceDocument(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable UUID documentId,
                                            @RequestParam String category, @RequestParam String reason,
                                            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                            @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return stored(staff, id, category, file, documentId, reason, key);
    }

    private ResponseEntity<Created> stored(AuthenticatedUser staff, UUID id, String category, org.springframework.web.multipart.MultipartFile file,
                                           UUID replaces, String reason, String key) {
        try (java.io.InputStream content = file.getInputStream()) {
            UUID created = register.addDocument(staff, id, category, file.getOriginalFilename(), content, replaces, reason, key);
            return ResponseEntity.created(URI.create("/api/v1/register/transactions/" + id + "/documents/" + created + "/content")).body(new Created(created));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Always an attachment, with the headers of ADR-033 that forbid a browser from rendering or guessing it. */
    @GetMapping("/api/v1/register/transactions/{id}/documents/{documentId}/content")
    @PreAuthorize("@access.canAt(authentication, 'register.read', @registerOffices.transaction(#id))")
    ResponseEntity<org.springframework.core.io.InputStreamResource> documentContent(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                                                    @PathVariable UUID documentId) {
        RegisterService.DocumentDownload download = register.downloadDocument(staff, id, documentId);
        Register.DepositDocument d = download.document();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(d.mediaType()));
        headers.setContentLength(d.sizeBytes());
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename(d.originalName(), java.nio.charset.StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Repr-Digest", "sha-256=:" + java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(d.sha256())) + ":");
        return ResponseEntity.ok().headers(headers).body(new org.springframework.core.io.InputStreamResource(download.content()));
    }

    private static String ceremony(HttpServletRequest request) {
        var session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(CEREMONY);
        if (session != null) {
            session.removeAttribute(CEREMONY);
        }
        if (id == null) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "SIGNATURE_NOT_STARTED");
        }
        return id.toString();
    }
}
