# Tracking and workload report

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Two people follow an application without handling it:

- **The applicant** sees where their application stands: its state, since when, whose turn it
  is, and what the office asked for. They never see who in the office handled it, what was
  recommended, or why it went back to review.
- **The supervisor** of an office sees its workload without opening a case:
  - how many applications are in each state;
  - which cases are under way, for how long, and which are past the DEMO target of their stage;
  - who holds what;
  - how long each stage took over the last 90 days.

Every time comes from the history of states, which only the database writes. Nothing a person
types can move a time. Decision: [ADR-036](../adr/036-tracking-and-workload-report.md)
(MVP-06, task E-16).

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V17__tracking_and_workload.sql` | `office_stays` (stays from `application_transition`), `workload_office` (the permission check), `workload_target`, and the three report functions; grants |
| `application/domain/Tracking.java` | the applicant's projection: state, `since`, `waitingOn`, `pendingRequest`, steps naming a party (`APPLICANT`, `OFFICE`, `SYSTEM`) |
| `application/application/ApplicationService.java` | `read` builds the `Tracking` of the caller's application |
| `application/infrastructure/web/ApplicationController.java` | the applicant's response records (`StepResponse`, `ApplicantCompletenessResponse`, `PendingRequestResponse`) |
| `reporting/domain/` | `StageTargets` (DEMO targets, checked), `Workload` (the report's records), `WorkloadRepository` (port) |
| `reporting/application/WorkloadService.java`, `ReportingProperties.java` | names the actor, reads the report; settings `SNIGF_REPORTING_*` |
| `reporting/infrastructure/persistence/JdbcWorkloadRepository.java` | SQL over the report functions; intervals as seconds |
| `reporting/infrastructure/web/WorkloadController.java`, `ReportExceptionHandler.java` | the two endpoints; `422 STATE_NOT_LISTED`, `PAGE_OUT_OF_RANGE` |
| `frontend/src/features/applications/TrackingCard.tsx` | "Where your application stands" |
| `frontend/src/features/applications/CaseRecord.tsx` | history and completeness cards: a person for staff, a party for the applicant |
| `frontend/src/features/reports/` | `WorkloadPage` (stage tiles, counts, load per officer, durations, queue), `format.ts` (durations in words) |
| `backend/src/test/java/org/snigf/mvp/application/ApplicantPayloadSchema.java` | every field the applicant may receive |
| `backend/src/test/java/org/snigf/mvp/application/TrackingIT.java`, `reporting/WorkloadIT.java` | the two sides through the real endpoints |
| `backend/src/test/sql/t/19_tracking_and_workload.sql` | the functions' rules |

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/applications/{id}` | `application.read#own` | the application with `since`, `waitingOn`, `pendingRequest`, and history and completeness steps naming a party |
| `GET /api/v1/reports/workload?office=` | `report.workload` at the office | targets, counts per state, open and overdue per stage, load per person, stage durations |
| `GET /api/v1/reports/workload/cases?office=&state=&overdue=&page=` | `report.workload` at the office | cases under way, longest waiting first |

## How it works

**The applicant.**
1. `ApplicationService.read` loads the caller's application (row-level security, 404 for any
   other), its transitions and completeness records, as before.
2. `Tracking.of` keeps only what is meant for the applicant:
   - each move becomes `{from, to, by, at}`. `by` is `SYSTEM` without an actor, `APPLICANT` for
     the owner and `OFFICE` for anyone else. The reason recorded with the move is dropped;
   - a completeness step keeps its message only for an information request (written to them) or
     their own response;
   - `since` is the time of the last move; `waitingOn` follows from the state;
   - `pendingRequest` is the latest information request while the state is `INFO_REQUESTED`.
3. The screen shows the state and what it means (`texts.tracking.explain`), for how many days,
   and whose turn it is. The history says "You", "The office" or "The system".

**The supervisor.**
1. The endpoint checks `report.workload` at the office named.
2. `WorkloadService` names the actor to the database (`snigf.actor_id`), then calls the report
   functions with the targets in seconds (`{"COMPLETENESS_REVIEW": 432000, ...}`).
