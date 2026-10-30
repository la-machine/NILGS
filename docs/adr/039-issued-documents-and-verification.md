# ADR-039: Documents issued by SNIGF: an issuance registry, a signed manifest, public verification and incidents

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted (Slice 1, decisions of 29 Oct 2026) | 2026-10-29 | Project owner, Pythagore (FLYT) |

## Context

SNIGF must recognise the documents it issues itself, and detect documents that claim to come from
SNIGF but cannot be verified. A logo, a number, a watermark or a QR code proves nothing: all of
them can be copied. The project owner approved Slice 1 on 29 Oct 2026. It covers one document
type, a **DEMO application-submission acknowledgment**, and the reusable service behind it:

- generation (PDFBox) with a versioned template;
- the registry and a signature held in OpenBao, with a **dedicated key**;
- a QR code and public verification;
- statuses, an incident queue, audit, rate limits.

No title, certificate of ownership or decision is issued: their legal basis, signatory and
procedure are not established.

What already exists:

- the OpenBao transit engine signs Ed25519 without exporting the key (ADR-018), as it does for
  audit checkpoints;
- the `RECORD` key, reserved for officers' decision signatures (ADR-019, not yet implemented);
- uploaded documents (ADR-033), a different concept.

## Decision

### Issued documents are a module and a registry of their own

The new module `issuance`, with migration V20, is separate from `document` (uploads). An issued
document is never an uploaded document, and the reverse:

- a file SNIGF did not issue is never "fraudulent" for that reason;
- a file someone uploads is never "authentic" because it looks like one of ours.

| Table | Holds |
| --- | --- |
| `issued_document` | number, type, template version, subject (the application), office, issued-to, time, SHA-256 of the exact PDF, the manifest, its signature and key version, status, the operation key; append-only except status, which only moves forward |
| `issued_document_artifact` | the exact PDF bytes, kept in the database with the record (one backup, one transaction) |
| `document_verification` | every verification attempt: channel, number claimed, fingerprint presented, outcome, reason, time; who, when signed in |
| `verification_incident` | a suspicious verification for review: kind, status, resolution, note, resolver |

- **The number** (`SNIGF-` + 26 characters, 130 random bits) cannot be guessed. Several hundred
  billion billion attempts per real document would be needed.
- **The demo flag.** `demo = true` is enforced by a `CHECK`. The PDF carries a
  "DEMONSTRATION — NO LEGAL VALUE" banner. The verification page says the same.

### The signature: a manifest signed in OpenBao with a dedicated key

The canonical form is `snigf-issued-document-v1|f(number)|f(type)|f(template)|f(subject type)|
f(subject reference)|f(office)|f(issued at)|f(sha256 hex of the PDF)`, with the field encoding of
the audit chain: length-prefixed, so no value can contain a separator.

1. **The signing key** is `SNIGF_KEY_DOCUMENT_ISSUANCE`: Ed25519, not exportable, no plaintext
   backup, not deletable, auto-rotated on `SNIGF_KEY_ROTATE_SIGNING`. The application may only sign
   with it and read its public keys (`app-policy`). It is separate from `RECORD` (ADR-019) and from
   the audit checkpoint key: one key per purpose.
2. **The signature binds the manifest**, which contains the SHA-256 of the PDF. The PDF is not
   signed with PAdES. Verification compares the exact bytes presented with the registered
   fingerprint, and the manifest with its signature, so a database administrator who rewrites a
   registry row breaks the signature.
3. **Rotation and history.** The signature records its key version. Verification uses the public
   key of that version, so a document signed before a rotation still verifies after it. OpenBao
   keeps every version; `min_available_version` is never raised. The public keys of every version
   can be listed for verification outside the application.

### Verification

Verification works by number (QR code, printed copy, typed) or by file (the PDF itself).

| Case | Presented | Outcome |
| --- | --- | --- |
| A, B | the original PDF, or an exact copy (same bytes) | `AUTHENTIC`, with the current status |
| C | a PDF naming a known number (in its metadata) but with other bytes | `ALTERED`, plus a security incident |
| D | a recreated PDF: the look and number copied, other bytes | `ALTERED` when the number is known, `UNVERIFIED` otherwise; never authentic |
| E | no number, or an unknown one | `UNVERIFIED`. An incident is opened when a file claims to be from SNIGF; a typed number that matches nothing is only recorded, since a typo is not evidence |
| F | a revoked, cancelled or superseded document | the historical issuance confirmed; `REVOKED`, `CANCELLED` or `SUPERSEDED`, with the replacement number when superseded |
| registry altered | the stored signature no longer verifies | `UNVERIFIED`, plus an incident (`REGISTRY_INTEGRITY`) |

- **What the public sees** (no sign-in, rate-limited per address): the outcome, document type,
  status, issue date, issuing office and replacement number. **Never** the applicant, the parcel,
  the case reference, or the content.
- **A number lookup** confirms only that a record exists and its status. It does not prove a
  paper is unaltered: the page says to compare the paper with the authorised holder's PDF or with
  the issuing office.
