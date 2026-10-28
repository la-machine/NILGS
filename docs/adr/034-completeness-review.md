# ADR-034: Completeness review: one check in the database, every move backed by its record

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-24 | Project owner, Pythagore (FLYT) |

## Context

MVP-03 (task E-14) adds the completeness review between submission and substantive review:

- requirements are data (type → required fields and document categories), versioned;
- the check reports presence only;
- officer outcomes are `COMPLETE`, or `INFO_REQUESTED` with listed items and a message;
- the applicant's response adds documents as new versions; nothing is overwritten;
- the flow:
  1. the officer opens the case and sees the automatic result;
  2. the officer accepts (→ `UNDER_REVIEW`) or requests information (→ `INFO_REQUESTED`);
  3. the applicant responds (→ back to completeness review);
- accept is refused while required items are missing (409);
- acceptance: a missing category shows as missing; the request-info round trip keeps both
  document versions; invalid transitions get 409; the history keeps the round trip.

The lifecycle diagram labels the move from Submitted to Completeness review "auto check": no
person makes it.

## Decision

**One check, in the database.** `application_missing_items(id)` (V15) lists, for the type
version the application was submitted under:
- required fields left empty (`MISSING:<field>`);
- required document categories without a stored file (`MISSING_DOCUMENT:<category>`).

The case screen, the service and the guards all call it, so the screen, the refusal and the
database can never disagree. It runs with the caller's rights, under row-level security.

**Every step is a record, append-only** (`completeness_record`):

| Kind | Who | When |
| --- | --- | --- |
| `AUTOMATIC` | the system (no person) | on submission, and again on each response |
| `COMPLETE` | an intake officer | accepting |
| `INFO_REQUESTED` | an intake officer | requesting information: the document categories, and a message of 10 to 2000 characters |
| `RESPONSE` | the applicant | responding, with an optional message |

The database fills in what was missing at that moment; nobody supplies it. A guard checks
who may write each kind:
- an officer needs `completeness.review` or `completeness.request-info` at the office, by role
  or by special grant (`holds_at`), and is never the applicant;
- acceptance needs nothing missing;
- requested categories must belong to the type;
- only the applicant responds.

**Every move needs its record** (the application guard, replaced by V15):

| Move | Needs |
| --- | --- |
| `SUBMITTED → COMPLETENESS_REVIEW` | an `AUTOMATIC` record, and no actor: the only move without a person (`application_transition.actor_id` may be null there only) |
| `→ UNDER_REVIEW` | a `COMPLETE` record by the same officer |
| `→ INFO_REQUESTED` | an `INFO_REQUESTED` record by the same officer |
| `INFO_REQUESTED → COMPLETENESS_REVIEW` | a `RESPONSE` by the applicant and a fresh `AUTOMATIC` |
| Withdrawal | the applicant |
| Moves of review and decision (E-15) | already need `review.recommend` or `decision.record` at the office, never the applicant's |
| Anything else | refused |

Every state change must be made by the actor named for the transaction.

**Staff move the state of their office's applications.** A new row-level policy
(`application_handle`) lets staff holding `application.read` at the office update its
applications, but never their own. The guard decides which moves and by whom.

**Files during an information request.** `document_accepted` (V14, replaced) takes files in
every category of a draft, and only in the categories asked for while information is
requested. The submitted form itself stays frozen.

**Endpoints.**

| Endpoint | Permission | Purpose |
| --- | --- | --- |
| `GET /api/v1/cases?office=&state=&page=` | `application.read` at the office | the office's queue, waiting longest first; completeness review by default in the interface |
| `GET /api/v1/cases/{id}` | `application.read` at the case's office | the case: applicant, answers, what is missing now, the completeness records, the history |
| `GET /api/v1/cases/{id}/documents` and `.../content` | `application.read` at the case's office | documents for staff, read only, downloads audited and hash-checked |
| `POST /api/v1/cases/{id}/acceptance` | `completeness.review` at the case's office | `{expectedVersion}` |
| `POST /api/v1/cases/{id}/information-request` | `completeness.request-info` at the case's office | `{expectedVersion, categories, message}` |
| `POST /api/v1/applications/{id}/response` | `application.respond#own` | `{expectedVersion, message?}` |

The office comes from the database (`application_office_code()`, `@caseOffices.of(#id)`).

**Refusals.** The service refuses first, with a precise code, and audits the refusal
(`COMPLETENESS_REJECTED`):

| Code | Status |
| --- | --- |
| `ITEMS_MISSING` (every item listed) | 409 |
| `INVALID_TRANSITION`, `STALE_VERSION` | 409 |
| `APPLICANT_CANNOT_REVIEW` | 403 |
| `CATEGORIES_REQUIRED`, `CATEGORY_UNKNOWN`, `MESSAGE_REQUIRED` | 422 |

A disagreement from the database becomes `COMPLETENESS_REFUSED` (403) or the matching code.
Outcomes are audited: `COMPLETENESS_ACCEPTED`, `COMPLETENESS_INFO_REQUESTED`,
`COMPLETENESS_RESPONSE_SENT`.

## Why

- A rule in two places drifts. The check lives once, where the data is, and the guard cannot be
  satisfied by a caller that skipped it.
- Records before moves make every state of the application explainable from the evidence
  (who decided what, on what was then missing). They also keep a later audit timeline (E-18)
  a query, not a reconstruction.
- A null actor on exactly one move is honest. A pretend system user would be an account
  nobody holds, in every list of accounts.

## Alternatives considered

- **The automatic check in Java only:** the guard could not verify acceptance, and two
  implementations would drift.
- **A system user account for the automatic move:** an account with no person behind it,
  shown in directories, with a password hash to protect.
- **The officer starting the review by hand (`SUBMITTED → COMPLETENESS_REVIEW` on opening):**
  the diagram says the check is automatic, and a queue would show cases nobody looked at as
  "submitted" forever.

## Consequences

- New:
  - migration V15 and pgTAP test `17`;
  - `CaseService`, `CaseController`, `CaseOffices`, `CaseDocumentController`;
  - `ApplicationService.respond`, `ApplicationDirectory.forStaff` and `openCategories`;
  - four audit actions;
  - the frontend `features/cases`, the applicant's information request panel, and a dashboard
    count of cases waiting.
- Submission now ends in `COMPLETENESS_REVIEW`, and its answer's version is two higher.
- Payment confirmation before acceptance (the Baseline's flag) joins with E-17. Case
  assignment within an office comes with E-22.
