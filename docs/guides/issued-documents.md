# Documents issued by SNIGF: issuance, verification and incidents

*Copyright (c) 2026 FLYT. Author: Pythagore.*

> **DEMONSTRATION. NO LEGAL VALUE.** Slice 1 issues one document type only: a DEMO
> acknowledgment that an application was submitted. It is not a land title, a certificate of
> ownership or a decision, and no such document is issued until its legal basis, signatory and
> procedure are established.

## What and why

SNIGF must be able to recognise the documents it issued, and to detect documents that only
claim to come from it. A logo, a number, a watermark or a QR code proves nothing on its own,
because each of them can be copied. What proves a document is the **registry**:

- every issued document has a row: a random number, what it is about, and the SHA-256 of the
  exact PDF;
- a **manifest** binds those fields together, and OpenBao signs it with a dedicated Ed25519
  key that never leaves OpenBao;
- the exact PDF is kept beside the row;
- anyone can verify a number or a file against that registry, and the answer names a status,
  never personal data.

Decision: [ADR-039](../adr/039-issued-documents-and-verification.md) (Slice 1, approved on
29 Oct 2026). It extends [ADR-018](../adr/018-openbao-key-management.md) with one key purpose
and changes no other decision: uploads ([ADR-033](../adr/033-documents-upload-and-storage.md))
and issued documents never mix, and the `RECORD` key (ADR-019) stays reserved for officers'
decision signatures.

| What is presented | What SNIGF answers | Incident |
| --- | --- | --- |
| The exact PDF, or a byte-for-byte copy | `AUTHENTIC` (or the status: revoked, superseded with its replacement, cancelled) | none |
| The PDF edited after issuance, or a new PDF carrying a genuine number (a copied QR code) | `ALTERED` | `ALTERED` |
| A PDF claiming to come from SNIGF with a number nobody issued | `UNVERIFIED` (`NO_ISSUANCE_RECORD`) | `FABRICATED` |
| A PDF from elsewhere, with no SNIGF claim | `UNVERIFIED` (`NO_SNIGF_EVIDENCE`): not ours, and not suspicious for that | none |
| A file that is not a PDF | `UNVERIFIED` (`NOT_A_PDF`) | none |
| A number, typed or read from the QR code of a printed or scanned copy | `RECORD_FOUND` and the status; this confirms the record, **never the paper** | none |
| An unknown number | `UNVERIFIED` (`UNKNOWN_NUMBER`): a typo is not evidence | none |
| Anything matching a registry row that no longer agrees with its signed manifest | `UNVERIFIED` (`REGISTRY_INTEGRITY`), no document details | `REGISTRY_INTEGRITY` |

