# ADR-041: External professional accounts: notaries deposit through their own account

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| **Accepted, revision 6.** D11 to D16, and the sign-in and suspension answers, of 1 Nov 2026: built in steps 1 to 6, documented in step 7, verified locally in step 8 (2 Nov 2026); what differs from the decision is under "As built". **D17 to D21 of 2 Nov 2026** (conflict visibility, retention, document replacement, account order, dashboard counts): recorded in step 9; D17 built in step 10, D20 in step 11, D21 in step 12; the three readings confirmed on 3 Nov 2026; D18 and D19 to be built in steps 13 to 16 (see "Decisions of 2 Nov 2026" and the plan). | 2026-11-04 | Project owner, Pythagore (FLYT) |

## Context

[ADR-040](040-land-register.md) §6 (decision D3) designed external professional accounts, and
§4.7 (D10) made counter deposits a temporary measure "until slice 3". Today:

- **A notary is only a named party.** A conservator records the deposit at the counter and is the
  only authenticated actor. The notary has no account, so SNIGF cannot attribute the submission to
  them.
- **Only two account types exist**, `APPLICANT` and `STAFF` (`app_user.account_type`, V1).
- **Slices 4 and later need professionals.** Morcellement needs sworn surveyors, and the procedures
  need notaries on cases.

The aim is to let a notary deposit a transaction on a title through their own verified account,
without giving any professional a right to decide, register, inscribe or administer anything.

## Decision

### 1. A third account type, `PROFESSIONAL`

- **Type.** `app_user.account_type` gains `PROFESSIONAL` (migration V23). A professional account
  cannot create applications, hold back-office roles, or receive special grants.
- **Profile.** A new table, `professional_profile`, one row per account:
  - profession: `NOTARY` or `SWORN_SURVEYOR`;
  - credential: chamber, registration number and jurisdiction;
  - status: `REQUESTED`, `VERIFIED`, `ACTIVE`, `SUSPENDED` or `REVOKED`;
  - `demo`, enforced by a `CHECK`. In this MVP the registration number must contain `DEMO`.
- **No delegation.** A clerk needs their own account (ADR-040 §6).

### 2. Identity and capacity (D14)

**Decision: separate accounts for now, a documented limitation.** The project owner preferred one
verified identity with separated capacities. The current model cannot carry that safely within this
slice:

- `account_type` is a single value, and it is a security boundary in about ten places. Every database
  permission function (`holds_permission`, `holds_permission_at`, `holds_grant_at`,
  `holds_assignable`, `require_staff`) and several Java services (special grants, role assignment,
  officer signatures) decide on it.
- Making it multi-valued means re-deciding each of those checks for combined accounts, inside the
  slice whose purpose is to keep professionals away from staff rights. A mistake there would weaken
  the boundary this ADR depends on.

So a person who is both an applicant and a professional holds two accounts in this MVP.

**The security control is not the e-mail address.** What separates capacities is:

- the account type, checked by the database for every permission;
- the professional's own permission function and office table (§4);
- the capacity recorded on every action: each audit event of a professional names `capacity`
  (`NOTARY` or `SWORN_SURVEYOR`) and the profile id, and each deposit records the submitting
  professional's account.

**Future work:** a unified identity with capacities (one verified person, an applicant capacity and a
professional capacity, each action naming its capacity) needs its own ADR. It re-decides the
account-type checks one by one, with a pgTAP case for each.

### 3. Lifecycle: verified by one, approved by another

| Step | Who | Effect |
| --- | --- | --- |
| **Request** | the professional, on a public form (`#/professional-request`), throttled like registration | account created as `PROFESSIONAL`, profile `REQUESTED`. They can sign in, enrol two security keys and see their status; no business permission |
| **Verify** | administrator A (`admin.professional.verify`) | records how the credential and identity were checked with the professional body: the body, a reference, the date, a note, documents where required (D13). Profile `VERIFIED` |
| **Approve** | administrator B (`admin.professional.approve`), never A, never the professional | profile `ACTIVE`, with the offices named in the approval (§4). Refused while the account has fewer than two security keys |
| **Reject** | administrator B, never A | profile `REVOKED` with a reason |
| **Suspend** | one administrator (`admin.professional.suspend`), with a reason | profile `SUSPENDED`: every professional permission stops at the next request. Reversible |
| **Reinstate** | requested by one administrator, approved by another | back to `ACTIVE` |
| **Revoke** | requested by one administrator, approved by another, with a reason | profile `REVOKED`, final. The account is usually suspended first, so it cannot act meanwhile |
| **Change offices** | requested by one administrator, approved by another (D15) | an office added or removed |

- **One person to stop, two to grant, restore, end or move.** Suspension is the fast, reversible
  stop for abuse or compromise. Every other change needs a second administrator. This answers the
  project owner's choice of 1 Nov 2026: suspension by one, revocation by two.
- **Who approves.** The approver is never the requester or verifier of that step, and never the
  professional. Approvers are computed from active staff role assignments, and the database
  refuses otherwise, as V8 does.
- **Expiry.** A request expires after `SNIGF_ADMIN_REQUEST_TTL`, like other administration requests.
- **Audit.** Every step and refusal is audited, with its reason and actor.

### 4. Permissions: the professional's own path, never the staff path

New catalogue permissions:

| Permission | Scope | Allows |
| --- | --- | --- |
| `register.deposit.submit` | office | look up a title (§6) and deposit a transaction on it at that office, as the submitting professional |
| `register.deposit.read` | own | read one's own deposits, their status and their documents |
| `register.deposit.document` | own | add or replace documents on one's own deposit while it is open (ADR-040 §4.7 rules) |
| `register.deposit.withdraw` | own | withdraw one's own deposit, with a reason |

- **Baseline roles.** `NOTARY` holds these four. `SWORN_SURVEYOR` holds **none** (D12): identity
  and credentials are verified now; operational permissions arrive only with an authorised workflow.
