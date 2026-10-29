# Changelog

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Every notable change to SNIGF/NILGS, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once the first release (R0) is tagged.
Each entry names the decision record (ADR) and the guide (`docs/guides/`) that explain it.

## [Unreleased]

### Audit timeline: case and parcel reconstruction from audit alone (E-18, MVP-07)

Decision: [ADR-038](docs/adr/038-audit-subjects-and-case-timeline.md), implementing
[ADR-027](docs/adr/027-office-context-on-audit-events.md) (decision D6).
Guide: [audit trail, case and parcel timeline](docs/guides/audit-trail.md#case-and-parcel-timeline-e-18).

- **Audit subjects (migration V19).** Every new audit event records what it is about:
  - the application, which also covers its document versions, payment requests and matched
    provider reports;
  - a parcel, or both parcels of an alert;
  - each with the record's office at that moment.

  The database finds them from the record in the same transaction (`audit_subjects()`) and
  overwrites anything the caller sends. A draft has no office until it is submitted.
- **In the hash, nothing rewritten.**
  - Events with subjects are encoded `snigf-audit-v2`, the subjects hashed last.
  - Events without subjects, including every event recorded before V19, keep `snigf-audit-v1`
    byte for byte, so every existing hash and checkpoint still verifies.
  - No backfill.
  - The Java verifier implements both versions (`ChainedEvent`); `docs/audit.md` documents v2 for
    offline verifiers.
- **Supervisors read their office's events** (ADR-027, D6). `GET /api/v1/audit/events` moves to
  `audit.event.search` somewhere:
  - each row is filtered in the database by `audit_readable()`: everywhere for the auditor, at
    the office of one of the event's subjects for a supervisor;
  - new filters `subjectType` and `subjectId`;
  - each event now shows its subjects with office codes, the account's current name, and
    `intact`.
- **The timeline.** `GET /api/v1/audit/timeline?type=APPLICATION|PARCEL&reference=` (or `&id=`):
  - every readable event of the case or parcel, oldest first, at most 500;
  - the record is found from the audit trail itself;
  - each event is checked intact;
  - reading is audited, with the new action `AUDIT_TIMELINE_READ`;
  - `404 TIMELINE_NOT_FOUND` alike for an unknown record and another office's.
- **Interface:**
  - `#/audit/timeline`: the events in words (French and English), with who acted, the office,
    the outcome, what was recorded, and an intact or altered badge;
  - links from the case and parcel pages;
  - the audit page's event search is open to supervisors, limited to their offices, with the
    account name, the office and the altered badge.
- **Tests:**
  - `TimelineIT`: a whole case read by the auditor and by the office's supervisor; another
    office's supervisor gets 404, a reviewer 403; the office-scoped search; Java and SQL v2
    agree; an altered row shows;
  - pgTAP `21`: 16 tests;
  - `AuditDomainRulesTest` (v2);
  - `AuditReadingScopeTest`: exactly event search and the timeline are office-scoped;
  - `PermissionMatrixIT`;
  - Vitest: the timeline page;
  - e2e: the supervisor's step is now "reads the events of their office only"; a new step
    reconstructs the case from the audit trail, from the submission to the decision.

### Simulated payment and reconciliation (E-17, MVP-08)

Decision: [ADR-037](docs/adr/037-simulated-payment-and-reconciliation.md).
Guide: [simulated payment and reconciliation](docs/guides/payments.md).

**No real money moves, and no real payment API is called in any environment.** The only
provider is a DEMO simulator; any other is refused by setting and by database constraint.

- **The DEMO fee.** 25,000 XAF for `DEMO_REGISTRATION` (`payment-fees.json`,
  `SNIGF_PAYMENT_FEES`), labelled "DEMO platform service charge — not an official administrative
  fee". It is applied at start-up as versions that never overlap, and audited
  (`PAYMENT_FEE_CHANGED`). What an application owes is the fee in force when it was submitted.
- **The payment gate** (`SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE`, on by default): an unpaid
  application's completeness cannot be accepted. It is enforced twice:
  - `CaseService` refuses with 409 `PAYMENT_REQUIRED`, through the new `application.PaymentLookup`
    port the payment module implements;
  - the trigger `completeness_payment_guard` refuses it too.

  The case shows the fee's standing, and the Accept button says why it waits.
- **The applicant pays** (`payment.pay`, own applications only, 404 otherwise):
  `POST /api/v1/applications/{id}/payments` with an `Idempotency-Key` and a DEMO scenario.
  - A request `DEMO-PAY-{yyyy}-{seq8}-{check}` opens and is committed before the provider is
    called.
  - It expires after `SNIGF_PAYMENT_PENDING_TTL` (`PT24H`). The database bounds that to 1 minute
    to 7 days.
  - Only one is pending or paid at a time. After a failure or an expiry, paying again opens a new
    one.
  - If the provider is unreachable, the answer is 503 `PROVIDER_UNAVAILABLE` and paying again
    restarts the same request.

  `GET /api/v1/applications/{id}/payment` shows the fee, the gate and the standing.
- **The DEMO simulator** (`payment-sim`): a new Compose service in Node.js with no dependency,
  hardened like the others.
  - It takes charges only from the app's provider-client certificate.
  - It signs its callbacks inside OpenBao, with its own certificate login and a policy allowing
    the HMAC of the payment callback key and nothing else. **The key never leaves OpenBao and is
    never in `.env`.**
  - It sends them through the proxy.

  Scenarios: `SUCCEED`, `FAIL`, `NO_RESPONSE`, `DUPLICATE`, `WRONG_AMOUNT`, `UNKNOWN_REFERENCE`.
- **The callback path.**
  - The proxy has a second listener (`SNIGF_PROVIDER_CALLBACK_PORT`, 47090, internal network
    only). It requires and verifies the provider's client certificate, and passes its subject in
    `SNIGF_PROXY_PROVIDER_CLIENT_HEADER`.
  - The browser listener removes that header and answers 404 on the callback path.
  - The app then checks the provider name, a timestamp within `SNIGF_PAYMENT_CALLBACK_MAX_SKEW`
    (`PT5M`), and an HMAC-SHA-256 over `<timestamp>.<body>` recomputed in OpenBao, before reading
    the body.
  - Unsigned, forged, tampered, stale or unvouched callbacks get 401 with their reason; nothing
    of them is stored.
- **Reconciliation in the database** (`payment_callback_apply()`, under a lock on the request):
  - matched by reference **and** amount;
  - each provider transaction id recorded once, so duplicates and concurrent copies change
    nothing;
  - `AMOUNT_MISMATCH`, `UNMATCHED_REFERENCE`, `AFTER_EXPIRY` and `ALREADY_SETTLED` stay
    exceptions, never payments;
  - expiry decided by `expires_at`, so a late success never pays an expired request, whether or
    not the sweep (`SNIGF_PAYMENT_EXPIRY_CRON`) has run.
- **The finance officer** (`payment.reconcile`), on `#/payments`:
  - counts, exceptions to reconcile, resolved exceptions, payment requests;
  - resolution with `REFUND_SIMULATED` or `NO_ACTION` and a note, with an `Idempotency-Key`,
    append-only, never on their own application, never paying a request.

  The dashboard counts open exceptions.
- **A replaceable provider port** (`payment.domain.PaymentProvider`). The only adapter is
  `DemoSimulatorProvider`. A future provider needs its adapter, an approved `Kind`, a migration
  and a new ADR, and stays disabled until then.
- **Audit:** eleven actions, `PAYMENT_FEE_CHANGED`, `PAYMENT_POLICY_CHANGED`,
  `PAYMENT_REQUESTED`, `PAYMENT_REFUSED`, `PAYMENT_PROVIDER_UNAVAILABLE`,
  `PAYMENT_CALLBACK_ACCEPTED`, `PAYMENT_CALLBACK_REJECTED`, `PAYMENT_CALLBACK_DUPLICATE`,
  `PAYMENT_EXCEPTION_RAISED`, `PAYMENT_EXPIRED`, `PAYMENT_EXCEPTION_RESOLVED`.
  `COMPLETENESS_REJECTED` may carry `PAYMENT_REQUIRED`.
- **Migration V18:** `payment_policy`, `fee_schedule`, `payment_request` (row-level security),
  `payment_record`, `payment_exception_resolution`, and their functions and guards.
- **Infrastructure:**
  - three internal networks (`provider-out`, `provider-in`, `provider-key`);
  - four certificates from `dev-pki.sh` (`web_provider_tls`, `payment_sim_tls`,
    `payment_sim_client`, `app_provider_client`);
  - the simulator's OpenBao role and policy (`payment-sim-policy.hcl.template`);
  - the SSL bundle `snigf-payment-provider`.
- **Settings:** `SNIGF_PROVIDER_CALLBACK_PORT`, `SNIGF_PAYMENT_SIM_PORT`,
  `SNIGF_PROXY_PROVIDER_CLIENT_HEADER`, `SNIGF_PAYMENT_FEES`,
  `SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE`, `SNIGF_PAYMENT_PENDING_TTL`,
  `SNIGF_PAYMENT_PROVIDER`, `SNIGF_PAYMENT_CALLBACK_MAX_SKEW`,
  `SNIGF_PAYMENT_PROVIDER_CLIENT_CNS`, `SNIGF_PAYMENT_EXPIRY_CRON`, `SNIGF_PAYMENT_PAGE_SIZE`,
  `SNIGF_PAYMENT_SIMULATOR_CONNECT_TIMEOUT`, `SNIGF_PAYMENT_SIMULATOR_REQUEST_TIMEOUT`,
  `SNIGF_PAYMENT_APP_CLIENT_CN`, `SNIGF_PAYMENT_SIM_CLIENT_CN`, `SNIGF_BAO_PAYMENT_SIM_ROLE`,
  `SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS`, `SNIGF_PAYMENT_SIM_MEMORY`, `SNIGF_PAYMENT_SIM_CPUS`,
  `SNIGF_PAYMENT_SIM_PIDS`.

  **Action for an existing setup:**
  1. Add these lines to `.env` (see `.env.example`).
  2. Run `bash infra/scripts/dev-pki.sh` for the four new certificates.
  3. Run `docker compose up -d --build --wait`: `bao-init` provisions the simulator's login,
     because its inputs changed.
- **Tests:**
  - `PaymentDomainTest`, `DemoSimulatorProviderTest`;
  - `PaymentIT`: the six outcomes; callbacks after expiry with and without the sweep; eight
    concurrent copies of one callback and six concurrent successes; every rejected signature
    case; ownership, idempotency, an unreachable provider, the finance lists;
  - `CompletenessIT` checks the gate;
  - `PermissionMatrixIT` covers the six new endpoints;
  - pgTAP `20` (38 tests);
  - Vitest: the payment panel, the finance page, the acceptance held until paid;
  - the end-to-end journey pays before the intake officer accepts.

  The integration tests' `underReview()` and `paid()` helpers pay through the real callback
  endpoint.

### Tracking and workload report (E-16, MVP-06)

Decision: [ADR-036](docs/adr/036-tracking-and-workload-report.md).
Guide: [tracking and workload report](docs/guides/tracking-and-workload.md).

- **Fixed, the applicant's view leaked internals.** Until now `GET /api/v1/applications/{id}`
  returned the staff history:
  - the name of every officer who moved the application, and of the intake officer in the
    completeness steps;
  - the reason recorded with each move: the reviewer's recommendation (`RECOMMEND_REFUSAL`),
    `RETURNED` for a return to review.

  The applicant now reads a projection (`Tracking`):
  - steps name a party, `APPLICANT`, `OFFICE` or `SYSTEM`, never a person, never a reason;
  - messages only when written to the applicant or by them;
  - `since` (the date the current state was entered, from the history), `waitingOn`
    (`APPLICANT`, `OFFICE`, or `null` once ended) and `pendingRequest` (categories, message,
    date).

  Staff endpoints are unchanged.
- **Schema test** (Baseline acceptance): `ApplicantPayloadSchema` lists every field the applicant
  may receive. `TrackingIT`, `ApplicationIT` and `ReviewIT` check it, along with "no staff name,
  finding, return reason or recommendation code".
- **Migration V17** (no table):
  - `office_stays()`: every stay in a state, from `application_transition` only;
  - `workload_office()`: `report.workload` at the office, role or special grant;
  - `workload_target()`;
  - `workload_counts()`, `workload_open_cases()`, `workload_completed_stays()`: `SECURITY
    DEFINER`, returning nothing for an office the actor may not read, never a form, a document,
    an applicant's name or a review.
- **Module `reporting`** (was empty): `StageTargets`, `Workload`, `WorkloadService`,
  `ReportingProperties`, `JdbcWorkloadRepository`, `WorkloadController`.
- **Endpoints**, both at `report.workload` for the office:
  - `GET /api/v1/reports/workload?office=`: targets, counts per state, open and overdue per
    stage, load per person and stage, stage durations (count, average, median, longest, over
    target) of the period;
  - `GET /api/v1/reports/workload/cases?office=&state=&overdue=&page=`: cases under way,
    longest waiting first; `422 STATE_NOT_LISTED` for a state a case does not wait in.
- **Settings:**
  - `SNIGF_REPORTING_TARGET_COMPLETENESS_REVIEW` (`P5D`), `_INFO_REQUESTED` (`P30D`),
    `_UNDER_REVIEW` (`P10D`), `_PENDING_DECISION` (`P5D`): DEMO targets, checked at startup;
  - `SNIGF_REPORTING_DURATION_WINDOW` (`P90D`), `SNIGF_REPORTING_PAGE_SIZE` (`50`).

  **Action for an existing `.env`:** add these six lines (see `.env.example`), or the app refuses
  to start.
- **Interface:**
  - **Where your application stands** on every submitted application: state, since when, what
    it means, whose turn it is. The applicant's history reads "You", "The office", "The system";
  - **Workload** (Oversight) for supervisors: a tile per stage with its target and a red edge
    once a case is overdue, counts per state, load per officer, stage durations, and the queue
    (`?state=`, `?overdue=1`). A reference opens the case only for readers of the office's
    cases;
  - the dashboard counts overdue cases and offers the workload;
  - the information request panel reads `pendingRequest`.
- **Tests:**
  - `TrackingTest`, `StageTargetsTest`;
  - `TrackingIT` (a whole life, information request and return to review included, checked at
    every step);
  - `WorkloadIT` (counts, overdue, assignees, stays counted from the transition table, the
    queue; other offices, intake officers and applicants refused);
  - `PermissionMatrixIT` gains both endpoints;
  - pgTAP `19` (20 tests);
  - `CaseFlowIntegrationTest`: the case-flow helpers, taken out of `ReviewIT`;
  - Vitest: `TrackingCard`, `WorkloadPage`, `formatDuration` (74 tests);
  - an end-to-end step: the applicant's page and API name no officer, and the supervisor reads
    the workload with the decided case.

### Review and simulated decision (E-15, MVP-05)

Decision: [ADR-035](docs/adr/035-review-and-simulated-decision.md).
Guide: [applications, review and simulated decision](docs/guides/applications.md#review-and-simulated-decision-e-15).

- **Migration V16:**
  - `case_assignment`: a reviewer or decision authority of the office claims a case waiting at
    their stage, for themselves, one per case and stage, append-only;
  - `review_step` (recommendations with findings, returns with reasons) and `decision` (one per
    application, `APPROVED` or `REFUSED`, grounds, 1 to 50 cited stored versions, the
    recommendation it follows, `simulated` forced true), both append-only;
  - separation of duties in the database: whoever recommended never claims the decision nor
    decides; nobody handles their own application;
  - no decision while an overlap or duplicate alert on the parcel is open, checked last;
  - the application guard replaced: review and decision moves need their record by the person
    assigned;
  - row-level security for assignees (`application_assigned_read`, `application_assigned_handle`);
    the review hidden from the applicant.
- **Permissions at scope ASSIGNED** now open something: `@access.canAssigned`, the service
  checking the assignment per case (404 for any other).
- **Endpoints:**
  - `GET /api/v1/reviews` (mine, or waiting at my stage) and `GET /reviews/{id}`;
  - `POST /reviews/{id}/review-claim`, `/recommendation`, `/decision-claim`, `/return`,
    `/decision`, each with `expectedVersion`;
  - `GET /api/v1/assigned-cases/{id}` and its documents, for the assignee;
  - `GET /api/v1/cases/{id}/review`, for the office's readers;
  - the applicant's application now carries the decision (`simulated: true`), never the review.
- **Audit:** `CASE_CLAIMED`, `REVIEW_RECOMMENDED`, `DECISION_RETURNED`, `DECISION_RECORDED`;
  refusals as `REVIEW_REJECTED` with their code.
- **Interface:**
  - **Review** for reviewers and decision authorities: their cases and those waiting to be
    claimed;
  - a case page with the case beside the action panel (claim, recommend, return, decide with
    grounds and ticked citations) and the review record;
  - the decision button disabled while an alert is open; the recommender told why they cannot
    decide;
  - `DecisionCard` for staff and applicant, always under "Simulated decision — no legal effect",
    printable (print styles and tokens).
- **Tests:**
  - pgTAP `18` (27 tests); test `17` #24 now expects `restrict_violation`;
  - `ReviewIT` (the Baseline's acceptance: no grounds 422, a reviewer deciding 403, a case not
    pending decision 409; the recommender blocked; an open alert blocks);
  - `PermissionMatrixIT` gains the assigned reach and ten endpoints;
  - `ReviewPanel.test.tsx` (6 tests);
  - an end-to-end journey: the reviewer claims and recommends, the decision authority claims and
    decides, the applicant reads the simulated decision, the auditor finds the events.

### Completeness review and information requests (E-14, MVP-03)

Decision: [ADR-034](docs/adr/034-completeness-review.md).
Guide: [applications, completeness review](docs/guides/applications.md).

- **Migration V15:**
  - `application_missing_items()`: the automatic check (required fields, required document
    categories without a stored file), used by screens, service and guards alike;
  - `completeness_record`, append-only: automatic results, acceptances, information requests
    with categories and message, responses; what was missing is filled in by the database;
  - the application guard replaced: every move needs its record, and the automatic check is
    the only move without a person;
  - review and decision moves already need their permission at the office;
  - during an information request, files only in the categories asked for;
  - staff of the office move the state of its applications, never their own.
- **Submission:** now ends with the automatic check; the application goes to completeness
  review.
- **Staff endpoints:**
  - `GET /api/v1/cases` (office queue) and `GET /api/v1/cases/{id}`;
  - the case's documents, read only and audited;
  - `POST /cases/{id}/acceptance`: 409 `ITEMS_MISSING` with every item while anything is
    missing;
  - `POST /cases/{id}/information-request`.
- **Applicant endpoint:** `POST /applications/{id}/response`.
- **Refusals:** audited as `COMPLETENESS_REJECTED`; nobody handles their own application.
- **Audit:** `COMPLETENESS_ACCEPTED`, `COMPLETENESS_INFO_REQUESTED`, `COMPLETENESS_RESPONSE_SENT`
  and `COMPLETENESS_REJECTED`.
- **Interface:**
  - **Cases** for staff: the queue, and a case with the automatic check in words, answers,
    documents, completeness steps, history, and the intake officer's decision (accept, or
    request with the missing categories ticked and a message);
  - the applicant's information request: what was asked and why, uploads in those categories
    only, the response;
  - the dashboard counts cases waiting.
- **Tests:**
  - pgTAP `17`;
  - `CompletenessIT` (the round trip keeps both versions and the history; invalid transitions
    409);
  - permission matrix entries;
  - `CaseDetailPage.test.tsx`, `InfoRequestPanel.test.tsx`;
  - an end-to-end round trip with an intake officer.

### Documents: upload, storage, download (E-13, MVP-02)

Decision: [ADR-033](docs/adr/033-documents-upload-and-storage.md).
Guide: [documents](docs/guides/documents.md).

- **Upload pipeline:**
  - staged and hashed (SHA-256) as it arrives;
  - recognised from its bytes with Apache Tika 4.1.0 (`tika-core`, without commonmark): PDF,
    JPEG or PNG only, with the name's extension agreeing;
  - PDFs with scripts, launch actions, embedded files, submitting forms, rich media, XFA (escaped
    names decoded) or encryption refused;
  - images over the pixel limit refused.

  A refusal is audited with its code, and nothing is kept.
- **Storage:** under random 128-bit keys, files read-only, directories the application's only,
  on the volume `document-data` mounted into the app alone. Paths are built only from
  key-shaped strings.
- **Migration V14:**
  - `document` and `document_version`: append-only, numbered by the database;
  - only the owner adds, to their draft, in a category of the type, under their own name;
  - deleted only with their draft;
  - row-level security following the application's;
  - `document_storage_keys_known()` for the cleanup.
- **Download:** the hash is checked before anything is sent (altered bytes are never served and
  are audited). Always an attachment, with `nosniff`, a sandbox CSP, `no-store` and
  `Repr-Digest`.
- **Orphan cleanup:** scheduled; removes files nobody references, and staging leftovers, after a
  grace period; audited.
- **Limits:**
  - the proxy (`request_body max_size`);
  - servlet multipart (lazy);
  - the service (bytes, versions per document);
  - the database (100 MB cap).

  New settings: `SNIGF_DOCUMENT_*` and `SNIGF_PROXY_MAX_BODY_BYTES`.
- **Shared code:** the application module publishes `ApplicationDirectory`. The HTTP client
  sends `FormData` as multipart.
- **Audit:** five actions, `DOCUMENT_UPLOADED`, `_REJECTED`, `_DOWNLOADED`, `_INTEGRITY_FAILED`
  and `_ORPHANS_REMOVED`.
- **Interface:** the draft form and the submitted view show the documents:
  - one row per category: required, provided or optional;
  - the latest file as a download link, earlier versions folded;
  - an upload per row, with pre-checks;
  - the server's refusals in words;
  - what is still missing.
- **Tests:**
  - pgTAP `16`;
  - `TikaContentInspectorTest` (disguised files), `FileDocumentStorageTest`, `DocumentNameTest`,
    `DocumentIT` (orphan cleanup, tampered bytes, IDOR, limits);
  - permission matrix entries;
  - `DocumentsPanel.test.tsx`;
  - the applicant's end-to-end journey with a refused disguised file, uploads and a download.

### Applications: drafts and submission (E-12, MVP-02)

Decision: [ADR-032](docs/adr/032-applications-drafts-and-submission.md).
Guide: [applications](docs/guides/applications.md).

- **Migration V13:**
  - the application type catalogue (one row per version, insert-only, DEMO in both names);
  - applications (owner, type version, nine states, parcel and office from submission, form
    data, reference once submitted);
  - their history, written by the database on every state change and never edited.
- **The database guards:**
  - an application starts as a draft;
  - only the drawn transitions are legal;
  - submitting and withdrawing are the owner's acts;
  - a submission names an active parcel and takes its office;
  - what was submitted never changes;
  - only drafts are deleted.
- **Row-level security:** an application is visible only to its owner, or to staff holding
  `application.read` at its office. The actor is named per transaction (`DatabaseActor`);
  without one, nothing is visible.
- **Endpoints** for the applicant's own records (`@access.canOwn`):
  - types;
  - list, create, read, update and delete drafts;
  - an idempotent submission that issues `DEMO-APP-{yyyy}-{seq6}-{check}` and lists every
    missing item when incomplete.

  Someone else's id gets 404, and the attempt is audited.
- **Type catalogue:** configured in `SNIGF_APPLICATION_TYPES` (built-in
  `application-types.json`: "Demande d'immatriculation (DEMO)", four fields, three DEMO document
  categories). It is checked strictly at startup, and a change becomes a new version (audited).
  New settings: `SNIGF_APPLICATION_REFERENCE_ZONE`, `SNIGF_APPLICATION_PAGE_SIZE` and
  `SNIGF_APPLICATION_MAX_DRAFTS`.
- **Shared code:** the parcel module publishes `ParcelDirectory`. The ISO 7064 check character
  moved to `shared/id/CheckCharacter` (parcel references unchanged).
- **Audit:** six actions, `APPLICATION_TYPE_CHANGED`, `APPLICATION_DRAFT_CREATED`, `_UPDATED`,
  `_DELETED`, `APPLICATION_SUBMITTED` and `APPLICATION_SUBMIT_REJECTED`.
- **Interface:** "My applications" for applicants:
  - the list;
  - a new draft;
  - the draft form drawn from the type: parcel, fields, and document categories marked "not
    available yet";
  - save, delete, and submit with confirmation;
  - missing items in a summary and at each field;
  - a submitted application read-only, with its history.

  Dashboard actions for applicants.
- **Tests:**
  - pgTAP `15`;
  - `ApplicationDomainTest`, `ApplicationIT` (IDOR, double submission, incomplete submission,
    frozen after submission), `ApplicationTypeConfigIT` (C4);
  - permission matrix entries for the seven endpoints (applicants reach only these);
  - `DraftForm.test.tsx`;
  - three end-to-end journeys.

### Alert review screens (E-11, MVP-04)

Decision: [ADR-031](docs/adr/031-alert-review-and-resolution.md).
Guide: [overlap and duplicate detection, review and resolution](docs/guides/conflict-detection.md).

- **Endpoints:**
  - `GET /api/v1/alerts?office=&status=&type=&page=`: the alerts of an office;
  - `GET /api/v1/alerts/{id}`: both sides as compared, the evidence, both versions and the
    intersection in one UTM zone, and whether the reader authored a compared version;
  - `POST /api/v1/alerts/{id}/resolution`: an outcome (`NO_CONFLICT`, `DATA_ERROR_CORRECTED`,
    `REFERRED_FOR_ADJUDICATION`) and a note of 10 to 2000 characters, against the version read.

  They need `alert.review` or `alert.resolve` at the office of either parcel (new
  `@access.canAtAny`).
- **Separation of duties, twice:** the author of either compared version is refused by the
  service (`RESOLVER_IS_AUTHOR`) and by the database guard.
- **Audit:** every refusal is recorded (`CONFLICT_ALERT_RESOLVE_REJECTED`), and so is every
  resolution (`CONFLICT_ALERT_RESOLVED`).
- **Migration V12:** the guard accepts `alert.resolve` held through a special grant in force at
  either office, as the application does (`holds_grant_at`). The resolution note is bounded to
  2000 characters.
- **Interface:**
  - an **Alerts** place for reviewers, with filters in the address;
  - a review page:
    - a map of both parcels and the hatched intersection, with a zoom to it;
    - the two parcels side by side;
    - the resolution form with explained outcomes and a counted note, confirmed before
      sending;
    - for the author, the reason they cannot resolve;
    - an alert changed meanwhile is reloaded, never overwritten;
  - each alert on a parcel page links to its review;
  - the dashboard counts open alerts.
- **Demonstration:**
  - `surveyor.yde`, a second cadastral officer at YDE1, resolves what `cadastre.yde` raises;
  - `dev-seed-demo.sh` can run again when roles already exist (a `\gset` on an empty insert
    failed).
- **Tests:**
  - pgTAP `14` (special grants in force, revoked, expired, other office; note bound);
  - `AlertResolutionSoDIT`, `ConflictAlertTest`, `AlertDetailPage.test.tsx`;
  - permission matrix entries for the three endpoints;
  - an end-to-end journey: the author sees no form and is refused, and a colleague resolves.

### Overlap and duplicate detection (E-10, MVP-04)

Decision: [ADR-030](docs/adr/030-overlap-and-duplicate-detection.md).
Guide: [overlap and duplicate detection](docs/guides/conflict-detection.md).

- **Migration V11:** every parcel version written is compared, by the database and in the
  same transaction, with the current version of every other active parcel: OVERLAP (interiors
  intersect over at least 1 m² on the spheroid), DUPLICATE_GEOMETRY (at least 95 % of each
  parcel), DUPLICATE_REFERENCE (same normalised external reference). One open alert per pair
  and type, with both version ids, the intersection area, both shares and the intersection
  geometry. A correction supersedes the parcel's open alerts and is checked afresh. Writers are
  serialised during the check, so concurrent writes see each other. Alerts are created only
  by the database; resolution rules (entitled resolver, author of neither version, note,
  optimistic lock) are enforced by a guard ready for E-11. Existing parcels were checked once.
- **Conflict module:** the alerts a version raised are in the creation and correction answers
  and recorded in the audit trail (`CONFLICT_ALERT_RAISED`, `CONFLICT_ALERT_SUPERSEDED`);
  `GET /api/v1/parcels/{reference}/alerts` for holders of `alert.review` at the parcel's
  office; thresholds from `SNIGF_CONFLICT_MIN_OVERLAP_M2` and `SNIGF_CONFLICT_DUPLICATE_SHARE`,
  applied at startup and audited when changed (`CONFLICT_POLICY_CHANGED`).
- **Interface:** after recording or correcting, a warning names how many alerts the version
  raised; the parcel page lists its alerts for cadastral officers (type, other parcel,
  versions, intersection and shares, history).
- **Tests:** pgTAP `13` (the Baseline's eight-parcel fixture set yields exactly its expected
  alerts; references; one open per pair; what the application's account cannot do; resolution
  rules; supersession; policy), `ConflictDetectionIT`, `ConflictPolicyTest`,
  `ParcelAlerts.test.tsx`, and an end-to-end journey.

### Satellite view: real imagery for the whole country

- `infra/scripts/maps-build-satellite.sh` builds a national Copernicus Sentinel-2 mosaic at
  10 m, cloud-masked tile by tile from the clearest scenes, with sharper aerial images drawn
  over it where they exist (OpenAerialMap, CC-BY 4.0: Douala 11 cm, Ngaoundéré 5 cm). The
  fetch is resumable and takes the towns first; building and installing run offline.
- A background may hold up to 20 detail archives besides its base, each with its own
  attribution, licence, date, extent and zooms in the manifest, checked like the base; only
  files the manifest lists are served (`GET /api/v1/map/{kind}/{file}.pmtiles`).
- The interface draws the details over the base within their zooms and credits each source.
- New settings `SNIGF_MAPS_SATELLITE_*`, `SNIGF_MAPS_SENTINEL_*`, `SNIGF_MAPS_AERIAL_*`.
- Downloads are checked for completeness and readability and resumed until whole; the scene
  list is kept a day so a resumed run starts at once.
- Operator guide: [satellite imagery](docs/guides/satellite-imagery.md) (trial, national
  download, build, install, checks, updates, troubleshooting, cleanup).
- Tests: detail archives in `FileMapDataStoreTest` and `MapApiIT`; the satellite style with
  details validated by MapLibre's validator.

### Geographic view: the real map of Cameroon

- The OpenStreetMap background for Cameroon is built and installed (extract of 2026-10-21):
  parcels now show among the real streets, buildings and places.
- The geographic style draws road outlines, street names, quarter and neighbourhood names,
  landmarks by name and house numbers, besides buildings, roads, water and places; names in
  French where available.
- Fixed: the tile build failed loading SQLite's native library from `/tmp` (no execute
  rights in the hardened container): `planetiler` now unpacks it in its build volume.