3. Each function first resolves the office through `workload_office(code)`, which returns the
   office only if `holds_at(current_actor(), 'report.workload', office)`. Otherwise every
   result is empty.
4. `office_stays` turns the transitions into stays: a transition *into* a state starts a stay;
   the next transition of the same application ends it (`lead(at)`).
   - **Open cases:** the stays not yet ended.
   - **Durations:** the stays that ended since the start of the period.
5. A case is overdue when it has waited longer than the target of its state. A state without a
   target (`SUBMITTED`) is never overdue.

## How to do it again

### Change a target or the period

1. Set the value in `.env`, as an ISO-8601 duration: `SNIGF_REPORTING_TARGET_UNDER_REVIEW=P15D`.
   The value must be above zero and at most a year; otherwise the application refuses to start.
2. Restart the app: `docker compose up -d app`. Nothing is stored: the next report uses the new
   target, for past and present stays alike.

### Add a figure to the report

1. Compute it in SQL from `office_stays` or the report functions, never from `updated_at` or a
   column a person writes.
2. Gate any new function the same way: `workload_office(code)` first, `SECURITY DEFINER`,
   `SET search_path`, `REVOKE` from `PUBLIC`, `GRANT EXECUTE` to `snigf_app` only.
3. Add a pgTAP test for who reads it (the supervisor of the office, nobody else).

### Show the applicant something new

1. Add it to `Tracking` (domain) and the applicant's response record.
2. Decide whether it is public: does it name a person, or say what a reviewer thought?
3. Add the field path to `ApplicantPayloadSchema.ALLOWED`. Until you do, `TrackingIT`,
   `ApplicationIT` and `ReviewIT` fail.

## How to verify

```sh
(cd backend && ./mvnw verify -Dit.test='TrackingIT,WorkloadIT,ReviewIT,ApplicationIT,PermissionMatrixIT')
bash infra/scripts/db-test.sh          # pgTAP, including 19_tracking_and_workload.sql
(cd frontend && npm test)              # TrackingCard, WorkloadPage, format
```

By hand, with the demonstration accounts:
1. As an applicant, open a submitted application. "Where your application stands" gives the
   state, the date, and whose turn it is. The history names no officer.
2. As the supervisor of the office, open **Workload**. Tiles show what is under way per stage.
   **Overdue only** filters the queue.
3. In the browser's network panel, the applicant's application has no `actorName` and no
   `reason`.

## Pitfalls we hit

- **The applicant's history named the staff and the recommendation.** Before E-16 the applicant
  received the staff history: `actorName` for each move, and the reason recorded with it,
  `RECOMMEND_REFUSAL` or `RETURNED` for review moves. `ReviewIT` only checked that the finding's
  text was absent, so the gap went unnoticed. The fix is a projection plus a schema test that
  lists allowed fields rather than forbidden ones.
- **The test's `JdbcTemplate` is `snigf_app`.** Under row-level security with no actor named, a
  count on `application_transition` returns 0, which looks like a passing check of nothing.
  `WorkloadIT` counts transitions on the superuser connection.
- **One label, two elements.** "Completeness review" is both a stage tile and an option of the
  queue's filter. The page test finds the tile by `data-stage`.
- **`new Date()` during render.** The linter rejects it (impure render). The card reads the time
  once into state, and tests pass `now`.
- **pgTAP runs in one transaction.** `now()` is the transaction's start, so the functions use
  `clock_timestamp()`. The test sleeps a few milliseconds so that durations and overdue flags are
  measurable.

## Production differences

- The targets are DEMO placeholders. Set the validated ones in `.env`, and record the decision.
- Targets count calendar time. Business days and public holidays need a calendar table and a
  change to `workload_target`: plan it before the pilot.
- Reading a report is not audited (ADR-036). If the pilot requires it, add an audit event in
  `WorkloadService`.

*Copyright (c) 2026 FLYT. Author: Pythagore.*
