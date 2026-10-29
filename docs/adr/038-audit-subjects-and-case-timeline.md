# ADR-038: Audit subjects and the case timeline: the record and its office at the time, in the hash, read by office

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-28 | Project owner, Pythagore (FLYT) |

## Context

E-18 ("Audit timeline view") asks for a case and parcel reconstruction screen for the auditor
and the supervisor. MVP-07's acceptance criterion is that "a supervisor can reconstruct a case's
timeline from audit alone"; E-18's test is that the demo script's events are all present.

[ADR-027](027-office-context-on-audit-events.md) (decision D6) set the rules and left the
implementation to this task:

- the office is the office of the record the event is about, taken in the same transaction as
  the business change;
- the office is part of the hash;
- there is no retrospective office;
- events without an office are visible to readers everywhere only;
- until all of that holds, every audit endpoint needs its permission everywhere.

The office alone does not make a timeline:

- **Different records.** A case's events are about different records: the application, its
  document versions (`DOCUMENT`), its payment requests and provider reports (`PAYMENT_REQUEST`,
  `PAYMENT_RECORD`). Only the application's own events name the application.
- **Two parcels.** An alert is about two parcels, which may belong to two offices.

## Decision

### Subjects: what an event is about, found by the database

`audit_event` gains `subjects` (V19): a JSON array, `[{"type": "APPLICATION", "id": "…",
"office": "…"}]`.

- **Found by the database, never sent by the caller.** The insert trigger computes it with
  `audit_subjects(entity_type, entity_id)`, before the hash, in the same transaction as the
  business change. Any value the caller sends is overwritten.

| Event entity | Subjects |
| --- | --- |
| `APPLICATION` | the application, with its office now (none while a draft) |
| `DOCUMENT` (a version) | its application |
| `PAYMENT_REQUEST` | its application |
| `PAYMENT_RECORD` | the application of the request it matched; none for an unmatched reference |
| `PARCEL` | the parcel, with its office |
| `CONFLICT_ALERT` | both parcels, each with its own office |
| anything else (accounts, roles, the chain itself) | none (`NULL`) |

- **The office is the record's at that moment.** It is never taken from the actor or from
  anything later. A draft has no office until it is submitted, so the events of a draft carry
  none. No event recorded before V19 is ever given subjects: no backfill (ADR-027 point 2).
- **`audit_subjects` reads as the owner of the tables.** A system actor, such as the payment
  callback, finds the application too, even though row-level security would hide it from them.

### In the hash, without touching what was already recorded

The canonical form gets a second version.

- An event with subjects is encoded `snigf-audit-v2|…the v1 fields…|f(subjects::text)`.
- An event without subjects keeps `snigf-audit-v1`, byte for byte, so every existing hash and
  checkpoint still verifies.
- Adding, removing or changing subjects breaks the content hash either way, and verification
  reports `CONTENT_HASH`.
- The Java verifier (`ChainedEvent`) implements both versions. It reads `subjects::text` as the
  database hashed it, as it already does for `details`. `TimelineIT` checks Java and SQL agree on
  real case events.

### Reading by office

`audit_readable(subjects)` decides who reads an event, in the database, for the actor of the
transaction:

- **everywhere** (`audit.event.search` at scope ANY, or a special grant without an office): every
  event;
- **at an office**: an event one of whose subjects belongs to that office. An alert between two
  offices is readable from both;
- **anyone else, or nobody named**: nothing. An event without an office is readable everywhere
  only.

`GET /api/v1/audit/events` moves to `@access.canSomewhere(authentication, 'audit.event.search')`.

- The database filters every row with `audit_readable`.
- It gains `subjectType` and `subjectId`.
- Each event comes with its subjects and their office codes.

The other audit endpoints (verify, checkpoints, keys) keep `@access.can`. `AuditReadingScopeTest`
now allows exactly these two office-scoped endpoints.

### The timeline

`GET /api/v1/audit/timeline?type=APPLICATION|PARCEL&reference=…` (or `&id=…`), at the same
permission:

1. **The record is found from the audit trail itself.** Its id comes from the first event naming
   it by reference: `entity_type` = the type and `entity_ref` = the reference.
2. **Every readable event that has it as a subject**, oldest first, at most 500 (`truncated` beyond
   that). Each event comes with the actor's roles, the account's current name, its offices, the
   outcome, reason and details.
3. **Each event says whether it is intact**: its hash recomputed in Java from its own row and
   its stored link equals the stored hash. A tampered event shows. The full verification of the
   chain stays the auditor's and the supervisor's verify action.
4. **Reading a timeline is audited**: `AUDIT_TIMELINE_READ`, with the type, the reference and
   the number of events returned.
5. **404 when nothing is readable.** An unknown record, or one the reader may not read, answers
   `404 TIMELINE_NOT_FOUND` alike, so the answer reveals nothing.

### The screen

`#/audit/timeline?type=APPLICATION&reference=DEMO-APP-…` is for holders of `audit.event.search`
somewhere. It lists the events in order, each with:

- the time, the action in words and its code;
- the outcome;
- who acted: the account's name and roles, or the service's name;
- the office;
- what was recorded: the reason, and the details unfolded;
- the badge "intact" or "altered".

A field searches by reference. The case page and the parcel page link to it for those who may
read it. The supervisor's audit page now shows the event search, limited to their offices.

## Alternatives rejected

- **A single `office_id` column (ADR-027 as first written).** It cannot carry an alert's two
  offices, and it does not link a document's or a payment's events to their case. The subjects
  carry the office per record, so ADR-027's rule holds for each of them.
- **Finding a case's events through `details` written by each service.** The caller would choose
  the link, and a service that forgot it would hide events from the timeline. The database
  derives the subjects from the record instead.
- **Joining the live tables to build the timeline** (documents of the application, its payment
  requests). That is not "from audit alone": a deleted draft document or a later change would
  rewrite the past. The timeline reads `audit_event` only. Office codes and the account's current
  name are added for reading, and are never needed to know what happened.
- **Giving events recorded before V19 their subjects from today's tables.** ADR-027 forbids it:
  they stay readable everywhere only.

## Consequences

- **New:**
  - migration V19 and pgTAP test `21`;
  - `subjects` in `ChainedEvent`, `AuditEventView` and the event search;
  - `AuditTrail.timeline`;
  - the action `AUDIT_TIMELINE_READ`;
  - `TimelineIT`;
  - the frontend `AuditTimelinePage` and links from the case and parcel pages;
  - the guide section in `docs/guides/audit-trail.md`, and `docs/audit.md` with encoding v2.
- **Supervisors now read the events of their offices.** The e2e step "a supervisor verifies the
  chain without reading audit events" becomes "reads only their office's events".
  `PermissionMatrixIT` treats event search as "somewhere".
- **Offline verifiers** must implement v2 (`docs/audit.md`). Checkpoints are unchanged.
- **A draft's history** (creation, edits, uploads before submission) has no office. It is in the
  auditor's timeline, not the supervisor's: the office was not responsible for it yet.
