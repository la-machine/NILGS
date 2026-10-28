# Applications: drafts, submission, review and decision

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

An applicant files a service request for a parcel. While it is a draft, it is theirs alone:
nobody else, staff included, can see it. When the applicant submits, the server:
- checks every requirement of the type and reports all that is missing;
- resolves the parcel, which must be active;
- sends the application to the parcel's office;
- issues the reference `DEMO-APP-{yyyy}-{seq6}-{check}`;
- records the submission in a history that nobody edits.

What was submitted never changes. Ownership is checked by the endpoint, the service and the
database (row-level security), so one forgotten check cannot leak someone's claim. Decision:
[ADR-032](../adr/032-applications-drafts-and-submission.md) (MVP-02, task E-12).

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V13__applications.sql` | `current_actor()`; `application_type`, `application`, `application_transition`; the guard, the history trigger, draft-only deletion; row-level security policies; grants |
| `backend/src/main/resources/application-types.json` | the built-in catalogue: `DEMO_REGISTRATION` with four fields and three DEMO document categories |
| `shared/db/DatabaseActor.java` | names the actor of the transaction (`snigf.actor_id`) before any query |
| `shared/id/CheckCharacter.java` | ISO 7064 MOD 37,36, shared with parcel references |
| `parcel/ParcelDirectory.java`, `parcel/application/ParcelDirectoryService.java` | the active parcel behind a reference (identity and office only) |
| `application/domain/` | `ApplicationState` (nine states, legal moves), `ApplicationReference`, `ApplicationType` and `Requirements` (definition checks, form checks), `Application`, the repository port |
| `application/application/ApplicationService.java` | drafts, reading, deleting, submission; 404 for anything not the caller's; audit |
| `application/application/ApplicationTypeCatalogue.java` | writes a new version when a definition changes; marks removed types unavailable |
| `application/infrastructure/startup/ApplicationTypeSync.java` | reads `SNIGF_APPLICATION_TYPES` strictly at startup |
| `application/infrastructure/web/ApplicationController.java` | the endpoints below |
| `identity/infrastructure/security/Access.java` | `canOwn`: the permission for the caller's own records |
| `frontend/src/features/applications/` | list, new draft, `DraftForm` (drawn from the type), submitted view, `form.ts` (items to messages) |
| `backend/src/test/sql/t/15_applications.sql` | every database rule |

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/application-types` | `application.create#own` | available types with their requirements |
| `GET /api/v1/applications?page=` | `application.read#own` | the caller's applications |
| `POST /api/v1/applications` + `Idempotency-Key` | `application.create#own` | 201, the draft's id |
| `GET /api/v1/applications/{id}` | `application.read#own` | the application, its type version, where it stands and its history naming parties, never staff (E-16, [tracking guide](tracking-and-workload.md)); 404 if not the caller's |
| `PUT /api/v1/applications/{id}` | `application.create#own` | `{expectedVersion, parcelReference, data}`: the draft replaced |
| `DELETE /api/v1/applications/{id}?expectedVersion=` | `application.create#own` | 204; drafts only |
| `POST /api/v1/applications/{id}/submission` + `Idempotency-Key` | `application.submit#own` | `{expectedVersion}`: the reference and state |

## How it works

1. Every use case starts with `DatabaseActor.bind(user)`. For that transaction the database
   shows only the actor's applications. It also shows staff the applications of offices where
   they hold `application.read`; a draft has no office, so it is never shown to staff.
2. **Draft.** The form is checked against the type version in force:
   - unknown fields, values too long, choices not offered;
   - a parcel reference with a bad shape or check character.

   Saving moves the draft to the current version of its type.
3. **Submission.**
   1. The service checks the draft against the current version, with every required field.
   2. It checks the parcel: named, then active through `ParcelDirectory`.
   3. It lists **all** problems at once (422 `APPLICATION_INCOMPLETE`, items such as
      `MISSING:statement`, `PARCEL_NOT_FOUND`). The refusal is audited and the draft stays a
      draft.
   4. When complete, it takes the next number of the year (`next_reference_number('APP', 'ALL', year)`)
      and updates the row.
   5. The guard checks the move (`DRAFT` → `SUBMITTED`): the actor is the owner, the parcel is
      active and of that office, and the type version is current. It sets `submitted_at`.
   6. The history trigger writes the transition.
   7. The answer is stored under the `Idempotency-Key`: the same key replays it, and another key
      gets 409 `INVALID_TRANSITION`.
4. **After submission** nothing submitted can change. Only the state moves, along the legal
   transitions, in later slices.

| Code | Status | When |
| --- | --- | --- |
| `APPLICATION_NOT_FOUND` | 404 | unknown, or someone else's (recorded as `ACCESS_DENIED`) |
| `FORM_INVALID` | 422 | a draft with a wrong value (`items`) |
| `APPLICATION_INCOMPLETE` | 422 | a submission with missing items (`items`) |
| `TYPE_NOT_AVAILABLE` | 422 | unknown or retired type |
| `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED` | 422 | no key, or the key of another request |
| `STALE_VERSION` | 409 | changed meanwhile |
| `NOT_A_DRAFT`, `INVALID_TRANSITION` | 409 | editing or submitting what is no longer a draft |
| `DRAFT_LIMIT_REACHED` | 409 | `SNIGF_APPLICATION_MAX_DRAFTS` drafts already held |

