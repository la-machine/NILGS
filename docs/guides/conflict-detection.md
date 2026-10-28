# Overlap and duplicate detection, review and resolution

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Two parcels must not claim the same ground unnoticed, and the same title must not be recorded
twice. Every parcel version written, whether a creation or a correction, is compared by the
database, in the same transaction, with the current version of every other active parcel.
Each overlap or duplicate becomes an alert that keeps exactly what was compared. No code path
can write a version without the check, and the application can neither invent, edit nor
delete an alert. A cadastral officer reviews each alert and resolves it with an outcome and a
note; the author of either compared version cannot, whatever path they take. Decisions:
[ADR-030](../adr/030-overlap-and-duplicate-detection.md) (MVP-04, detection, task E-10) and
[ADR-031](../adr/031-alert-review-and-resolution.md) (review and resolution, task E-11).

| Alert | Raised when |
| --- | --- |
| `OVERLAP` | interiors intersect (DE-9IM `T********`) and the intersection is at least `SNIGF_CONFLICT_MIN_OVERLAP_M2` (1.00 m²) on the spheroid |
| `DUPLICATE_GEOMETRY` | an overlap covering at least `SNIGF_CONFLICT_DUPLICATE_SHARE` (95 %) of **each** parcel; it replaces the overlap, never both |
| `DUPLICATE_REFERENCE` | the same external reference after normalisation (letters and digits only, upper case: `TF 123/Mfoundi` = `tf-123-MFOUNDI`) |

A shared edge or a shared corner raises nothing. A sliver under the minimum raises nothing.

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V11__conflict_detection.sql` | tables `conflict_policy` and `conflict_alert`; `normalize_external_reference()`; `raise_conflicts_for_version()`; the detection trigger on `parcel_version`; the guard on updates; no delete; grants; the one-time check of existing parcels |
| `backend/src/main/java/org/snigf/mvp/parcel/ParcelVersionChecks.java` | published by the parcel module: called after each version is written, returns the alerts it raised |
| `conflict/application/ConflictService.java` | implements it: reads the alerts the version raised or superseded, records them in the audit trail; lists a parcel's alerts |
| `conflict/application/ConflictPolicyService.java`, `ConflictProperties.java` | applies `snigf.conflict.*` to the policy row, audits a change |
| `conflict/infrastructure/startup/ConflictPolicySync.java` | runs it at startup (system actor `conflict-policy`) |
| `backend/src/main/resources/db/migration/V12__alert_resolution.sql` | `holds_grant_at()`; the guard accepts `alert.resolve` by role or special grant at either office; note at most 2000 characters |
| `conflict/infrastructure/web/ConflictController.java` | `GET /api/v1/parcels/{reference}/alerts` |
| `conflict/infrastructure/web/AlertController.java`, `AlertOffices.java` | `GET /api/v1/alerts`, `GET /api/v1/alerts/{id}`, `POST /api/v1/alerts/{id}/resolution`; the offices of an alert for `@access.canAtAny` |
| `conflict/infrastructure/web/ConflictExceptionHandler.java`, `application/ConflictRefused.java` | refusals as problems with stable codes |
| `conflict/infrastructure/persistence/JdbcConflictAlertRepository.java` | reads alerts (with authors, areas, current versions), the three shapes in one UTM zone, the guarded resolution update, the policy |
| `conflict/domain/` | `ConflictAlert` (sides, `authoredBy`, `offices`, `problems` of a resolution), `ConflictPolicy` (bounds), the repository port |
| `identity/infrastructure/security/Access.java` | `canAtAny`: the permission at any of an alert's offices |
| `frontend/src/features/conflicts/` | `ParcelAlerts.tsx` on the parcel page; `AlertListPage`, `AlertDetailPage`, `AlertMap`, `ResolutionPanel`; `rules.ts` (who may resolve); `api.ts` |
| `backend/src/test/sql/t/13_conflicts.sql`, `14_alert_resolution.sql` | the Baseline's fixture set and every database rule; resolution by special grant |

## How it works

1. The parcel service inserts a `parcel_version` (creation or correction).
2. The `AFTER INSERT` trigger `parcel_version_detect_conflicts` (SECURITY DEFINER):
   - takes `pg_advisory_xact_lock(hashtext('snigf.conflict-detection'))`. A second writer
     waits until the first commits, then sees its parcel;
   - marks the parcel's open alerts `SUPERSEDED`, with the new version as the cause;
   - calls `raise_conflicts_for_version(new id)`. It finds candidates with `&&` (GiST index),
     then `ST_Relate(…, 'T********')`, measures `ST_Area(intersection::geography)`, classifies
     against the policy row and inserts the alert. Then it compares the normalised
     reference.
3. Each alert stores the pair with `parcel_a_id < parcel_b_id`, both version ids, the area
   (2 decimals), both shares (5 decimals), the intersection as a MultiPolygon (or the
   normalised reference), and the version that raised it. A partial unique index keeps one
   open alert per pair and type (`ON CONFLICT DO NOTHING`).
4. Back in Java, `ParcelService` calls `ParcelVersionChecks.afterVersionWritten`. The conflict
   module records `CONFLICT_ALERT_SUPERSEDED` and `CONFLICT_ALERT_RAISED` (facts, no geometry)
   under the write's correlation id and returns the alerts. They appear in the creation and
   correction answers (`alerts`), and the interface shows how many.
5. Rolling back the write rolls back its alerts.

### Review and resolution (E-11)

1. A reviewer opens **Alerts** (`#/alerts`): the alerts involving a parcel of their office,
   open ones by default (`GET /api/v1/alerts?office=YDE1&status=OPEN`).