- Fixed: a size rule nested a zoom expression inside `match`, which MapLibre refuses; the
  view fell back to its "could not be displayed" state. `style.test.ts` now validates both
  styles with MapLibre's own validator (`@maplibre/maplibre-gl-style-spec` 26.4.4, the version
  MapLibre itself uses, as an exact development dependency).

### Interface redesign: application shell, design system, parcel workspace

Frontend only: no API, permission, database or security change.

#### Added

- **Shell:** sidebar of the places the session can use (permissions and special grants), top
  bar with offices, language switch and account menu, mobile drawer, skip link, breadcrumbs.
  Addresses by fragment (`#/parcels/<reference>?view=satellite`), surviving reload and back
  button; a place the session cannot use shows "not authorized". Pages load on first use (the
  sign-in bundle went from 425 kB to 306 kB).
- **Design system:** tokens from the logo's palette, an icon set drawn for the project,
  buttons, cards, page headers, fields with tied hints and errors, alerts, badges, skeletons,
  empty states; responsive at 80/64/48/36 rem; WCAG 2.2 AA as the target.
- **Dialogs** replace `window.prompt` and `window.confirm`: the consequence is stated, a
  required reason is checked as it is typed.
- **Dashboard:** decisions waiting (real pending counts), what to start, latest parcels of the
  person's offices, map data status, own security. No totals: the API has no count endpoint.
