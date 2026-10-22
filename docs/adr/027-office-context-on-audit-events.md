# ADR-027: Office context on audit events, recorded at the time of the event

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted (decision D6); implementation with the case module | 2026-10-22 | Project owner, Pythagore (FLYT) |

## Context

The Baseline gives supervisors audit reading for their own office and requires that "a
supervisor can reconstruct a case's timeline from audit alone" (acceptance criterion, task
E-18). Audit events carry no office today, so the SUPERVISOR's `audit.event.search` at office
scope opens nothing ([permission matrix review](../permission-matrix-review.md), D6).

## Decision

1. **Audit events get an office, with the case module.** The office is the office **of the
   record the event is about** (the application's assigned office, the parcel's office),
   taken from that record **in the same transaction** as the business change. It is part of
   the canonical form and therefore of the hash: a later change breaks the chain and is
   detected by verification.
2. **No retrospective office.** Events recorded before the column exists keep **no office**.
   They are never given one from a user's current office, nor from any other present-day
   data. A backfill is allowed only from evidence written at the time of the event (for
   example an office already in the event's details), by a reviewed migration that records
   its provenance, under a separate decision. `audit_event` stays append-only: the
   application cannot update a row.
3. **Events without an office are not office-visible.** Office-scoped search returns only
   events whose office equals the reader's office; events with no office are visible only
   to readers holding the permission everywhere (the auditor).
4. **Until then, office-scoped audit reading and timeline reconstruction stay unavailable.**
   Every audit endpoint requires its permission everywhere (`@access.can`).
   `AuditReadingScopeTest` fails if one accepts an office-scoped permission, and
   `PermissionMatrixIT` checks that a supervisor gets 403 on event search. Switching the
   search to `@access.canAt` is part of implementing this ADR, together with its tests.

## Why

- An office taken from the user's **current** office would rewrite history: a person who
  moved offices would carry their old actions into their new office's view, and their
  former supervisor would lose them.
- The office of the record at the time is what a supervisor is responsible for.
- Hashing the office keeps the guarantee of ADR-007: what was recorded is what is read.

## Consequences

- The case module adds `office_id` to `audit_event` (nullable), sets it from the record in
  `AuditRecorder`, includes it in `audit_canonical()`, and tests: no backfill, hash covers it,
  office search excludes events with no office, a supervisor reads only their office.
- Until then the supervisor verifies the chain (ADR-026) but does not read events.