- **Offices without role assignment.** Professionals do not use `role_assignment`, which stays
  staff-only (V8's `require_staff`). Their offices are rows of `professional_office`, added and
  removed only through two-administrator requests. Their permissions are computed from three things:
  an `ACTIVE` profile, the profession's role, and those offices. Suspension therefore takes effect on
  the next request, in Java and in the database.
- **A database function of their own.** `holds_professional_at(user, permission, office)` is true
  only for an `ACTIVE` professional, a permission flagged `professional`, the profession's role and an
  approved office. The staff functions are **not** widened: they keep requiring `STAFF`, so a
  professional fails every existing check by construction.
- **What a professional can never hold**, refused by the database:
  - any administration, audit, decision, proposal, inscription, confirmation or registration
    permission;
  - any special grant.

  A new catalogue column, `professional`, marks the four permissions above;
  `PermissionCatalogueCheck` compares it with the code as for the rest.
- **Server-side everywhere.** Every professional endpoint checks its permission at the office, or for
  the professional's own records. The database function checks the same again.

### 5. Sign-in: WebAuthn, as for staff

Professionals authenticate with a password and WebAuthn: two keys, user verification required, same
ceremonies, recovery codes, lockout and supervised reset (ADR-016, ADR-023, ADR-028). No
authenticator-app codes: ADR-024 stands, confirmed by the project owner on 1 Nov 2026.

In code, only the second-factor checks widen, from "is `STAFF`" to "is `STAFF` or `PROFESSIONAL`".
Officer signatures (ADR-019) stay staff-only: a professional signs nothing in the register.

### 6. Title lookup (D16)

`register.deposit.submit` allows one lookup: by title number, at an office the professional serves.

- **The answer:** the number, its office, and whether it accepts a deposit (it exists and is not
  closed).
- **Never in it:** holders, charges, restrictions, history, or whether a deposit or conflict is
  pending. Exposing a pending deposit would tell a third party about a sale in progress. *(Unchanged
  by D17, which concerns a professional's own deposits only.)*
- **Unknown or elsewhere:** an unknown number, and a title at an office the professional does not
  serve, get the same answer, so the lookup cannot be used to find titles.
- **Audit and limits:** each lookup is audited (`REGISTER_TITLE_LOOKED_UP`, with the capacity) and
  throttled per account.

Any fuller verification needs a separately authorised workflow.

### 7. Professional deposits (D11)

- **Valid and attributable, or no lock.** A professional's deposit is one request, carrying:
  - the title;
  - the kind of transaction;
  - the depositor (a `register_party` of the office, never a notary);
  - at least one `DEED` document.

  Every file passes the checks of ADR-033 and is kept first. Then one database function inserts the
  transaction and its documents together. A refused file, a missing deed, a closed title, an
  unserved office or an invalid party refuse the whole deposit: **nothing is recorded and no title
  is locked.** Each refusal is audited.
- **Then the V21 rules, unchanged.** The deposit is `HOLDING` at once if none holds the title (the
  lock), otherwise `IN_CONFLICT`, opening a conflict for conservators. Never refused for arriving
  second, never queued, never expiring on its own, reviewed when overdue.
- **Duplicates.** An `Idempotency-Key` makes a retried request return the same deposit. The same
  professional submitting the same deed (same SHA-256) on the same title while their earlier deposit
  is open is refused (`DUPLICATE_DEPOSIT`), so a double click cannot open a conflict with oneself.
- **Who is recorded.**
  - the depositor;
  - the submitting professional's account, an authenticated actor (`submitted_by_account`, new);
  - their `register_party` of kind `NOTARY`, linked to that account;
  - the capacity.

  No conservator is recorded as receiving it. A `CHECK` ties `received_by` to `COUNTER` and
  `submitted_by_account` to `PROFESSIONAL_ACCOUNT`.
- **The holder rule.** A professional cannot deposit on a title in which they, as a party linked to
  their account, hold an interest.
- **Documents.** Same validation, storage, retention and audit as counter deposits (ADR-040 §4.7).
  A professional reads and adds documents only on their own deposits, and never sees another
  deposit's documents, a conflicting one included. Conservators see all of the office's.
  *Amended by D19 (2 Nov 2026): replacing a document needs a second person.*
- **Conservators decide.** A professional's deposit is refused, registered or put in conflict by
  conservators, exactly like a counter deposit. Depositing registers nothing.
- **Counter deposits remain**, unchanged, beside the professional channel.

### 8. Verification evidence (D13)

- **What is kept.** References, notes and documents, in `professional_verification` and
  `professional_verification_document`. Files go through `DocumentFiles` (ADR-033).
- **Who reads it.** Administrators holding `admin.professional.verify` or `admin.professional.approve`
  only, enforced by row-level security. Every read and download of a document is audited
  (`PROFESSIONAL_EVIDENCE_READ`).
- **Retention.** No retention policy has been approved yet. Until one is, nothing is removed, as for
  deposit documents. Applying a policy will need its own migration, because the tables refuse
  deletion. *Completed by D18 (2 Nov 2026): retention metadata and legal holds, still no deletion.*

### 9. Audit

New actions:

- the lifecycle: `PROFESSIONAL_REQUESTED`, `_VERIFIED`, `_APPROVED`, `_REJECTED`, `_SUSPENDED`,
  `_REINSTATEMENT_REQUESTED`, `_REINSTATED`, `_REVOCATION_REQUESTED`, `_REVOKED`,
  `_OFFICE_CHANGE_REQUESTED`, `_OFFICES_CHANGED`, `_EVIDENCE_READ`;
- `REGISTER_TITLE_LOOKED_UP`;
- `REGISTER_TRANSACTION_DEPOSITED` with `channel = PROFESSIONAL_ACCOUNT`, the account and the
  capacity;
- refusals as `REGISTER_REFUSED` and `PROFESSIONAL_REFUSED`, with their codes.

Deposit events take the title as their subject (`LAND_TITLE`, ADR-038). Account events take none.

## Slice 3 scope and acceptance

**Delivered:** §1 to §9; the professional's screens (request, status and enrolment, title lookup,
deposit with documents, "my deposits"); the administrators' screens (queue, verify, approve, reject,
suspend, reinstate, revoke, offices); conservators seeing the channel and the professional; a
synthetic DEMO seed of one active notary and one verified surveyor (the notary becomes active once
they have registered two security keys, step 6).

**Not delivered:**

- surveyor workflows (slice 4);
- professionals on applications (procedures);
- any inscription or registration authority for professionals;
- a unified identity (§2);
- a retention policy (§8);
- verification with a real professional body, or real professionals' data.

**Acceptance:**

- pgTAP: the lifecycle and its four-eyes rules; `holds_professional_at`; every forbidden permission
  and special grant refused for a professional; every staff function still false for a professional;
  professional deposits under the V21 rules; no lock from a refused deposit.
- Concurrency: professionals and conservators racing deposits on one title leave exactly one
  `HOLDING`.
- Integration tests:
  - every lifecycle transition and refusal, a suspended professional refused at the next request;
  - duplicate submissions;
  - a deposit at an unserved office, or on a title the professional holds;
  - document isolation between deposits;
  - the lookup revealing nothing more.
- The permission matrix covers every endpoint for every role, professionals included.
- e2e: a notary requests an account and enrols keys, A verifies, B approves with an office, the
  notary deposits with a deed, a conservator registers the transfer, and the notary sees it
  registered.
- CI is green, and no existing test is weakened.

## As built in step 2 (1 Nov 2026)

- **A module of its own, `professional`.** The lifecycle needs `document.DocumentFiles` for evidence,
  and `document` already depends on `identity`, so it cannot live in `identity` without a cycle.
  `identity` publishes `AccountRegistration`, so the professional's password is still peppered and
  hashed there.
- **Four administration permissions**, held by SYSTEM_ADMIN: `admin.professional.read`, `verify`,
  `approve` and `suspend`. Reading is separate, so a custom role can follow the queue without
  deciding. Evidence is still readable only by verifiers and approvers (row-level security), never by a
  reader-only role.
- **The verifier proposes the offices; the approver decides.** So the initial offices also have two
  administrators, as D15 asks of every office change.
- **One open request per professional.** It avoids two contradictory changes waiting at once.
- **Expiry.** An expired request is closed in its own transaction, audited, and an expired activation
  sends the professional back to `REQUESTED`.
- **Lost keys.** A reset may target a professional and is confirmed by a supervisor of one of their
  offices. Two V10 functions changed for this: `mfa_reset_request_insert` (it tested for staff, which
  the step 1 review missed) and `mfa_reset_request_decide` (which reads the offices). Both read
  professionals through owner-run functions, `is_professional` and `professional_office_ids`, since
  neither the requester nor the supervisor may read the professional tables.
- **Status page.** A professional sees their status after enrolling their keys (a full session), not
  before.
- **Audit actions** as listed in the data model chapter. `PROFESSIONAL_EVIDENCE_ADDED`,
  `PROFESSIONAL_CHANGE_REJECTED` and `PROFESSIONAL_REQUEST_EXPIRED` were added to §9's list.

## As built in step 3 (1 Nov 2026)