- **Parcels:** table with search kept in the address; recording in three steps with plain-words
  coordinate systems, a description of the geometry as typed, a preview and a review; refusals
  next to the boundary with PostGIS's reason, input kept; detail page with properties and a
  version timeline; correction mode with the proposed boundary, never overwriting a newer
  version.
- **Map workspace:** Technical, Geographic and Satellite in one segmented control; each says
  whether it works offline or needs the server; an unavailable one can be chosen, explains why
  and offers the technical view; an offline state; the parcel's outline is cased so it reads on
  imagery, and the first view fits the parcel.
- **Administration:** account directory with role cards, pending requests with people's names
  when known, a person lookup for special grants, roles by family and scope with a permission
  matrix, identity confirmations confirmed in a dialog; audit page with verification,
  checkpoints, keys and an events table with details on demand.
- Tests: 37 unit and component tests (from 13); the browser journeys follow the new screens
  with every security assertion unchanged; a design tour (`E2E_TOUR=1`) captures every main
  screen at three sizes.

#### Fixed

- Input `pattern`s invalid under the `v` flag were silently ignored by browsers (security-key
  names, beneficiary ids): escaped, and a test compiles every pattern.
- A role approved while its holder was signed in did not appear until a reload: the session is
  read again on navigation and window focus.
