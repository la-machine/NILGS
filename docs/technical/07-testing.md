# 7. Testing

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Suites

| Suite | Command | Needs | Proves |
| --- | --- | --- | --- |
| Backend unit (`*Test`) | `cd backend && ./mvnw test` | JDK 25 | domain rules, guards, encodings, layering, module boundaries |
| Backend integration (`*IT`) | `cd backend && ./mvnw verify` | JDK 25 + Docker | the application against real PostGIS, as `snigf_app` |
| Database (pgTAP) | `docker compose --profile test run --rm --build dbtest` | Docker | grants, constraints, triggers, geometry, audit chain, function hardening, roles and grants (09), the approval workflow (10), checkpoints (11), supervised reset and key removal (12), overlap and duplicate detection on the Baseline's fixture set (13), resolution by special grant and the note bound (14), applications under row-level security (15), documents (16), the completeness review (17), review and simulated decision (18), tracking and workload (19), simulated payment and reconciliation (20), audit subjects (21); concurrent chain test with pgbench |
| End-to-end (Chromium) | `bash infra/scripts/e2e.sh` | running development stack | sign-in with security keys, four-eyes role assignment and custom role, special grant spent only by the confirmed action, audit verification by auditor and supervisor, recovery, parcels and the three map views (only our own origin contacted, no CSP violation) ([guide](../guides/end-to-end-testing.md)) |
| Security probe | `docker compose --profile test run --rm --build security-probe` | running stack | every transport and key-custody guarantee, each refusal for its reason |
| Segmentation probe | `docker compose --profile test run --rm segmentation-probe` | running stack | the proxy's network reaches neither the database, OpenBao nor the internet |
| Frontend | `cd frontend && npm run lint && npm test && npm run build` | Node 24 | lint (oxlint), structure rules and signatures (`scripts/check-architecture.mjs`); unit and component tests in jsdom (Vitest, 82: technical-view geometry and drawing, measurement, the three map views and their unavailable and offline states, GeoJSON reading, the recording form sending exactly what was typed and showing refusals at the field, dialogs, addresses, permission helpers, navigation by session, every input pattern, audit actions in step with the server, a parcel's alerts and their history, the alert review: map, the author told why not, outcome and note sent with the version read, a stale alert reloaded, who may resolve, an application draft saved with its version, created once, submitted after confirmation, missing items shown in the summary and at their field, the documents of an application: latest file as a download, earlier versions folded, a file refused before sending, uploaded with a key, a server refusal in words, no upload once submitted, a case: what is missing in words, acceptance refused until complete, the missing documents ticked for a request with its message, no decision for readers only, the applicant's response with its message, the simulated decision recorded with grounds and a citation after confirmation, refused without a citation, blocked by an open alert, the recommender told why not, a reviewer's claim, the notice on every recorded decision, where an application stands for its applicant and whose turn it is, a party named in the applicant's history and a person in the staff's, the workload's stages against their targets, the overdue cases flagged and opened only for readers of the office's cases, durations in words, the DEMO payment said to move no real money, paid once with a key, offered again after a failure, waited for while pending, the acceptance held until it is paid, the finance officer's exceptions resolved with a note after confirmation and the requests by status, the audit timeline in order and in words with who acted and the office, an altered event shown, the office-only notice, nothing for an unreadable record); type-check (English texts against French), build |
| Map tools | `bash infra/scripts/maps-synthetic-imagery.sh` | Docker | the import pipeline end to end: GeoTIFF → raster PMTiles → manifest → installed ([guide](../guides/map-data.md)) |
| Offline checkpoint | `bash infra/scripts/verify-checkpoint.sh keys.json checkpoint-*.json` | bash, OpenSSL 3 | checkpoint files are authentic |

On Windows, set `JAVA_HOME` to a JDK 25 before running Maven. The enforcer rule refuses
older JDKs with "Detected JDK ... is version 17 ... not in the allowed range [25,)".

## Backend tests by area