- **Computed roles.** `role_definition.professional` marks NOTARY and SWORN_SURVEYOR. They are system
  roles, never assignable. A trigger refuses them in `role_assignment` and `role_assignment_request`,
  for everyone, the superuser included, which also closes the self-registration path.
- **Where the session gets them.** For a professional, `AuthorizationService` takes the roles from
  `professional_grants()`: the profession at each approved office of an `ACTIVE` profile. It does not
  read `role_assignment`. Since the session is rebuilt on every request, a suspension, revocation or
  office change applies at the next request.
- **One catalogue rule each way.** `permission.professional` marks the four permissions, which must be
  `BUSINESS` and not delegable, so no special grant can carry them. A trigger keeps professional
  permissions out of every other role, and staff permissions out of professional roles.
  `PermissionCatalogueCheck` compares the flag with the code. The custom-role rules refuse a
  professional permission before the database does (`PERMISSION_PROFESSIONAL_ONLY`).
- **The database function.** `holds_professional_at(user, permission, office)` is true only for an
  `ACTIVE` professional whose profession holds the permission: at an approved office for `submit`, or
  without an office for the three `own` permissions. Step 4's deposit function uses it.
- **Tests.** pgTAP `26`; `ProfessionalPermissionsIT`. `PermissionMatrixIT` checks that an active notary
  and an active surveyor are refused on every staff endpoint. Its staff loop now skips the two
  professional roles, because the database refuses giving them to staff.

## As built in step 4 (1 Nov 2026)

Migration V26; the professional channel lives in the register module (`ProfessionalDepositService`,
`ProfessionalDepositController`), beside the counter, and shares its document and refusal code.

- **The lookup (§6).** `GET /api/v1/professional/titles?number=` answers four fields: the title's id,
  its number, its office, and whether it accepts a deposit. An unknown number, a title at an office not
  served, a surveyor and a suspended notary all get the same `404 TITLE_NOT_FOUND`. Each lookup is
  audited as `REGISTER_TITLE_LOOKED_UP`, found or not, with the capacity and the number asked. It is
  throttled per account, not per address: `SNIGF_RATE_LOOKUP_PER_HOUR` (60), then `429`.
- **One deposit, one call (§7).** `POST /api/v1/professional/deposits` is one multipart request: the
  title, the kind, the client, and the files with a category each. The files pass the ADR-033 checks
  and are kept first. Then `transaction_deposit_professional` records the transaction and its documents
  together. A missing `DEED`, a refused file, an unknown category, an unserved office, a closed title,
  an invalid client, the holder rule or a duplicate refuses the whole deposit. Nothing is recorded,
  nothing is locked, the kept files are forgotten, and the refusal is audited.
- **The size of one deposit.** All its files travel in one request, so together they stay within
  `SNIGF_PROXY_MAX_BODY_BYTES`, and there are at most 20 of them. More documents are added afterwards,
  one per request, through the same function as a conservator's (`deposit_document_add`). The proxy
  limit was not raised.
- **Who is recorded.** `submitted_by_account` and `submitted_capacity` are new columns. `received_by`
  is now nullable. `ck_transaction_channel` ties `received_by` to `COUNTER` and the account, the
  capacity and the professional's party to `PROFESSIONAL_ACCOUNT`. The guard freezes the new columns
  with the rest.
- **Parties.**
  - *The professional's party:* their `NOTARY` party at the title's office, linked to their account.
    If they have none there, it is created from the profile: chamber, registration number,
    jurisdiction. A display name without "DEMO" is prefixed with it, because parties hold synthetic
    data only.
  - *The client:* a professional cannot read the office's parties. They declare the client as kind,
    name and identity, and the deposit records the client as a party of the office. A party is reused
    only when every field matches exactly, and the answer is the same either way.
- **Duplicates.** An `Idempotency-Key` retry returns the same deposit. Another request by the same
  professional, on the same title, with a deed of the same SHA-256 as their open deposit, is refused
  with `DUPLICATE_DEPOSIT` (409). Another notary depositing the same deed is recorded in conflict.
- **What the professional reads, decided in this step.** Their own deposits only, through
  `professional_deposits` and `professional_deposit_documents`. The register's tables keep their
  staff-only row-level security, and the functions check the actor themselves. An open deposit reads
  `OPEN`, never `HOLDING` or `IN_CONFLICT`: telling a notary that the title is already held would tell
  them a sale is in progress (§6). Conservators see the real status, the account and the capacity. A
  file a conservator added shows as not uploaded by the professional, without the name.
  **For review:** whether a professional should see that their deposit is in conflict. *Decided by
  D17 (2 Nov 2026): they do.*
- **Withdrawal and documents.** `transaction_close` and `deposit_document_add` gain the professional's
  branch: their own deposit, while they hold the permission. They withdraw only, and never refuse.
  Every other rule is unchanged, the holder rule and the 100-document limit included. Conservators
  refuse, resolve and register professional deposits exactly as counter deposits.
- **Tests.**
  - pgTAP `27` (48 assertions).
  - `db-test.sh`: conservators and a notary's account race on one title, ending with 1 holding and
    199 in conflict, each professional deposit with its deed.
  - `ProfessionalDepositIT` (8 tests): the lookup, throttling, valid and refused deposits, retries and
    duplicates, isolation, withdrawal, the endpoint boundary and suspension.
  - `PermissionMatrixIT`: the seven professional endpoints refuse every staff role and the surveyor,
    and the notary reaches exactly its own.

## As built in step 5 (2 Nov 2026)

A new front-end feature, `features/professionals`, and the conservator's title page. No back-end
change: the screens call the endpoints of steps 2 and 4, and every control is checked again by the
server and the database.

- **The public request.** `#/professional-request`, also linked from the sign-in screen ("Notary or
  sworn surveyor?"). It asks the profession, the account and the credential, and answers the same
  whether or not the address was new. The screen is loaded only when asked for.
- **The professional's page, `#/professional`.** In the menu of every `PROFESSIONAL` account, whatever
  its status, and of no other. It shows the credential, the status in words, the offices and the
  history. An active notary also gets:
  - *the lookup:* the number, the office and whether it accepts a deposit, nothing else. An unknown
    number and a title elsewhere get the same sentence;
  - *the deposit:* the kind, the client (kind, name, identity), and the files with a category each,
    the first one a deed by default. The form refuses to send without a deed, with more than 20 files,
    or with a client whose name or number lacks "DEMO"; the server checks it all again. It confirms
    first, and keeps one `Idempotency-Key` per deposit being prepared, so a retry after a lost answer
    returns the same deposit;
  - *their deposits:* a table, then one deposit's page with its documents (add, replace with a
    reason, download; a file added by the conservation is shown as such, without the name) and the
    withdrawal with a reason while it is open.

  The page reads the status again when the session's permissions change, so an approval or a
  suspension never leaves a stale status beside the controls. A sworn surveyor who is active sees
  that they hold no operational permission yet.
- **The administrators' page, `#/professionals`.** The requests waiting for a second administrator,
  then the accounts by status, 50 a page. One account's page has the verification form (body,
  reference, date, note, offices) while it is requested; the verification with its evidence, and
  the evidence upload for its verifier while the activation is undecided; the requests, with approve
  and reject for anyone but their requester; suspension with a reason for an active account; and
  the changes for another administrator (reinstate, revoke, change offices), offered by status.
- **Conservators** see each deposit's channel, and for a professional one the account's name and
  capacity instead of a receiving officer.
