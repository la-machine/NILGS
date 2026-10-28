# ADR-036: Tracking and workload report: an applicant view without internals, times from the transition table only

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-26 | Project owner, Pythagore (FLYT) |

## Context

MVP-06 (task E-16) has two sides:

- **Applicant view.** Current state, date entered, a public-safe message and any pending request.
  Internal notes and reviewer identities are hidden.
- **Back office.** A queue by state and office, the time in the current stage, an overdue flag
  against DEMO stage targets ("for example 5 days for completeness"), and counts per state.

Its acceptance criteria:

- stage durations derive from the transition table, not from fields people edit;
- the applicant API response contains no internal fields, tested by schema.

Before E-16, the applicant's `GET /api/v1/applications/{id}` returned the same history as
staff see. It named every staff member who moved the application (`actorName`) and gave the
reason recorded with each move. For a review, that reason is the reviewer's recommendation
(`RECOMMEND_REFUSAL`), and `RETURNED` for a return to review. The completeness steps also named
the intake officer. ADR-035 hid the review's records (`review_step`) from the applicant, but its
trace was still visible in the history. E-16 closes this gap.

The catalogue already holds `report.workload` (ADR-025), for `SUPERVISOR` at OFFICE scope.

## Decision

### Applicant: a projection, not a filter

The applicant reads a **projection** built for them (`Tracking`, in the application domain).
It is not the staff record with some fields removed.

| Field | Content |
| --- | --- |
| `state`, `since` | the state, and when it was entered: the time of the last move in the history (its creation, for a draft) |
| `waitingOn` | `APPLICANT` (draft, information requested) or `OFFICE` (submitted, completeness review, review, pending decision); `null` once ended |
| `pendingRequest` | while information is requested: the categories asked for, the office's message to the applicant, and when |
| `history[]` | `from`, `to`, `at`, and `by`: `APPLICANT`, `OFFICE` or `SYSTEM`; never a person, never the reason |
| `completeness[]` | kind, what was missing, what was requested, `by`, `at`; a message only when it was written to the applicant (information request) or by them (response) |
| `decision` | unchanged (ADR-035): outcome, grounds, `simulated`, date |

- **The public-safe message** is the state itself. The interface says what each state means, in
  French and English (`texts.tracking.explain`). The API stays language-neutral (ADR-011).
- **Staff are unchanged.** The case, review and assigned-case endpoints still name who acted,
  with the reason (`Application.Transition` now also carries `actorId`, which the projection
  uses to tell the applicant from the office).
- **The schema test.** `ApplicantPayloadSchema` lists every field path the applicant may
  receive. `TrackingIT` walks a full life through it, step by step: submission, an information
  request, the response, acceptance, a refusal recommended, a return to review, a new
  recommendation, the decision. At every step it checks that:
  - no other field exists;
  - every step names a party, never a person;
  - no staff name, finding, return reason or recommendation code appears.

  `ApplicationIT` and `ReviewIT` call the same check. A new field fails the build until it is
  added to the list on purpose.

### Supervisor: report functions in the database

V17 adds four report functions (`office_stays` is internal):

| Function | Answers |
| --- | --- |
| `office_stays(office)` | every stay of the office's applications in a state: entered, left (`NULL` while still there). Window `lead()` over `application_transition` only. Not granted to the application. |
| `workload_counts(code)` | applications per state (submitted ones) |
| `workload_open_cases(code, targets)` | cases under way: since, time waited, target of the state, `overdue`, who holds the case (the `REVIEW` assignment while under review, `DECISION` while pending decision) |
| `workload_completed_stays(code, targets, from)` | per state, the stays that ended since `from`: count, average, median, longest, over target |

- **Gated in the database.** Each function answers only for an office where the actor of the
  transaction holds `report.workload`, through a role or a special grant in force
  (`holds_at`). For anyone else, or with no actor named, it returns nothing. The endpoint checks
  the same permission first (`@access.canAt(authentication, 'report.workload', #office)`).
- **`SECURITY DEFINER`, minimal output.** The functions read as the owner of the tables, so a
  holder of `report.workload` needs no `application.read`. What they return is minimal:
  - references, states and times;
  - the names of the staff holding cases.

  They never return a form, a document, an applicant's name or a review.
- **Times from the transition table only.** No function reads `updated_at`, `submitted_at` or
  any column a person writes. The application cannot change `application_transition` (no
  `UPDATE` grant, append-only trigger); pgTAP checks both.
- **Targets come from settings**, as whole seconds per state:
  - completeness review: 5 days;
  - information requested: 30 days;
  - under review: 10 days;
  - pending decision: 5 days.

  They are DEMO values, set in `.env` (`SNIGF_REPORTING_TARGET_*`) until the team validates
  them, and must be above zero and at most a year (checked at startup). `SUBMITTED` has no
  target: the automatic check moves a case on in the same transaction.
- **Durations cover a period**, `SNIGF_REPORTING_DURATION_WINDOW` back from now (90 days).

**Endpoints.**

| Endpoint | Permission | Purpose |
| --- | --- | --- |
| `GET /api/v1/reports/workload?office=` | `canAt` `report.workload` | Targets, counts per state, open and overdue per stage, load per person and stage, stage durations of the period |
| `GET /api/v1/reports/workload/cases?office=&state=&overdue=&page=` | `canAt` `report.workload` | Cases under way, longest waiting first; one working state (`422 STATE_NOT_LISTED` otherwise), overdue only |

Durations are whole seconds; the interface shows days, hours and minutes.

**Interface.**
- **Applicant:** a "Where your application stands" card on every submitted application, and
  history and completeness steps naming "You", "The office" or "The system".
- **Supervisor:** a **Workload** page under Oversight with:
  - a tile per stage, with a red edge once a case is overdue;
  - counts per state, the load per officer, and the stage durations;
  - the queue, filtered in the address.

  A reference opens the case only for someone who also reads the office's cases. The
  dashboard counts overdue cases.

## Consequences

- The applicant learns where their application stands and whose turn it is, without learning
  who in the office handled it or what was recommended.
- The supervisor sees delays without reading any case, and without any extra permission.
- A report is only as fresh as the transition table, which is exact: every move is recorded by
  the database in the transaction that makes it.
- Reading a report is not audited: it changes nothing and shows no personal data beyond staff
  names. If the team decides otherwise, a `REPORT_READ` event can be added to the service.
- Not here:
  - reassigning a case (E-22);
  - the audit timeline of one case (E-18, which needs ADR-027's office on audit events);
  - business-day calendars and holidays: targets count calendar time.

*Copyright (c) 2026 FLYT. Author: Pythagore.*