- The language choice is remembered.

### Demonstration accounts (development only)

- `infra/scripts/dev-seed-demo.sh` creates the demo offices `YDE1` (Yaoundé) and `DLA1`
  (Douala) and eleven accounts: one per baseline role, a second administrator for four-eyes
  approvals, and a second cadastral officer at Douala to show office scoping. Accounts are
  registered through the API (passwords cannot be written in SQL), then made staff as by
  `dev-make-staff.sh`; each role grant is audited. Passwords are random and kept in
  `infra/secrets/demo_accounts` (git-ignored, 0600). Running it again creates only what is
  missing. It refuses to run unless `SNIGF_PROFILE=dev` and is never a migration.
- Guide: [demonstration accounts](docs/guides/demo-accounts.md).

### Parcels and three map views (slice 1)

Decision: [ADR-029](docs/adr/029-map-views-and-sovereign-map-data.md).
Guide: [map data](docs/guides/map-data.md).

#### Added

- **Parcels** (`org.snigf.mvp.parcel`): create from GeoJSON (EPSG:4326, 32632, 32633) with an
  `Idempotency-Key`, server-issued reference `DEMO-PCL-<office>-<year>-<seq>-<check>` (ISO 7064
  MOD 37,36), PostGIS validation that never repairs silently, area on the ellipsoid; corrections
  as new versions with a reason and optimistic version check; read, history and search.
  Permissions `parcel.create`, `parcel.correct` (office), `parcel.read`. Audit actions
  `PARCEL_CREATED`, `PARCEL_CREATE_REJECTED`, `PARCEL_CORRECTED`, `PARCEL_CORRECT_REJECTED`.
- **Map views** (`org.snigf.mvp.maps`): technical (SVG in UTM metres: grid, zoom/pan, scale,
  coordinate readout, measurement; offline, no WebGL), geographic (OpenStreetMap vector tiles)
  and satellite (licensed imagery), all over the same authoritative geometry. Map data is
  ours: PMTiles archives with manifests in the read-only volume `map-data`, served by the
  application with range requests behind `parcel.read`; a view without data is disabled with
  its reason. `SNIGF_MAP_EXTERNAL_PROVIDERS=none` is the only accepted value.