- **Tests.**
  - Vitest: `professionals.test.tsx` (16 cases): the form's rules, the request, the status page per
    status, the lookup that reveals nothing, the deposit in one request with one key, a closed title,
    the own deposits, withdrawal, isolation, the four-eyes queue, verification, suspension, a
    reader's page. `register.test.tsx`: the channel on the title page. `navigation.test.ts`: who sees
    the two places.
  - e2e: `e2e/tests/professionals.ts`, the acceptance journey above, in the `flows` project.

## As built in step 6 (2 Nov 2026)

`infra/scripts/dev-seed-professionals.sh`, beside `dev-seed-demo.sh` and `dev-seed-register.sh`:
refused unless `SNIGF_PROFILE=dev`, never a migration. No change to the application or the schema.

- **The cast.** `notary.yde@demo.invalid` (NOTARY) and `sworn-surveyor.yde@demo.invalid`
  (SWORN_SURVEYOR), both proposed for YDE1, with DEMO chambers and registration numbers
  (`DEMO-N-YDE-SEED`, `DEMO-GE-YDE-SEED`). Random passwords, kept in `infra/secrets/demo_accounts`.
- **The real workflow.** The request goes through `POST /api/v1/professional-requests`. The
  verification and the approval call `professional_verify` and `professional_decide` with
  `snigf.actor_id` set to `admin1`, then `admin2`. The application calls the same functions, so the
  database checks the permissions, the four eyes, the note, the offices and the two keys itself. The
  history names the real actors. The audit trail records `PROFESSIONAL_VERIFIED` and
  `PROFESSIONAL_APPROVED` as the service `dev-seed-professionals`, with the administrator in
  `details.asAdministrator`, as `dev-seed-demo.sh` records its roles.
- **The keys are not bypassed.** An activation needs two registered security keys (§5), and the
  script holds none. The first run leaves the notary verified and says so. Once the notary has signed
  in and registered two keys, a second run approves the activation. The surveyor is left verified:
  its activation waits in the queue for `admin2`, which shows the four eyes on screen.
- **Idempotent.** It requests only missing accounts, verifies only a `REQUESTED` profile, and approves
  only the notary's pending activation. Any other status, set by hand, is left as it is.
- **Checked.** It was run twice on the development stack: the second run added no account, no
  request and no audit event. The approval branch was exercised inside a transaction that was rolled
  back, with two throwaway credentials: the notary became `ACTIVE` at YDE1, with the history
  `REQUESTED` (the notary), `VERIFIED` (`admin1`), `ACTIVATION_APPROVED` (`admin2`), and a further
  pass changed nothing.

## As built in step 7 (2 Nov 2026)

Documentation only.

- **This ADR is Accepted.**
- **The amendments below are applied**, as dated notes in ADR-016, 023, 024, 025, 026, 028, 033, 038
  and 040; accepted ADRs are not rewritten.
- **The guide** is [`docs/guides/professional-accounts.md`](../guides/professional-accounts.md).
- **The manual:**
  - chapter 2: the module rows;
  - chapter 3: a control row and two known limits;
  - chapter 4: the migrations and tables, already there;
  - chapter 5: the request limit and time to live;
  - chapter 7: the tests;
  - chapter 8: the seed and the error codes;
  - chapter 10: the screens.
- **Also updated:** the land register and demo accounts guides, the README and the CHANGELOG.
- **The permission matrix** already held the eight permissions and the two roles, since step 3.

## Verified in step 8 (2 Nov 2026)

Every suite CI runs, run locally on a rebuilt stack. No code or schema changed in this step.

| Suite | Result |
| --- | --- |
| Backend (`mvnw verify`) | 190 unit and 190 integration tests, none failed or skipped. Among them: `ProfessionalLifecycleIT` 7, `ProfessionalPermissionsIT` 5, `ProfessionalDepositIT` 8, `ProfessionalSignInIT` 3, `PermissionMatrixIT` 3, `MfaFlowIT` 10, `RegisterIT` 19 |
| pgTAP (`dbtest`) | 27 files, 642 tests, `24` to `27` for this slice |
| Concurrent audit chain | 8 clients x 250 transactions, about 10% rolled back: contiguous and valid |
| Racing deposits on one title | 8 clients x 25, conservators and a notary: one holding, 199 in conflict, each of the 99 professional deposits with its deed |
| Frontend | lint and structure check, 122 unit tests, type-check and build |
| Security probe (ADR-017) | 38/38 |
| Segmentation probe (ADR-020) | 4/4 |
| End-to-end, Chromium | 37 passed, 1 skipped (the design tour, run only with `E2E_TOUR=1`); the four professional journeys among them |
| Secret scan (gitleaks) | clean on the history, and on the 131 changed and new files not yet committed |
| Development audit chain after the run | verified, 12,625 rows |

The `AUDIT CHAIN VERIFICATION FAILED` lines in the backend log come from `AuditChainIT` and
`AuditApiIT`, which break the chain on purpose and expect it to be detected.

## Decisions of 2 Nov 2026 (D17 to D21)

The five open questions left after step 8 were answered by the project owner on 2 Nov 2026. Each
decision below gives the owner's words first, then how SNIGF applies it. Where SNIGF had to choose
how to read it, the reading is marked **(reading)**. The project owner confirmed the three readings
on 3 Nov 2026, with safeguards for D19 (see "Readings confirmed").

| # | Question | Decision |
| --- | --- | --- |
| **D17** | Should a notary see that their deposit is in conflict? | Yes, for their own deposit: its status, whether review is pending, and any action required from them. Never the competing party's identity, documents or confidential information |
| **D18** | Retention of verification evidence and deposit documents | No fixed period is invented. Retention follows an approved legal and administrative retention schedule. Until one is approved, nothing is deleted automatically. Retention metadata, restricted access, audit and legal holds are built now |
| **D19** | Replacing a document | Formally submitted evidence is never silently overwritten or deleted. Replacing it needs a second authorised person's approval. The original file, its hash, uploader, time, reason and version history are kept. A draft may be corrected by its uploader before formal submission, with an audit trail |
| **D20** | The administrators' directory order | Newest accounts first by default: creation time descending, with a deterministic tie-breaker; pagination, search and filters |
| **D21** | Dashboard counts | Pending professional-account requests, verifications awaiting action and approvals awaiting action, as separate counts. Each links to its filtered work queue, is shown only to those allowed to act on it, and counts pending items, never completed ones |

### D17: a notary sees their own deposit's conflict

**What changes.** Step 4 showed an open deposit as `OPEN` to its professional. From now on,
`professional_deposits` and `professional_deposit` show the professional the real status of
**their own** deposit, with three more fields:

| Field | Values | Source |
| --- | --- | --- |
| `status` | `HOLDING`, `IN_CONFLICT`, `REGISTERED`, `WITHDRAWN`, `REFUSED` | `pending_transaction.status` |
| `conflict` | `NONE`; `UNDER_REVIEW` while a conflict naming this deposit is open; `RESOLVED` once each has been decided | `transaction_conflict`, holding or arriving side |
| `reviewDueAt` | the date by which conservators review an open deposit | `review_due_at` |
| `actionRequired` | `NONE` today | see below |

- **Both sides of a conflict (reading).** A holding deposit that a later one conflicts with shows
  `conflict = UNDER_REVIEW` as well: its transfer cannot be registered until the conflict is decided
  (ADR-040), so its notary needs to know why it is waiting. Its status stays `HOLDING`.
- **`actionRequired`.** No workflow asks a professional anything yet, so it is always `NONE`. It
  exists so that a later step (for example a conservator asking for a document) adds a value, not a
  new field. A pending document replacement (D19) is shown with the document, not here.
