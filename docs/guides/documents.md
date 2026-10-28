# Documents: upload, storage, download

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

An applicant adds supporting documents to their draft, one or more versions per category of
the application type. An upload is the most direct way for anyone outside to put bytes on the
server, so nothing about it is trusted: not the name, not the declared type, not the size, not
the content.

The server reads the bytes. It keeps only real PDF, JPEG and PNG files whose name agrees with
what they are, without PDF scripts or similar active content, and without images that would
explode in memory. It stores them under random keys and records each version. It checks every
file's hash before sending it back, and only ever sends it as an attachment. Decision:
[ADR-033](../adr/033-documents-upload-and-storage.md) (task E-13).

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V14__documents.sql` | `document`, `document_version`; guards (owner, draft, category of the type, own name); numbering; no update; deleted only with the draft; `document_storage_keys_known()`; row-level security |
| `document/application/DocumentService.java` | upload, list, download, `removeOrphans()`; audit |
| `document/application/DocumentStorage.java`, `ContentInspector.java` | the ports |
| `document/infrastructure/storage/FileDocumentStorage.java` | the filesystem adapter: staging, random keys, read-only files |
| `document/infrastructure/inspection/TikaContentInspector.java` | recognition from the bytes, PDF screen, image dimensions |
| `document/infrastructure/schedule/DocumentCleanupJob.java` | the scheduled orphan cleanup |
| `document/infrastructure/web/DocumentController.java` | the endpoints below; download headers |
| `application/ApplicationDirectory.java` | the application module's answer: is it the caller's, does it take documents, which categories |
| `infra/proxy/Caddyfile` | `request_body max_size` on `/api/*` |
| `frontend/src/features/documents/` | `DocumentsPanel` (rows per category, upload, download links), `files.ts` (pre-checks) |
| `backend/src/test/sql/t/16_documents.sql` | every database rule |

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/applications/{id}/documents` | `application.read#own` | categories with their versions (newest first), required ones still missing, the size limit |
| `POST /api/v1/applications/{id}/documents/{category}` (multipart `file`) + `Idempotency-Key` | `application.create#own` | 201, the new version |
| `GET /api/v1/applications/{id}/documents/{versionId}/content` | `application.read#own` | the file, as an attachment |

## How it works

### Upload

1. The application must be the caller's own and take documents (a draft), and the category must
   belong to its type. Otherwise the answer is 404, 409 `DOCUMENTS_CLOSED` or 422
   `CATEGORY_UNKNOWN`.
2. **Staging.** `FileDocumentStorage.stage` copies the stream into `.staging/`, counting and
   computing SHA-256. Beyond the limit it stops and deletes (413 `FILE_TOO_LARGE`).
3. **Recognition.** `TikaContentInspector`:
   - `Tika.detect(InputStream)` reads the bytes only, without the name;
   - the type must be PDF, JPEG or PNG, and the name's extension must agree;
   - a PDF needs `%PDF-` at the start and `%%EOF` in the last 2 KB. Every `/Name` is read with
     its `#xx` escapes decoded, and `Encrypt` or an active name refuses it;
   - an image's header gives width × height, which must stay under the pixel limit.
4. A refusal is audited (`DOCUMENT_REJECTED`: code, size, what the bytes were), the staged file
   is deleted, and the answer is 422 with the code.
5. **Store.** The file moves atomically to `ab/cd/<key>` (a random 128-bit key) and becomes
   read-only. A `document_version` row is added, and the database numbers it.
   `DOCUMENT_UPLOADED` is audited, and the answer is kept under the `Idempotency-Key`.
6. If anything fails after the file was stored, the service deletes it. If the process dies,
   the cleanup does.

### Download

1. The version must belong to the caller's application.
2. The file is read once to recompute its SHA-256 and size. A mismatch answers 500
   `DOCUMENT_INTEGRITY_FAILED` and is audited; nothing is sent.
3. The file is sent with:
   - `Content-Disposition: attachment`;
   - the detected type;
   - `nosniff`;
   - `Content-Security-Policy: default-src 'none'; sandbox`;
   - `Cache-Control: no-store`;
   - `Repr-Digest`.

   `DOCUMENT_DOWNLOADED` is audited.

### Orphan cleanup

Hourly by default:
1. List stored files older than the grace period.
2. Ask the database which keys a version still names (keys only, through a SECURITY DEFINER
   function).
3. Delete the others, and the old staging leftovers.
4. A run that removed something is audited (`DOCUMENT_ORPHANS_REMOVED`).

### Limits on every layer

| Layer | Limit | Setting |
| --- | --- | --- |
| Proxy | request body cut beyond the limit | `SNIGF_PROXY_MAX_BODY_BYTES` |
| Servlet | multipart file and request size, parsed lazily | `SNIGF_DOCUMENT_MAX_BYTES`, `SNIGF_PROXY_MAX_BODY_BYTES` |
| Service | bytes counted while staging; versions per document | `SNIGF_DOCUMENT_MAX_BYTES`, `SNIGF_DOCUMENT_MAX_VERSIONS` |
| Images | width × height | `SNIGF_DOCUMENT_MAX_PIXELS` |
| Database | `size_bytes` at most 100 MB, allowed types only | V14 |

## How to do it again

### Change a limit

Set the value in `.env` and restart: `docker compose up -d app web`. Raise
`SNIGF_PROXY_MAX_BODY_BYTES` together with `SNIGF_DOCUMENT_MAX_BYTES` (leave about 100 kB for
the multipart envelope).

### Add a document category

Add it to the type's `documents` in the catalogue ([applications guide](applications.md)).
Its code must start with `DEMO_`, and it needs labels in both languages. A new version of the
type is written at startup, and drafts move to it when saved.

### Move to object storage (pilot, P-02)

Write a second `DocumentStorage` adapter (S3 API) with the same contract:
- staging outside the final namespace;
- commit under a random key;
- list by age;
- delete.

Choose it by a setting. Add the scanner between staging and commit: versions wait as
`PENDING`, and a scan's verdict moves them to `STORED` or `REJECTED`. V14 already allows only
that move.

## How to verify

| Check | Command | Expected |
| --- | --- | --- |
| Database rules | `docker compose --profile test run --rm --build dbtest` | test `16`: 20 ok |
| Back end | `cd backend && ./mvnw verify` | `TikaContentInspectorTest`, `FileDocumentStorageTest`, `DocumentNameTest`, `DocumentIT` pass |
| Interface | `cd frontend && npm test` | `DocumentsPanel.test.tsx` passes |
| Journey | `bash infra/scripts/e2e.sh` | the applicant's journey uploads, is refused a disguised file, downloads after submission |
| Files on disk | `docker compose exec app sh -c 'find /var/lib/snigf/documents -type f -ls'` | names are 32 hex characters, mode `-r--------`, owner `snigf` |

From Git Bash, prefix the last command with `MSYS2_ARG_CONV_EXCL="*"` so the container path is
not rewritten.

## Pitfalls we hit

- **The domain may not use `java.nio.file`** (`DddLayeringTest`). The storage and inspection
  ports, which deal in `Path`, live in the application layer; the domain keeps the types and
  rules.
- **A multipart request builder is not a request builder in Spring 7.** The test `Browser.send`
  takes `AbstractMockHttpServletRequestBuilder<?>` so multipart uploads go through the same
  cookies and CSRF.
- **One PL/pgSQL trigger function for two tables** failed: the `CASE` referring to
  `OLD.application_id` is resolved against `document_version`'s row type too. There is one
  function per table.
- **The proxy limit cuts, it does not answer.** Caddy stops the body at the limit; the app then
  sees an incomplete request (400). No larger body ever reaches it, which is the guarantee
  that matters.
- **Tika brings commonmark** (Markdown rendering), which recognising a file never needs. It is
  excluded and the tests prove detection is unchanged.

## Production differences

- **Backups:** the `document-data` volume must be backed up with the database, from the same
  moment (P-01).
- **Malware scanning** (ClamAV) and object storage are pilot requirements (P-02). The PDF screen
  does not read compressed object streams.
- Uploads are what the applicant sent. They are recorded and kept intact, but they are not
  verified evidence.