2. The review page (`#/alerts/<id>`, `GET /api/v1/alerts/{id}`) shows both compared versions
   and the intersection on one map, then the two parcels side by side. The server says whether
   the person asking authored a compared version (`resolverIsAuthor`).
3. An entitled reviewer who authored neither version chooses an outcome and writes a note, then
   confirms. The interface sends `POST /api/v1/alerts/{id}/resolution` with
   `{expectedVersion, resolution, note}`.
4. `@access.canAtAny` checks `alert.resolve` at either parcel's office (role or special grant).
5. `ConflictService.resolve` refuses first, each refusal audited as
   `CONFLICT_ALERT_RESOLVE_REJECTED` with its code:
   - outcome or note missing, or note too long: 422 `RESOLUTION_REQUIRED`, `NOTE_REQUIRED`,
     `NOTE_TOO_LONG`;
   - not open: 409 `ALERT_NOT_OPEN`;
   - stale: 409 `STALE_VERSION`;
   - author: 403 `RESOLVER_IS_AUTHOR`.
6. The guarded `UPDATE … WHERE status = 'OPEN' AND version = :expected` runs.
   `conflict_alert_guard` (V12) requires:
   - the alert is `OPEN`;
   - the evidence is unchanged;
   - `version` is the old value + 1 (otherwise `serialization_failure`);
   - the resolver authored neither version and holds `alert.resolve` at either parcel's office,
     by role or by special grant in force (otherwise `insufficient_privilege`, answered
     `RESOLUTION_REFUSED`);
   - the note is 10 to 2000 characters (check constraints).

   `resolved_at` is set by the database.
7. `CONFLICT_ALERT_RESOLVED` is recorded with the outcome, both parcels and versions, and the
   note as reason.

| Outcome | When |
| --- | --- |
| `NO_CONFLICT` | both parcels legitimately coexist (an easement, an accepted tolerance) |
| `DATA_ERROR_CORRECTED` | a data entry error, corrected or recorded in the source file; a geometry correction of either parcel also supersedes the alert by itself |
| `REFERRED_FOR_ADJUDICATION` | the rights genuinely compete; the case goes to adjudication |

## How to do it again

### Change the thresholds

1. Set `SNIGF_CONFLICT_MIN_OVERLAP_M2` (0.01 to 1000) and `SNIGF_CONFLICT_DUPLICATE_SHARE`
   (0.5 to 1) in `.env`.
2. Restart the app: `docker compose up -d app`. The log says
   `Conflict detection: overlaps from … m2, duplicates from … of each parcel`, and a change is audited as
   `CONFLICT_POLICY_CHANGED` with the previous values.
3. New thresholds apply to versions written afterwards. Existing alerts keep their evidence.
   To re-check a parcel, correct it.

A value out of bounds stops the startup with the reason, as it should.

### Add an alert type

1. A migration: widen `alert_type`'s check, add the comparison to
   `raise_conflicts_for_version` (new function body via `CREATE OR REPLACE`, same
   `SET search_path`), and update the evidence check constraint.
