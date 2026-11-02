# ADR-041: External professional accounts: notaries deposit through their own account

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| **Accepted, revision 3, implemented for Slice 3** (decisions D11 to D16, and the sign-in and suspension answers, of 1 Nov 2026). Built in steps 1 to 6, documented in step 7 and verified locally in step 8 (2 Nov 2026); what differs from the decision is under "As built". CI runs once the branch is pushed. The questions left for review are listed under "Open questions". | 2026-11-02 | Project owner, Pythagore (FLYT) |

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
  pending. Exposing a pending deposit would tell a third party about a sale in progress.
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
  deletion.

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
  **For review:** whether a professional should see that their deposit is in conflict.
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

## Open questions

Left for the project owner's review. None of them weakens a control as built.

| Question | Today |
| --- | --- |
| Should a notary see that their deposit is in conflict? | No: an open deposit reads `OPEN` (step 4), because a conflict would tell them that the title is held |
| Retention of verification evidence and deposit documents | Nothing is removed until a policy is approved (§8) |
| Should replacing a deposit's document need a second person? | No: one person, with a reason; the original stays listed and downloadable (ADR-040 §4.7) |
| The administrators' directory order | Oldest first, 50 a page, with Previous and Next; the queue shows every waiting request |
| A dashboard count of professional requests waiting | Not built (step 5) |

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

### Risks

- **Widening second-factor checks.** Only those change; the table above lists each, and every widened
  check gets a test showing applicants unchanged and professionals still refused staff actions.
- **Lock abuse (D11).** Only a valid deposit with a deed locks. Every deposit is audited and
  attributed; conservators refuse at once; one administrator suspends; the overdue review still
  applies.
- **Personal data.** Credentials and evidence are readable by the administrators concerned only, reads
  audited, and synthetic in the MVP.