### Completeness review (E-14)

Decision: [ADR-034](../adr/034-completeness-review.md).

1. **On submission** the database records the automatic check (`AUTOMATIC`, with what
   `application_missing_items()` finds missing) and the system moves the application to
   `COMPLETENESS_REVIEW`. No person is named: the history shows "the system".
2. **The intake officer** of the office opens **Cases** (`GET /api/v1/cases?office=YDE1`), then
   the case (`GET /api/v1/cases/{id}`). They see what is missing now, the answers, and the
   documents (`/cases/{id}/documents`, downloads audited).
3. They either:
   - **accept** (`POST /cases/{id}/acceptance`): refused with 409 `ITEMS_MISSING` while
     anything required is missing; the case goes to `UNDER_REVIEW`;
   - **request information** (`POST /cases/{id}/information-request`): the document
     categories wanted and a message (10 to 2000 characters); the case goes to
     `INFO_REQUESTED`.
4. **The applicant** sees the request, adds files only in the categories asked for (each a
   new version; the form stays frozen), and responds (`POST /applications/{id}/response`,
   optional message). The automatic check runs again, and the case returns to
   `COMPLETENESS_REVIEW`.

Each step is a `completeness_record`. Each move needs its record, written in the same
transaction by an entitled person, never the applicant for an officer's step; the database
checks this (V15).

| Code | Status | When |
| --- | --- | --- |
| `ITEMS_MISSING` | 409 | accepting while something required is missing (`items`) |
| `APPLICANT_CANNOT_REVIEW` | 403 | an officer handling their own application |
| `CATEGORIES_REQUIRED`, `CATEGORY_UNKNOWN`, `MESSAGE_REQUIRED` | 422 | an incomplete information request |
| `CATEGORY_NOT_REQUESTED` | 409 | an upload outside the requested categories during a request |
| `INVALID_TRANSITION`, `STALE_VERSION` | 409 | the case is not in that state, or changed meanwhile |

### Review and simulated decision (E-15)

Decision: [ADR-035](../adr/035-review-and-simulated-decision.md). Code: `org.snigf.mvp.review`
(`domain/Review.java`, `application/ReviewService.java`, `infrastructure/web/ReviewController.java`),
`V16__review_and_decision.sql`, pgTAP `18_review_and_decision.sql`, `frontend/src/features/reviews/`.

1. **A reviewer** of the office opens **Review** (`GET /api/v1/reviews?stage=REVIEW&mine=false`):
   the cases `UNDER_REVIEW` that nobody has claimed. They open one (`GET /reviews/{id}`, and
   the case itself through `/assigned-cases/{id}` and its documents) and **claim** it
   (`POST /reviews/{id}/review-claim`). Nobody else sees it from then on.
2. They **recommend** (`POST /reviews/{id}/recommendation`): `RECOMMEND_APPROVAL`,
   `RECOMMEND_REFUSAL` or `RETURN_FOR_INFORMATION`, with a finding of 10 to 4000 characters.
   The finding is internal: the applicant never reads it. The case goes to `PENDING_DECISION`.
3. **A decision authority** of the office, who did not recommend, claims it
   (`POST /reviews/{id}/decision-claim`) and either:
   - **returns** it to review with a reason (`POST /reviews/{id}/return`): the same reviewer
     recommends again;
   - **decides** (`POST /reviews/{id}/decision`): `APPROVED` or `REFUSED`, grounds of 10 to
     4000 characters, and 1 to 50 stored document versions cited. The decision is refused with
     409 `OPEN_ALERT` while an overlap or duplicate alert on the parcel is open.
4. **The applicant** reads the decision on their application, under the notice "Simulated
   decision — no legal effect", and can print it. Office readers see the whole review on the
   case page (`GET /cases/{id}/review`).

The database refuses, whatever the application does:
- a claim for someone else;
- a second claim at a stage;
- a decision by whoever recommended, or by the applicant;
- a move without its record;
- a decision that is not simulated.

| Code | Status | When |
| --- | --- | --- |
| `ALREADY_ASSIGNED` | 409 | someone claimed the case at that stage first |
| `NOT_ASSIGNED` | 403 | acting on a case not assigned to the caller at that stage |
| `RECOMMENDER_CANNOT_DECIDE` | 403 | the recommender claiming or deciding |
| `REVIEW_REFUSED` | 403 | the database refused the person (role ended, own application) |
| `OPEN_ALERT` | 409 | an alert on the parcel is open |
| `RECOMMENDATION_REQUIRED`, `OUTCOME_REQUIRED`, `FINDING_REQUIRED`, `REASON_REQUIRED`, `GROUNDS_REQUIRED`, `CITATIONS_REQUIRED` | 422 | an incomplete form |
| `INVALID_TRANSITION`, `STALE_VERSION` | 409 | the case is not at that step, or changed meanwhile |
| `APPLICATION_NOT_FOUND` | 404 | assigned to someone else, or not waiting at the caller's stage |