- **Map tools** (Compose profile `maps`): `maps-build-geographic.sh` (Planetiler 0.10.2, Noto
  Sans glyphs pinned by SHA-256), `maps-import-imagery.sh` (GDAL 3.13.3, go-pmtiles 1.31.2),
  `maps-synthetic-imagery.sh`; downloads in two jobs with network, everything else without;
  `maps-install` alone writes `map-data`. New settings `SNIGF_PARCEL_*`, `SNIGF_MAP_*`,
  `SNIGF_MAPS_*`; volumes `map-sources`, `map-work`; network `maps-egress`.
- **Interface:** Parcels panel (search, record, detail, history, correction) and the three
  views; MapLibre 6.13 loaded only for the tiled views, worker from our origin (CSP unchanged).
- **Tests:** `ParcelDomainTest`, `ParcelApiIT`, `FileMapDataStoreTest`, `MapApiIT`; frontend
  unit tests with Vitest (`npm test`, in CI); end-to-end parcel and map journeys (only our own
  origin contacted, no CSP violation, switching never re-reads the parcel).

#### Fixed

- **Security:** relative scopes (assigned, own, linked) were issued as the bare permission
  code, which `can` reads as scope *any*. They are now `<permission>#<scope>` authorities that
  no access check accepts ([permission matrix](docs/permission-matrix.md#scopes), ADR-025).
- An end-to-end recovery check matched audit rows of earlier runs; it now reads this run's row.

#### Not yet available

- The real geographic tiles were not built here: osmdata.openstreetmap.de (water polygons)
  was unreachable. Run `bash infra/scripts/maps-build-geographic.sh` where it is reachable.
- Satellite imagery awaits a source and a licence that allows self-hosting (ADR-029).

### CI: end-to-end tests and pinned actions

- The `stack` job runs the end-to-end suite in Chromium after the probes and uploads the
  report and traces on failure; timeout 45 minutes.
- Every action is pinned to a commit SHA (checkout 7.0.1, setup-java 6.0.1, setup-node 7.0.0,
  upload-artifact 7.0.1, gitleaks-action 2.3.9), without persisted credentials; runs of the
  same branch cancel each other.
- `e2e.sh` makes `e2e/out` writable for the container's user on Linux runners.
- [Chapter 7](docs/technical/07-testing.md#continuous-integration) describes the jobs.

### Recovery gaps closed: owners remove lost keys; resets confirmed by the supervisor

Decision: [ADR-028](docs/adr/028-key-removal-and-supervised-reset.md).
Procedure: [account recovery](docs/technical/08-operations-and-troubleshooting.md#account-recovery-procedure).

#### Added

- **Your security keys** on the session screen: name, registration and last use; **Remove
  (lost)** (`GET`/`DELETE /api/v1/auth/mfa/keys`). The last key stays (409 `LAST_KEY`); a
  removed key is no longer offered at sign-in; audited `MFA_KEY_REMOVED`.
- **Supervised reset:** an administrator requests (`POST /admin/users/{id}/mfa-reset`, 202);
  the person's supervisor confirms in **Identity confirmations**
  (`/api/v1/identity/reset-confirmations`), saying how the identity was checked; the database
  performs the reset. Permission **`identity.reset.confirm`** (business, office, never
  delegable) for SUPERVISOR. Audit actions `MFA_RESET_REQUESTED`, `MFA_RESET_REJECTED`.
- **Migration V10:** the last key of an enrolled account cannot be deleted; reset requests
  with the three-person rule and the identity check; only a confirmed reset (or the
  superuser) clears an enrolment. pgTAP test `12_supervised_reset`.
- Tests: `SecurityKeysIT`, `SecondFactorResetIT`; end-to-end recovery journeys updated.

#### Changed

- `admin.mfa.reset` now means "request a reset"; the free-text `confirmedBy` is gone.
- The reset code moved from `AccountAdministrationService` to `SecondFactorResetService`.

#### Security

- Closes both gaps found when verifying ADR-024's recovery procedure.

### Project owner's decisions D5, D6, D14 and ADR-024; recovery procedure verified

Records: [`docs/permission-matrix-review.md`](docs/permission-matrix-review.md) (decisions),
[ADR-027](docs/adr/027-office-context-on-audit-events.md),
[ADR-024](docs/adr/024-no-totp-fallback.md) (accepted),
[account recovery procedure](docs/technical/08-operations-and-troubleshooting.md#account-recovery-procedure).

#### Added

- **ADR-027 (D6):** audit events get an office with the case module, taken from the record
  at the time of the event and hashed; never assigned retrospectively from a user's current
  office; until then office-scoped audit reading stays closed (`AuditReadingScopeTest`).
- **Migration V9 (D5):** one checkpoint per chain position. pgTAP test `11_checkpoints`.
- **The account recovery procedure** (chapter 8): one key lost, both keys lost, everything
  lost; what the system enforces and what remains procedure; the audit query; two gaps to
  close before go-live.
- **End-to-end:** the auditor signs a checkpoint; recovery with a single-use code (reuse
  refused) and a new key; a supervised reset by another administrator (self-reset refused),
  old codes dead, enrolment again, `MFA_RESET` found by the auditor.
- `FileCheckpointArchiveTest`.

#### Changed

- ADR-024 (no TOTP fallback) is **accepted**, with its go-live conditions.
- The permission matrix records D5 (auditor checkpoints on demand), D6 (office-scoped audit
  reading only after ADR-027) and D14 (no parcel reading for finance and administration).

#### Security

- **A checkpoint file can no longer be replaced by a racing writer.** It was published with
  an atomic rename after an existence check; on POSIX a rename replaces the target, so two
  writers could overwrite one another. It is now published with a hard link, which fails
  atomically if the name exists, and the temporary file is always removed.
- Recovery verification found that **lost keys stay registered after a recovery-code
  sign-in** (until a reset) and that **the identity confirmation of a reset is free text**;
  both are documented with proposed fixes, not yet implemented.

### Frontend structure and the SNIGF logo

Manual: [chapter 10, frontend architecture](docs/technical/10-frontend-architecture.md);
map and debugging table: [`frontend/src/README.md`](frontend/src/README.md);
logo files: [`frontend/BRAND.md`](frontend/BRAND.md).

#### Added

- **The SNIGF logo:** the emblem in the header, the full logo above the sign-in and
  registration forms, favicons and a home-screen icon. Derived from the official
  `public/SNIGF Integrated Land Governance Logo.png` (kept unchanged): 27 KB instead of
  823 KB on the sign-in page. End-to-end test `branding` checks that each image loads.
- **Structure check** `frontend/scripts/check-architecture.mjs`, run by `npm run lint`:
  layer rules, features imported only through their `index.ts`, every feature with `api.ts`
  and `index.ts`, every file signed.

#### Changed

- **The frontend is organised by layer and feature** (`app/`, `features/{auth,accounts,
  roles,grants,audit}/`, `shared/{api,i18n,session,ui}/`, `styles/`), as the back end is by
  module. The 360-line `App.tsx` became a shell plus one file per screen; the single
  `api.ts` became one `api.ts` per feature on a shared client; the stylesheet is split into
  tokens, base, layout and components. Behaviour is unchanged (end-to-end 9/9).
- **Texts:** `shared/i18n/fr.ts` is the reference and `en.ts` is type-checked against it, so
  a missing English text fails the build (before, a cast hid it).
- The audit panel's checkpoint button reads "Show checkpoints" (it read "Search", like the
  event search).

### Security foundation, step 4e: four eyes on every assignment, enforced by the database; end-to-end tests

Decision: [ADR-026](docs/adr/026-approval-workflow-enforced-by-the-database.md).
Guides: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md),
[`docs/guides/end-to-end-testing.md`](docs/guides/end-to-end-testing.md).
Review: [`docs/permission-matrix-review.md`](docs/permission-matrix-review.md).

#### Added

- **Four eyes on every role assignment:** a role is requested by one administrator and
  approved or rejected by another (`/api/v1/admin/assignment-requests`); neither the
  requester nor the beneficiary approves; approval creates the assignment in the same
  statement. New permissions `admin.assignment.read` and `admin.assignment.approve`
  (SYSTEM_ADMIN). Audit actions `ROLE_ASSIGNMENT_REQUESTED`, `ROLE_ASSIGNMENT_REJECTED`.
- **Migration V8, the approval workflow enforced by the database:** the application's
  database account can no longer assign a role without an approved request, insert a request
  or grant already approved, approve without holding the approval permission, approve for
  itself, write role definitions (approved changes are applied by the database), move a
  grant outside its allowed transitions, or move uses by more than one. Requests are timed
  by the database and expire after 30 days at most. The superuser remains the bootstrap path.
- **Supervisors verify the audit chain** (`audit.chain.verify`): read-only, no event content.
- **Actions held only through a special grant** are shown with their uses left, never run on
  their own, and asked for confirmation; viewing or cancelling spends nothing
  (`frontend/src/shared/session/access.ts`). The audit panel offers the public keys (`audit.key.read`).
- **End-to-end tests in Chromium** (Compose service `e2e`, profile `e2e`,
  `infra/scripts/e2e.sh`): sign-in with virtual security keys, four-eyes role assignment and
  custom role across two administrators, a special grant spent only by the confirmed
  action, audit verification by auditor and supervisor. Settings `SNIGF_E2E_MEMORY`,
  `SNIGF_E2E_CPUS`, `SNIGF_E2E_PIDS`, `SNIGF_E2E_SHM`.
- **Permission matrix review** against the Baseline: 17 differences with security and
  business implications, three decisions requested.
- pgTAP test `10_approval_workflow` (30 checks).

#### Changed

- `POST /api/v1/admin/users/{id}/roles` answers **202** with a `requestId` (was 201 with an
  assignment): the role exists only once approved.
- `admin.role.assign` now means "request a role assignment".
- A database refusal the service did not foresee is answered 409 `REFUSED_BY_DATABASE` and
  logged as a warning.
- Tests seed roles as the superuser, the only path without a request
  (`IdentityIntegrationTest.insertStaff`, pgTAP `02`, `09`).
- The audit panel stays open for the visit once shown, so the result of a grant's last use
  remains visible.

#### Security

- Closes the gaps found in the 4d review: a single administrator could assign any role, and
  the application's database account could bypass every approval. Remaining: a compromised
  application can name another real administrator as approver (signed approvals, planned).

### Security foundation, step 4d: fine-grained permissions, administrator-defined roles, special grants

Decision: [ADR-025](docs/adr/025-fine-grained-permissions-and-special-grants.md).
Guide: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md).
Matrix: [`docs/permission-matrix.md`](docs/permission-matrix.md).

#### Added

- **Permission catalogue** (`Permission`, 33 permissions): one per function, each with a
  family (administration, audit, business), whether it may be delegated, the scopes it
  allows and descriptions in English and French. Seeded by V7 into `permission`;
  `PermissionCatalogueCheck` refuses to start when the code and the database differ.
- **Roles in the database:** the nine baseline roles (`BaselineRole`) are seeded as
  immutable system roles; administrators create, change and retire custom roles from the
  catalogue (`/api/v1/admin/roles`, `/api/v1/admin/role-requests`, `/api/v1/admin/permissions`).
- **Special grants** (`/api/v1/admin/grants`): one permission to one staff member,
  everywhere or at one office, with an optional end date and/or maximum number of uses.
  Each use is taken atomically before the action, given back if it fails, and audited.
  The session view lists the caller's `specialGrants`.
- **Four eyes:** every role change and special grant is a request that a second
  administrator approves; the requester, the beneficiary and holders of the role changed
  cannot approve. Requests expire after `SNIGF_ADMIN_REQUEST_TTL` (new setting, 7 days).
- **Separation of duties across families:** no role and no person holds administration
  and audit permissions together; administration permissions are never delegable.
- **Audit actions:** `ROLE_DEFINITION_REQUESTED`, `ROLE_DEFINITION_APPROVED`,
  `ROLE_DEFINITION_REJECTED`, `PERMISSION_GRANT_REQUESTED`, `PERMISSION_GRANT_APPROVED`,
  `PERMISSION_GRANT_REJECTED`, `PERMISSION_GRANT_REVOKED`, `PERMISSION_GRANT_USED`.
- **Migration V7** with its constraints and triggers; pgTAP test `09_authorization`.
- **Tests:** `RoleSeedIT`, `RoleAdministrationIT`, `SpecialGrantIT` (including concurrent
  uses), `PermissionCatalogueCheckTest`; `EndpointAnnotationArchTest` now fails on any
  permission check that is not `@access.can`/`canAt` or names an unknown permission.
- **Screens:** roles and permission catalogue, role requests and approvals, special grants
  (request, approve, reject, revoke); the user's own special grants on the session page.

#### Changed

- Every endpoint is now protected by a fine-grained permission through
  `@access.can(...)` / `@access.canAt(...)`: `user.manage` became `admin.account.list` and
  `admin.mfa.reset`; `role.assign` became `admin.role.assign` and `admin.role.revoke`;
  `audit.read` and `audit.verify` became `audit.event.search`, `audit.chain.verify`,
  `audit.checkpoint.create`, `audit.checkpoint.list`, `audit.checkpoint.download` and
  `audit.key.read`.
- `role_assignment.role` is a foreign key to `role_definition` instead of a fixed list;
  a retired role cannot be assigned. Assigning a role that would mix administration and
  audit is refused (`SOD_CONFLICT`).
- The SUPERVISOR baseline role keeps office-scoped audit search only (no chain
  verification); SYSTEM_ADMIN holds the eleven administration permissions.
- `Role` and `PermissionGrants` were replaced by `BaselineRole`, `RoleDefinition`,
  `EffectivePermissions` and `AuthorizationService`.

### Security foundation, step 4c: permissions, role administration, second-factor reset, auditor API

Guide: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md).
Matrix: [`docs/permission-matrix.md`](docs/permission-matrix.md).

#### Added

- **Permission model** (Slice 1 design §6):
  - the `Permission` enum (20 permissions) and the Baseline's role matrix in code
    (`Role`), with scopes `any`, `office`, `assigned`, `own` and `linked`;
  - grants become authorities, `audit.read` held everywhere or `parcel.create@DEMO1` at
    one office, and only in a FULL session;
  - `@access.allows(authentication, permission, officeCode)` for office-scoped checks;
  - the principal carries its role grants with offices, and the session view lists
    `grants` and `permissions`.
- **`docs/permission-matrix.md`:** `PermissionMatrixTest` fails when it differs from the
  code, and also checks that no role both administers and audits.
- **Role administration** (`/api/v1/admin/users`):
  - an account directory, with search that treats `%` as text;
  - grant and revoke a role at an office, always with a written reason;
  - back-office roles go to staff accounts only;
  - separation of duties: no self grant or revoke (refused by the service and audited as
    DENIED, refused again by the database);
  - changes take effect at the next request.
- **Administrator-assisted second-factor reset:**
  - it needs a reason and the name of whoever confirmed the identity out of band, and is
    refused on one's own account;
  - it removes every key, invalidates the recovery codes, ends every session (Spring
    Session principal index) and forces re-enrolment;
  - it is audited as `MFA_RESET`.
- **Auditor's API** (`/api/v1/audit`, AUDITOR role):
  - search events by entity, actor, action and time, with paging;
  - verify a range or the whole chain;
  - sign a checkpoint (409 `AUDIT_CHAIN_BROKEN` when the chain does not verify);
  - list checkpoints, download the files byte for byte, read the public keys;
  - searches and downloads are audited as `AUDIT_EVENTS_READ`.
- **Screens:** administration (search, grant, revoke, reset) and audit (search, verify,
  checkpoints, downloads), shown by permission, in French and English.
- **Tests:**
  - `PermissionMatrixIT`: every staff role against every protected endpoint, and
    applicants against none;
  - `AdminFlowIT` (7) and `AuditApiIT` (5);
  - `PermissionMatrixTest` (3).
- **Documents:**
  - ADR-024 (proposed): no TOTP fallback. Recovery uses the second key, the recovery
    codes, or a supervised administrator reset.
  - The roles and administration guide.

#### Changed

- Integration tests share one application context (`PostgisIntegrationTest`).
- `AuthenticatedUser` carries `grants` (role and office) instead of role names;
  `roles()` remains.
- ADR-016's TOTP fallback is replaced by ADR-024 (proposed).

#### Fixed (found by the new tests before release)

- **Integration tests in different Spring contexts disagreed on checkpoint signatures.**
  Each context generated its own in-memory test keys, so they could not verify each
  other's checkpoints (a test-only artefact, since production has one OpenBao key).

### Security foundation, step 4b: WebAuthn second factor, recovery codes, sign-in screens (ADR-016, ADR-023)

Guide: [`docs/guides/authentication-and-sessions.md`](docs/guides/authentication-and-sessions.md).

#### Added

- **WebAuthn second factor for staff,** on Spring Security 7.1's relying-party operations
  (`spring-security-webauthn`, verification by webauthn4j).
  - **Per ceremony:** user verification required, non-discoverable credentials,
    attestation `none` by default, exact origins on the relying-party id.
  - **At login:** only the account's own keys are offered, and the credential's owner
    must be the account proving it.
  - **Ceremony state:** kept on the server (`CeremonyStore`), single use, bound to
    account, kind and timeout; the session holds only a random id (ADR-022).
- **Enrolment:**
  - a staff password session registers `SNIGF_WEBAUTHN_MIN_AUTHENTICATORS` (2) keys;
  - enrolment then completes with ten recovery codes shown once, and the session
    becomes FULL with a new id;
  - once enrolled, keys can be added only from a FULL session.
- **Recovery codes:**
  - 16 Crockford base32 characters (80 bits), single use, forgiving input;
  - stored as domain-separated HMACs with the OpenBao pepper key, the key version
    recorded;
  - after use, the interface asks for a replacement key.
- **Failures:** wrong assertions and recovery codes count toward the lockout, are audited
  (`LOGIN_FAILED` with reason, `ACCOUNT_LOCKED`), and end the session when the account
  locks. Successes are audited as `MFA_ENROLLED`, `MFA_VERIFIED` and
  `MFA_RECOVERY_CODE_USED`.
- **Endpoints** `/api/v1/auth/mfa/webauthn/registration[/options]`,
  `/webauthn/assertion[/options]` and `/recovery`. Error codes: 401
  `SECOND_FACTOR_INVALID`, 403 `MFA_NOT_ALLOWED`, 422 `WEBAUTHN_REJECTED`.
  `GET /auth/session` now returns `nextStep`.
- **Migration `V6__webauthn.sql`:**
  - `user_entities` (one per account, a foreign key through a generated column) and
    `user_credentials` (public keys, labelled);
  - the trigger `app_user_mfa_enrolment_guard`, under which an account is enrolled only
    with a credential or a TOTP secret.
- **Sign-in screens** (React, French and English, no new dependency):
  - sign in, registration, key enrolment with progress, second factor, recovery code;
  - the recovery codes shown once, with an acknowledgement;
  - the session view, adding a key, and sign-out;
  - WebAuthn JSON conversion in `webauthn.ts`, and the CSRF token handled by `api.ts`.
- **Dev server:** the Vite proxy now passes the browser's address like Caddy.
- **`infra/scripts/dev-make-staff.sh`** (development profile only, audited) turns a
  registered account into staff at a demo office until role administration exists.
- **Settings:** 7 new settings (`SNIGF_WEBAUTHN_*`, `SNIGF_MFA_RECOVERY_CODES`), checked at
  startup:
  - origins are scheme://host[:port] on the relying-party id;
  - https outside tests;
  - at least 2 authenticators outside tests.
- **Tests:**
  - `MfaFlowIT`: 10 end-to-end flows with webauthn4j authenticator emulators (genuine
    attestations and signatures), covering phishing origin, replay, another account's
    key, recovery, lockout and ceremony binding;
  - `CeremonyStoreTest`, `RecoveryCodeTest`, settings and hasher tests;
  - pgTAP test `08_webauthn`.
- **Documents:** ADR-023.

#### Changed

- The V1 check `ck_mfa_enrolled_has_secret` (TOTP-only) is replaced by the V6 trigger.
- The identity integration tests share `IdentityIntegrationTest` (test browser with
  cookies and CSRF).
- ADR-016 is marked implemented by ADR-023. The TOTP fallback is deferred to step 4c;
  recovery codes are the fallback for now.

#### Fixed (found by the new tests before release)

- Starting a registration ran in a read-only transaction, but it creates the WebAuthn
  user entity.

### Security foundation, step 4a: password login, sessions, CSRF, throttling (ADR-004, ADR-022)

Guide: [`docs/guides/authentication-and-sessions.md`](docs/guides/authentication-and-sessions.md).

#### Added

- **Identity module** (`org.snigf.mvp.identity`) in DDD layers.
  - **Published API:** `AuthenticatedUser`, the principal of every request (implements
    `AuditedPrincipal`), plus `AccountType` and `AuthLevel`.
  - **Domain:** e-mail address, password policy P7, lockout policy P6, account, and the
    repository and hasher ports.
  - **Application:** registration and login use cases, every attempt audited.
- **Endpoints** `/api/v1/auth/csrf`, `/register`, `/login`, `/session` and `/logout`.
  - **No account discovery:** every login failure answers 401 `AUTH_FAILED` after the
    same hashing work; registration answers 201 whether or not the address existed.
  - **Error codes:** 422 `PASSWORD_WEAK` or `VALIDATION_FAILED`, 429 `RATE_LIMITED`,
    503 `BUSY`.
- **Password hashing:**
  - HMAC with the OpenBao `password-pepper` key, then Argon2id at the configured cost
    (OWASP minimum enforced outside tests);
  - the pepper version is recorded, and hashes are upgraded at login;
  - concurrent hashing is bounded.
- **Common-password list:** 29,982 passwords of 12+ characters from SecLists, pinned by
  SHA-256, with a provenance notice.
- **Sessions in PostgreSQL** (Spring Session JDBC, migration `V5__sessions.sql`).
  - **What is stored:** only the user id, level and login time; the principal is rebuilt
    from the database on every request, so disabled accounts and revoked roles take
    effect at once.
  - **Deserialization:** limited to plain values (filter plus size cap); a tampered
    session is ended.
  - **Lifetime:** new session id at login; idle 30 min and absolute 8 h limits.
  - **Cookie:** `__Host-snigf-session`, Secure, HttpOnly, SameSite Strict.
- **CSRF** on every state-changing call, including login: a `__Host-snigf-xsrf` cookie
  and the `X-XSRF-TOKEN` header.
- **Lockout and throttling:**
  - 5 wrong passwords lock an account for 15 min (atomic counter);
  - 20 logins per minute and 10 registrations per hour per client address (Bucket4j);
  - the client address is taken from the proxy only.
- **Audited denials:** refusals of authenticated users are recorded as `ACCESS_DENIED`.
  The audit trail now receives `USER_REGISTERED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`,
  `ACCOUNT_LOCKED` and `LOGOUT`.
- **Staff** stop at authentication level `PASSWORD` until the second factor (step 4b).
- **Code changes:**
  - `@PublicEndpoint`, and `EndpointAnnotationArchTest`, which fails the build for an
    endpoint without an access rule;
  - `KeyService.mac(key, message, version)` for recomputing a MAC with a recorded key
    version;
  - a shared `Clock` bean.
- **Settings:** 19 new settings (`SNIGF_PASSWORD_*`, `SNIGF_LOGIN_*`, `SNIGF_RATE_*`,
  `SNIGF_SESSION_*`, `SNIGF_CSRF_*`).
- **Probe:** 3 new checks (CSRF cookie attributes, login CSRF, uniform 401). pgTAP test
  `07_sessions`.
- **Tests:** 32 new backend tests, of which 13 are end-to-end flows in `AuthFlowIT`.
- **Documents:** ADR-022 and the authentication guide.

#### Changed

- Anonymous calls to protected endpoints answer 401 `AUTH_REQUIRED` instead of 403. The
  smoke test, the probe and CI were updated.
- The security filter chain moved from `shared.security.SecurityConfig` to the identity
  module (`identity.infrastructure.security.SecurityConfiguration`).
- ADR-004 is marked implemented and refined by ADR-022 (`SameSite` Strict by default,
  `__Host-` cookies).

#### Fixed (found by the new tests before release)

- **Session cookie unprotected.** Spring Boot 4 no longer applies
  `server.servlet.session.cookie.*` to Spring Session, so the cookie would have been a
  plain `SESSION` without Secure, HttpOnly or SameSite. It is now defined in code.
- **Size limit bypass.** The deserialization filter's size limit does not apply to plain
  strings, so values above 4 KB are now refused explicitly.
- **Tampered sessions.** A refused session value now ends the session (401) instead of
  failing every request.

### Technical manual

#### Added

- `docs/technical/`, the entry point for engineers. It has nine chapters:
  1. system overview;
  2. backend architecture (modules, DDD layers, startup and request sequences, health);
  3. security model;
  4. data model;
  5. configuration reference (every setting, variable, property and secret);
  6. how to rebuild the architecture in a new project;
  7. testing;
  8. operations and troubleshooting (error catalogue, audit incident procedure,
     diagnostic commands);
  9. documentation rules.
- `DocumentationSyncTest` fails the build when the manual is missing any of the
  following, and names each missing item:
  - a setting, secret or migration;
  - a module, Compose service or health contributor;
  - an audit action;
  - an ADR or guide.

#### Fixed

- CI's stack smoke test sourced `.env` as shell code, which would fail on the audit
  schedule values. It now reads the two values it needs as data.

### Security foundation, step 3: audit trail with signed checkpoints (ADR-007, ADR-021)

Guide: [`docs/guides/audit-trail.md`](docs/guides/audit-trail.md).

#### Added

- Audit module (`org.snigf.mvp.audit`) packaged in DDD layers:
  - **published API:** `AuditRecorder`, `AuditTrail`, `AuditEvent`, `AuditAction`,
    `Checkpoint`, `ChainVerification`, `SystemActor`, `AuditedPrincipal`;
  - **`domain`:** chain, verifier, evidence and repository interfaces, in plain Java;
  - **`application`:** use cases and ports;
  - **`infrastructure`:** JDBC, file archive, security context, scheduling, health.
- Recording:
  - **Transactions:** events are written inside the caller's READ COMMITTED transaction.
    Refused without one, in a read-only one, or under a stricter isolation. Denials and
    failures are recorded in their own transaction, so they survive the rollback.
  - **Who and where:** the actor, roles, correlation id and browser address come from the
    security context and the request, never from the caller. An unattributable
    authenticated principal is refused.
  - **Details:** limited to plain values and to `SNIGF_AUDIT_MAX_DETAILS_BYTES`.
- Java chain verifier, independent of the database's functions. It reports the first
  problem with its position and kind:
  - `GAP`, `PREV_HASH`, `CONTENT_HASH`: an event deleted, unlinked or edited;
  - `CHECKPOINT_MISMATCH`: the chain was rewritten after a checkpoint;
  - `TRUNCATED`: the newest events were deleted;
  - `CHECKPOINT_SIGNATURE`: a checkpoint is forged, or its copies disagree;
  - `CHECKPOINT_UNREADABLE`: a checkpoint file cannot be read.
- Checkpoints:
  - Ed25519, signed by OpenBao with the `audit-checkpoint` key.
  - Stored in `audit_checkpoint` and as read-only files on the new volume
    `audit-checkpoints`, mounted into the application only, with `checkpoint-keys.json`
    holding the public keys.
  - The events since the last trusted checkpoint are verified first. A tampered chain is
    never signed; the refusal is audited and reported.
- Schedules:
  - Daily checkpoint and daily full verification, plus a verification at startup. All
    settings are in `.env`.
  - Each run is audited (`AUDIT_VERIFIED`, `AUDIT_CHECKPOINT_CREATED`) and reported by the
    new health contributor `auditChain`. It is outside readiness, so monitoring alerts
    without taking the service down.
- `infra/scripts/verify-checkpoint.sh`: the auditor verifies checkpoint files offline with
  bash and OpenSSL only.
- Migration `V4__function_search_path.sql` (see Fixed).
- Browser address for audit records:
  - the proxy overwrites `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` with the TCP peer;
  - the app believes the header only on the proxy's admitted certificate;
  - non-IP values are discarded.
- `UuidV7` generator (ADR-008).
- 8 new settings: `SNIGF_AUDIT_*` (checkpoint directory, two schedules, zone, startup
  verification, details limit, fetch size) and `SNIGF_PROXY_CLIENT_ADDRESS_HEADER`.
- ADR-021 (domain-driven layering inside modules) and `DddLayeringTest`, which enforces it
  on every build.
- Tests:
  - **Unit (37):** 17 for the chain verifier, plus domain rules, layering, client
    address, UUIDv7, audit settings and the new guard rule.
  - **Integration (20, against PostGIS):**
    - `AuditRecorderIT` (11): transactions, actors, immutability;
    - `CanonicalHashParityIT` (2): Java and SQL produce identical bytes;
    - `AuditChainIT` (7): signing, offline verification, and every superuser tampering
      scenario.
  - **pgTAP:** test `06_function_hardening`.

#### Changed

- `EnvironmentGuard` also requires the proxy's client-address header outside tests; the
  audit module refuses disabled schedules outside tests.
- ADR-007 records the implementation; `docs/audit.md` documents the checkpoint format, the
  auditor's routine and two residual-risk corrections.
- The test support (`PostgisDatabase`) offers a superuser connection for tampering tests.

#### Fixed

- **Database functions used the caller's `search_path`** (latent since V1). Audit
  verification failed for a session without `snigf` on its path, and a caller could
  shadow a table, even with a temporary one. V4 pins
  `pg_catalog, snigf, public, pg_temp` on every function, and pgTAP checks it.
- **Shell scripts sourced `.env` as code.** A value with spaces broke `dev-setup.sh`, and
  any line could have run a command. `dev-pki.sh` now reads `SNIGF_*` settings as data.

### Security foundation, step 2: key custody in OpenBao (ADR-018)

#### Added

- OpenBao 2.7.1 in the stack (`openbao` service), pinned by digest, on a new internal
  network `key-net` that only the application and the provisioning job join. API on
  47820 and Raft cluster on 47821, mutual TLS 1.3 only; audit log to the container log
  and to the `bao-audit` volume (`infra/openbao/`).
- `bao-init` provisioning job: initialises OpenBao (Shamir, 5 shares, threshold 3 by
  default), unseals it after every restart, and when its inputs change provisions the
  transit keys, the application policy and the certificate login, using a temporary root
  token generated from a quorum of unseal shares and revoked before it exits. No root or
  administrator token is stored anywhere.
- Six transit keys, one per purpose, none exportable, deletable or backed up in
  plaintext: audit checkpoint and record signing (Ed25519), data-key wrapping
  (AES-256-GCM, derived per context), payment callback, password pepper and blind index
  (HMAC-SHA-256). Automatic rotation periods are settings.
- Application policy limited to sign, read public keys, generate and unwrap data keys,
  HMAC-SHA-256 and token self-lookup (`infra/openbao/app-policy.hcl.template`).
- Backend module `keys` (`org.snigf.mvp.keys`): one `KeyService` interface for signing,
  local signature verification, envelope encryption bound to a context, and HMAC; value
  types `Signature`, `Mac`, `SealedData`, `SigningPublicKey`. OpenBao provider with
  certificate login and automatic token renewal; in-memory provider for automated tests only.
- Startup self-test of key custody: every key's type and custody settings are checked
  and every operation is round-tripped (and must reject tampering) before the application
  accepts requests.
- Health contributor `keys`; readiness now includes the database and key custody.
- Development certificates for OpenBao (`bao_tls`), the application's login identity
  (`app_bao_client`), the provisioning job (`bao_admin_client`) and the health check
  (`bao_health_client`).
- 28 new settings in `.env.example` (`SNIGF_BAO_*`, `SNIGF_KEY_*`, OpenBao resource limits).
- Security probe: 8 new checks on OpenBao (TLS 1.2 refused, no certificate refused,
  foreign CA refused, another service's certificate refused with its reason, keys not
  exportable or deletable, 14 operations outside the policy refused, positive controls).
  Segmentation probe: the proxy's network cannot reach OpenBao.
- Unit tests for the key module (14) and the new guard rules (4).
- `CHANGELOG.md` and the how-to guides in `docs/guides/`.

#### Changed

- `EnvironmentGuard` also refuses: in-memory keys outside tests, an OpenBao URL without
  https, an OpenBao SSL bundle not restricted to TLS 1.3, one key name for two purposes,
  and (outside tests) a missing database password.
- `dev-setup.sh` appends settings added to `.env.example` to an existing `.env`, without
  changing existing values.
- `dev-pki.sh` restricts private-key files to the current user on Windows (ACL).
- ADR-018 records the implementation: certificate login instead of AppRole, local
  verification, Shamir custody, no `mlock` in OpenBao 2.x.
- `.gitattributes` keeps LF for `*.hcl`, `*.template` and `*.yaml`.

#### Fixed

- The application container could not open a database connection: Docker Desktop mounts
  secrets with mode 0777 and the PostgreSQL driver refuses such a private key. Hidden
  until readiness included the database. The entry point now copies the key to a private
  tmpfs with mode 0600 (`backend/docker-entrypoint.sh`).
- Running the backend from the IDE with `SNIGF_SECRETS_DIR=../infra/secrets` silently
  loaded no secrets (Spring's config tree reads nothing from a path containing `..`).
  The documented command uses an absolute path, and the guard now stops startup with a
  clear message.
- ADR-017 named a property that does not exist (`allowed-client-subjects`).

### Security foundation, step 1: transport security and hardening (ADR-016 to ADR-020)

#### Added

- ADR-016 (WebAuthn for staff, TOTP fallback; supersedes ADR-005), ADR-017 (TLS on every
  hop), ADR-018 (OpenBao key custody), ADR-019 (officer-signed records), ADR-020
  (container and network hardening).
- Development PKI (`infra/scripts/dev-pki.sh`): root and intermediate CA (P-384), service
  certificates (P-256), renewal before expiry, `--rotate`.
- TLS 1.3 on every hop: browser → proxy (HSTS, no plain-HTTP listener), proxy → app
  (mutual TLS, client allow-list in `ClientCertificateFilter`), app and migrations →
  database (`verify-full`, client certificate whose CN equals the role, SCRAM).
- `pg_hba.conf`: superuser only through the local socket; every network login over TLS
  with a client certificate.
- Networks `edge`, `app-net`, `data-net` (internal); containers read-only, all
  capabilities dropped, `no-new-privileges`, non-root, resource limits from `.env`.
- Security probe (16 checks) and segmentation probe (3 checks), each refusal checked for
  its reason; both run in CI.
- HTTPS Vite development server that calls the backend over mutual TLS.

#### Changed

- Every tunable value is a setting in `.env` (ADR-014 extended); Compose refuses to start
  when one is missing.
- `EnvironmentGuard` refuses any environment except test without complete transport
  security.

#### Fixed

- The backend accepted TLS 1.2: with an SSL bundle, `server.ssl.enabled-protocols` is
  ignored. The protocol list moved to the bundle options; the guard checks that setting.
- Password files generated on Windows ended with `\r`; `dev-setup.sh` normalises them.
- Caddy could not start with all capabilities dropped; the image removes the binary's
  file capability (ports above 1024 need none).

## [0.1.0-SNAPSHOT] - 2026-10-20 (commit `d77998a`, "Project Initialisation")

### Added

- Monorepo: `backend/` (Spring Boot 4.1, Java 25, Spring Modulith), `frontend/` (React 19,
  TypeScript, Vite), `infra/`, `docs/`.
- Database layer: roles `snigf_migrator`, `snigf_app`, `snigf_auditor`; migrations V1–V3
  (identity, parcels, geometry normalisation, hash-chained audit); pgTAP suite and a
  concurrent audit-chain test.
- Compose stack with images pinned by digest, non-default ports (47xxx), settings in
  `.env`, secrets as files.
- Environment profiles dev, test, staging, prod and `EnvironmentGuard` (E-23).
- CI: backend, frontend and whole-stack jobs with a secret scan.
- ADR-001 to ADR-015.