| Area | Tests |
| --- | --- |
| Architecture | `ModularityTest` (module boundaries), `DddLayeringTest` (layer rules), `ComposeImageParityTest` (test image = Compose image), `DocumentationSyncTest` (the manual lists every setting, secret, migration, module, service, health contributor, audit action, ADR and guide) |
| Configuration | `ProdStartupGuardTest` (environment guard), `AuditConfigurationTest`, `IdentityInfrastructureTest` (identity settings) |
| Web | `CorrelationIdFilterTest`, `ClientCertificateFilterTest`, `ClientAddressTest` |
| Keys | `EphemeralKeyServiceTest`, `OpenBaoKeyServiceTest` (OpenBao HTTP contract with a mock server) |
| Audit domain | `ChainVerifierTest` (every tampering kind against an in-memory chain), `AuditDomainRulesTest` |
| Audit rules (D5, D6) | `FileCheckpointArchiveTest` (a checkpoint file is never replaced, even by racing writers), `AuditReadingScopeTest` (no office-scoped audit reading before ADR-027) |
| Audit integration | `AuditRecorderIT` (transactions, actors, immutability), `CanonicalHashParityIT` (Java and SQL produce identical bytes), `AuditChainIT` (checkpoints, offline verification, superuser tampering) |
| Identity | `IdentityRulesTest` (password policy, e-mail, lockout), `IdentityInfrastructureTest` (peppered Argon2id, plain-value sessions, pinned password list), `SessionSecurityContextRepositoryTest` (absolute limit, authorities), `AuthFlowIT` (13 end-to-end scenarios), `MfaFlowIT` (10 WebAuthn scenarios with webauthn4j authenticator emulators: enrolment, login, phishing origin, replay, another account's key, recovery, lockout), `RecoveryCodeTest`, `CeremonyStoreTest`, `EndpointAnnotationArchTest` |
| Roles and administration | `PermissionMatrixTest` (document = code, catalogue rules, scopes, no combined role, custom role validation), `PermissionCatalogueCheckTest`, `RoleSeedIT` (V7 seeded exactly the code's catalogue and baseline roles), `PermissionMatrixIT` (every staff role × every protected endpoint at its reach, own, office or assigned; applicants none), `AdminFlowIT` (four-eyes role requests, revocations, SoD, what the application's database account can no longer do, second-factor reset), `RoleAdministrationIT` (four eyes, self-benefit, immutable baseline, retirement, SoD, the new role takes effect), `SpecialGrantIT` (four eyes, beneficiary, end date, use limit, concurrent uses, refund on failure, audit of uses, revocation, administration not delegable), `SecurityKeysIT` (list and remove one's own keys, a removed key no longer signs in, the last key stays), `SecondFactorResetIT` (request, supervisor confirmation, three people, other offices, expiry, what the application's account cannot do), `AuditApiIT` (search audited, checkpoint verifiable offline, broken chain refused, auditors only) |
| Parcels | `ParcelDomainTest` (reference check character, rules, display zone), `ParcelApiIT` (creation with idempotency and replay, every geometry refusal audited, correction and stale version, versions, search, permissions by office) |
| Conflicts | `ConflictPolicyTest` (thresholds within the database's bounds), `ConflictDetectionIT` (overlap in the answer, audited and listed for the parcel's reviewers only, edge and corner contact raise nothing, copies and rewritten references are duplicates, corrections supersede and are checked afresh, thresholds from the settings), `ConflictAlertTest` (author, offices, note bounds), `AlertResolutionSoDIT` (office list, review map in one zone, the author refused and audited, note and stale version, a colleague resolves once, other offices and roles refused, an alert of two offices resolved from either, the database refuses the author when the service is bypassed) |
| Applications | `ApplicationDomainTest` (references and their check character, legal moves, a draft may be incomplete never wrong, a submission names every missing item, a definition is checked), `ApplicationIT` (draft without reference, incomplete submission lists every item and stays a draft, a parcel reference with a wrong check character, idempotent submission with one reference, frozen once submitted, IDOR: another applicant gets 404 everywhere and the attempts are audited, staff 403, no row without an actor), `ApplicationTypeConfigIT` (C4: the DEMO type and its three DEMO categories, a changed definition is a new version, audited, a draft moves to it when saved, an invalid catalogue refused whole), `CompletenessIT` (the office queue; a missing category shown as missing; acceptance with missing items 409 with every item; the request-info round trip keeps both document versions and the whole history; files during a request only in the categories asked for; invalid transitions 409 and audited; other offices, cadastral officers and the applicant refused; a request names categories of the type and explains itself) |
| Review and decision | `ReviewIT` (claimed by one reviewer, invisible to the others; the reviewer never decides, 403; a decision without grounds 422 `GROUNDS_REQUIRED`, without citations `CITATIONS_REQUIRED`; an open overlap alert 409 `OPEN_ALERT`; returned, recommended again, a decision on a case not pending decision 409 `INVALID_TRANSITION`; decided once; the applicant reads the simulated decision and nothing of the review; whoever recommended never decides even holding both roles; the applicant's payload passes `ApplicantPayloadSchema`), pgTAP `18` |
| Tracking and workload | `TrackingTest` (parties never people, no reason, the date entered from the last move, the pending request, whose turn), `StageTargetsTest` (targets above zero, at most a year, only for working states, checked when the settings are read), `TrackingIT` (a whole life through `ApplicantPayloadSchema`: only allowed fields, parties never people, no staff name, finding, return reason or recommendation code at any step), `WorkloadIT` (counts per state, open and overdue per stage, who holds a case, stays counted from the transition table, the queue oldest first and filtered, `STATE_NOT_LISTED`; other offices, intake officers and applicants refused; nothing of the applicant, the form or the review in the report), pgTAP `19` (who reads the functions, the raw stays closed to the application, since from the last move, overdue against the targets given, durations between two moves, the transition table unchangeable) |
| Simulated payment | `PaymentDomainTest` (DEMO-PAY references and their check character, expiry as of now, the standing, fee definitions, which outcomes are exceptions), `DemoSimulatorProviderTest` (provider identity, headers, the skew window at its edges, any change to body or timestamp, an unknown key version, an oversized body never verified, the DEMO protocol only, custody unreachable is not the sender's fault), `PaymentIT` (the six outcomes, paid and matched, failed then paid again, pending then expired, duplicate, unmatched reference, amount mismatch; a success after expiry with and without the sweep; eight concurrent copies of one callback recorded once, six concurrent successes paying once; unsigned, forged, tampered, replayed, unvouched and non-DEMO callbacks rejected and nothing stored; ownership, `Idempotency-Key` required and replayed, the provider unreachable, the finance lists and resolutions), `CompletenessIT` (the gate: `PAYMENT_REQUIRED` until paid), pgTAP `20` (fee versions never overlap or change, what a request owes, one at a time, the 7-day bound, nobody else's request, only the simulator, every outcome, duplicates, expiry by `expires_at`, final statuses, append-only reports, the sweep, the gate, the finance views and resolutions) |
| Audit timeline | `AuditDomainRulesTest` (canonical v2 with subjects, the subjects in the hash), `AuditReadingScopeTest` (only event search and the timeline are office-scoped), `TimelineIT` (a whole case: the auditor reads every event oldest first, the draft's included, each intact; the office's supervisor the same from the submission on, never the draft's; a supervisor elsewhere 404, a reviewer 403; the office-scoped search; Java and SQL canonical v2 agree; an altered row shows), pgTAP `21` (subjects of parcels, alerts across two offices, drafts and submitted applications; the caller's claim replaced; v1 kept; the chain mixing v1 and v2 verifies; who reads; moving an event to another office detected) |
| Documents | `TikaContentInspectorTest` (PDF, JPEG, PNG recognised from bytes; HTML or an executable named .pdf, an image named .pdf, a name without extension refused; PDF scripts, escaped names, embedded files, launch actions, encryption, a missing end refused; an image over the pixel limit or truncated refused), `FileDocumentStorageTest` (staged, hashed, random keys, size limit leaves nothing, key-shaped paths only, cleanup listing), `DocumentNameTest`, `DocumentIT` (versions kept and downloaded as attachments with the right headers and bytes; disguised, oversized and active-content files refused and audited with nothing kept; another applicant 404, staff 403; idempotent retry; version limit; a submitted application takes no more; orphans removed, referenced files kept; altered bytes never served and audited) |
| Maps | `FileMapDataStoreTest` (manifest rules, size limit, paths kept inside the data directory, glyph names), `MapApiIT` (catalogue with reasons, range delivery, refused to sessions without `parcel.read`); `PermissionMatrixTest.relativeScopesNeverCountAsEverywhere` |
| Whole application | `ApplicationSmokeIT` (health, 401 for anonymous calls, least-privilege role) |
| Ids | `UuidV7Test` |

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request, with the same scripts and
images as local development. Every action is pinned to a commit SHA (a tag can be moved);
the version is in a comment, and a newer version is adopted only once it is two weeks old.

| Job | Runs | Fails when |
| --- | --- | --- |
| `backend` | `./mvnw verify`: unit and integration tests (Testcontainers PostGIS), architecture, permission matrix and documentation checks | any test fails |
| `frontend` | `npm ci`, `npm run lint` (oxlint + structure check), `npm test` (Vitest), `npm run build` (type-check + build) | lint, a structure rule, a test, a type error or the build fails |
| `stack` | `dev-setup.sh`, pgTAP, `docker compose up --wait` and the smoke test (every migration applied, page served, API answers 401), security and segmentation probes, `e2e.sh` (which builds the map tools and installs synthetic imagery), gitleaks | any of them fails; the e2e report and traces are uploaded as the `e2e-report` artifact (7 days) |

Commits and pull requests are made by the project owner; a change that adds a suite, a
script or a tool updates this workflow in the same change.

## How the integration tests work

`PostgisDatabase` starts one PostGIS container per JVM:

- from the same image digest and port as `compose.yml`;
- `01-roles.sql` runs as superuser;
- the migrations run as `snigf_migrator`;
- the application connects as `snigf_app`;
- passwords are random per run.

Tests run under the `test` profile, which allows plain TCP and in-memory keys. Transport
and key custody are covered by the probes and the startup self-test instead.

The database is shared by every test in the run, so:

- every integration test class extends `PostgisIntegrationTest`, so they share one
  application context (the test profile's in-memory keys are per context: checkpoints
  signed in one context fail verification in another);
- tests use positions and rows they created themselves;
- tampering tests (`AuditChainIT`) use `superuserConnection()` in a transaction they roll
  back, or restore what they changed in a `finally` block;
- never assume an empty table, and never depend on test order.

## Writing a test

- **Domain rule:** a unit test with an in-memory repository (see `InMemoryChain`). No
  Spring.
- **Adapter or transaction behaviour:** an `*IT` extending the module's integration base
  (`AuditIntegrationTest`).
- **Refusals:** assert the reason (message or code), and keep a positive control next to
  it. A test that passes because something unrelated failed is worse than no test.
- **A new security guarantee:** add a probe check for the running stack, with its reason
  and a control.
- **A database rule:** a pgTAP test in `backend/src/test/sql/t/NN_name.sql`. Change to a
  path without the schema to test what other roles see; keep `tap` on the path.
- **Prove it can fail:** break the code under test once and watch the test fail. V4's
  test was checked by removing V4.