### What the applicant follows (E-16)

The applicant reads their application through a projection: the state, since when, whose turn
it is, the pending information request, and steps naming "you", "the office" or "the system".
Staff names and the reasons recorded with moves (a recommendation, a return) stay with staff.
See the [tracking and workload guide](tracking-and-workload.md) and
[ADR-036](../adr/036-tracking-and-workload-report.md).

## How to do it again

### Change the catalogue

1. Copy `backend/src/main/resources/application-types.json`, edit it, and mount it into the
   app container (a read-only bind mount in `compose.yml`).
2. Set `SNIGF_APPLICATION_TYPES=file:/config/application-types.json` in `.env`.
3. Restart the app. The log says `Application types: 1 configured from … (1 new version(s))`,
   and the audit trail shows `APPLICATION_TYPE_CHANGED`.

Rules checked at startup (the start fails and names each problem):

| Item | Rule |
| --- | --- |
| Type code | `DEMO_` followed by UPPER_SNAKE_CASE |
| Type names | DEMO in both languages (C4) |
| Field keys | camelCase, unique |
| `kind` | `TEXT` or `LONG_TEXT` with `maxLength` 1 to 4000, or `CHOICE` with 2 to 30 options |
| Labels | in both languages; hints in both or neither |
| Document codes | `DEMO_...` |
| Counts | at most 30 fields and 20 document categories |
| The file | unknown properties are refused |

Removing a type from the file makes it unavailable: new drafts are refused, and existing ones
are kept.

### Protect another table of people's own records

Add the same pattern:
1. `ENABLE ROW LEVEL SECURITY` with policies on `current_actor()`;
2. `DatabaseActor.bind(...)` first in every use case;
3. `@access.canOwn` on the endpoint;
4. 404 for anything not the caller's.

## How to verify

| Check | Command | Expected |
| --- | --- | --- |
| Database rules | `docker compose --profile test run --rm --build dbtest` | test `15`: 26 ok |
| Back end | `cd backend && ./mvnw verify` | `ApplicationDomainTest`, `ApplicationIT`, `ApplicationTypeConfigIT`, `PermissionMatrixIT` pass |
| Interface | `cd frontend && npm test` | `DraftForm.test.tsx` passes |
| Completeness | `docker compose --profile test run --rm --build dbtest`; `./mvnw verify` | test `17`: 26 ok; `CompletenessIT` passes |
| Review and decision | `docker compose --profile test run --rm --build dbtest`; `./mvnw verify`; `npm test` | test `18`: 27 ok; `ReviewIT` passes; `ReviewPanel.test.tsx` passes |
| Journey | `bash infra/scripts/e2e.sh` | the application journeys pass, including the request-info round trip with `intake`, and the review by `reviewer` and the simulated decision by `decider` |
| By hand | as `applicant` ([demo accounts](demo-accounts.md)) | draft, incomplete submission listed, complete submission with a reference |
| The database hides rows | `docker compose exec -u postgres db psql -d snigf -c "SET ROLE snigf_app; SELECT count(*) FROM snigf.application"` | `0` (no actor named) |

## Pitfalls we hit

- **A text block's indentation.** A SQL constant ended on its last line, so `+ " WHERE"` was
  glued to `office_id` once Java stripped the common indentation. End shared SQL fragments with
  a newline.
- **The same address after submitting.** The detail page was keyed by id only, so
  `?submitted=` did not reload it and the draft stayed on screen. The page is now keyed by the
  query too.
- **Staff who registered as applicants.** In e2e, staff accounts start as applicants and keep
  that role. They reach the applicant endpoints and get 404 for someone else's application,
  not 403. The test expects 404.
- **Who is "me" on the review.** Comparing display names to find one's own assignment broke
  as soon as two people shared a name. The server now says it (`assignments[].mine`,
  `youRecommended`).
- **The order of the decision checks.** With the open-alert check first, a decision without
  grounds was reported as blocked by the alert. The guard checks grounds and citations first
  and the alert last.
- **Registration limit in repeated e2e runs.** Several runs in an hour reach the per-address
  registration limit. In development, restart the app (its limiter is in memory), as the seed
  script does.

## Production differences

- The actor setting is transaction-local (`set_config(..., true)`), so a pooled connection
  never carries it to the next request. Keep every use case `@Transactional`; `DatabaseActor`
  refuses outside a transaction.
- Mount the real catalogue read-only and keep it in configuration management. Each change is a
  version in the database and an audit event.
- Completeness (MVP-03) joined with E-14; the DEMO payment (MVP-08, simulated, no real money moves) joined with E-17: with the payment gate on, the intake officer accepts a case only once its fee is paid ([payments guide](payments.md)).
- Decisions are simulated and say so on every screen and printout. A real decision needs the
  legal framework, signatures and notification (national stage), not a setting.
  Documents are added to a draft ([documents guide](documents.md)).
