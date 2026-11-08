# Professional accounts: notaries and sworn surveyors

*Copyright (c) 2026 FLYT. Author: Pythagore.*

> **DEMONSTRATION. SYNTHETIC CREDENTIALS ONLY.** Every chamber, registration number and client in
> this MVP is synthetic: registration numbers must contain `DEMO`, and so must a client's name and
> identity number. No real professional body is consulted. A deposit registers nothing: conservators
> decide, as for every deposit in the DEMO register.

## What and why

A notary used to be only a named party: a conservator recorded the deposit at the counter, and was the
only authenticated actor. Now a notary deposits through their own verified account, and SNIGF can
attribute the deposit to them. The guarantee is that no professional ever gets a right to decide,
register, inscribe, sign or administer anything:

- **A third account type, never staff.** `PROFESSIONAL` accounts fail every staff permission check by
  construction: the staff functions still require `STAFF`. Professionals have their own permission
  function, `holds_professional_at`, and their own office table.
- **One to verify, another to approve.** An administrator records how the credential was checked and
  proposes the offices; a second administrator approves. One administrator can suspend at once; two
  are needed to reinstate, revoke or change offices. The database enforces all of it.
- **Valid and attributable, or nothing.** A deposit is one request, with a deed. It is recorded whole,
  or refused whole and locks nothing. Then the register's own rules apply unchanged: one holding
  deposit per title, the others in conflict, conservators decide.
- **Need to know.** The title lookup tells a notary the number, the office and whether the title
  accepts a deposit, nothing more. A notary reads only their own deposits.

Decision: [ADR-041](../adr/041-professional-accounts.md) (D11 to D16), built on
[ADR-040](../adr/040-land-register.md) (the register),
[ADR-016](../adr/016-webauthn-staff-authentication.md) and
[ADR-023](../adr/023-webauthn-second-factor-implementation.md) (sign-in),
[ADR-026](../adr/026-approval-workflow-enforced-by-the-database.md) (four eyes in the database).

## Where

