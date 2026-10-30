# 4. Data model

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Database, schema, roles

One database, `snigf`, with one application schema, `snigf`. PostGIS lives in `public`.
`infra/db/init/01-roles.sql` runs once as superuser on an empty volume. It is called by
`infra/db/docker-init-roles.sh` with passwords read from secrets.

| Role | Logs in | Owns / may | Used by |
| --- | --- | --- | --- |
| `postgres` (superuser) | local socket only, as the `postgres` OS user (`peer`) | everything | operators, never the application |
| `snigf_migrator` | TLS + certificate CN `snigf_migrator` + password | owns schema `snigf`; runs DDL | `migrate` service only, at deploy time |
| `snigf_app` | TLS + certificate CN `snigf_app` + password | per-table and per-column grants; READ COMMITTED by default | `app` |
| `snigf_auditor` | TLS + certificate + password | SELECT on audit tables; `audit_verify_chain` | auditors |

`REVOKE CREATE ON SCHEMA public FROM PUBLIC` and
`REVOKE CONNECT ON DATABASE FROM PUBLIC`: nobody creates objects or connects by default.

## Migrations

Flyway, in `backend/src/main/resources/db/migration`, applied in order by `migrate` as
`snigf_migrator`. **An applied migration is never edited**; Flyway rejects a changed
checksum. A change is a new migration.

