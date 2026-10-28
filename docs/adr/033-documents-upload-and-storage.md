# ADR-033: Documents: recognised from their bytes, stored under random keys, append-only

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-24 | Project owner, Pythagore (FLYT) |

## Context

E-13 adds the supporting documents of an application. The Baseline fixes:

- a `DocumentStorage` interface with a local-filesystem adapter for the MVP and an S3-API
  adapter for the pilot. Not MinIO (archived in 2026); Garage, SeaweedFS or a managed S3 are
  evaluated before the pilot;
- content-type detection by file signature (Apache Tika), PDF/JPEG/PNG only;
- a 10 MB DEMO limit, SHA-256 per file, download only as an attachment;
- malware scanning (ClamAV) is a pilot requirement (P-02);
- tables `document` (application, category) and `document_version` (storage key, SHA-256,
  size, detected type, status PENDING/STORED/REJECTED, append-only);
- tests: a disguised file is rejected; orphan cleanup.

Uploads are the most direct way for anyone outside to put bytes on the server. Every byte is
untrusted: its name, its declared type, its size, and its content.

## Decision

**Upload pipeline** (`DocumentService.upload`, one transaction, the actor named to the database):

1. **Ownership and state.** The application must be the caller's own and must take documents
   (a draft; "information requested" joins with E-14). The category must belong to its type
   version. Anyone else's application gets 404, as everywhere.
2. **Staging.** The bytes are streamed into a staging directory, counted and hashed as they
   arrive. Past `SNIGF_DOCUMENT_MAX_BYTES` the upload stops (413 `FILE_TOO_LARGE`). Empty
   files are refused. The size and type the browser declared are ignored.
3. **Inspection** (`TikaContentInspector`). The bytes alone decide the type: the name is not
   given to Tika. Then:

   | Check | Refused with |
   | --- | --- |
   | Only PDF, JPEG and PNG | `TYPE_NOT_ALLOWED` |
   | The name's extension must agree with the bytes | `TYPE_MISMATCH` |
   | A PDF must start with `%PDF-` and end with `%%EOF` | `PDF_MALFORMED` |
   | A PDF must not be encrypted | `PDF_ENCRYPTED` |
   | A PDF must not carry active content: `/JavaScript`, `/JS`, `/Launch`, `/EmbeddedFile(s)`, `/RichMedia`, `/XFA`, `/SubmitForm`, `/ImportData`, `/GoToE`, with `#xx` escapes decoded | `PDF_ACTIVE_CONTENT` |
   | An image header must be readable, with at most `SNIGF_DOCUMENT_MAX_PIXELS` pixels (against decompression bombs) | `IMAGE_MALFORMED`, `IMAGE_TOO_LARGE` |

   Every refusal is audited (`DOCUMENT_REJECTED`, with code, size and detected type), and
   nothing is kept.
4. **Storage** (`FileDocumentStorage`).
   - The file is moved atomically under a random 128-bit key (`ab/cd/abcd…`), never under its
     name, and made read-only.
   - Directories are the application user's alone.
   - Every path is built from a key that matches `^[0-9a-f]{32}$`.
   - The volume `document-data` is mounted into the app only.
5. **Record.** A `document_version` row is added: key, SHA-256, size, detected type, and the
   cleaned name, kept for display only. `DOCUMENT_UPLOADED` is audited, and the answer is
   kept under the `Idempotency-Key`. If anything fails after the file was stored, the file is
   deleted; if the process dies, the cleanup removes it.

**The database (V14) agrees independently:**
- **Row-level security:** a document is visible exactly when its application is.
- **Who adds what:** only the owner adds, only to their draft, only in a category of the type
  version, and only under their own name.
- **Versions** are numbered by the database (advisory lock), never updated, and deleted only
  with the draft that owns them.
- **Stored rows are complete:** a stored version always has its key, a 64-hex hash, a size
  within bounds and an allowed type.
- **Names are names:** they are never paths (no `/`, `\` or control characters).

**Download.**
- The hash and size are checked before anything is sent. Altered bytes are never served: the
  answer is 500 `DOCUMENT_INTEGRITY_FAILED`, and it is audited.
- Each download is audited (`DOCUMENT_DOWNLOADED`).
- The response headers are:
  - `Content-Disposition: attachment` with the cleaned name;
  - the detected type;
  - `X-Content-Type-Options: nosniff`;
  - `Content-Security-Policy: default-src 'none'; sandbox`;
  - `Cache-Control: no-store`;
  - `Repr-Digest`.

**Orphan cleanup.** A scheduled job (`SNIGF_DOCUMENT_CLEANUP_CRON`, service identity
`document-cleanup`):
- lists stored files older than `SNIGF_DOCUMENT_ORPHAN_GRACE`;
- asks the database which keys a version still names (`document_storage_keys_known()`,
  SECURITY DEFINER, keys only);
- deletes the rest and purges old staging leftovers;
- audits a run that removed something (`DOCUMENT_ORPHANS_REMOVED`).

This covers a crash between storing and committing, and a deleted draft.

**Limits on every layer.**

| Layer | Limit |
| --- | --- |
| Proxy | `request_body max_size` (`SNIGF_PROXY_MAX_BODY_BYTES`): no larger body reaches the app |
| Spring multipart | `max-file-size` and `max-request-size`, parsed lazily so the endpoint's own checks answer |
| Service | counts bytes; caps versions per document (`SNIGF_DOCUMENT_MAX_VERSIONS`) |
| Database | `size_bytes` at most 100 MB |

## Why

- A name or a declared type is chosen by the sender; the bytes are what a reader's software
  will open. Refusing a mismatch means a file is never served as something it is not.
- Random keys make the storage namespace unguessable, and no part of the request ever
  becomes a path.
- Hash before serving turns silent corruption or tampering of the storage into a visible,
  audited refusal.
- The PDF screen catches the common dangerous constructs cheaply. It does not replace a
  malware scanner (see consequences).

## Alternatives considered

- **Trusting the extension or the `Content-Type`:** trivially bypassed, which is the "disguised
  file" the Baseline names.
- **Storing files in the database (`bytea`):** simpler transactions, but heavier backups and no
  path to object storage.
- **Re-encoding every image and rasterising PDFs:** the strongest sanitisation, but it changes
  evidence the applicant provided. Left for the scanning pipeline decision of the pilot.

## Consequences

- New:
  - migration V14;
  - pgTAP test `16`;
  - the document module (`DocumentService`, `FileDocumentStorage`, `TikaContentInspector`,
    `DocumentCleanupJob`, `DocumentController`);
  - `ApplicationDirectory` (application module API);
  - dependency `tika-core` 4.1.0, without commonmark;
  - settings `SNIGF_DOCUMENT_*` and `SNIGF_PROXY_MAX_BODY_BYTES`;
  - volume `document-data`;
  - five audit actions;
  - the frontend `features/documents`.
- **Known limits:**
  - Objects inside compressed PDF object streams are not read by the screen.
  - There is no malware scan yet. `PENDING` and `REJECTED` are reserved for the scanning
    pipeline of the pilot (P-02: ClamAV, object storage).
  - Uploads are audited and stored, but their content is never verified evidence.
- **Backups (P-01)** must include `document-data` together with the database. A restore of one
  without the other leaves versions without files (refused at download) or files without
  versions (removed by the cleanup).