- **A scanned or photographed copy** cannot keep the PDF's fingerprint. It is verified by its
  number only, and the page says so. No OCR or image comparison in this slice.

### Statuses and actors

- **Issuance is automatic**, once, after a submission commits. The application module publishes
  `ApplicationSubmitted`, and `issuance` listens after commit. An endpoint lets the applicant
  retry. Both are **idempotent**: the operation key `SUBMISSION_ACK:<application>` is unique, and
  issuance holds an advisory lock on it, so concurrent attempts produce exactly one record.
- **Changing a document's status** belongs to a new baseline role, **DOCUMENT_OFFICER**
  (*agent de vérification documentaire*), with `document.issued.manage` (scope ANY):
  - `VALID → REVOKED` or `CANCELLED`, with a reason;
  - re-issue: `VALID → SUPERSEDED`, and a new document names the replacement.

  Every status is final once left. Nobody changes the status of a document issued to themselves.
  The database enforces both.
- **Incidents** are reviewed by the same role, with `document.incident.review`. Resolution is
  `CONFIRMED_ALTERATION`, `GENUINE_COPY`, `NOT_A_SNIGF_DOCUMENT` or `REFERRED`, always with a note.
  Resolving never accuses anyone automatically.
- **Who downloads.** The applicant downloads their own acknowledgment; staff who may read the
  case download it too (row-level security through the application).
- **Audit.** Every issuance, verification, status change, incident opened and incident resolved
  is audited. Issued-document events name the application as their subject (V20 extends
  `audit_subjects`), so they appear in the case timeline (ADR-038).

### Relation to earlier decisions

| ADR | Effect |
| --- | --- |
| ADR-018 | one more key purpose (`DOCUMENT_ISSUANCE`), same custody rules |
| ADR-019 | unchanged: `RECORD` stays reserved for officers' decision signatures |
| ADR-032 | unchanged lifecycle: the acknowledgment follows the submission, through an event |
| ADR-033 | unchanged: uploads stay in `document`; issued documents never mix with them |
| ADR-035 | unchanged: the acknowledgment is not a decision |
| ADR-037 | unchanged: no tariff in this slice |

## Clarification (30 Oct 2026): certificates of ownership

The project owner's decision D7 (recorded in [ADR-040](040-land-register.md) §11) makes the
Certificate of Ownership a **future** document type of this same registry and verification
service. It is not built, and none is issued, even as a DEMO, until a separate slice is
authorised. When it is built, this registry is extended, not replaced:

- the statuses `VALID`, `EXPIRED`, `REVOKED` and `SUPERSEDED` (`EXPIRED` is new);
- the issuance date, effective date and expiry date where applicable, plus the validity rule
  that governs them, configured from the approved legal rule;
- verification that tells a genuine historical certificate from one valid now;
- an official template, and a signatory who is never the processing officer;
- a physical channel: issuance details recorded, `READY_FOR_COLLECTION`, collection with the
  recipient's identity;
- a digital channel kept disabled until electronic issuance is legally authorised.

A QR code or a digital signature never replaces a legally required physical stamp or
signature. Slice 1's DEMO acknowledgment is unchanged.

## Alternatives rejected

- **A PAdES signature inside the PDF.** Readers would show a signature panel, but it needs a
  certificate chain the institution does not yet have, and a printed copy would lose it anyway.
  The registry and the signed manifest work for both. PAdES can be added later on the same key.
- **Putting the signature in the QR code.** The QR code would grow, and a copied QR code would
  still "verify" on altered paper. It stays a pointer to the authoritative record.
- **Storing the PDFs on the documents volume.** That would mean two stores and two backups. The
  PDFs are small, and the database keeps the record and its bytes together.
- **Reusing the `RECORD` key.** Rejected by the owner: one key per purpose.

## Consequences

- **New:**
  - migration V20 and pgTAP test `22`;
  - the module `issuance`;
  - dependencies `pdfbox` 3.0.5 and ZXing `core` 3.5.3 (Apache-2.0);
  - the key `SNIGF_KEY_DOCUMENT_ISSUANCE` (bao-init and policy);
  - the role DOCUMENT_OFFICER and the permissions `document.issued.manage` and
    `document.incident.review`;
  - the audit actions `DOCUMENT_ISSUED`, `DOCUMENT_VERIFIED`, `DOCUMENT_REVOKED`,
    `DOCUMENT_CANCELLED`, `DOCUMENT_SUPERSEDED`, `DOCUMENT_STATUS_REFUSED`,
    `VERIFICATION_INCIDENT_OPENED` and `VERIFICATION_INCIDENT_RESOLVED`;
  - the public page `#/verify`, the officer page `#/issued-documents`, the guide
    `docs/guides/issued-documents.md`.
- **Existing stacks:** after `dev-setup` adds the setting, bao-init creates the new key on its
  next run, because its inputs changed.
- **Not covered:**
  - OCR and image comparison of scans;
  - notifications to officers (the queue is the channel for now);
  - PAdES;
  - any document with legal effect.