2. `ConflictAlert.Type`, the frontend `api.ts` type and the `conflicts` texts in `fr.ts`/`en.ts`.
3. Fixtures in `13_conflicts.sql` and a case in `ConflictDetectionIT`.

### Read a parcel's alerts

```bash
curl --cacert infra/secrets/pki_root_ca_crt -b cookies.txt \
  https://localhost:47000/api/v1/parcels/DEMO-PCL-YDE1-2026-000001-7/alerts
```

This needs `alert.review` at the parcel's office: the demo account `cadastre.yde` for YDE1
([demo accounts](demo-accounts.md)). Others get 403.

### Resolve an alert by hand

1. Sign in as `cadastre.yde` and record a parcel over an existing one: a warning names the
   alert. Open **Alerts**, then **Review**: the page explains that you cannot resolve it.
2. Sign in as `surveyor.yde` (a second cadastral officer at YDE1) in another browser profile,
   open the same address, choose an outcome, write a note and confirm.
3. The alert shows the decision. `auditor` finds `CONFLICT_ALERT_RESOLVED` and the author's
   `CONFLICT_ALERT_RESOLVE_REJECTED` if they tried.

### Protect another record of two offices

Use `@access.canAtAny(authentication, '<permission>', @yourOffices.of(#id))`, with a bean that
reads the offices from the database (as `AlertOffices`). Add the endpoint to
`PermissionMatrixIT`; `EndpointAnnotationArchTest` accepts `canAtAny`.

## How to verify

| Check | Command | Expected |
| --- | --- | --- |
| Database rules and the fixture set | `docker compose --profile test run --rm --build dbtest` | test `13`: 35 ok; test `14`: 11 ok |
| Back end | `cd backend && ./mvnw verify` | `ConflictDetectionIT`, `ConflictPolicyTest`, `ConflictAlertTest`, `AlertResolutionSoDIT` pass |
| Interface | `cd frontend && npm test` | `ParcelAlerts.test.tsx`, `AlertDetailPage.test.tsx` pass |
| Journey | `bash infra/scripts/e2e.sh` | "a parcel over another raises an overlap alert…" and "the alert is reviewed on its own page…" pass |
| By hand | sign in as `cadastre.yde`, record a parcel over an existing one; resolve as `surveyor.yde` | warning after recording; alert on both parcel pages; no form for the author; resolved by the colleague |

The fixture set (pgTAP 13), from the Baseline:

| Fixture against the base parcel | Expected |
| --- | --- |
| clean, apart | nothing |
| shares an edge | nothing |
| shares a corner | nothing |
| 0.5 m² sliver | nothing |
| 30 % overlap | `OVERLAP` |
| 98 % copy | `DUPLICATE_GEOMETRY` |
| self-intersecting | refused at write (no alert, no version) |
| the same parcel typed in UTM 33N | `DUPLICATE_GEOMETRY` (normalised to WGS 84 first) |

## Pitfalls we hit

- **A threshold test beyond the database's bound.** A test set the minimum overlap above
  1000 m² to silence an alert, and the check constraint refused it. We moved the geometry
  instead of the threshold.
- **Expecting `P0001` on delete.** `reject_modification()` raises `restrict_violation`
  (`23001`). Tests must match that.
- **Concurrent writes missing each other.** Under READ COMMITTED, two transactions inserting
  overlapping parcels do not see each other's uncommitted row. The advisory lock closes that
  gap.
- **Areas in degrees.** `ST_Area` on the stored geometry (SRID 4326) gives square degrees.
  Always cast to `geography`.
- **A guard that knew only roles.** `alert.resolve` is delegable, so the application honoured
  a special grant that V11's guard then refused. V12 aligned the guard (`holds_grant_at`);
  pgTAP `14` tests it.
- **An alert of two offices.** `canAt` names one office; an officer of the second parcel's
  office was refused. `canAtAny` checks each office and takes at most one special-grant use.
- **`\gset` on a second run.** The demo seed's `INSERT … RETURNING \gset` failed when the role
  already existed ("no rows returned"); it now selects from the insert so one row always comes
  back.

## Production differences

- The 1 m² and 95 % are the Baseline's demonstration values. Surveyors must confirm them for
  real cadastral precision.
- With hundreds of thousands of parcels, the GiST index keeps candidate search cheap. The lock
  serialises writers only for the duration of the check, so watch write latency.
- The one-time check in V11 runs during migration. On a large existing register, plan the
  migration window, or check in batches.