- **Never shown to the professional**, whatever the status:
  - the competing deposit: its id, kind, depositor, notary, account, channel, dates and documents;
  - how many deposits compete;
  - the resolution's reason, evidence and conservator;
  - the conflict's id.

  A resolved conflict shows only its effect on their own deposit: kept `HOLDING`, now `HOLDING`
  (preferred), or `REFUSED`, with "refused by the conservation" and no reason text, because that text
  is written for conservators and may name the other party.
- **The lookup is unchanged.** §6 still never says whether a title is held or a deposit is pending. A
  professional learns about a conflict only by making a valid, attributed, audited deposit with a
  deed.
- **Risk: probing by deposit.** A notary could deposit to find out whether a title is held, then
  withdraw. That costs a valid deposit with a deed and a declared client, is audited and attributed,
  and opens a conflict that conservators see at once. In addition, a withdrawal while
  `conflict = UNDER_REVIEW` is audited with `"withdrawnDuringConflict": true`, so the pattern can be
  found in the audit trail.
- **Where.** V27 replaces the two functions. Their columns are listed explicitly, never `t.*`, so a
  later column cannot leak.

### D18: retention metadata and legal holds, no deletion

**No period is set, and nothing is deleted.** SNIGF holds no approved retention schedule, so it
stores none. The tables keep refusing `UPDATE` and `DELETE`, and no job removes these records or
their files.

- **Records covered:**
  - verification evidence: `professional_verification_document`;
  - deposit documents, of both channels: `deposit_document`;
  - the pending replacement files of D19.
- **Retention metadata.** A catalogue, `retention_class`: a code per kind of record, a description,
  and the reference and approval date of a schedule. Both stay empty until a schedule is approved,
  and a `CHECK` makes the class read **"no approved schedule: kept"** while they are. There is no
  period column: when a schedule is approved, its period, its trigger event and the disposal
  procedure need their own ADR and migration. Each record names its class (`NOT NULL`), set by the
  database on insert, never by the caller.
- **Legal holds.** `legal_hold`: the subject (one document, every document of a deposit, or every
  piece of evidence of a professional), a reason, who placed it and when.
  - One authorised person places a hold; releasing it is requested by one and approved by another,
    as §3 does for every change that restores or ends something.
  - A hold is never deleted. Its release is recorded beside it.
  - `record_disposal_allowed(record)` is the one function a future disposal must call. It is false
    while no approved schedule covers the class, and while any hold covers the record. Today it is
    therefore always false, and pgTAP checks both reasons separately.
- **Restricted access.** Three new permissions, never professional and never delegable:
  `records.retention.read` (classes and holds), `records.hold.place` and `records.hold.release`.
  **(reading)** SYSTEM_ADMIN holds the three, and AUDITOR holds `records.retention.read`. Reading
  retention data does not open the documents: downloads keep their own permissions.
- **Audit.** `RECORD_HOLD_PLACED`, `RECORD_HOLD_RELEASE_REQUESTED`, `RECORD_HOLD_RELEASED`,
  `RECORD_HOLD_RELEASE_REJECTED` and `RECORD_RETENTION_READ`, with the subject. Refusals are
  `RECORD_REFUSED`.
- **Orphan cleanup.** `document_storage_keys_known` also returns the pending replacement files, so
  the cleanup never removes a file that a record names (ADR-033).

### D19: replacing a submitted document needs a second person

**Which documents are formally submitted.**

| Documents | Submitted when | Today | From D19 |
| --- | --- | --- | --- |
| Deposit documents, professional channel | with the deposit (one request, §7) | the notary replaces at once, with a reason | the replacement is a **request**; a conservator of the office approves it |
| Deposit documents, counter channel | when a conservator adds them | a conservator replaces at once, with a reason | a request; **another** conservator of the office approves it |
| Verification evidence | when added by the verifier | never replaced: only added | unchanged; pgTAP holds that no replacement path exists |
| Application documents (ADR-033) | when the application is submitted | drafts: the owner adds versions. After submission: new versions only in the categories an intake officer asked for (ADR-034) | **(reading)** unchanged; see below |

- **The request.** The new file passes the ADR-033 checks and is kept first, as today. A row of
  `deposit_document_replacement` records:
  - the document to replace and the new file's hash, size, type and name;
  - who asked, when, and the reason (10 to 2,000 characters).
- **The decision.** One database function, `deposit_document_replacement_decide`.
  - It refuses the requester.
  - It refuses a professional.
  - It refuses anyone without `register.confirm` at the deposit's office.
  - On approval, it adds the `deposit_document` row that names the original (`replaces_id`), with the
    reason and the approver. The original is never changed.
  - On rejection, it records the decision and a reason, and the file is kept (D18).
  - The requester may withdraw a pending request.
- **While it waits.** The original stays the current document. The deposit's transfer cannot be
  registered while a replacement is pending (`REPLACEMENT_PENDING`, 409), so what a conservator signs
  never changes underneath them. Approval takes the title's lock, as `deposit_document_add` does, so
  an approval during a signature still makes that signature fail (ADR-040).
- **History.** For each document, the API lists every version: the original, each approved
  replacement, and each pending, rejected or withdrawn request. For each it gives the hash, uploader,
  time, reason and decision.
- **Adding a document** that replaces nothing is unchanged: it is not a replacement.
- **Application documents (reading).** After submission, an applicant can add a version only in a
  category that an intake officer asked for, and the officer reviews completeness again before
  accepting. That is already a second authorised person deciding, and every earlier version is kept.
  Before submission, the owner corrects their draft, and every upload is audited
  (`DOCUMENT_UPLOADED`). A deleted draft is audited (`APPLICATION_DRAFT_DELETED`), and its files are
  removed by the orphan cleanup. That matches "draft documents may be corrected by their uploader
  before formal submission", and D18 does not cover application drafts. So no change, unless the
  project owner wants the officer to approve each new version separately.

### D20: newest accounts first, searchable

`GET /api/v1/admin/professionals`:

- **Order.** `requested_at DESC, user_id DESC`. The account is created with its request, so
  `requested_at` is its creation time. The UUIDv7 id breaks ties deterministically.
- **Pagination.** `page` and `size` (1 to 100, default 50). The answer adds the total, so the screen
  shows "page x of y".
- **Search.** `q` matches the e-mail, the display name, the chamber and the registration number,
  ignoring case and accents. It needs 2 to 100 characters. The term is passed as a parameter and
  escaped for `LIKE`, never concatenated.
