# ADR-032: Applications: owner-only drafts, idempotent submission, row-level security

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-24 | Project owner, Pythagore (FLYT) |

## Context

MVP-02 (task E-12) opens slice 3: an applicant files a service request. The Baseline fixes:

- **one DEMO application type** (C4: "Demande d'immatriculation (DEMO)" with three
  configurable synthetic document categories, labelled as demonstration requirements);
- **the parcel link** is optional at draft and required at submission;
- **the reference** `DEMO-APP-{yyyy}-{seq6}-{check}` is issued at submission, not at draft;
- **submission is idempotent** per `Idempotency-Key`;
- **only the owner** sees or edits an application. Another user's id answers 404, with no
  existence leak;
- **an incomplete submission** gets 422 listing the missing items and stays `DRAFT`;
- **nine states**, with only the drawn transitions legal (`409 INVALID_TRANSITION`) and a
  status history row for each change;
- **acceptance:** applicant B cannot read applicant A's application.

Ownership is the risk that matters: an insecure direct object reference (IDOR) here would
expose personal land claims.

## Decision

**Ownership is enforced three times.**

1. The endpoint needs the permission for the caller's own records (`@access.canOwn`, authority
   `application.read#own`). Staff, whose roles hold office or assigned scopes, are refused 403.
2. The service works only on the caller's application. Anything else answers 404, and the
   attempt is audited (`ACCESS_DENIED`, reason `APPLICATION_NOT_FOUND`).
3. **Row-level security (V13).** Each use case first names its actor to the database for the
   transaction (`DatabaseActor`: `set_config('snigf.actor_id', id, true)`, read by
   `current_actor()`). The application's account sees an application only:
   - if it belongs to that actor;
   - or for staff, if the actor holds `application.read` at the application's office
     (`holds_permission_at`). A draft has no office, so staff never see one.

   With no actor named, no row is visible. Insert, update and delete follow the same rule,
   and only a draft can be deleted.

**The database guards the lifecycle** (`application_guard`):
- an application is created as a draft;
- owner, type and creation date never change;
- the reference never changes once issued;
- the optimistic lock must match;
- after submission, the form, parcel, office, type version and submission time never change;
- the nine states and their legal moves (`application_transition_legal`) are the only ones
  allowed;
- submitting and withdrawing are the applicant's acts;
- a submission names an active parcel, and the office is that parcel's;
- the draft follows the type version in force.

The history (`application_transition`) is written by the database itself on every state
change, as the table owner, and is append-only. The application can only read it.

**The type catalogue is configuration, versioned in the database.**
- `SNIGF_APPLICATION_TYPES` points to a JSON file (built in by default, or a mounted file). It
  holds each type's names, fields (text, long text, choice) and document categories.
- At startup the file is checked strictly: unknown properties, keys, label languages, the bounds
  of each field, and DEMO in names and category codes.
- If a definition changed, the file is written as a new version of the type. A type removed from
  the file gets a version marked unavailable.
- `application_type` is insert-only, and each version follows the last one.
- A draft follows its version until it is saved again; a submission is checked against the
  version in force and records it.
- Each change is audited (`APPLICATION_TYPE_CHANGED`).

**Submission.**
1. `POST /api/v1/applications/{id}/submission` carries `{expectedVersion}` and an
   `Idempotency-Key`. Creation of a draft carries a key too.
2. The service checks the form against the current version (`MISSING:field`, `TOO_LONG`,
   `NOT_AN_OPTION`, `UNKNOWN_FIELD`), the parcel reference (`MISSING:parcelReference`, then
   `PARCEL_NOT_FOUND` through the parcel module's `ParcelDirectory`) and the state.
3. It answers every missing item at once (422 `APPLICATION_INCOMPLETE`, audited
   `APPLICATION_SUBMIT_REJECTED`).
4. When complete, it issues the reference from the national counter
   (`next_reference_number('APP', 'ALL', year)`) with the shared ISO 7064 check character.
5. A retry with the same key gets the first answer; another key gets 409 `INVALID_TRANSITION`.

**Interface.** "My applications" is visible to holders of `application.read#own`:
- the list;
- a new draft;
- the draft's form: the parcel, the type's fields, and the document categories with an honest
  "not available yet" (uploads are E-13);
- save, delete, and submit with confirmation;
- missing items shown in a summary and next to each field;
- a submitted application read-only, with its reference and history.

## Why

- Row-level security makes a forgotten `WHERE applicant_id = ?` harmless. The guard makes a
  wrong transition impossible from any code path. The service gives the precise, translated
  answer and the audit record.
- 404 for someone else's id, rather than 403, tells nothing about existence (Baseline
  alternative flow).
- A configured, versioned catalogue lets the demonstration's requirements change without a
  release. What was asked of each submission stays known.
- The office comes from the parcel, never from the request, so later office-scoped work
  (intake, review) cannot be steered by the applicant.

## Alternatives considered

- **Ownership in the service only:** one missed condition and the IDOR is back.
- **A database role per user:** heavy with a connection pool, and unnecessary when the actor is
  named per transaction.
- **Types as code (an enum):** every requirement change would need a release, and old versions
  would not be kept.
- **Writing the history from Java:** a code path could change the state without a row.

## Consequences

- New:
  - migration V13: `application_type`, `application`, `application_transition`,
    `current_actor()`, guards, row-level security;
  - pgTAP test `15`;
  - the application module (`ApplicationService`, `ApplicationTypeCatalogue`,
    `ApplicationTypeSync`, `ApplicationController`);
  - `ParcelDirectory` (parcel module API), `DatabaseActor` and `CheckCharacter` (shared);
  - `Access.canOwn`;
  - settings `SNIGF_APPLICATION_*`;
  - six audit actions;
  - the frontend `features/applications`.
- Any table holding people's own records follows the same pattern: name the actor, add
  policies. Staff reads of applications (intake, E-14) add their policy for the office and
  assigned scopes.
- In e2e, staff accounts registered as applicants first. They keep the applicant role, so they
  reach the applicant endpoints for their own (empty) records and get 404 for anyone else's.
