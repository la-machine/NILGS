# ADR-035: Review and simulated decision: assignment by claim, separation of duties in the database

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-25 | Project owner, Pythagore (FLYT) |

## Context

MVP-05 (task E-15) adds the substantive review and the decision after completeness:

- a reviewer of the office examines the case and records a recommendation with a finding;
- a decision authority records the outcome, `APPROVED` or `REFUSED`, with grounds and the
  documents cited, or returns the case to review;
- the decision is **simulated**: the demonstration has no legal effect, and every screen and
  printout says so;
- whoever recommended never decides the same case (separation of duties);
- no decision while an overlap or duplicate alert on the parcel is open (MVP-04);
- the applicant reads the decision, never the internal review.

The catalogue (ADR-025) holds `application.read`, `review.recommend` and `decision.record` for
the `REVIEWER` and `DECISION_AUTHORITY` roles at scope **ASSIGNED** only: a reviewer does not
see every case of the office, only the ones assigned to them. Until E-15 nothing assigned a case
to anybody, so those permissions opened nothing.

## Decision

**Assignment by claim.** A reviewer, or a decision authority, of the case's office claims a case
waiting at their stage (`case_assignment`, V16):

| Stage | Claimable when | Claimed by |
| --- | --- | --- |
| `REVIEW` | the application is `UNDER_REVIEW` and nobody holds the stage | a holder of `review.recommend` at ASSIGNED scope at the office |
| `DECISION` | the application is `PENDING_DECISION` and nobody holds the stage | a holder of `decision.record` at ASSIGNED scope at the office, who never recommended on the case |

- One person per case and stage (unique index). A claim is for oneself only.
- Nobody claims their own application. The decision authority of a case does not review it.
- Assignments are append-only. A supervisor's reassignment comes with E-22.
- "Waiting to be claimed" is part of what an assignee may see: the queue lists the office's
  unclaimed cases at the person's stage, oldest first. Once claimed, the case leaves the others'
  view.

**Every step is a record, append-only.**

| Record | Who | Content |
| --- | --- | --- |
| `review_step` `RECOMMENDATION` | the reviewer assigned, while `UNDER_REVIEW` | `RECOMMEND_APPROVAL`, `RECOMMEND_REFUSAL` or `RETURN_FOR_INFORMATION`, and a finding of 10 to 4000 characters |
| `review_step` `RETURN` | the decision authority assigned, while `PENDING_DECISION` | a reason of 10 to 4000 characters |
| `decision` | the decision authority assigned, while `PENDING_DECISION` | the outcome, the grounds (10 to 4000 characters), the stored document versions cited (1 to 50), the recommendation it follows; `simulated` is `true` by a `CHECK` |

The decision guard refuses, in this order:
1. an author who is not the actor of the transaction, or not the one assigned;
2. an author who recommended on the case, or applied;
3. missing grounds;
4. a decision that does not follow the latest recommendation;
5. citations that are not stored versions of the application's own documents;
6. an open overlap or duplicate alert on the parcel (`application_open_alerts`, `restrict_violation`).

The alert comes last, so a malformed decision is reported as such even while an alert is open.

**Every move needs its record** (the application guard, replaced by V16):

| Move | Needs |
| --- | --- |
| `UNDER_REVIEW → PENDING_DECISION` | a recommendation by the reviewer assigned |
| `PENDING_DECISION → UNDER_REVIEW` | a return by the decision authority assigned |
| `PENDING_DECISION → APPROVED` / `REFUSED` | the decision row, with the same outcome |

A move without its record is refused with `restrict_violation` (23001), as for completeness.
pgTAP test 17 #24 now expects that code: a move to review without a recommendation is refused
for the missing record before anything else.

**Row-level security for assignees.**
- `application_assigned_read`: an assignee sees the cases assigned to them at any stage, and the
  cases they may claim now (`case_visible_to_assignee`, `SECURITY DEFINER` so the policy reads
  `case_assignment` without recursion).
- `application_assigned_handle`: an assignee moves the state of the cases assigned to them. The
  guard decides which moves.
- `review_step` is read by staff who see the case, never by the applicant.
- `decision` is read by staff who see the case and by the applicant.

**Endpoints.**

| Endpoint | Permission | Purpose |
| --- | --- | --- |
| `GET /api/v1/reviews?stage=&mine=&page=` | `canAssigned` `application.read` | The queue: mine, or waiting at my stage |
| `GET /api/v1/reviews/{id}` | `canAssigned` `application.read` | The review: assignments (`mine`), steps, decision, open alerts, `youRecommended` |
| `POST /api/v1/reviews/{id}/review-claim` | `canAssigned` `review.recommend` | Claim for review |
| `POST /api/v1/reviews/{id}/recommendation` | `canAssigned` `review.recommend` | Recommend, with the finding |
| `POST /api/v1/reviews/{id}/decision-claim` | `canAssigned` `decision.record` | Claim for decision |
| `POST /api/v1/reviews/{id}/return` | `canAssigned` `decision.record` | Return to review, with the reason |
| `POST /api/v1/reviews/{id}/decision` | `canAssigned` `decision.record` | Decide, with the grounds and citations |
| `GET /api/v1/assigned-cases/{id}` (+ `/documents`) | `canAssigned` `application.read` | The case itself, as the assignee sees it |
| `GET /api/v1/cases/{id}/review` | `canAt` `application.read` | The review as the office's readers see it |

- Every action carries `expectedVersion`; a stale one answers 409 `STALE_VERSION`.
- Refusals are audited (`REVIEW_REJECTED`) with their code.
- Successes are audited as `CASE_CLAIMED`, `REVIEW_RECOMMENDED`, `DECISION_RETURNED` and
  `DECISION_RECORDED`.
- The applicant's `GET /api/v1/applications/{id}` carries the decision (outcome, grounds,
  `simulated`), never the review. The `application` module reads it through the
  `DecisionLookup` interface, which the `review` module implements, so the dependency points one
  way.

**Simulated, everywhere.**
- The database refuses a decision that is not simulated.
- The API returns `simulated: true`.
- Every decision card, on the screen and in print, opens with "Simulated decision — no legal
  effect".
- The print layout keeps the notice and hides the controls.

## Deviation noted

The plan says "an INFO_REQUESTED case cannot be decided (409)". Here, a case that is not pending
decision is not claimable, so it is invisible to a decision authority who holds it at no stage:
they get 404, as for any case outside their reach. A decision authority already assigned to the
case (after it was returned to review, for example) gets 409 `INVALID_TRANSITION`. `ReviewIT`
covers both.

## Consequences

- Reviewers and decision authorities see only their own work and what they may claim: no
  browsing of the office's cases.
- Separation of duties holds in the database:
  - the service checks it first, for a clear message (`RECOMMENDER_CANNOT_DECIDE`);
  - the screen explains it instead of offering the action.
- An overlap alert blocks decisions until a cadastral officer resolves it (E-11). The screen says
  so and disables the button.
- Reassignment, workload and deadlines are not here: E-22 (supervisor) and E-16 (tracking).

*Copyright (c) 2026 FLYT. Author: Pythagore.*