- **Filters.** `status`, `profession`, `office` (an office where the account is approved or proposed),
  and `pending` (`VERIFICATION`, `ACTIVATION` or `CHANGE`, for D21's links). They combine with `AND`.
- **Unchanged.** It still needs `admin.professional.read`, and it never shows evidence. The queue of
  waiting requests stays oldest first: it is a work queue, not the directory.

### D21: counts on the dashboard, one per queue

`GET /api/v1/admin/professionals/counts`, counted exactly in the database rather than as the length
of a first page.

| Count | What it counts | Shown to | Link |
| --- | --- | --- | --- |
| **Account requests** | profiles `REQUESTED`, waiting for a verification | holders of `admin.professional.verify` | the directory, `pending=VERIFICATION` |
| **Verifications awaiting a decision** | `VERIFIED` profiles whose activation request is pending, and that the viewer neither verified nor requested | holders of `admin.professional.approve` | `pending=ACTIVATION` |
| **Approvals awaiting action** | pending reinstatement, revocation and office-change requests not made by the viewer | holders of `admin.professional.approve` | `pending=CHANGE` |

- **Pending only.** Approved, rejected, expired and withdrawn requests, and active, suspended and
  revoked accounts, are never counted.
- **Four eyes on screen.** A request the viewer made, or a verification they recorded, is not counted
  as waiting for them, since they may not decide it. It appears in a separate line, "waiting for
  another administrator", which is informational and has no action.
- **Permissions.** The server leaves out every count the viewer may not act on. It does not send a
  zero, so the absence reveals nothing to the viewer and the screen shows nothing. A holder of
  `admin.professional.read` only gets no count. A professional gets `403`. The linked queues check
  their own permissions again.

## As built in step 10 (2 Nov 2026): D17

Migration V27 replaces `professional_deposits`, the one function behind the list and the detail. No
table changed.

- **What it returns.** The real status; `conflict` from every conflict naming the deposit, on the
  holding or the arriving side (`UNDER_REVIEW` while any is open, `RESOLVED` once each is decided,
  `NONE` otherwise); `review_due_at` while the deposit is open; `action_required`, always `NONE`. The
  columns are listed one by one. pgTAP `28` and `ProfessionalDepositIT` compare them with an
  allow-list, so a column added later fails both until it is reviewed.
- **A gap closed.** `conflict_resolve` (V21) copies the conservators' reason into the refused
  deposit's `closed_reason`, and step 4 showed that column to the professional. A conflict decision's
  reason may name the other deposit, so V27 no longer shows it. That is the case when a resolved
  conflict naming the deposit has the closing conservator and the same reason. The screen says
  "refused by the conservation when deciding a conflict". A refusal outside a conflict decision keeps
  its reason, as before: conservators write it for the depositor.
- **A withdrawal after a conflict.** Withdrawing does not resolve the conflict (V21), so the deposit
  reads `WITHDRAWN` with `conflict = UNDER_REVIEW` until a conservator decides it. The application
  reads the deposit after the withdrawal, in the same transaction, and records
  `"withdrawnDuringConflict": true` or `false` in `REGISTER_TRANSACTION_WITHDRAWN`.
- **The screens.** The professional's list shows "Under review by the conservation" beside the status.
  The deposit's page shows a warning while the conflict is under review, then the conflict's state,
  the review date and "action required from you: none". It never names the other deposit.
- **Unchanged.** The lookup (§6); the professional's documents, still their own deposit's only; the
  conservators' screens; every rule of the register.
- **Tests.**
  - pgTAP `28` (29 assertions): the allow-list; holding and arriving sides; two conflicts on one
    holding deposit; a resolution's reason hidden and still recorded for conservators; a plain refusal's
    reason shown; preferring the arriving deposit; a withdrawal during a conflict; the lookup
    unchanged; suspension. `27` now expects the real status.
  - `ProfessionalDepositIT` (10 tests, 2 new): the field allow-list on the list and the detail; both
    sides under review without the other's id or account; the resolution's reason withheld; the audit
    flag, `true` during a conflict and `false` outside one.
  - Vitest (18): the list with a conflict, the deposit under review, and a refusal by a conflict decision.
  - e2e: a counter deposit conflicts with the notary's; the notary sees it under review and nothing of
    the other deposit; a second conservator decides; the notary sees it resolved.

## As built in step 11 (3 Nov 2026): D20

`GET /api/v1/admin/professionals` now answers a page: `items`, `total`, `page` and `size`.

- **Order.** `requested_at DESC, user_id DESC`. The account and its profile are created in one
  transaction, so `requested_at` is the account's creation time. The UUIDv7 id breaks a tie the same
  way every time.
- **Pagination.** `page` (0 to 10,000) and `size` (1 to 100, default 50). The total is counted with
  the same conditions, so the screen shows "page x of y". A page past the end is empty, not an error.
- **Search.** `q`, 2 to 100 characters once trimmed; a blank `q` is no search. It matches the e-mail,
  the display name, the chamber or the registration number. Migration V28 adds `search_fold`, an
  immutable function that lower-cases and maps accented Latin letters to their base letter (and `œ`,
  `æ`, `ß` to two letters), applied to the term and to each column. The match uses `position()`, not
  `LIKE`, so `%` and `_` match only themselves. The term is a bound parameter.
- **Filters.** `status`, `profession`, `office` (approved, or proposed by a pending request) and
  `pending` (`VERIFICATION`: a `REQUESTED` account; `ACTIVATION`: a pending activation; `CHANGE`: a
  pending reinstatement, revocation or office change). They combine with `AND`. A pending request
  older than `SNIGF_ADMIN_REQUEST_TTL` counts as expired here, as the queue would close it.
- **Refusals.** Each value outside its bounds or its list is refused with `422` and its own code
  (`SIZE_OUT_OF_RANGE`, `PAGE_OUT_OF_RANGE`, `SEARCH_INVALID`, `STATUS_INVALID`, `PROFESSION_INVALID`,
  `OFFICE_INVALID`, `PENDING_INVALID`), never widened or ignored.
- **Unchanged.** `admin.professional.read` and row-level security (V24) as before; no evidence in the
  answer; the queue of waiting requests still oldest first, since it is a work queue.
- **The screen.** A search, the office code, and status, profession and "waiting for" filters, kept in
  the address (`#/professionals?q=...&pending=ACTIVATION`), so a reload and D21's links keep them; the
  total, "page x of y", Previous and Next. A search shorter than two characters is not sent.
- **Tests.**
  - `ProfessionalLifecycleIT` (4 new): newest first, a tie on `requested_at` broken by the id, pages
    with the total; each filter, combined, and an expired request no longer pending; the search across
    case, accents, ligatures, the e-mail and the chamber, with `%` and `_` taken literally (checked
    against a `LIKE` version of the query, which the test rejects); every bound refused with `422` and
    its code; a professional, an auditor and an intake officer refused with `403`.
  - pgTAP `29_search_fold` (11 assertions): the folding, wildcards kept, immutable, callable by the
    application only.
  - Vitest (4 new): the query string, values from the address checked, the total and the page of
    pages, a short search not sent, filters written to the address.
  - e2e: the notary's request heads the directory, and a search finds it.

## As built in step 12 (4 Nov 2026): D21

`GET /api/v1/admin/professionals/counts` answers the counts for the signed-in administrator, counted
exactly in the database (one query, under row-level security). No table, function or permission
changed, so there is no migration.

| Field | What it counts | Sent when the viewer holds | Tile links to |
| --- | --- | --- | --- |
| `requests` | profiles `REQUESTED` | `admin.professional.verify` | `#/professionals?pending=VERIFICATION` |
| `activations` | pending activations of `VERIFIED` profiles, neither requested nor verified by the viewer | `admin.professional.approve` | `?pending=ACTIVATION` |
| `changes` | pending reinstatements, revocations and office changes not requested by the viewer | `admin.professional.approve` | `?pending=CHANGE` |
| `waitingForOthers` | the viewer's own pending requests and verifications | `admin.professional.verify` or `admin.professional.suspend` | none: a line for information |

- **Permissions.** The endpoint needs `admin.professional.read`, like the directory its tiles open.
  Each count is decided by `holds_permission` in the database, as `professional_decide` decides. A
  count the viewer may not act on is left out of the answer, not sent as zero. A reader only gets
  `{}`, and the screen does not ask. A professional, an auditor or an intake officer gets `403`. The
  administration permissions cannot be delegated by special grant, so a count never uses one.
- **Pending only.** Approved, rejected and expired requests are never counted. A request older than
  `SNIGF_ADMIN_REQUEST_TTL` is not counted either, though nothing has closed it yet, as in the
  directory's `pending` filter.
- **The links.** Each tile opens the directory filtered on its step (D20). The directory lists every
  account at that step, including those the viewer may not decide; its queue still refuses their
  decision (`FOUR_EYES`).
- **Unchanged.** Reading the counts is not audited, like reading the directory; every decision is
  audited as before.
- **Tests.**
  - `ProfessionalLifecycleIT` (1 new): the fields per role (a full administrator, a verifier, an
    approver, a reader only with `{}`); a public request counted; one's own verification and change
    request not counted but in `waitingForOthers`, and counted for another administrator; an approved
    and a rejected request no longer counted; an expired request not counted; outsiders refused with
    `403`. Checked against a version of the query that counts one's own requests, which the test
    rejects.
  - `PermissionMatrixIT`: the new endpoint.
  - Vitest `dashboard.test.tsx` (4): a tile for each count returned and its link; nothing for a count
    left out; "nothing waiting" with one's own line; nothing asked for a reader only.
  - e2e: the verifier sees their verification waiting for another administrator; the approver opens
    the activations from the tile and finds the notary.

## Readings confirmed (3 Nov 2026)

The project owner confirmed the three readings.

| # | Reading | Decision |
| --- | --- | --- |
| 1 | D17: the holding side of a conflict also sees `UNDER_REVIEW` | **Confirmed.** Neither side sees the competing party's identity, documents or confidential information (built in step 10) |
| 2 | D18: SYSTEM_ADMIN places and releases holds, AUDITOR reads retention data | **Confirmed.** A system administrator places a hold; releasing it needs two distinct authorised people; auditors read retention records and the hold and release history, read-only; every action audited (step 14) |
| 3 | D19: for application documents, the intake officer's information request is the second person | **Confirmed with safeguards**, below (step 13) |

**The D19 safeguards for application documents**, in the owner's words: a formal information
request from an authorised intake officer, different from the original uploader, can count as the
second-person control for replacing an application document. The request must identify the document
and the reason, and the replacement must be linked to it. The original document, its hash, uploader,
timestamps and complete replacement history are kept, with no silent overwrite or deletion.

Today an ADR-034 information request names categories, not a document, and a new version is not
linked to the request that allowed it. Step 13 therefore also covers application documents:

- the request names each document it asks to be replaced, with the reason;
- the database refuses a replacement version unless an open request names that document, and refuses
  the request when its officer uploaded the document;
- the new version records the request that allowed it;
- the version history lists each version with its hash, uploader, times, reason and request.

Versions added in a category without replacing a document stay as ADR-034 has them.

## Answers of the project owner (1 Nov 2026)

| # | Decision |
| --- | --- |
| **D11** | Immediate lock on a valid, attributable deposit only (§7); conflicts to conservators; no automatic expiry |
| **D12** | Accounts for notaries and sworn surveyors now; surveyors without operational permissions; neither with registration rights |
| **D13** | References, notes and documents, administrators only, reads audited, retention per the approved policy (none yet, §8) |
| **D14** | A unified identity preferred; separate accounts kept for now as a documented limitation, with capacity recorded on every action (§2) |
| **D15** | Deposits only at approved offices; office changes by two administrators, audited; enforced on the server |
| **D16** | Restricted lookup only (§6) |
| Sign-in | WebAuthn, unchanged; no authenticator app (ADR-024 stands) |
| Suspension | One administrator suspends; revocation, reinstatement and office changes need two |

## Staff-only checks reviewed (1 Nov 2026)

| Check | Where | Verdict |
| --- | --- | --- |
| `holds_permission`, `holds_permission_at`, `holds_grant_at`, `holds_assignable` | V8, V10, V12, V16 | **unchanged**: still `STAFF` only; professionals use `holds_professional_at` |
| `require_staff` (role assignment target and approver) | V8 | **unchanged**: professionals never receive `role_assignment` |
| Special grants (`SpecialGrantService`, `AuthorizationService`) | Java | **unchanged**: staff only |
| Role administration target (`AccountAdministrationService.staff`) | Java | **unchanged** |
| Officer signatures (`OfficerSignatureService`) | Java | **unchanged**: staff only |
| Second factor (`SecondFactorService`, `LoginService`) | Java | **widened** in step 1 to `STAFF` or `PROFESSIONAL` (`AccountType.requiresSecondFactor`), with a test that applicants still have none |
| Supervised reset target (`SecondFactorResetService`, `JdbcMfaResetRepository`) | Java | **widened in step 2**: a professional's offices come from `professional_office`, read through `professional_office_ids` |
| `mfa_reset_request_insert`, `mfa_reset_request_decide` | SQL (V10) | **widened in step 2**: `require_staff` skipped for a professional with a profile (`is_professional`); the confirmer's offices include the professional's |
| Key guards and reset (`user_credentials_keep_last`, `mfa_reset_request_apply`, V10; `app_user_mfa_enrolment_guard`, V6) | SQL | **unchanged**: they never test the account type |

## Conflicts with existing ADRs and amendments

| ADR | Effect | Amendment (applied 2 Nov 2026, step 7) |
| --- | --- | --- |
| ADR-016, ADR-023, ADR-024 | WebAuthn is for staff; no TOTP | Professionals use WebAuthn with the same rules; ADR-024 unchanged |
| ADR-025 | Account types `APPLICANT` and `STAFF` | Permissions gain a `professional` flag; special grants stay staff-only |
| ADR-026 | Four eyes on role assignment | Extended to professional approval, reinstatement, revocation and office changes; suspension stays one-person |
| ADR-028 | Supervised reset for staff | Applies to professionals |
| ADR-033 | `DocumentFiles` for deposit documents | Also for verification evidence |
| ADR-038 | Audit subjects | Professional deposits keep `LAND_TITLE` |
| ADR-040 | §4.7 counter deposits "until slice 3"; §6 | Counter deposits **remain** beside the professional channel; §6 implemented as above |

**Conflicts of D17 to D21 (2 Nov 2026).** Dated notes are to be added to each ADR below in step 15,
when the behaviour is built. None of these decisions weakens a control.

| ADR | Today | D17 to D21 | Resolution |
| --- | --- | --- | --- |
| ADR-041 §7, step 4 | an open deposit reads `OPEN` to its professional | **D17** shows `HOLDING` or `IN_CONFLICT` and the conflict's state | the professional's own deposit only; §6's lookup unchanged; the competing deposit never shown |
| ADR-040 §4.7 | a conservator replaces a deposit document alone, with a reason | **D19** needs a second conservator | amended: a replacement request, approved by another conservator holding `register.confirm` |
| ADR-041 §4, §7 | `register.deposit.document` lets a notary replace their own document | **D19**: a conservator approves it | the permission now lets the notary *ask* for a replacement; its catalogue description changes, the permission does not |
| ADR-040 §4.7 (signature binding) | a transfer's bytes name every document | **D19** adds pending replacements | a transfer is refused while one is pending; the bytes are unchanged, so earlier signatures still verify |
| ADR-040 §4.7, ADR-041 §8 | "no retention period; nothing removed; a rule needs its own decision" | **D18** confirms, and adds metadata and holds | consistent; any disposal still needs its own ADR and migration |
| ADR-033 | the orphan cleanup deletes files no record names | **D18**: nothing deleted automatically | `document_storage_keys_known` also returns pending replacement files; application drafts are outside D18 |
| ADR-033, ADR-034 | versions after submission only in categories an officer asked for | **D19** | read as already satisfying D19 (open question 3) |
| ADR-025 | the permission catalogue | **D18**: three `records.*` permissions | added to the catalogue, the code and `PermissionCatalogueCheck`; never professional, never delegable |
| ADR-026 | four eyes enforced by the database | **D18** (hold release) and **D19** (replacement) | the same pattern: the requester never decides, and the database refuses otherwise |
| ADR-038 | audit subjects | **D18**, **D19** | deposit-document events keep `LAND_TITLE`; holds on evidence take none, like account events |
| ADR-041 step 5 | the directory is oldest first; the dashboard counts lists of up to 50 | **D20**, **D21** | newest first; exact counts from a dedicated endpoint |

## Alternatives considered

- **Professionals as staff with a restricted role.** Least new code, but one role change by an
  administrator pair could give a notary register rights. A separate type, and a separate permission
  function, let the database refuse that outright. Rejected.
- **Professionals as applicants with a flag.** Applicants have no second factor. Signing in with a
  password alone is too weak for an actor who locks titles. Rejected.
- **One account with capacities now (D14).** Preferred, but it re-decides every account-type check
  inside this slice (§2). Deferred to its own ADR.
- **A deposit locks only after a conservator acknowledges it.** It moves the priority date to a
  conservator's working hours, which defeats the lock. D11 chose immediate locking, but only of a
  valid deposit.
- **Authenticator-app codes for professionals.** Phishable, for accounts that lock titles. Rejected,
  confirmed on 1 Nov 2026.

## Implementation plan for Slice 3

Small steps; each ends with its tests green and is reported for review.

| # | Step | Content | Tests |
| --- | --- | --- | --- |
| 1 | **Account type and second factor** | V23: `PROFESSIONAL` in `account_type`; `professional_profile`. Second-factor checks widened (table above). Nothing else | pgTAP `24_professionals`: type and profile `CHECK`s; every staff function false for a professional; `MfaFlowIT` for a professional; applicants still have no second factor; all existing suites unchanged |
| 2 | **Lifecycle and four eyes** | Request (public, throttled), verify with evidence, approve with offices, reject, suspend, reinstate, revoke, office changes; functions owned by the schema owner, as V8; `professional_office`; evidence tables, administrators only | pgTAP: every transition and its four-eyes rule; the professional never approves; two keys before approval; expiry. `ProfessionalLifecycleIT`, including evidence access audited and refused to others |
| 3 | **Permissions** | The four permissions with the `professional` flag; `NOTARY`, `SWORN_SURVEYOR`; `holds_professional_at`; Java authorities from profile, profession and offices | `RoleSeedIT`, `PermissionMatrixTest`, `PermissionMatrixIT` with professionals; pgTAP: every forbidden permission and special grant refused; suspension effective at the next request |
| 4 | **Lookup and deposits** | §6 lookup; `transaction_deposit_professional` (documents and transaction in one call); `submitted_by_account`, channel `CHECK`s; duplicates; withdrawal; own documents only | pgTAP: no lock from a refused deposit, holder rule, offices; `db-test.sh` race of professionals and conservators; `RegisterIT`: duplicates, unserved office, document isolation, lookup |
| 5 | **Screens** | Professional, administrator and conservator screens (scope above) | Vitest per view; the e2e journey of the acceptance |
| 6 | **Seed** | One active DEMO notary and one verified surveyor at YDE1, through the real workflow | idempotent |
| 7 | **Docs** (done) | This ADR to Accepted; the amendments; guide `docs/guides/professional-accounts.md`; manual chapters 02, 03, 04, 05, 08, 10; permission matrix; CHANGELOG; README | `DocumentationSyncTest` |
| 8 | **Full verification** (done locally; CI once pushed) | backend, pgTAP, frontend, stack rebuild, probes, e2e, secret scan; then CI | real results only |
| 9 | **D17 to D21 recorded** (done) | This ADR (revision 4), its index, the CHANGELOG | `DocumentationSyncTest` |
| 10 | **D17: conflict visibility** (done) | V27: `professional_deposits` and `professional_deposit` with the real status, `conflict`, `reviewDueAt`, `actionRequired`, explicit columns; the withdrawal's `withdrawnDuringConflict`; the notary's deposit page | pgTAP `28`: each status and conflict state, holding and arriving sides; no column of the competing deposit; a resolved conflict without its reason; another professional still sees nothing. `ProfessionalDepositIT`: the answer's exact field set (an allow-list, so a new field fails the test); the lookup unchanged. Vitest; the e2e journey with a second deposit |
| 11 | **D20: the directory** (done) | V28 `search_fold`; order, `size`, total, `q`, the filters; the screen's search, filters and "page x of y" | `ProfessionalLifecycleIT`: newest first, a tie on `requested_at` broken by id, each filter, the search (case, accents, `%` and `_` taken literally), bounds refused with `422`; a professional refused; pgTAP `29`; Vitest; e2e |
| 12 | **D21: the counts** (done) | `GET /api/v1/admin/professionals/counts`; the dashboard's three tiles and the informational line, each linking to its filter | IT: each count per role (verifier, approver, reader only, professional `403`), one's own request and verification not counted, completed requests never counted, an absent count rather than zero; Vitest: tiles only for what is returned, the links' filters |
| 13 | **D19: replacement with a second person** | V29: `deposit_document_replacement`, `_request`, `_decide`, `_withdraw`; both channels; `REPLACEMENT_PENDING` on registration; the version history in the API; application documents: the information request names the document and the reason, the replacement is linked to it, its officer never the uploader (confirmed reading 3); the screens for the requester and the conservator | pgTAP `30`: the requester, a professional, or a conservator elsewhere cannot decide; the original stays current until approval; rejected and withdrawn files kept; no `UPDATE` or `DELETE`; approval under the title's lock; no replacement path for evidence. `RegisterIT`, `ProfessionalDepositIT`: both channels, the history, a transfer refused while pending; `PermissionMatrixIT`: the new endpoints; `db-test.sh`: an approval racing a signature; Vitest; e2e |
| 14 | **D18: retention and holds** | V30: `retention_class` (no period, no schedule), the class on each covered record, `legal_hold` and its release request, `record_disposal_allowed`; the three permissions; the screens beside the evidence and the deposit's documents | pgTAP `31`: a class cannot claim a schedule without its reference and date; every covered record has its class, set by the database; a hold placed by one, released by two; `record_disposal_allowed` false without a schedule and false under a hold; no delete path, and the cleanup keeps pending files. IT: holds and retention reads audited; refused without the permission; `PermissionMatrixIT`, `PermissionCatalogueCheckTest` |
| 15 | **Docs** | the guide, the manual chapters, the dated notes in ADR-025, 026, 033, 038 and 040, the permission matrix, the README | `DocumentationSyncTest` |
| 16 | **Full verification** | backend, pgTAP, frontend, stack rebuild, probes, e2e, secret scan; then CI on the pushed branch | real results only |

### Risks

- **Widening second-factor checks.** Only those change; the table above lists each, and every widened
  check gets a test showing applicants unchanged and professionals still refused staff actions.
- **Lock abuse (D11).** Only a valid deposit with a deed locks. Every deposit is audited and
  attributed; conservators refuse at once; one administrator suspends; the overdue review still
  applies.
- **Personal data.** Credentials and evidence are readable by the administrators concerned only, reads
  audited, and synthetic in the MVP.
- **D17 leaks through a new field.** The functions list their columns explicitly, and the integration
  test compares the answer with an allow-list, so a field added later fails the build until it is
  reviewed.
- **D19 blocks a transfer.** A replacement left pending holds the registration. The conservator who
  would sign also sees it, and either decides it or asks another conservator to; the requester may
  withdraw it. A pending replacement never expires on its own, as deposits do not (D11).
- **D18 and storage growth.** Nothing is deleted, by decision. Storage is monitored as for the other
  documents (P-01). It is not a reason to delete.
