# ADR-030: Overlap and duplicate detection in the database, on every version written

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-24 | Project owner, Pythagore (FLYT) |

## Context

MVP-04 (task E-10) asks the system to signal parcels whose geometries overlap and records
that repeat a reference, so a cadastral officer can review them before any decision on a
case. The Baseline fixes the rules:

- **overlap:** interiors intersect (DE-9IM `T********`) over at least 1 m², computed on the
  spheroid; parcels that only share an edge or a point are not flagged;
- **possible duplicate geometry:** an overlap covering at least 95 % of each parcel;
- **duplicate reference:** the same normalised external reference on two active parcels;
- compare the new version with the **current versions of active parcels**, through the
  spatial index; **one open alert per parcel pair**; the alert keeps both version ids, the
  intersection area, both shares and the intersection geometry as evidence;
- resolution (E-11): NO_CONFLICT, DATA_ERROR_CORRECTED or REFERRED_FOR_ADJUDICATION, always
  with a note; **the resolver authored neither compared version**; an open alert on a case's
  parcel blocks the decision (E-15).

The thresholds are "DEMO thresholds, configurable" and the Baseline's open question asks the
surveyors to confirm them.

## Decision

**The database detects.** An `AFTER INSERT` trigger on `parcel_version` (V11) runs the
check in the same transaction as the write. No code path can store a version without it, and
the alerts exist exactly when the version does (a rollback removes both). The application's
database account cannot insert an alert, change its evidence or delete it; only the
detection function (SECURITY DEFINER) creates alerts.

**What it does on each version:**

1. takes a transaction-scoped advisory lock, so two parcels written at the same moment see
   each other: the second check runs after the first commits, with a fresh snapshot;
2. closes the parcel's open alerts as `SUPERSEDED` (their evidence names its previous
   version), recording the version that superseded them;
3. compares the new version with the current version of every other active parcel: spatial
   index (`&&`), then `ST_Relate(…, 'T********')`, then the intersection's area on the
   spheroid (`ST_Area(geography)`); below the minimum, nothing; at or above the duplicate
   share of both parcels, `DUPLICATE_GEOMETRY`, otherwise `OVERLAP`;
4. compares the normalised external reference (letters and digits only, upper case) with
   the other active parcels' current ones: `DUPLICATE_REFERENCE`.

At most one open alert per pair and type (a partial unique index). A duplicate geometry is not
also reported as an overlap.

**Thresholds** are settings (`SNIGF_CONFLICT_MIN_OVERLAP_M2`, default 1.00 m²;
`SNIGF_CONFLICT_DUPLICATE_SHARE`, default 0.95). The application applies them at startup to
a one-row policy table the trigger reads; a change is recorded in the audit trail
(`CONFLICT_POLICY_CHANGED`) with the previous values. Both the application and the database
refuse values outside 0.01–1000 m² and 0.5–1.

**Reporting.** The parcel module publishes `ParcelVersionChecks`, called after each write in
the same transaction; the conflict module implements it, so parcels never depend on
conflicts. It returns the alerts the version raised (shown to the officer at once) and records
`CONFLICT_ALERT_RAISED` and `CONFLICT_ALERT_SUPERSEDED` under the same correlation id as the
parcel write. The facts are audited; the geometry stays in the alert.

**Resolution rules are in the database too** (guard trigger, V11), checked when E-11's
endpoint updates an alert: only an open alert moves; evidence never changes; the version
must match (optimistic lock); the resolver holds `alert.resolve` at the office of either
parcel and authored neither compared version; the note has at least 10 characters.

**Reading.** `GET /api/v1/parcels/{reference}/alerts` lists a parcel's alerts, seen from its
side, to holders of `alert.review` at the parcel's office (cadastral officers).

**Existing parcels** were checked once by the migration, oldest first.

## Why

- A check in Java after the insert could be skipped by a new code path, a script or a
  partial failure. In the trigger, it cannot.
- Version-to-version evidence (not parcel-to-parcel) keeps an alert true forever: what was
  compared is what is shown, even after corrections.
- Superseding on correction keeps the rule "one open alert per pair" honest without editing
  evidence: the new version is checked afresh.
- The advisory lock costs little at cadastral write volumes and closes the gap of two
  concurrent writes not seeing each other under READ COMMITTED.

## Alternatives considered

- **Detection in the application only:** easier to test in Java, but bypassable and not
  atomic with the write.
- **Asynchronous detection (a queue or scheduled job):** the officer would not see the alert
  when recording, and a decision could slip through before detection.
- **Parcel-to-parcel alerts updated in place:** loses the evidence of what was compared.
- **SERIALIZABLE isolation for parcel writes:** also closes the concurrency gap, but turns
  every concurrent write into a possible retry; the advisory lock is narrower.

## Consequences

- New: migration V11 (`conflict_policy`, `conflict_alert`, detection and guard triggers);
  pgTAP test `13` runs the Baseline's fixture set; the conflict module (`ConflictService`,
  `ConflictPolicyService`, `GET /parcels/{reference}/alerts`); settings `SNIGF_CONFLICT_*`;
  audit actions `CONFLICT_ALERT_RAISED`, `CONFLICT_ALERT_SUPERSEDED`, `CONFLICT_POLICY_CHANGED`.
- Every parcel write takes the detection lock: writes are serialised during the check (a few
  milliseconds per neighbour compared).
- The thresholds remain the Baseline's demonstration values until surveyors confirm them.
- E-11 builds on this: the alert list across an office, the side-by-side map with the
  intersection, and resolution through the guarded update.