| What | Where |
| --- | --- |
| Account type and profile | `V23__professional_accounts.sql`; `identity`: `AccountType` (`requiresSecondFactor`), `AccountRegistration` |
| Lifecycle, four eyes, offices, evidence | `V24__professional_lifecycle.sql`; module `professional`: `ProfessionalService`, `ProfessionalController`, `ProfessionalRateLimiter`, `JdbcProfessionalRepository` |
| Permissions | `V25__professional_permissions.sql` (`holds_professional_at`, `professional_grants`); `Permission`, `BaselineRole` (NOTARY, SWORN_SURVEYOR); `AuthorizationService` |
| Lookup and deposits | `V26__professional_deposits.sql`, `V27__professional_conflict_visibility.sql`, `V28__search_fold.sql` (the directory's search); `register`: `ProfessionalDepositService`, `ProfessionalDepositController` |
| Screens | `frontend/src/features/professionals` (`#/professional-request`, `#/professional`, `#/professional/deposits/<id>`, `#/professionals`, `#/professionals/<userId>`); the channel on the title page, `features/register/TitlePage.tsx` |
| Tests | pgTAP `24_professionals`, `25_professional_lifecycle`, `26_professional_permissions`, `27_professional_deposits`, `28_professional_conflict_visibility`, `29_search_fold`, the racing deposits in `db-test.sh`; `ProfessionalSignInIT`, `ProfessionalLifecycleIT`, `ProfessionalPermissionsIT`, `ProfessionalDepositIT`, `PermissionMatrixIT`; `professionals.test.tsx`; `e2e/tests/professionals.ts` |
| Synthetic data | `infra/scripts/dev-seed-professionals.sh` ([demo accounts](demo-accounts.md#professional-accounts)) |
| Who may do what | [permission matrix](../permission-matrix.md) |

## How it works

### An account's life

| Step | Who | Endpoint | Status after |
| --- | --- | --- | --- |
| Request | the professional, signed out, throttled per address like registration | `POST /api/v1/professional-requests` | `REQUESTED` |
| Verify | administrator A (`admin.professional.verify`): body, reference, date, note, offices proposed | `POST /api/v1/admin/professionals/{id}/verification` | `VERIFIED`, with an `ACTIVATION` request |
| Evidence | A only, while the activation is undecided | `POST .../verification/documents` | unchanged |
| Approve or reject | administrator B (`admin.professional.approve`), never A, never the professional | `POST /api/v1/admin/professional-requests/{id}/approval` or `/rejection` | `ACTIVE` at the offices, or `REVOKED` |
| Suspend | one administrator (`admin.professional.suspend`), with a reason | `POST .../{id}/suspension` | `SUSPENDED` |
| Reinstate, revoke, change offices | requested by one administrator, decided by another | `POST .../{id}/requests`, then approval or rejection | `ACTIVE`, `REVOKED` (final), or new offices |

- **The request answers the same** whether or not the address was new, like registration.
- **The professional signs in like staff:** a password, then two security keys, recovery codes and the
  supervised reset ([authentication guide](authentication-and-sessions.md)). They see their status
  from their first full session, at `#/professional`.
- **An activation needs two registered keys.** The database refuses it otherwise
  (`SECURITY_KEYS_REQUIRED`).
- **One open request per account**, so two contradictory changes never wait at once.
- **Requests expire** after `SNIGF_ADMIN_REQUEST_TTL`. An expired activation sends the account back to
  `REQUESTED`, to be verified again.
- **The directory** (`GET /api/v1/admin/professionals`, D20) lists the newest account first, with
  the id breaking a tie, 50 a page (`size` 1 to 100) and the total. `q` (2 to 100 characters) searches
  the e-mail, name, chamber and registration number, ignoring case and accents; `%` and `_` are
  ordinary characters. `status`, `profession`, `office` and `pending` (`VERIFICATION`, `ACTIVATION`,
  `CHANGE`) filter it. A value out of bounds is refused with `422`. The screen keeps the filters in
  the address: `#/professionals?pending=ACTIVATION`.
- **The dashboard's counts** (`GET /api/v1/admin/professionals/counts`, D21): account requests to
  verify, verified accounts awaiting a decision, and changes awaiting a decision, each a tile opening
  the directory on that step. Pending items only. Each is sent only to those who may act on it, never
  as zero; one's own requests and verifications are not counted, but shown on a line "waiting for
  another administrator".
- **Every write is one `SECURITY DEFINER` function** that checks the actor's permission, the four eyes,
  the status and the reason itself. Its refusals are SQLSTATE `PR001` with a code; the service turns
  them into a precise answer and an audit event (`PROFESSIONAL_REFUSED`).
- **Evidence** is read only by verifiers and approvers (row-level security), and each read is audited
  (`PROFESSIONAL_EVIDENCE_READ`).

### Permissions

- **Computed, never assigned.** NOTARY and SWORN_SURVEYOR are system roles flagged `professional`. A
  trigger refuses them in `role_assignment` and in assignment requests, for everyone.
- **What a session holds.** For a professional, `AuthorizationService` reads `professional_grants()`:
  the profession's role at each approved office of an `ACTIVE` profile. The session is rebuilt on
  every request, so a suspension, a revocation or an office change applies at the next request.
- **Four permissions**, held by NOTARY only: `register.deposit.submit` (at an office),
  `register.deposit.read`, `register.deposit.document` and `register.deposit.withdraw` (own deposits).
  They are not delegable, so no special grant carries them, and a trigger keeps them out of every
  other role. SWORN_SURVEYOR holds none yet (D12).
- **The database checks again.** The deposit functions call `holds_professional_at(user, permission,
  office)`, true only for an `ACTIVE` professional whose profession holds the permission, at an
  approved office for `submit`.

### The lookup

`GET /api/v1/professional/titles?number=` answers four fields: the title's id, its number, its office
and whether it accepts a deposit. Never its holders, charges, restrictions, history, or whether a
deposit or conflict is pending: that would tell a third party about a sale in progress. An unknown
number, a title at an office the notary does not serve, a surveyor and a suspended notary all get the
same `404 TITLE_NOT_FOUND`. Each lookup is audited (`REGISTER_TITLE_LOOKED_UP`, found or not) and
throttled per account (`SNIGF_RATE_LOOKUP_PER_HOUR`).

### A deposit

`POST /api/v1/professional/deposits` is one multipart request: the title, the kind, the client (kind,
name, identity), and the files with a category each, with an `Idempotency-Key`.

1. Every file passes the checks of [ADR-033](../adr/033-documents-upload-and-storage.md) and is kept.
2. `transaction_deposit_professional` records the transaction and its documents together. It refuses
   the whole deposit without a `DEED`, for a refused file, an unserved office, a closed title, an
   invalid client, a title the notary holds an interest in, or the same deed on the same title while
   their earlier deposit is open. Then nothing is recorded, nothing is locked, and the kept files are
   forgotten.
3. A valid deposit follows the register's rules (V21): `HOLDING` if none holds the title, otherwise
   `IN_CONFLICT`, with a conflict for conservators.

The deposit records the account, the capacity and the notary's own party, and no receiving officer: a
`CHECK` ties `received_by` to the counter and the account to the professional channel. The client is
recorded as a party of the office, reused only when every field matches. More files are added later,
one per request; a file is replaced, never deleted. The notary can withdraw an open deposit, with a
reason. They never refuse, register or decide anything.

**A conflict (D17).** The notary reads their deposit's real status, `HOLDING` or `IN_CONFLICT`, and
whether a conflict naming it is `UNDER_REVIEW` or `RESOLVED`. Both sides see it: the holding notary's
transfer waits for the decision too. They never see the other deposit, its parties, documents or
number. A conflict decision's reason is the conservators' and is not shown: the page says the deposit
was refused by the conservation when deciding a conflict. A withdrawal while the conflict is under
review is recorded with `withdrawnDuringConflict` in the audit trail, so deposits made only to learn
whether a title is held can be found.

### Who sees what

| Who | Sees |
| --- | --- |
| The notary | their status, offices and history; the lookup's four fields; their own deposits and their documents. Each deposit's real status, whether a conflict naming it is under review or decided, the review date and the action required of them (none yet), never the other deposit (D17). A file the conservation added shows as such, without its name |
| A sworn surveyor | their status, offices and history, and a note that they hold no operational permission yet |
| Administrators | the queue of requests waiting for a second administrator, the accounts by status, one account's requests and history; the verification and its evidence for verifiers and approvers only |
| Conservators | each deposit's channel; for a professional deposit, the account's name and capacity, and the real status |

## How to do it again

```sh
bash infra/scripts/dev-seed-demo.sh            # YDE1, admin1, admin2, the conservators
bash infra/scripts/dev-seed-register.sh        # a DEMO title proposal; sign it as conservator.yde
bash infra/scripts/dev-seed-professionals.sh   # notary.yde and sworn-surveyor.yde, verified by admin1
```

1. Sign in as `notary.yde@demo.invalid` (password in `infra/secrets/demo_accounts`) and register two
   security keys. **My professional account** shows "Verified".
2. Run `dev-seed-professionals.sh` again: `admin2` approves the activation. Reload: the status is
   "Active", and the lookup and the deposit appear.
3. Look up the title's number, then deposit a sale with a PDF as the deed and a client whose name and
   number contain `DEMO`.
4. As `conservator.yde@demo.invalid`, open the title: the deposit shows the professional channel and
   the notary. Propose and register the transfer.
5. As the notary, the deposit now reads "Registered".
6. As `admin2`, open **Professionals** and approve or reject `sworn-surveyor.yde`. As `admin1`, the
   same request offers no button: they verified it.

By hand, without the seed: request an account at `#/professional-request`, then use the account's
page on **Professionals** as one administrator to verify, and as another to approve.

To give another profession permissions later, add them to the catalogue with the `professional` flag
and to the profession's role, in one migration with the code (`PermissionCatalogueCheck` compares
them), and have the database function behind each new endpoint check it with
`holds_professional_at`. Never give a professional a permission through a
staff role: the triggers refuse it, by design.

## Verify

```sh
docker compose --profile test run --rm --build dbtest   # pgTAP 24 to 27 and the racing deposits
cd backend && ./mvnw verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='Professional*IT,PermissionMatrixIT'
bash infra/scripts/e2e.sh                               # the journey in e2e/tests/professionals.ts
```

Expected: every suite green. The e2e journey covers the request, the two keys, the verification, the
verifier refused as approver (on screen and by the API), the approval, the lookup that reveals nothing
more, the deposit with a deed, the conservator's registration and the notary seeing it registered.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| After the approval, the notary's page still said "Requested" next to the deposit form | choosing the current place in the menu does not reload the page, so the status was stale while the permissions were new (found by the e2e run) | the page reads its status again whenever the session's permissions change |
| A reset for a professional was refused by the database | `mfa_reset_request_insert` tested for staff, which the step 1 review missed | V24 reads professionals through `is_professional` and `professional_office_ids` |
| `PermissionMatrixIT`'s staff loop failed on NOTARY and SWORN_SURVEYOR | the database refuses giving a professional role to staff | the staff loop skips the two professional roles; the professionals have their own cases |
| An e2e run did not find the new account in the directory | the directory listed the oldest first, 50 a page, and earlier runs left theirs | D20: newest first, and a search; the test now finds the account both ways |
| A directory test passed for the wrong reason: `%20` and `%25` arrived literally | MockMvc's `get(String)` treats the address as a template and encodes `%` again | the test sends a `URI`; it also checks that a `LIKE` version of the query fails it |
| A notary could read the conservators' reason for refusing their deposit in a conflict | `conflict_resolve` copies the decision's reason into the deposit's `closed_reason`, which step 4 showed to the professional (found while building D17) | V27 hides it when a conflict decision closed the deposit |
| The seed leaves the notary verified | an activation needs two keys, and the script holds none | sign in as the notary, register two keys, run the seed again |
| 429 `RATE_LIMITED` on a request | requests share `SNIGF_RATE_REGISTER_PER_HOUR` with registration, per address, in memory | wait; in development the seed restarts the app |

Other answers are in the [error catalogue](../technical/08-operations-and-troubleshooting.md#requests).

## Production differences

Not production-ready, by design:

- **Credentials are synthetic.** Verification is a human check whose reference and note are recorded;
  checking with a real professional body, and real professionals' data, need their own decision.
- **One person, two accounts.** Someone who is both an applicant and a professional holds two accounts;
  a unified identity with capacities needs its own ADR (ADR-041 §2, D14).
- **No retention policy** for verification evidence and deposit documents: nothing is removed, and
  applying one will need a migration, because the tables refuse deletion.
- **Sworn surveyors** hold no operational permission until slice 4; professionals do not take part in
  applications yet.
- **Throttling** of requests and lookups is in memory, for one application instance.
- **Being built (ADR-041 D18, D19):** replacing a submitted document needs a second person;
  retention metadata and legal holds.
- **The directory's search** reads every profile matching the filters; past tens of thousands of
  accounts it would need an index on the folded columns.
