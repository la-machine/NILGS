# ADR-031: Alert review and resolution, with separation of duties checked twice

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-24 | Project owner, Pythagore (FLYT) |

## Context

[ADR-030](030-overlap-and-duplicate-detection.md) has the database raise overlap and duplicate
alerts on every parcel version. E-11 adds the review: "alert list, side-by-side map with
intersection, resolution with SoD". The rules, from MVP-04 and the Slice 1 design (C6b):

- a cadastral officer reviews and resolves;
- the resolution is `NO_CONFLICT`, `DATA_ERROR_CORRECTED` or `REFERRED_FOR_ADJUDICATION`,
  each with a mandatory note;
- the resolver authored neither compared version: a trigger compares `resolved_by` with both
  versions' `created_by`, and `AlertResolutionSoDIT` tests it.

Two facts shaped the design. First, an alert can join parcels of **two offices**, while every
endpoint check so far named one office. Second, `alert.resolve` is **delegable** (V7), so a
special grant may carry it. The V11 guard, however, only knew role assignments.

## Decision

**Endpoints** (conflict module):

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/alerts?office=&status=&type=&page=` | `alert.review` at `office` | alerts involving a parcel of that office, newest first, pages of 50 |
| `GET /api/v1/alerts/{id}` | `alert.review` at either parcel's office | both sides (compared and current version, author, area, share), the evidence, the three shapes in one UTM zone, the outcome, and `resolverIsAuthor` for the person asking |
| `POST /api/v1/alerts/{id}/resolution` | `alert.resolve` at either parcel's office | `{expectedVersion, resolution, note}`; the alert as resolved |

**Either office.** `Access.canAtAny(authentication, permission, offices)` passes when the
permission is held at any of the offices. It takes at most one special-grant use. The offices
come from the database (`@alertOffices.of(#id)`), never from the request. An unknown alert has
none, so only a holder "everywhere" passes, and then gets 404.

**Separation of duties, twice.** The service refuses first, with a precise code:

| Code | Status | Meaning |
| --- | --- | --- |
| `RESOLVER_IS_AUTHOR` | 403 | the resolver wrote one of the compared versions |
| `ALERT_NOT_OPEN` | 409 | the alert is already resolved or superseded |
| `STALE_VERSION` | 409 | the alert changed since it was read |
| `RESOLUTION_REQUIRED`, `NOTE_REQUIRED`, `NOTE_TOO_LONG` | 422 | no outcome, or a note outside 10–2000 characters |

The database's guard (V11, replaced by V12) checks the same rules again on the `UPDATE`. If
it disagrees, the service answers `RESOLUTION_REFUSED` (403) or the matching conflict code.
Each refusal is audited (`CONFLICT_ALERT_RESOLVE_REJECTED`, own transaction, with the code).
A resolution is audited as `CONFLICT_ALERT_RESOLVED`, with:
- the outcome;
- both parcels and versions;
- the note as the reason (the alert keeps it whole).

**Grants count in the database too.** V12 adds `holds_grant_at(user, permission, office)`:
- the grant is approved, not revoked, not expired and not used up;
- it covers that office, or everywhere.

The guard accepts a resolver who holds `alert.resolve` at either office by a role or by such
a grant. The author rule still comes first. V12 also bounds the note at 2000 characters.

**Interface.** A new "Alerts" place is shown to holders of `alert.review` somewhere:
- **The list** filters by office, status and type. The filters live in the address.
- **The review page** shows:
  - an SVG map: parcel A solid, parcel B dashed (told apart without colour), the intersection
    hatched, a scale bar, and a "zoom to the intersection" control for slivers;
  - the two parcels side by side;
  - then either the resolution form, the reason the person cannot resolve (author: separation
    of duties), or the decision once taken.

The form asks for an outcome (each explained) and a note with a counter, and confirms before
sending. A resolution made meanwhile overwrites nothing: the reviewer reloads. The dashboard
counts open alerts at the reviewer's offices.

## Why

- Checking in the service gives a precise, translatable answer and an audited refusal. The
  guard keeps the rule true for any path that reaches the table.
- `resolverIsAuthor` is computed by the server for the person asking. The interface does not
  need authors' ids and does not guess.
- One shared zone for both parcels and the intersection keeps the drawing honest: the shapes
  are the server's, transformed once by PostGIS.
- Accepting special grants in the guard aligns the two checks. Otherwise a grant approved
  under four eyes would be honoured by the application and refused by the database.

## Alternatives considered

- **Only the database check:** correct, but the person would see a generic refusal, and the
  refusal would not be audited.
- **`canAt` on the first parcel's office only:** an officer of the second office could not
  review an alert their own parcel raised.
- **Making `alert.resolve` non-delegable:** simpler, but it removes a lever the Baseline gives
  (a temporary reviewer for a backlog) and would change V7's catalogue.

## Consequences

- New:
  - migration V12;
  - pgTAP test `14`;
  - `AlertController`, `AlertOffices`, `ConflictExceptionHandler`, `ConflictRefused`;
  - `Access.canAtAny` (allowed by `EndpointAnnotationArchTest`);
  - `AlertResolutionSoDIT`, `ConflictAlertTest`;
  - audit actions `CONFLICT_ALERT_RESOLVED` and `CONFLICT_ALERT_RESOLVE_REJECTED`;
  - the frontend `features/conflicts` pages.
- A demonstration account `surveyor.yde` (a second cadastral officer at YDE1) resolves what
  `cadastre.yde` raises.
- E-15 builds on this: an open alert on an application's parcel blocks the decision.