**Printed and scanned copies** cannot carry the electronic signature. A scan is a new file, so
it can never be `AUTHENTIC`. Its QR code leads to the verification page, which looks the number
up: the record and its status are confirmed, and the reader compares the paper with what the page
shows (type, date, issuing office, status). The page says so in words.

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V20__document_issuance.sql` | permissions, the DOCUMENT_OFFICER role, the registry, the stored PDFs, verifications, incidents, `issued_document_evidence()`, row-level security |
| `issuance/domain/` | `DocumentNumber` (128 random bits in Crockford base 32), `DocumentType` (type and template version), `Manifest` (the signed canonical form), `Verification` (what a number or a file proves), `IssuedDocument`, the ports `IssuanceRepository` and `DocumentRenderer` |
| `issuance/application/IssuanceService.java` | issues (once per operation key, under an advisory lock), signs, stores, audits; reads, revokes, cancels, re-issues |
| `issuance/application/VerificationService.java` | public verification by number or file, the incident queue and its resolution |
| `issuance/application/SubmissionAcknowledgment.java` | issues the acknowledgment after the submission has committed (`ApplicationSubmitted`) |
| `issuance/application/IssuanceProperties.java` | `snigf.issuance.*` |
| `issuance/infrastructure/pdf/PdfDocumentRenderer.java` | the template `ack-demo-1` (PDFBox): DEMO banner, facts in French and English, the number, a QR code (ZXing) naming the verification address |
| `issuance/infrastructure/web/` | `IssuedDocumentController` (applicant and office), `PublicVerificationController` (`@PublicEndpoint`), `DocumentOfficerController`, `VerificationRateLimiter`, `IssuanceExceptionHandler` |
| `keys/SigningKey.java`, `keys/internal/*KeyService.java` | `DOCUMENT_ISSUANCE`, mapped to `SNIGF_KEY_DOCUMENT_ISSUANCE` |
| `infra/openbao/bao-init.sh`, `app-policy.hcl.template` | creates the key (Ed25519, not exportable, rotated with `SNIGF_KEY_ROTATE_SIGNING`); the app may sign with it and read its public keys, nothing else |
| `frontend/src/features/issuance/` | `AcknowledgmentCard` (application and case pages), `VerifyPage` (`#/verify/<number>`, public), `IssuedDocumentsPage` (`#/issued-documents`) |
| `frontend/src/app/PublicLayout.tsx` | the frame of the public page, with no session |
| `backend/src/test/java/org/snigf/mvp/issuance/`, `backend/src/test/sql/t/22_document_issuance.sql` | every outcome above, concurrency, authorization, input limits, registry tampering |

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/applications/{id}/acknowledgment` | `application.read#own` | number, type, status, date, verification address, replacement; `404 NOT_ISSUED` |
| `POST /api/v1/applications/{id}/acknowledgment` | `application.read#own` | issues it if missing (idempotent), then as above |
| `GET /api/v1/applications/{id}/acknowledgment/file` | `application.read#own` | the exact PDF (attachment), its SHA-256 checked first |
| `GET /api/v1/cases/{id}/acknowledgment`, `.../file` | `application.read` at the case's office | the same for the office |
| `GET /api/v1/public/verifications/{number}` | public, throttled | outcome, reason, scope `RECORD_ONLY`, minimal summary |
| `POST /api/v1/public/verifications` (multipart `file`, CSRF token) | public, throttled | outcome, reason, scope `EXACT_FILE`, minimal summary |
| `GET /api/v1/issued-documents/{number}` | `document.issued.manage` | what it states, status, reason, replacement |
| `POST /api/v1/issued-documents/{number}/revocation`, `/cancellation`, `/reissue` (`{reason}`) | `document.issued.manage` | the document after the change (the replacement for a re-issue) |
| `GET /api/v1/verification-incidents?status=OPEN\|RESOLVED&page=` | `document.incident.review` | the queue |
| `POST /api/v1/verification-incidents/{id}/resolution` (`{expectedVersion, resolution, note}`) | `document.incident.review` | the resolved incident |

## How it works

**Issuance.**

1. The applicant submits. `ApplicationService.submit` publishes `ApplicationSubmitted`.
2. After the commit, `SubmissionAcknowledgment` calls `IssuanceService.issueSubmissionAcknowledgment`
   in its own transaction, as the applicant. A failure is logged and audited, and never undoes the
   submission.
3. The service takes a transaction-scoped advisory lock on the operation key
   (`SUBMISSION_ACK:<application id>`). If a document already exists for that key, it returns it.
4. It reads the case through `ApplicationDirectory.submitted`, draws a random number, renders the
   PDF, computes its SHA-256, builds the manifest and has OpenBao sign it.
5. It verifies the signature, then inserts the row and the PDF in the same transaction and audits
   `DOCUMENT_ISSUED`. The database refuses a second row with the same operation key, an issuance
   time more than a minute from its own clock, a status other than VALID, and a PDF whose hash
   differs from the row's.

If OpenBao is unreachable, nothing is issued unsigned: `ISSUANCE_UNAVAILABLE` (503), audited
`FAILED`. The applicant can then ask again from the application page.

**The manifest** is a canonical byte string, versioned, each field length-prefixed:

```text
snigf-issued-document-v1|<len>:<number>|<len>:<type>|<len>:<template>|<len>:<subject type>|<len>:<subject reference>|<len>:<office code>|<len>:<issued at, UTC, microseconds>|64:<sha-256 hex>
```

The signature is stored as `snigf:v<key version>:<base64>`. Verification rebuilds the manifest
from the row, so a field rewritten in the database no longer matches what was signed.

**Verification.**

- **By file:** the server hashes the bytes. An exact match is checked against its manifest and
  signature, then answered with its status. Otherwise it reads the PDF's metadata
  (`SNIGF-Document-Number`, producer `SNIGF (DEMO)`) to tell `ALTERED` from `FABRICATED` from
  "not ours".
- **By number:** the server looks the record up and checks it the same way.
- **What the public sees:** the registry is read only through `issued_document_evidence()`
  (`SECURITY DEFINER`); the tables themselves are not readable anonymously. Every verification
  is recorded (`document_verification`, readable by reviewers only) and audited
  `DOCUMENT_VERIFIED`. The presented file is never stored.
- **Limits:** verification is throttled per client address (`SNIGF_RATE_VERIFY_PER_MINUTE`,
  then 429 with `Retry-After`), and files are limited to `SNIGF_ISSUANCE_MAX_UPLOAD_BYTES`.

**Statuses.**

- A document is issued VALID. It leaves VALID once, by the document officer, who is never its
  holder:
  - **revoked**: no longer valid;
  - **cancelled**: issued in error;
  - **superseded**: replaced by a re-issue.
- A re-issue creates a new document with a new number for the same application and holder,
  signed again, and marks the old one superseded with a pointer to the new one.
- The old file still verifies as genuine, answered `SUPERSEDED` with the replacement's number.
- The database enforces every rule; a reason of 10 to 1000 characters is kept.

**Incidents.**

- Altered, fabricated and registry-integrity verifications open an incident. The document
  officer resolves it once, with a resolution and a note:
  - confirmed alteration;
  - genuine copy;
  - not an SNIGF document;
  - referred.
- Nobody resolves an incident about a document issued to themselves.
- Opening and resolving are audited, on the application's timeline.

## Key management

| Aspect | Value |
| --- | --- |
| Key | `SNIGF_KEY_DOCUMENT_ISSUANCE` (default `snigf-document-issuance`), OpenBao transit |
| Algorithm | Ed25519 (`ed25519` transit key), signature over the manifest bytes |
| Separation | its own key, never the record key (ADR-019) nor the checkpoint key |
| Export | not exportable, not deletable, no plaintext backup (`bao-init.sh` sets it; the security probe checks it) |
| Policy | `sign` and `keys` read only, for the app's certificate login |
| Rotation | automatic every `SNIGF_KEY_ROTATE_SIGNING` (2160 h), or by hand with `bao write transit/keys/<name>/rotate` |
| History | each signature names its key version; OpenBao keeps every version (`min_available_version` is never raised), so documents signed before a rotation still verify. `SigningKeyRotationTest` proves it on the real verification code |
| Identifier | the key version stored with each row (`key_version`) and in the signature prefix |

**Never raise `min_available_version`** on this key, and never delete a version. Doing so would
make every document signed with the dropped versions unverifiable, and public verification would
then report `REGISTRY_INTEGRITY` for genuine documents.

## How to do it again

To add a document type later, once its legal basis is approved and recorded in an ADR:

1. Add the type to `DocumentType` with its template version, and to the `doc_type` check in a
   new migration.
2. Render it in a `DocumentRenderer` template. Keep the number, the QR code, the
   `SNIGF-Document-Number` metadata and the DEMO marking until the type has legal effect.
3. Issue it from its own workflow event with an operation key that names the event, so retries
   and concurrent requests issue it once.
4. Add its permissions to the catalogue and the matrix; the verification page needs no change.
5. Update this guide, ADR-039 or its successor, the CHANGELOG and the manual.

## How to verify

```bash
cd backend && ./mvnw verify -Dit.test=IssuanceIT          # issuance, verification, statuses, incidents, limits
docker compose --profile test run --rm --build dbtest     # pgTAP 22: the database rules
cd frontend && npm test -- src/features/issuance           # the three views
bash infra/scripts/e2e.sh                                  # issue on submission, verify, the officer
```

By hand, on the running stack:

- submit an application as the demo applicant, open it, and download the acknowledgment;
- open `https://localhost:47000/#/verify` signed out and upload the PDF: the page says
  "Authentic document";
- edit the PDF's title in any editor, save it and upload it again: the page says
  "Content altered";
- sign in as the document officer: the incident is in `#/issued-documents`.

## Pitfalls we hit

- **`INSERT ... RETURNING` under row-level security.** RETURNING needs the SELECT policy too, and
  an anonymous verifier may not read verifications. The ids are generated in Java (`UuidV7`)
  and inserted without RETURNING.
- **Re-issue and the application's visibility.** The document officer cannot see applications
  under row-level security, so the insert policy allows a re-issue by a manage holder, and the
  guard checks the subject and holder against the replaced document. The printed facts are kept
  in `printed`, so a re-issue prints the same content without reading the case.
- **A verification without a file** answered Spring's default `400`
  (`MissingServletRequestPartException`) although the module's advice maps that exception. The
  `file` part is therefore optional, and the controller answers `422 FILE_REQUIRED` itself.
- **The pgTAP catalogue counts** in `09_authorization` must move with every new permission or
  role.

## Production differences

- **Legal effect:** none of these documents has any. A real document needs its legal basis, its
  signatory, probably a qualified signature (PAdES) on a certificate chain, and its own ADR.
- **The verification address** (`SNIGF_ISSUANCE_VERIFY_BASE_URL`) must be the public HTTPS name
  printed on every document. It cannot change later without breaking the printed QR codes, so
  choose it before the first real issuance.
- **Rate limits** are per instance, in memory. Several instances need a shared limiter, or a
  limit at the proxy.
- **Incidents** have no notification yet; the queue is the channel.
- **Scans:** there is no OCR or image comparison. The printed number is the only link from paper
  to record.
- **Backups:** the registry, the stored PDFs and OpenBao's key versions must be restored together.
  Without the key versions, no signature can be checked.