| Migration | Content |
| --- | --- |
| `V1__identity.sql` | `reject_modification()` guard; `office`, `app_user`, `mfa_recovery_code`, `role_assignment` (no self-grant, office scope, revocation rules), `idempotency_record`; grants |
| `V2__parcel.sql` | `reference_counter` and `next_reference_number()`; `parcel` and append-only `parcel_version` (gapless version numbers, current pointer only moves forward); geometry type `geometry_check_result` and `normalize_parcel_geometry()` (CRS, GeoJSON, type, validity, bounds, vertex limit, transformation to EPSG:4326) |
| `V3__audit.sql` | `audit_event` (hash chain assigned by trigger under an advisory lock), `audit_field()`, `audit_canonical()`, `audit_event_chain()`, append-only triggers, `audit_checkpoint`, `audit_verify_chain()`; grants |
| `V4__function_search_path.sql` | Pins `search_path = pg_catalog, snigf, public, pg_temp` on every function of the schema |
| `V5__sessions.sql` | Spring Session JDBC tables `spring_session` and `spring_session_attributes` (ADR-004); the application role alone may read and write them |
| `V6__webauthn.sql` | WebAuthn tables `user_entities` (one per account, the generated `app_user_id` is a foreign key) and `user_credentials` (public keys, labelled); replaces V1's TOTP-only enrolment check with the trigger `app_user_mfa_enrolment_guard` (an account is enrolled only with a credential or a TOTP secret) |
| `V7__roles_and_special_grants.sql` | Permission catalogue `permission` (33 rows, administration never delegable); `role_definition` and `role_permission` with the nine baseline roles seeded as immutable system roles (trigger `role_definition_guard`); scopes checked against the catalogue (`role_permission_scope_allowed`); `role_assignment.role` becomes a foreign key to `role_definition`, retired roles cannot be assigned (`role_assignment_active_role`); four-eyes `role_change_request` (one open request per role); `permission_grant` (special grants: four-eyes, never self-requested or self-revoked, end date in the future, 1 to 100000 uses, only delegable permissions); grants |
| `V8__approval_workflow_enforced.sql` | The approval workflow enforced by the database (ADR-026): permissions `admin.assignment.read` and `admin.assignment.approve` (SYSTEM_ADMIN) and `audit.chain.verify` for SUPERVISOR; helpers `holds_permission()`, `mixes_duties()`, `require_permission()`, `require_staff()`, `require_fresh()`, `is_superuser()`; table `role_assignment_request`, whose approval creates the assignment; `role_assignment.request_id`, and a non-self-registration assignment only for an approved request (superuser excepted: bootstrap); revocation only by a holder of `admin.role.revoke`; role change requests applied by the database on approval (`role_change_request_apply`, SECURITY DEFINER) and `snigf_app` loses every write on `role_definition` and `role_permission`; special grants inserted only PENDING, allowed status transitions only, uses moving one at a time; every request: created PENDING at the database time, decided by a holder of the approval permission who is neither requester nor beneficiary, at most 30 days old |
| `V9__checkpoint_once_per_position.sql` | One checkpoint per chain position (`ux_audit_checkpoint_position`), decision D5: a checkpoint is never replaced |
| `V10__supervised_reset_and_key_removal.sql` | ADR-028: permission `identity.reset.confirm` (business, office, not delegable) for SUPERVISOR; `holds_permission_at()`; the last key of an enrolled account cannot be deleted (`user_credentials_keep_last`); table `mfa_reset_request` (asked by a holder of `admin.mfa.reset`, confirmed by a holder of `identity.reset.confirm` at an office of the person, three different people, the identity check recorded); a confirmation performs the reset (`mfa_reset_request_apply`, SECURITY DEFINER); only that reset or the superuser clears an enrolment (`app_user_enrolment_cleared_by_reset`) |
| `V11__conflict_detection.sql` | ADR-030 (MVP-04): `conflict_policy` (one row: minimum overlap m², duplicate share; the application applies its settings at startup); `conflict_alert` (type, both parcels and compared versions, intersection area, both shares, intersection geometry or normalised reference, the version that raised it, status OPEN / RESOLVED / SUPERSEDED, resolution, optimistic lock; one open per pair and type); `normalize_external_reference()`; detection on every `parcel_version` insert (`parcel_version_detect_conflicts`, SECURITY DEFINER, advisory lock, supersedes the parcel's open alerts, then `raise_conflicts_for_version`); guard on updates (evidence immutable, only open alerts move, resolver entitled and author of neither version, note ≥ 10); no delete; existing parcels checked once |
| `V12__alert_resolution.sql` | ADR-031 (E-11): `holds_grant_at()` (an approved special grant in force, at the office or everywhere); `conflict_alert_guard()` replaced so a resolver may hold `alert.resolve` by role or by such a grant at either parcel's office (the author rule still first); resolution note at most 2000 characters |
| `V13__applications.sql` | ADR-032 (MVP-02): `current_actor()` (the actor the application names per transaction); `application_type` (DEMO catalogue, one row per version, insert-only, versions follow each other, DEMO in both names); `application` (owner, type version, nine states, parcel and office from submission, form data as JSON, optimistic lock; reference only once submitted); `application_transition` (written by the database on every state change, append-only); guard (draft first, owner and reference immutable, legal moves only, submit and withdraw by the owner, active parcel of the office, frozen once submitted); only drafts deleted; row-level security: the owner, or staff holding `application.read` at the office |
| `V14__documents.sql` | ADR-033 (E-13): `document` (one per application and category); `document_version` (random storage key, SHA-256, size, detected type PDF/JPEG/PNG, display name never a path, status, numbered by the database, never updated); guards (only the owner, only to their draft, only a category of its type version, under their own name); deleted only with the draft; `document_storage_keys_known()` for the cleanup; row-level security following the application's |
| `V15__completeness.sql` | ADR-034 (MVP-03): `holds_at()` (role or special grant at an office); `application_office_code()` (for permission checks); `application_missing_items()` (the automatic check, one function for screens, service and guards); `completeness_record` (append-only: automatic results, acceptances, information requests with categories and message, responses; what was missing filled in by the database); the application guard replaced (every move needs its record; the automatic check the only move without a person; review and decision moves need their permission at the office; withdrawal by the applicant); `application_transition.actor_id` nullable for the automatic check only; `document_accepted()` replaced (during a request, only the categories asked for); staff of the office move the state of its applications (`application_handle` policy) |
| `V16__review_and_decision.sql` | ADR-035 (MVP-05): `holds_assignable()` (a permission at scope ASSIGNED through a role at the office); `case_assignment` (claim by self, one per case and stage, append-only); `case_assignee()`, `case_visible_to_assignee()`; `review_step` (recommendations with findings, returns with reasons, 10 to 4000 characters, append-only); `decision` (one per application, APPROVED or REFUSED, grounds, 1 to 50 cited stored versions, the recommendation it follows, `simulated` always true); `application_open_alerts()`; guards (separation of duties: whoever recommended never claims the decision nor decides; never the applicant; the open-alert check last); the application guard replaced (review and decision moves need their record by the person assigned); row-level policies `application_assigned_read`, `application_assigned_handle`; the review hidden from the applicant |
| `V17__tracking_and_workload.sql` | ADR-036 (MVP-06): no table; `office_stays()` (every stay of an office's applications in a state, from `application_transition` only, `lead()` over its moves; not granted to the application); `workload_office()` (the office when the actor holds `report.workload` there, role or special grant); `workload_target()` (a target in seconds from the settings, none when zero or not a number); `workload_counts()`, `workload_open_cases()` (since, waited, target, overdue, who holds the case), `workload_completed_stays()` (count, average, median, longest, over target since a date), all `SECURITY DEFINER`, returning nothing for an office the actor may not read |
| `V18__payments.sql` | ADR-037 (MVP-08), **simulated payment, no real money moves**: `payment_policy` (one row: the gate before acceptance, the only approved provider `DEMO_SIMULATOR`; `payment_policy_apply()`); `fee_schedule` (DEMO fees per type in XAF, versions that never overlap, closed once, never edited or deleted; `fee_in_force()`); `payment_request` (DEMO-PAY reference, amount and currency copied from the fee in force at submission by the guard, `expires_at` 1 minute to 7 days after creation, one `PENDING` or `PAID` per application, status changed only in the functions below: never `PAID` past its expiry, never `EXPIRED` before it, final once settled; row-level security through the application's visibility); `payment_record` (every authenticated provider report, append-only, one per provider transaction id, with the database's outcome and `exception`); `payment_exception_resolution` (the finance officer's closing of an exception, append-only, `payment.reconcile`, never on their own application); `payment_callback_apply()` (decides a report under a lock on the request: matched, failed, amount mismatch, unmatched reference, after expiry, already settled, no effect; duplicates by transaction id change nothing); `payment_expire_due()`; `payment_blocks_acceptance()` and the trigger `completeness_payment_guard` (`55000` on an unpaid `COMPLETE` record); the finance views `payment_requests_for_finance()`, `payment_exceptions_for_finance()`, `payment_finance_summary()` and `payment_exception_resolve()`, gated on `payment.reconcile`; all `SECURITY DEFINER` |
| `V20__document_issuance.sql` | ADR-039 (Slice 1), **DEMO acknowledgment only**: the permissions `document.issued.manage` and `document.incident.review` and the role DOCUMENT_OFFICER; `holds_everywhere()`; `issued_document` (the registry: a random `SNIGF-` number, type, template version, a unique operation key, the application and its reference, office, holder, issuance time within a minute of now, what was printed, SHA-256 of the exact PDF, manifest, signature and key version, `demo` forced true, status and its reason, who changed it, supersedes or superseded by; guard: issued VALID, what was issued never changes, a status leaves VALID once by a manage holder who is not the holder, a superseded document names its replacement, a re-issue replaces a valid document of the same subject and holder; never deleted); `issued_document_artifact` (the exact PDF, matching the registered SHA-256, append-only); `document_verification` (every public verification, append-only, readable by reviewers only); `verification_incident` (altered, fabricated or registry-integrity incidents, resolved once with a note by a reviewer who is not the holder); `issued_document_evidence()` (`SECURITY DEFINER`: the minimal record by number or by SHA-256, with the replacement's number); `audit_subjects()` replaced to map issued documents and incidents to their application; row-level security following the application |
| `V19__audit_subjects.sql` | ADR-038 (E-18), implementing ADR-027: `audit_event.subjects` (JSON array, GIN index): what an event is about, found by the insert trigger with `audit_subjects()` (`SECURITY DEFINER`) from the record in the same transaction, never from the caller: an application (also for its document versions, payment requests and matched provider reports), a parcel, or both parcels of an alert, each with its office at that moment (none for a draft); `audit_canonical()` encodes such an event `snigf-audit-v2` with the subjects hashed, and keeps `snigf-audit-v1` unchanged for every event without them (no backfill); `audit_readable()`: `audit.event.search` everywhere, or at the office of one of the event's subjects |

Every new function must carry the same `SET search_path`. pgTAP test `06` enforces it.
V7's constraints and triggers are tested by pgTAP test `09`, V8's approval workflow by test `10`,
the checkpoint rules (V3, V9) by test `11`, the supervised reset and key removal (V10) by test `12`,
overlap and duplicate detection (V11, the Baseline's fixture set) by test `13`, resolution by a special-grant
holder and the note bound (V12) by test `14`, applications under row-level security (V13) by test `15`, documents (V14) by test `16`, the completeness review (V15) by test `17`, review and simulated decision (V16) by test `18`, the tracking and workload functions (V17) by test `19`, simulated payment and reconciliation (V18) by test `20`, audit subjects (V19) by test `21`, issued documents and verification (V20) by test `22`.

## Tables

| Table | Purpose | Mutability for `snigf_app` |
| --- | --- | --- |
| `office` | Demo offices (`is_demo` must be true until gate G1) | SELECT |
| `app_user` | Accounts: email, display name, password hash, account type, MFA fields, lockout counters | SELECT, INSERT, UPDATE of listed columns |
| `mfa_recovery_code` | One-use recovery codes (hashed) | SELECT, INSERT, UPDATE `used_at`, `invalidated_at` |
| `role_assignment` | Role grants with office scope and reasons; revocation is final; `request_id` names the approved request (V8) | SELECT, INSERT (only the self-registration role, or for an approved request), UPDATE of revocation columns (by a holder of `admin.role.revoke`) |
| `idempotency_record` | Responses replayed for a repeated `Idempotency-Key` | SELECT, INSERT, DELETE |
| `reference_counter` | Counter per (prefix, office, year) | SELECT, INSERT, UPDATE (used by `next_reference_number()`) |
| `parcel` | Stable parcel identity and current version pointer | SELECT, INSERT, UPDATE of `status`, `current_version_no`, `version`; never DELETE |
| `parcel_version` | Immutable geometry history | INSERT, SELECT |
| `audit_event` | Hash-chained audit events | SELECT, INSERT only |
| `audit_checkpoint` | Database copy of signed checkpoints | SELECT, INSERT only |
| `spring_session` | Server-side sessions: id (the cookie value), times, `principal_name` (the user id, indexed so all of a user's sessions can be revoked) | SELECT, INSERT, UPDATE, DELETE; the auditor has no access |
| `user_entities` | WebAuthn user handle per account; `name` is the account id, never the e-mail | SELECT, INSERT, UPDATE, DELETE; the auditor has no access |
| `user_credentials` | Registered authenticators: credential id, public key (COSE), signature counter, flags, label, created and last-used times; never a private key | SELECT, INSERT, UPDATE, DELETE (never the last key of an enrolled account, V10); the auditor has no access |
| `permission` | The permission catalogue: code, family (ADMINISTRATION, AUDIT, BUSINESS), delegable, allowed scopes, descriptions. Must equal `Permission.java` (`PermissionCatalogueCheck`) | SELECT |
| `role_definition` | Roles: code, names, description, `system` (baseline, immutable), `assignable`, `status` ACTIVE or RETIRED (retired roles stay for history), `version` | SELECT only: changes are applied by the database when a role change request is approved (V8) |
| `role_permission` | Permission and scope per role | SELECT only (V8) |
| `conflict_policy` | The detection thresholds (one row) | SELECT, UPDATE of the thresholds |
| `conflict_alert` | Overlap and duplicate alerts between two exact parcel versions, with their evidence (V11) | SELECT; UPDATE of resolution columns only (guarded); never INSERT or DELETE: alerts are raised by the database |
| `application_type` | The application type catalogue: one row per version (fields and document categories as JSON) | SELECT, INSERT (a new version only); never UPDATE or DELETE |
| `application` | Applications: owner, type version, state, parcel, office, form, reference once submitted (V13). **Row-level security:** visible only to its owner or to staff holding `application.read` at its office, for the actor named in the transaction | SELECT, INSERT, DELETE (drafts), UPDATE of form and state columns, all under row-level security and the guard |
| `application_transition` | Every state change of an application: from, to, actor, reason, time | SELECT only (through its application's visibility); written by the database |
| `document` | A supporting document of an application, one per category of its type | SELECT, INSERT (owner, draft, category of the type), under row-level security |
| `document_version` | Each file uploaded for a document: storage key, SHA-256, size, detected type, display name, status | SELECT, INSERT (owner, draft), under row-level security; never UPDATE or DELETE (gone only with a deleted draft) |
| `completeness_record` | Each step of the completeness review: kind, what was missing then (computed by the database), categories requested, message, actor, time | SELECT, INSERT (guarded: who may write which kind), under row-level security; never UPDATE or DELETE |
| `case_assignment` | Who works on a case at a stage (`REVIEW`, `DECISION`): the assignee, the time | SELECT, INSERT (claim by self, guarded), under row-level security; never UPDATE or DELETE |
| `review_step` | The reviewer's recommendation with its finding, the decision authority's return with its reason | SELECT (staff who see the case, never the applicant), INSERT (the person assigned); never UPDATE or DELETE |
| `payment_policy` | The payment gate and the only approved provider (one row, V18) | SELECT; changed through `payment_policy_apply()` |
| `fee_schedule` | DEMO fees per application type, versioned (V18) | SELECT, INSERT (a new version), UPDATE of `valid_to` (closing, once); never DELETE |
| `payment_request` | What an applicant owes for an application, its DEMO-PAY reference, status and expiry (V18). **Row-level security:** visible with its application | SELECT, INSERT (the applicant, guarded), UPDATE of `provider_charge_id` (once, while pending); the status only through the payment functions |
| `payment_record` | Every authenticated provider report with its outcome (V18) | none: written and read through the payment functions |
| `payment_exception_resolution` | The finance officer's resolution of an exception (V18) | none: written through `payment_exception_resolve()` |
| `issued_document` | The registry of documents issued by SNIGF, with their signed manifest and status (V20) | insert as the applicant (or a re-issue by `document.issued.manage`); update of the status columns only, by `document.issued.manage` |
| `issued_document_artifact` | The exact PDF of each issued document (V20) | insert only, matching the registered SHA-256 |
| `document_verification` | Every public verification and its outcome (V20) | insert only (in one's own name or anonymously); read by `document.incident.review` |
| `verification_incident` | Altered, fabricated and registry-integrity incidents (V20) | insert open; resolved once by `document.incident.review` |
| `decision` | The simulated outcome with its grounds, the cited document versions, the recommendation followed, the author | SELECT (staff who see the case, the applicant), INSERT (the decision authority assigned, guarded); never UPDATE or DELETE |
| `mfa_reset_request` | Supervised second-factor resets: person, reason, requester, status, confirmer, how the identity was checked (V10) | SELECT, INSERT, UPDATE of decision columns |
| `role_assignment_request` | Four-eyes requests to assign a role at an office: requester, reason, decision. Approval creates the `role_assignment` with the same id (V8) | SELECT, INSERT, UPDATE of decision columns |
| `role_change_request` | Four-eyes requests to create, change or retire a custom role: proposal (JSON), reason, requester, decision | SELECT, INSERT, UPDATE of decision columns |
| `permission_grant` | Special grants: user, permission, optional office, reason, `valid_until`, `max_uses`, `uses`, status, requester, approver, revocation | SELECT, INSERT, UPDATE of decision, `uses` and revocation columns |
| `spring_session_attributes` | Session values: only `snigf.auth.user`, `snigf.auth.level`, `snigf.auth.at` (plain values, see chapter 3) | SELECT, INSERT, UPDATE, DELETE |

The geometry error codes returned by `normalize_parcel_geometry()` are listed in the
Slice 1 design ("Error format").

## The audit chain

For every inserted event, the trigger `audit_event_chain()`:

1. takes the advisory lock `hashtextextended('snigf.audit_event.chain', 0)`;
2. reads the last row;
3. sets `chain_pos = last + 1`, `occurred_at = clock_timestamp()` and
   `prev_hash = last.hash` (32 zero bytes for the first event);
4. sets `hash = sha256(prev_hash || audit_canonical(row))`.

The client's values for these four columns are ignored.

The canonical encoding (`snigf-audit-v1`) and the checkpoint payload
(`snigf-audit-checkpoint-v1`) are specified in [`docs/audit.md`](../audit.md). The Java
implementation is `ChainedEvent` and `AuditEncoding`; `CanonicalHashParityIT` proves both
sides produce identical bytes.

### Columns of `audit_event`

| Column | Source |
| --- | --- |
| `chain_pos`, `occurred_at`, `prev_hash`, `hash` | database trigger |
| `id` | application, UUIDv7 |
| `actor_type`, `actor_id`, `actor_label`, `actor_roles` | security context or `SystemActor`; constraint `ck_actor` keeps them coherent |
| `action` | `AuditAction` (closed list) |
| `entity_type`, `entity_id`, `entity_ref` | the service |
| `outcome` | `SUCCESS`, `DENIED` or `FAILED` |
| `reason` | the actor's justification (at most 1000 characters) |
| `correlation_id` | server-generated per request (`CorrelationIdFilter`) or per scheduled task |
| `source_ip` | browser address from the proxy, believed only from the admitted proxy |
| `details` | JSON object of plain values, at most `SNIGF_AUDIT_MAX_DETAILS_BYTES` |

### Audit actions

| Action | Emitted when | Module |
| --- | --- | --- |
| `USER_REGISTERED` | an applicant account is created | identity |
| `LOGIN_SUCCEEDED` | a login completes | identity |
| `LOGIN_FAILED` | a login attempt fails | identity |
| `ACCOUNT_LOCKED` | an account is locked after failures | identity |
| `MFA_ENROLLED` | a second factor is registered | identity |
| `MFA_VERIFIED` | a second factor is verified | identity |
| `MFA_RECOVERY_CODE_USED` | a recovery code is consumed | identity |
| `MFA_RESET_REQUESTED` | an administrator requests the reset of a person's second factor; `DENIED` for oneself | identity |
| `MFA_RESET` | the person's supervisor confirms the reset (actor), which the database performs; details: requester, identity check, keys removed, sessions ended; `DENIED` when four-eyes refused it | identity |
| `MFA_RESET_REJECTED` | the supervisor rejects the reset | identity |
| `MFA_KEY_REMOVED` | a staff member removes one of their own security keys (label, keys remaining) | identity |
| `LOGOUT` | a session ends on request | identity |
| `ROLE_ASSIGNMENT_REQUESTED` | an administrator requests a role for a staff member; `DENIED` for a self-assignment | identity |
| `ROLE_ASSIGNMENT_REJECTED` | a second administrator rejects the request | identity |
| `ROLE_GRANTED` | a second administrator approves a role request (the assignment exists from then); `DENIED` when four-eyes refused the approval | identity |
| `ROLE_REVOKED` | a role is revoked | identity |
| `ROLE_DEFINITION_REQUESTED` | an administrator requests to create, change or retire a custom role | identity |
| `ROLE_DEFINITION_APPROVED` | a second administrator approves it (applied); `DENIED` when four-eyes or self-benefit refused it | identity |
| `ROLE_DEFINITION_REJECTED` | a second administrator rejects it | identity |
| `PERMISSION_GRANT_REQUESTED` | an administrator requests a special grant for a staff member | identity |
| `PERMISSION_GRANT_APPROVED` | a second administrator approves it; `DENIED` when four-eyes refused it | identity |
| `PERMISSION_GRANT_REJECTED` | a second administrator rejects it | identity |
| `PERMISSION_GRANT_REVOKED` | a special grant is revoked before its end | identity |
| `PERMISSION_GRANT_USED` | an action relied on a special grant: `SUCCESS` (use counted) or `FAILED` (use given back) | identity |
| `PARCEL_CREATED` | a parcel and its first version are created | parcel |
| `PARCEL_CREATE_REJECTED` | a parcel fails validation | parcel |
| `PARCEL_CORRECTED` | a correction adds a new version, with its reason | parcel |
| `PARCEL_CORRECT_REJECTED` | a correction fails validation (recorded even though nothing is saved) | parcel |
| `CONFLICT_ALERT_RAISED` | a parcel version raised an overlap or duplicate alert (type, other parcel and version, intersection and shares or reference; same correlation as the write) | conflict |
| `CONFLICT_ALERT_SUPERSEDED` | a correction closed an open alert of its parcel | conflict |
| `CONFLICT_POLICY_CHANGED` | the detection thresholds changed at startup (new and previous values) | conflict |
| `CONFLICT_ALERT_RESOLVED` | an alert was resolved (outcome, both parcels and versions; the note as reason) | conflict |
| `APPLICATION_TYPE_CHANGED` | a type of the catalogue got a new version at startup (code, version, available, previous version) | application |
| `APPLICATION_DRAFT_CREATED` | an applicant started a draft (type and version) | application |
| `APPLICATION_DRAFT_UPDATED` | a draft was saved (fields present, whether a parcel is named; never the values) | application |
| `APPLICATION_DRAFT_DELETED` | a draft was deleted by its owner | application |
| `APPLICATION_SUBMITTED` | an application was submitted (reference, type version, office, parcel) | application |
| `APPLICATION_SUBMIT_REJECTED` | a submission was refused: every missing item (recorded even though nothing changed) | application |
| `DOCUMENT_UPLOADED` | a file was kept as a new version (category, version, type, size, SHA-256) | document |
| `DOCUMENT_REJECTED` | an upload was refused (code, size, what the bytes were); nothing kept | document |
| `DOCUMENT_DOWNLOADED` | a stored version was downloaded | document |
| `DOCUMENT_INTEGRITY_FAILED` | stored bytes no longer matched their hash; not served | document |
| `DOCUMENT_ORPHANS_REMOVED` | the cleanup removed files nobody references (counts) | document |
| `COMPLETENESS_ACCEPTED` | an intake officer accepted a complete case (office) | application |
| `COMPLETENESS_INFO_REQUESTED` | an intake officer requested information (office, categories) | application |
| `COMPLETENESS_RESPONSE_SENT` | the applicant responded (with or without a message; what is still missing) | application |
| `COMPLETENESS_REJECTED` | an acceptance or request was refused (`ITEMS_MISSING`, `PAYMENT_REQUIRED`, `INVALID_TRANSITION`, `APPLICANT_CANNOT_REVIEW` ...) | application |
| `CASE_CLAIMED` | a reviewer or decision authority claimed a case (stage, office) | review |
| `REVIEW_RECOMMENDED` | the reviewer recorded a recommendation (the recommendation; never the finding's text) | review |
| `DECISION_RETURNED` | the decision authority returned the case to review | review |
| `DECISION_RECORDED` | the simulated decision was recorded (outcome, versions cited, `simulated`) | review |
| `REVIEW_REJECTED` | a claim, recommendation, return or decision was refused (`ALREADY_ASSIGNED`, `RECOMMENDER_CANNOT_DECIDE`, `OPEN_ALERT`, `STALE_VERSION` ...) | review |
| `PAYMENT_FEE_CHANGED` | a DEMO fee version was opened or closed at start-up (type, amount, previous amount) | payment |
| `PAYMENT_POLICY_CHANGED` | the payment gate setting changed at start-up | payment |
| `PAYMENT_REQUESTED` | an applicant opened a payment (reference, application, amount, scenario, provider, expiry, `demo`) | payment |
| `PAYMENT_REFUSED` | a payment or resolution was refused (`ALREADY_PAID`, `PAYMENT_PENDING`, `IDEMPOTENCY_KEY_REQUIRED`, `APPLICATION_NOT_FOUND`, `ALREADY_RESOLVED` ...) | payment |
| `PAYMENT_PROVIDER_UNAVAILABLE` | the provider could not take a charge; the request stays pending without one | payment |
| `PAYMENT_CALLBACK_ACCEPTED` | an authenticated callback was decided (outcome, transaction, reference, amounts, key version) | payment |
| `PAYMENT_CALLBACK_REJECTED` | a callback was not authentic or not readable (`SIGNATURE_MISSING`, `SIGNATURE_INVALID`, `TIMESTAMP_OUT_OF_WINDOW`, `PROVIDER_NOT_RECOGNISED`, `CALLBACK_MALFORMED` ...); nothing of it is stored | payment |
| `PAYMENT_CALLBACK_DUPLICATE` | a callback for a transaction already recorded; nothing changed | payment |
| `PAYMENT_EXCEPTION_RAISED` | a report became an exception (`AMOUNT_MISMATCH`, `UNMATCHED_REFERENCE`, `AFTER_EXPIRY`, `ALREADY_SETTLED`) | payment |
| `PAYMENT_EXPIRED` | a pending payment expired (`PENDING_TTL_ELAPSED` by the sweep, `CALLBACK_AFTER_EXPIRY` by a late callback) | payment |
| `PAYMENT_EXCEPTION_RESOLVED` | the finance officer resolved an exception (resolution, note length) | payment |
| `DOCUMENT_ISSUED` | a document was issued (number, type, template, key version, `demo`), or could not be (`FAILED`: key custody unavailable) | issuance |
| `DOCUMENT_VERIFIED` | a public verification ran (channel, outcome, reason, number when known); never the presented file | issuance |
| `DOCUMENT_REVOKED` | the document officer revoked a document (number, reason length) | issuance |
| `DOCUMENT_CANCELLED` | the document officer cancelled a document (number, reason length) | issuance |
| `DOCUMENT_SUPERSEDED` | a re-issue replaced a document (old and new numbers, reason length) | issuance |
| `DOCUMENT_STATUS_REFUSED` | a status change, re-issue or resolution was refused (`NOT_VALID`, `OWN_DOCUMENT`, `REASON_REQUIRED`, `INCIDENT_RESOLVED` ...) | issuance |
| `VERIFICATION_INCIDENT_OPENED` | a verification opened an incident (`ALTERED`, `FABRICATED`, `REGISTRY_INTEGRITY`) | issuance |
| `VERIFICATION_INCIDENT_RESOLVED` | the document officer resolved an incident (resolution, note length) | issuance |
| `CONFLICT_ALERT_RESOLVE_REJECTED` | a resolution was refused (`RESOLVER_IS_AUTHOR`, `STALE_VERSION`, `ALERT_NOT_OPEN`, note or outcome missing; recorded even though nothing changed) | conflict |
| `ACCESS_DENIED` | an authenticated call is refused by an access rule | shared security |
| `AUDIT_EVENTS_READ` | someone reads audit events | audit |
| `AUDIT_TIMELINE_READ` | someone reads a case's or a parcel's timeline (type, reference, events returned) | audit |
| `AUDIT_VERIFIED` | a chain verification ran (details hold the result) | audit |
| `AUDIT_CHECKPOINT_CREATED` | a checkpoint was signed, or refused (`FAILED`) | audit |

### Verification findings

| Kind | Meaning |
| --- | --- |
| `GAP` | a position is missing: an event was deleted |
| `PREV_HASH` | an event does not link to the previous hash |
| `CONTENT_HASH` | an event's content no longer matches its hash |
| `CHECKPOINT_SIGNATURE` | a checkpoint's signature does not verify, or its copies disagree |
| `CHECKPOINT_MISMATCH` | the chain's hash at a checkpoint differs from the signed head |
| `TRUNCATED` | a checkpoint signs a position the chain no longer reaches |
| `CHECKPOINT_UNREADABLE` | a checkpoint file cannot be parsed |
