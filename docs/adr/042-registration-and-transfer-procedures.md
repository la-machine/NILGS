# ADR-042: Procedures A and B: immatriculation directe and mutation totale, from application to delivery

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| **Accepted, revision 3.** D22 to D25 answered by the project owner on 11 Nov 2026; P1 to P3 confirmed and P4 amended the same day, with three clarifications (D26 to D28); the step-2 deviation (procedure A as a new type) and D29 (four distinct acts) confirmed with step 3. Steps 2 and 3 of 10 built (11 Nov 2026); step 3 verified and step 4 built, and D30 to D34 decided by the owner (13 Nov 2026); step 5 built (14 Nov 2026); step 6 designed and D45 to D47 decided (16 Nov 2026). | 2026-11-11 | Project owner, Pythagore (FLYT) |

## Context

[ADR-040](040-land-register.md) §10 (D6) names three procedures as the business context of the register,
and leaves their workflows to later slices. Today:

- **Applications** (ADR-032 to ADR-037) go from a draft to a simulated decision: completeness review,
  review by claim, decision by a different person, payment before acceptance. One DEMO type exists,
  `DEMO_REGISTRATION`. An `APPROVED` case is final and changes nothing in the register.
- **The register** (ADR-040, ADR-041) creates synthetic titles only (`ck_land_title_synthetic_only`).
  It takes deposits from conservators and notaries, and registers signed TRANSFERs through the lock.
  A deposit may already name an application (`pending_transaction.application_id`), and nothing reads
  it.
- **Nothing connects them:**
  - no stage between intake and decision records fieldwork, publication or objections;
  - no approved case leads to a title;
  - no transfer case ties a deed to a title;
  - nothing records delivery.

This ADR closes those gaps for procedure A (*immatriculation directe*) and procedure B (*mutation totale
par vente*). Procedure C (*morcellement*) stays for a later slice.

Everything stays **DEMO**:
- no stage, period, document list or actor here is a validated legal requirement;
- each must be confirmed with MINDCAF and the conservations before real use (ADR-040 §10);
- the periods are settings, never constants in code.

## Decisions of the project owner (11 Nov 2026)

| | Question | Answer |
| --- | --- | --- |
| **D22** | Scope and order | One slice, Slice 4: procedure A first (it creates titles), then procedure B; one verification at the end |
| **D23** | The stages between intake and decision | Configured per application type version: each version lists its stages, each recorded by an officer with evidence. Publication opens an objection period (a DEMO setting); an open objection blocks the decision until a second officer resolves it. No universal timeline is hard-coded |
| **D24** | Government duties in procedure B | The duty receipt is deposited as evidence and checked by the officer of the stage. SNIGF neither computes nor collects any tax or stamp duty |
| **D25** | The last stage, "title issuance and delivery" | A DEMO notice of registration, issued in the ADR-039 registry and verifiable ("not a title, not a certificate"), and a recorded physical collection: date, recipient and their identity. Delivery is complete once the collection is recorded |
| **D26** | Who records the commission's report (P4, amended) | Its own stage, `COMMISSION_REPORT`, recorded and validated by an authorised commission secretary, or by a member designated for it. The cadastral officer keeps the cadastral review and the field visit, and never records the report of a visit they recorded |
| **D27** | The title record and the documents | Creating and registering the title in the register is distinct from issuing the DEMO notice. Generating the official title document stays reserved for the legally authorised production workflow: SNIGF generates none |
| **D28** | Identity of the title in a mutation | Mutation totale keeps the existing title and its number. Ownership changes are recorded as new inscriptions on it, never by creating a second title |
| **D29** | Four acts, kept apart (with step 3) | Registering the title in the register, generating the official title document, issuing the DEMO notice and the physical delivery are four distinct acts, by distinct functions and records. The long-term target is generating the actual land title in the established official layout, reserved for the legally authorised production workflow (its own ADR); SNIGF generates none today. Also confirmed: `DEMO_REGISTRATION` stays unchanged for backward compatibility, with its limits stated, and `DEMO_IMMATRICULATION` is procedure A's type. Stage corrections are traceable, never destructive |
| **D30** | The objector's identity (13 Nov 2026) | Kept with the objection, outside the land register; read one objection at a time by those who receive or decide objections, each read audited (see "As built in step 4") |
| **D31** | Open objections and the final decision | An open objection blocks approval and refusal alike |
| **D32** | Publications | Each kept as its own record; a stage recorded again after one calls for a new notice and a new objection period |
| **D33** | Independence of the objection's decider | A supervisor who recorded any stage of the case never decides its objections |
| **D34** | The applicant and objections | Objections and objectors stay confidential; after an upheld objection the applicant is told the stages carried out again and what they must do |

## Decision

### 1. A procedure is part of the application type's version (D23)

`application_type.requirements` gains a `procedure` object. It is versioned with the type (ADR-032),
so a case keeps the procedure of the version it was submitted under.

```json
"procedure": {
  "code": "A",
  "stages": ["CADASTRAL_REVIEW", "FIELD_VISIT", "PUBLICATION"],
  "registers": "CREATION",
  "delivery": true
}
```

**The stage catalogue** is fixed in code and in the database. A type may only list stages from it, and
only in an order the catalogue allows:

| Stage | What is recorded | By (permission, scope) | Evidence |
| --- | --- | --- | --- |
| `CADASTRAL_REVIEW` | the parcel checked against the cadastre: no overlap open (ADR-030), the parcel version reviewed, a finding | `procedure.stage.cadastral` (CADASTRAL_OFFICER, office) | the parcel version; optional documents |
| `FIELD_VISIT` | the date of the visit, the commission's members present (name and capacity, 1 to 12, DEMO), the cadastral officer's finding | `procedure.stage.cadastral` | optional documents of the case |
| `COMMISSION_REPORT` | the commission's report, validated (D26): the report's reference and date, the signatories named in it, the outcome; it follows a completed `FIELD_VISIT` | `procedure.commission.report` (COMMISSION_SECRETARY, office; or a member designated by an ADR-025 special grant) | the report, a stored document of the case (required) |
| `PUBLICATION` | the notice published, opening the objection period (§3) | `procedure.stage.publish` (INTAKE_OFFICER, office) | none: the notice is generated |
| `TITLE_VERIFICATION` | for B: the title, its current version, holders, charges and restrictions as read, and that the seller is a holder | `procedure.stage.title` (CONSERVATOR, office) | the title version read |
| `DUTIES_EVIDENCE` | for B: the duty receipt checked (D24): the receipt, its reference and the officer's finding; never an amount computed by SNIGF | `procedure.stage.title` | the receipt, a document of the case's deposit (required) |

- **Each stage record** is append-only, with its officer, time, outcome (`COMPLETED`, or
  `NOT_SATISFIED` with a reason) and evidence. A `NOT_SATISFIED` stage may be recorded again, and the
  latest record counts.
- **Separation.** The applicant never records a stage of their own case. The officer of a stage is
  never the case's reviewer or decision authority (`register_actor_interested` and the ADR-035 rules
  are extended). Whoever validates the commission's report did not record the field visit it reports
  on (D26). The database enforces all three.
- **The gate.** An approval needs every stage of the case's procedure to be `COMPLETED`, the objection
  period to be over and no objection open (§3, from V33):
  - a recommendation of approval is refused until then (`PROCEDURE_INCOMPLETE`);
  - an approved decision is refused too, re-checked since a stage may change in between;
  - a **refusal** is never gated: a claim found unfounded at the field visit can still be refused.

  Triggers on `review_step` and `decision` do the check in the database, after the existing guards.
  The code is told why with a reason code.
- **The applicant** sees each stage's name, its status and its date, never the officer, the finding's
  internal notes or the commission's members (the ADR-036 projection, pinned by its schema test).

### 2. The two DEMO types

| Type | Version | Procedure | Fields and documents |
| --- | --- | --- | --- |
| `DEMO_IMMATRICULATION` | 1 (a new type: see "As built in step 2") | A: `CADASTRAL_REVIEW`, `FIELD_VISIT`, `COMMISSION_REPORT`, `PUBLICATION`; registers `CREATION`; delivery | the applicant's capacity (`OCCUPANT`, `HEIR`), land use, occupied since, a statement; identity document, evidence of development, site plan; the commission's report is added by the commission secretary (step 3) |
| `DEMO_MUTATION` | 1 | B: `TITLE_VERIFICATION`, `DUTIES_EVIDENCE`; registers `TRANSFER`; delivery | the title number, the applicant's capacity (`BUYER`, `SELLER`), a statement; identity document; the deed and the duty receipt arrive with the notary's deposit (§5) |

Both are labelled DEMO in both languages (the existing `CHECK`).

### 3. Publication and objections (D23)

- **The notice.** Recording `PUBLICATION` creates a `procedure_notice`: the case, the parcel's
  reference and locality, the office, the opening time, and the end of the period. The end is the
  opening time plus `SNIGF_PROCEDURE_OBJECTION_PERIOD`, a DEMO setting (`P30D` by default, `PT5M` in
  development so the journey can be shown). The notice is append-only.
- **Public reading.** `GET /api/v1/public/notices` lists the notices whose period is open: the
  reference, locality, office and period only. It shows no applicant's name or document, and is
  throttled like public verification (ADR-039). Every page says "DEMO publication, no legal effect".
- **An objection** is recorded at the counter for a named objector, like D10's counter deposits:
  - its parties are the receiving officer (`procedure.objection.record`: INTAKE_OFFICER, office) and the
    objector, a `register_party` with their identity reference;
  - it carries a statement and its evidence;
  - one received after the period's end is recorded, marked late, and still needs a decision. Nothing
    is lost.
- **Deciding an objection.** A holder of `procedure.objection.decide` (SUPERVISOR, office) decides it,
  and must not be its recorder. The outcome is `UPHELD` or `DISMISSED`, with grounds. An upheld
  objection does not refuse the case by itself: the decision authority still decides, citing it.
- **The block.** While an objection is open, the case can be neither recommended nor decided. The
  database refuses both with `OBJECTION_OPEN`.

### 4. Registration after an approved case, procedure A

- **The registration task.** An `APPROVED` case whose procedure `registers = CREATION` appears in the
  conservators' queue, "Approved, to register". It is a view over the cases, with no table of its own.
- **The proposal.** A conservator proposes a CREATION from the case (`register_propose_from_case`):
  - the parcel version is the case's parcel at its current version;
  - the area comes from the parcel;
  - the holder is a `register_party` created from the applicant's identity (DEMO), with full
    ownership; a conservator may correct the holder only through ADR-040's proposal, with a reason;
  - the authority is the case's decision (its reference), and the evidence is the cited document
    versions.
- **The database refuses:**
  - a case not `APPROVED`;
  - a case of another procedure;
  - a second CREATION for the same case (unique);
  - a parcel on which a title already rests and is not closed;
  - a conservator who applied, recommended, decided, or recorded a stage on the case.
- **Registration** is ADR-040's: one conservator, signed with their security key and the `RECORD` key
  (D2: no dual control on a creation). The title's `origin` is `IMMATRICULATION`, and
  `land_title.application_id` names the case. `ck_land_title_synthetic_only` is replaced by a `CHECK`:
  `SYNTHETIC_DEMO` without a case, or `IMMATRICULATION` with one. `MORCELLEMENT` stays refused.
- **A case is not a title.** The case stays `APPROVED`. Its tracking shows "Registered: DEMO-TF-…"
  once the inscription is registered.
- **The record, not a document (D27).** Registering creates the title's record in the DEMO register,
  and nothing else. SNIGF generates no title document: no *titre foncier*, no duplicate, no image of
  one. That stays reserved for the legally authorised production workflow, which needs its own ADR.
  The notice of §6 is a separate, later act, and says it is not a title.

### 5. Procedure B, mutation totale

1. **Opening.** The applicant (buyer or seller) opens a `DEMO_MUTATION` case naming the title number.
   At submission, the database records the title (`application.title_id`) and refuses one it cannot
   find at the case's office.
2. **The deposit.** The notary deposits the deed and the duty receipt through their account (ADR-041),
   naming the case (`pending_transaction.application_id`, now checked). The case must be submitted,
   of procedure B, and on the same title. The lock and conflict rules are ADR-040 §4, unchanged. A
   counter deposit may name a case too.
3. **The stages.** `TITLE_VERIFICATION` by a conservator: the seller is a holder at the version read,
   no blocking restriction, no open conflict. `DUTIES_EVIDENCE` (D24): the receipt checked, citing the
   deposit's document.
4. **Review and decision**, ADR-035 unchanged: the authorised mutation is the case's approval.
5. **The TRANSFER.** A conservator registers it from the case: it completes the case's holding deposit,
   and the new holders are entered from the deed. ADR-040's checks stay: holding, no open conflict, no
   blocking restriction, the expected version. The database adds:
   - a deposit that names a case registers only once that case is `APPROVED`;
   - the conservator may not have handled the case, as in §4.

   The previous holders stay in the history.
6. A deed, a title image, a payment, a deposit or an approval **never changes a holder by itself**:
   only the registered TRANSFER does (ADR-040 §10).
7. **One title, kept (D28).** The mutation is a new inscription on the existing title, which keeps its
   identity and number. The database refuses a CREATION proposed from a procedure B case, and a
   TRANSFER from a case onto any title other than the one the case names.

### 6. Notice of registration and delivery (D25)

- **Distinct from registration (D27).** The notice is a second act, by the `issuance` module after
  the registration commits, in its own transaction. It is neither the title record nor a title
  document.
- **The notice.** When a case's CREATION or TRANSFER is registered, SNIGF issues a
  `DEMO_REGISTRATION_NOTICE` in the ADR-039 registry:
  - it uses the same flow, key and verification as the acknowledgment: a random number, a signed
    manifest, the exact PDF kept;
  - it names the title, the inscription number, the registration time and the holders;
  - it reads, on every page: "DEMO — notice of registration in the SNIGF demonstration register. Not a
    land title. Not a certificate of ownership. No legal effect";
  - issuance listens after the registering transaction commits, as for the acknowledgment. It is
    idempotent (operation key `REGISTRATION_NOTICE:<inscription>`), and an endpoint lets an officer
    of the office retry it. A failure never undoes a registration: the case then shows "notice not
    yet issued".
- **Collection.** The case shows "Ready for collection". An officer (`procedure.delivery.record`:
  INTAKE_OFFICER, office) records the physical handover:
  - the date;
  - the recipient, the holder or a representative, with their identity reference, and the
    representative's authority document when there is one;
  - the officer.

  The record is append-only, and recorded once per case. The officer is never the recipient, and the
  applicant never records their own collection.
- **Statuses after the decision.** They are read from the records, never stored as a field:
  1. `APPROVED`;
  2. registered;
  3. notice issued;
  4. collected.

  ADR-032's state machine is unchanged: `APPROVED` stays final.

### 7. Permissions (ADR-025)

| Permission | Family | Held by (baseline) | Never |
| --- | --- | --- | --- |
| `procedure.stage.cadastral` | BUSINESS | CADASTRAL_OFFICER, office | professional |
| `procedure.commission.report` | BUSINESS | COMMISSION_SECRETARY (new baseline role), office; **delegable**, so a designated member receives it by an ADR-025 special grant (two administrators) | professional; its holder never validates the report of a field visit they recorded |
| `procedure.stage.publish` | BUSINESS | INTAKE_OFFICER, office | professional |
| `procedure.stage.title` | BUSINESS | CONSERVATOR, office | professional |
| `procedure.objection.record` | BUSINESS | INTAKE_OFFICER, office | professional |
| `procedure.objection.decide` | BUSINESS | SUPERVISOR, office | professional; its holder never decides an objection they recorded |
| `procedure.delivery.record` | BUSINESS | INTAKE_OFFICER, office | professional |

Only `procedure.commission.report` is delegable. None is ADMINISTRATION or AUDIT, so no role mixes
duties. The catalogue, the code and
`PermissionCatalogueCheck` move together, as always.

### 8. Audit (ADR-038)

- **Actions:**
  - `PROCEDURE_STAGE_RECORDED`, `PROCEDURE_NOTICE_PUBLISHED`;
  - `PROCEDURE_OBJECTION_RECORDED`, `PROCEDURE_OBJECTION_DECIDED`;
  - `PROCEDURE_REGISTRATION_PROPOSED`, `PROCEDURE_NOTICE_ISSUED`;
  - `PROCEDURE_DELIVERY_RECORDED`;
  - `PROCEDURE_REFUSED`, with its code.
- **Subjects.** Every event takes the application as its subject, and the parcel or the title as a
  second subject. The case's timeline therefore reads from intake to delivery.

## Confirmed points (11 Nov 2026)

P1 to P3 are confirmed as written. P4 is amended by D26: the commission secretary role is added.

| | Point | Decision |
| --- | --- | --- |
| **P1** | Transfers outside procedure B: a deposit that names no case (the Slice 2 and Slice 3 journeys) | Still registered as today, labelled "outside a transfer case" on the title's history. A later decision may require a case for every transfer |
| **P2** | Who may lodge an objection | At the counter only, recorded by an officer for a named objector (as D10). An online objection needs its own decision on identity and abuse |
| **P3** | The objection period | `SNIGF_PROCEDURE_OBJECTION_PERIOD`, `P30D`, a DEMO value and never a legal deadline; `PT5M` in development |
| **P4** | The stage officers | CADASTRAL_OFFICER (cadastral review, field visit), COMMISSION_SECRETARY or a designated member (the commission's report, D26), INTAKE_OFFICER (publication, objections received, collection), SUPERVISOR (objection decision), CONSERVATOR (title verification, duty receipt) |

## Slice 4 scope and acceptance

**Delivered:** §1 to §8.

**Not delivered:**
- procedure C;
- online objections;
- fees for the procedures beyond ADR-037's DEMO catalogue;
- any tax computation;
- any certificate of ownership (ADR-040 §11);
- any real data.

**Acceptance:**
- every rule above has a pgTAP test, including the gate, the late objection, a second CREATION for one
  case, and a TRANSFER of a case not yet approved;
- integration tests walk procedure A and procedure B end to end through the API, with the signatures;
- `PermissionMatrixIT` covers every new endpoint;
- the applicant's projection is pinned by its schema test;
- e2e covers both procedures, from submission to the recorded collection;
- CI is green.

## Conflicts with existing ADRs and amendments

| ADR | Effect | Amendment |
| --- | --- | --- |
| ADR-032 | the type's `requirements` gains `procedure`; a new version of `DEMO_REGISTRATION` | dated note; the state machine unchanged |
| ADR-035 | the recommendation and the decision also need the procedure's gate | dated note; the decision guard's order gains the gate after the alert check |
| ADR-036 | the applicant's projection gains the stages and the post-decision statuses | dated note; schema test updated |
| ADR-039 | a second document type, `DEMO_REGISTRATION_NOTICE` | dated note; the registry and verification unchanged |
| ADR-040 | CREATION from a case with origin `IMMATRICULATION`; a deposit's case is checked; TRANSFER of a case needs its approval | dated note under "Amended by ADR-042"; §4 and §5 unchanged |
| ADR-025, ADR-038 | seven permissions and the COMMISSION_SECRETARY role; new audit actions with the application as subject | dated notes |

## As built in step 2 (11 Nov 2026)

Migration V31, the stage catalogue in code (`ProcedureStage`), the procedure in `Requirements`.

**One deviation, for the owner's review: procedure A is a new type.** §2 planned procedure A as version 2
of `DEMO_REGISTRATION`. It is a new type, `DEMO_IMMATRICULATION`, and `DEMO_REGISTRATION` keeps no procedure:
- the e2e journey and eight integration tests take `DEMO_REGISTRATION` cases through review and decision.
  Under step 3's gate, each would first need four stages and an objection period;
- a type without a procedure must stay tested, since §1 promises that such types are unchanged;
- its approval still leads to nothing in the register, as before.

Renaming or retiring `DEMO_REGISTRATION` is the owner's call. Either is a one-line change to the catalogue.

**As decided:**
- **The catalogue.** `procedure_stage`: six stages, with procedure, rank, prerequisite (`COMMISSION_REPORT`
  needs `FIELD_VISIT`) and the recorder's permission. It is append-only and read-only for the application.
  `ApplicationTypeConfigIT` compares it row by row with `ProcedureStage`.
- **The check, twice.** `Requirements.Procedure.problems()` refuses an invalid procedure at start, and
  `application_procedure_problems()` refuses it again on insert (`check_violation`), with the same reason
  codes. A stage of the other procedure is reported as such, never also as out of order.
- **The types.** `DEMO_IMMATRICULATION` (A, DEMO charge 25,000 XAF) and `DEMO_MUTATION` (B, the title number
  as a field, DEMO charge 15,000 XAF). Both charges read "not an official administrative fee" or "not a
  registration or stamp duty".
- **Permissions (D26, P4).**
  - The seven `procedure.*` permissions, all `BUSINESS`, at an office, never professional.
  - Only `procedure.commission.report` is delegable; the database refuses a special grant of the other six.
  - Roles: CADASTRAL_OFFICER (`stage.cadastral`); the new COMMISSION_SECRETARY (`commission.report`, and
    reading the office's cases); INTAKE_OFFICER (`stage.publish`, `objection.record`, `delivery.record`);
    SUPERVISOR (`objection.decide`); CONSERVATOR (`stage.title`).
- **Not yet used.** Nothing records a stage, and no case is gated: that is step 3.

**Tests.**
- pgTAP: `32` (33), and `09_authorization` (61 permissions, 14 baseline roles).
- Unit: `ProcedureTest` (6), `PermissionMatrixTest`.
- `ApplicationTypeConfigIT` (3 new tests). It now applies the whole catalogue: a catalogue holding only
  `DEMO_REGISTRATION` made the other two types unavailable.
- e2e: the applications journey picks its type from the picker.

## As built in step 3 (11 Nov 2026)

Migration V32, the `procedure` module (back end) and `features/procedures` (front end).

**Stage records.**
- **One writer.** `procedure_stage_record_add()` is the only path to `procedure_stage_record`; the application
  cannot insert directly. It refuses, with a reason code (SQLSTATE `PC001`):
  - a stage outside the catalogue or the case's procedure;
  - an officer without the stage's permission at the case's office;
  - a case not under review;
  - the applicant, the case's reviewer or its decision authority (`ASSIGNEE_CANNOT_RECORD`);
  - a commission's report recorded by the officer of the visit it reports on (`VISIT_RECORDER_CANNOT_REPORT`,
    D26), even if that officer also holds the report's permission.
- **What a record holds.** The outcome (`COMPLETED`, or `NOT_SATISFIED` with the reason in the finding), the
  finding, the stage's details, the cited stored versions of the case's documents, and up to five attached
  files (ADR-033 checks, retention class `PROCEDURE_STAGE_EVIDENCE`, kept by the orphan cleanup).
- **Details by stage.**
  - `CADASTRAL_REVIEW`: none from the officer. The database records the parcel, its reference and its version,
    and the open alerts; "completed" is refused while an overlap alert is open.
  - `FIELD_VISIT`: the date (from submission to today) and 1 to 12 members present, each with a name and a
    capacity.
  - `COMMISSION_REPORT`: its reference, its date (from the visit to today), 1 to 12 signatories, and the report
    itself as a file or a cited version. It needs a completed visit, and the database records which visit it
    reports on.
  - `PUBLICATION` and procedure B's stages answer `STAGE_NOT_YET_AVAILABLE` until V33 and V35.

**Corrections, traceable and never destructive.**
- Every record after the first of a stage names the latest one (`supersedes_id`) and gives a reason of 10 to
  2,000 characters. `supersedes_id` is unique, and one first record exists per case and stage, so the chain
  is linear.
- Nothing is updated or deleted: a trigger refuses both. The screens list the chain newest first, and the
  audit trail has `PROCEDURE_STAGE_CORRECTED` naming the record replaced.
- **Retries add nothing:** a second first record answers `STAGE_ALREADY_RECORDED`, a correction of a record
  already corrected answers `STALE_RECORD`. No idempotency key is needed.

**The gate** (`procedure_gate_problems`) lists:
- `STAGE_MISSING:<stage>` and `STAGE_NOT_SATISFIED:<stage>`;
- `REPORT_ON_SUPERSEDED_VISIT`, when the visit was corrected after its report;
- `PARCEL_CHANGED_SINCE_REVIEW`, when the parcel was corrected after its review.

It holds a recommendation of approval and an approved decision, never a refusal. The review screen lists the
problems. **Separation:** whoever recorded a stage of a case never claims it for review or decision
(`STAGE_RECORDER_CANNOT_REVIEW`).

**Who reads.**
- The case's readers at its office, its assignees and its stage officers (`procedure_readable`).
- The applicant reads no record: their case carries `stages`, with each stage, its status and its date only,
  pinned by `ApplicantPayloadSchema`.
- A member designated for a commission receives `procedure.commission.report` **and** `application.read` at
  the office, both by special grant, so they can read the case they report on.

**Endpoints.**
- `GET /api/v1/procedures/{id}`.
- `POST /api/v1/procedures/{id}/stages/{stage}`: multipart, a JSON part `record` and up to five `files`. There
  is one mapping per permission, each with its literal permission, and the database checks it again.
- `GET /api/v1/procedures/{id}/files/{fileId}`: an attachment only, its hash checked first, the read audited.
- `GET /api/v1/procedures/queue`: the officer's open stages. `Access.canSomewhere` now takes several
  permissions, and `EndpointAnnotationArchTest` checks every permission a rule names, not only the first.

**Screens.**
- "Procedure stages" (`#/procedures`), for whoever records a stage.
- The procedure panel on the staff case and on the assigned case: the stages, their records, corrections and
  files, the gate, and the form that records or corrects a stage.
- The gate's problems on the review form.
- The applicant's "Stages of the procedure".

**`DEMO_REGISTRATION`, kept unchanged, and its limits (D29).** It stays for backward compatibility: the cases
and tests of Slices 1 to 3 rely on it. It is the generic case flow of ADR-032 to ADR-037 and **not** procedure
A:
- it has no stages: no cadastral review, field visit, commission's report or publication is recorded for it;
- nothing gates its approval, so an approved `DEMO_REGISTRATION` case has been through no procedure;
- its approval leads to nothing in the register: no CREATION will be proposed from it (step 5 accepts procedure
  A cases only), no notice is issued and no delivery recorded;
- its name, "Land registration request (DEMO)", is the generic one; the picker shows `DEMO_IMMATRICULATION`
  beside it as "Direct first registration (DEMO)".

Retiring it, or renaming it to say "generic", is a one-line change to the catalogue, for the owner to decide.

**The four acts (D29).**

| Act | Where | Built |
| --- | --- | --- |
| Registering the title (CREATION or TRANSFER, signed) | the register, ADR-040 | step 5 (A), step 6 (B) |
| Generating the official title document, in the established official layout | a legally authorised production workflow, its own ADR | not in Slice 4 |
| Issuing the DEMO notice of registration | the ADR-039 registry, after the registration commits | step 7 |
| Physical delivery | a collection record | step 7 |

No act implies another: a registration issues no title document, a notice is not a title, and a collection
records only the handover.

**Tests.**
- pgTAP: `33` (56) and `31` (four retention classes).
- `ProcedureIT` (2): procedure A through the API, and the generic type without a procedure.
- `ReviewIT`, `TrackingIT`, `RetentionIT`, `PermissionMatrixIT` (five endpoints, two new reach kinds),
  `EndpointAnnotationArchTest`.
- Vitest: `procedures.test.tsx` (6) and the navigation.

## Step 3 verified (13 Nov 2026)

Asked by the owner before closing step 3. Each point is proven by a test, not only by design.

**1. Stage evidence integrity.**

| Property | How it holds | Proven by |
| --- | --- | --- |
| SHA-256 recorded | computed by the server from the bytes received (`DocumentFiles.receive`), never taken from the client; stored in `procedure_stage_file.sha256` | `ProcedureIT.stageEvidenceKeepsItsHashItsBytesItsReadersAndItsAudit`: the response and the database row equal the SHA-256 of the bytes sent |
| Recorded hash never changed | `procedure_stage_file` is append-only (trigger), written by `procedure_stage_record_add` only | pgTAP `33`: an `UPDATE` of the hash and a `DELETE` are refused (`23001`) |
| Original preserved | kept under a random key, moved atomically without replacing, read-only on disk; the orphan cleanup keeps it (`document_storage_keys_known`) | the IT reads the stored file: byte for byte the original, not writable; pgTAP `33`: the cleanup knows the key |
| Served unchanged | the hash is checked before every download; the response is the original bytes, an attachment, `no-store`, with `Repr-Digest` | the IT compares the bytes and the digest header |
| Tampering detected | a file altered in storage is refused (`409 FILE_INTEGRITY_FAILED`) and never served | the IT alters the stored file: refused, and no download is counted |
| Access control | the case's readers at its office, its assignees and its stage officers (`procedure_readable`, row-level security); never the applicant; never another office; never through another case (the file is looked up with its case) | the IT: applicant 403, an officer of another office 403, the file asked through another case 404 `FILE_NOT_FOUND` |
| Downloads audited | `PROCEDURE_STAGE_FILE_DOWNLOADED` for each download; a refused one is `PROCEDURE_REFUSED` with its code, on its own transaction | the IT counts both |

**2. A corrected field visit invalidates its commission's report.**
- The report is never changed: it stays, still naming the visit it reported on (`fieldVisitRecord`).
- The gate lists `REPORT_ON_SUPERSEDED_VISIT`, and the database refuses a recommendation of approval with
  `PROCEDURE_INCOMPLETE: REPORT_ON_SUPERSEDED_VISIT` until a new report is recorded on the latest visit, by
  someone other than that visit's officer.
- The decision trigger checks the same on its own: a visit corrected behind a recommendation (written as the
  schema owner, since the writer refuses it once the case is pending decision) still stops the approval.
- **A gap found and closed in V33.** The superseded report read `COMPLETED` to the applicant and left the
  secretary's queue, so nobody was asked for a new one. Since V33, a record that no longer holds reads `OUTDATED`
  everywhere: on the staff panel, in the officers' queue and to the applicant. The fix is in V33, not in V32, so
  databases that already applied V32 keep a valid Flyway checksum.
- Proven by pgTAP `33` (the status, the kept report, the queue, the applicant's view, the reason in the
  refusal, the decision trigger) and `ProcedureIT`.

## As built in step 4 (13 Nov 2026)

Migration V33, the objections in the `procedure` module, the public notices.

**The publication.**
- Recorded by an intake officer once every stage before it in the case's procedure is completed and still
  holds (`EARLIER_STAGES_INCOMPLETE`). Its outcome is always `COMPLETED`; the finding says where and how the
  notice was posted.
- The period is the setting `SNIGF_PROCEDURE_OBJECTION_PERIOD` (`P30D`; `PT5M` in the development `.env`).
  The service sends it, refusing any detail from the request (`DETAILS_INVALID`). The database bounds it again
  (one minute to a year) and records the parcel's reference and locality itself.
- It opens a `procedure_notice`: a public number `DEMO-PUB-<year>-<n>`, the parcel and locality, the office,
  the opening and the end of the period. Append-only. A corrected publication opens a new notice naming the
  one it replaces.
- While the period is open, the stage reads `IN_PROGRESS` and the gate `OBJECTION_PERIOD_OPEN`.

**The public notices** (`GET /api/v1/public/notices`, `#/notices`, no sign-in).
- Only the notices whose period is open, of cases still under examination, and only the current notice of each.
- Each carries what notification needs and nothing more: the notice's number, the procedure, the parcel's
  reference and locality, the office, the period. Never the applicant, the case's reference, a document or an
  objection. The function's result type is pinned by pgTAP, the fields by `ProcedureIT`.
- Throttled per address (`SNIGF_RATE_NOTICES_PER_MINUTE`, `429` with `Retry-After`), like public verification.
  Not audited per read: it is a public list, and reads would flood the trail.
- Every page says "DEMO publication, no legal effect".

**Objections, at the counter only (P2).**
- Recorded by an officer holding `procedure.objection.record` at the case's office, for a named objector: name,
  identity document and number (DEMO data), interest (`NEIGHBOUR`, `CLAIMANT`, `HEIR`, `CUSTOMARY_AUTHORITY`,
  `OTHER`), a statement (20 to 4,000 characters) and up to five files (ADR-033 checks, retention class
  `PROCEDURE_OBJECTION_EVIDENCE`).
- Received while the case is under review, pending decision or approved (not yet registered), once a notice is
  published. One received after the period is recorded and marked late: nothing is lost.
- Never by the applicant, the case's reviewer or its decision authority.
- An `Idempotency-Key` is required: a retry by the same officer with the same key gives back the first
  objection, and the retry's files are not kept. The same key with another objection is refused.
- Numbered `DEMO-OBJ-<year>-<n>`: the receipt the objector is given.

**Deciding an objection.**
- By a holder of `procedure.objection.decide` at the case's office, once (`OBJECTION_ALREADY_DECIDED`).
- Never by its recorder (`OBJECTION_RECORDER_CANNOT_DECIDE`), the applicant, the case's reviewer or decision
  authority (`ASSIGNEE_CANNOT_DECIDE`), or an officer who recorded one of the case's stages
  (`STAGE_RECORDER_CANNOT_DECIDE`). The database decides each; the screens only follow its hint.
- `UPHELD` or `DISMISSED`, always with grounds (20 to 4,000 characters, `OBJECTION_GROUNDS_REQUIRED`), kept with
  the decision and as the reason of the audit event `PROCEDURE_OBJECTION_DECIDED`.
- **An upheld objection triggers a review, never a refusal.** It names the stages of the case's procedure to
  examine again (`REVIEW_STAGES_REQUIRED`). Each reads `OUTDATED` and the gate `OBJECTION_REVIEW_PENDING:<stage>`
  until its officer records it again; the case stays as it was. When an earlier stage is recorded again after
  the publication, the notice is to be published anew (`PUBLISHED_BEFORE_CHANGE`), which opens a new period.
  The decision authority still decides the case.
- Whoever recorded or decided an objection of a case never claims it for review or decision
  (`OBJECTION_HANDLER_CANNOT_REVIEW`).

**The block.**
- While an objection is open, the database refuses every recommendation of approval or refusal and every
  decision, with `OBJECTION_OPEN`; the review service refuses first, with the same code. A return for
  information stays possible.
- `procedure_registration_problems()` states what blocks a registration from a case: `CASE_NOT_APPROVED`,
  `OBJECTION_OPEN`, and `OBJECTION_UPHELD_AFTER_DECISION` (an objection upheld after the case's decision, which
  the decision authority never weighed). Step 5 (V34) calls it before a CREATION; pgTAP `34` tests it now.

**Personal data.**
- The objector's identity is readable only by those who receive or decide objections at the case's office, one
  objection at a time, each read audited (D30): the application's account has no `SELECT` on those two columns, and
  no list returns them. Other readers of the case (cadastral officers, reviewers) see the name, interest and statement.
- The applicant reads no notice record and no objection: their projection keeps statuses and dates
  (`IN_PROGRESS`, `OUTDATED` added) and, after an upheld objection, the instructions of D34, pinned by
  `ApplicantPayloadSchema`.
- The audit trail names the objection by its id and number, never the objector's identity (tested).

**Endpoints.**
- `POST /api/v1/procedures/{id}/stages/PUBLICATION` (step 3's mapping, now accepted).
- `POST /api/v1/procedures/{id}/objections`: multipart, a JSON part `objection` and up to five `files`, with
  `Idempotency-Key`.
- `POST /api/v1/procedures/{id}/objections/{objectionId}/decision`: `outcome`, `grounds`, `reviewStages`,
  `applicantInstructions` (D34).
- `GET /api/v1/procedures/{id}/objections/{objectionId}/files/{fileId}`: an attachment, its hash checked first,
  the read audited (`PROCEDURE_OBJECTION_FILE_DOWNLOADED`).
- `GET /api/v1/procedures/{id}/objections/{objectionId}/objector`: the objector's identity, to objection officers,
  audited (D30).
- `GET /api/v1/procedures/objection-queue`: the open objections a supervisor may decide.
- `GET /api/v1/public/notices`: public.
- `GET /api/v1/procedures/{id}` now also returns the notices, the objections and whether the reader may record one.

**Screens.** The notice and the objections on the procedure panel, with the counter form and the decision form;
"Objections" (`#/objections`) for supervisors; the public notices (`#/notices`); the applicant's new statuses.

**Decided by the owner (13 Nov 2026), D30 to D34.** The five points raised with step 4, approved with a
clarification on the fifth:

| | Decision | As built |
| --- | --- | --- |
| **D30** | The objector's identity stays with the objection, outside the land register, inaccessible to unauthorised users; access restricted and auditable | stored in `procedure_objection`, never a `register_party`. The application's account has no `SELECT` on the identity columns. No list carries it: the case view says only whether the reader may ask (`identityVisible`). It is read one objection at a time (`GET .../objections/{id}/objector`, `procedure_objector_identity()`), by those who receive or decide objections at the office, and every read is audited (`PROCEDURE_OBJECTOR_IDENTITY_READ`, without the identity) |
| **D31** | Open objections block approval **and** refusal; no final decision bypasses an unresolved objection | `OBJECTION_OPEN` on every recommendation of approval or refusal and on every decision (triggers and service) |
| **D32** | Every publication is kept as a separate historical record; any stage recorded again after a publication triggers republication and a new objection period | each publication opens its own `procedure_notice`, append-only, the new one naming the one it replaces; `PUBLISHED_BEFORE_CHANGE` until the notice is published anew |
| **D33** | A supervisor who recorded any stage of a case never decides its objections | `STAGE_RECORDER_CANNOT_DECIDE` |
| **D34** | Objections and objectors stay confidential from the applicant; but when an upheld objection asks for more work, the applicant receives the procedural instructions: the stages to repeat and any action expected of them | an upheld decision may carry `applicant_instructions` (10 to 1,000 characters, written for the applicant; refused on a dismissal). The applicant reads `procedureInstructions`: the stages to carry out again, the publication anew after them, the action, and whether any of it is still pending (`application_procedure_instructions()`). Never the objection, its number, the objector, the statement or the grounds: the function's result type is pinned by pgTAP and the payload by `ApplicantPayloadSchema` |

**The upheld-objection workflow (owner, 13 Nov 2026).**
1. **Identify.** The decision names the stages of the case's procedure to examine again (`review_stages`,
   append-only with the decision). Nothing else is reopened.
2. **Reopen only those.** Each named stage reads `OUTDATED` (gate: `OBJECTION_REVIEW_PENDING:<stage>`) and enters
   its officers' queue. Every other stage keeps its record and stays `COMPLETED`. A stage that depends on a reopened
   one is reopened only when that one is recorded again: a commission's report once its visit is made again
   (`REPORT_ON_SUPERSEDED_VISIT`).
3. **Never destructive.** A stage is recorded again by a correction: a new record naming the latest one, with a
   reason. Earlier records, their files and hashes, the earlier notice and the objection stay exactly as they were;
   the database refuses any update or deletion.
4. **Then republish.** A new publication is refused until every stage before it holds again
   (`EARLIER_STAGES_INCOMPLETE`). It opens a new notice, with a new number and a whole new period from now. The
   first notice stays as history, and the objection stays on the notice it was lodged against.
5. **Never a refusal.** Upholding writes nothing on the case, its review or its decision: the case stays under
   review, and the decision authority decides it once the procedure holds again.

pgTAP `34` walks this on a full procedure A case (second case, `DEMO_PROC_F`); `ProcedureIT` walks it through the API.

**The audit-chain messages in the backend suite** (checked 13 Nov 2026). A full `mvnw verify` logs three ERROR lines
from `AuditTrailService`, all from two tests that tamper with the audit trail on purpose, as the schema owner:
- `AuditApiIT.aBrokenChainIsReportedAndNeverSigned` edits one event, then asks for a checkpoint and a verification:
  `AUDIT CHECKPOINT REFUSED: chain broken at position N` and `AUDIT CHAIN VERIFICATION FAILED: first mismatch at
  position N`. It restores the event and checks the chain verifies again.
- `AuditChainIT.aTamperedChainIsNeverSignedAndTheRefusalIsAudited` edits one event and asks for a checkpoint:
  `AUDIT CHECKPOINT REFUSED`.

Run alone, each test emits exactly its lines; the ten other tests of the two classes, run without them, emit none,
and no other test of the suite does. A normal run never reaches them.

**Tests.**
- pgTAP: `34` (119: publication, objections, the block, the upheld-objection workflow on a full procedure A case,
  the applicant's instructions, the identity one objection at a time), `33` (65, with the step 3 verification), `31`
  (five retention classes).
- `ProcedureIT` (4): the step 3 evidence integrity, and the journey from publication through two objections (one
  upheld, reopening the field visit only, with instructions to the applicant; one late) to the approval
  recommended. `ReviewIT`, `TrackingIT`, `RetentionIT`, `PermissionMatrixIT` (six more endpoints),
  `NoticeRateLimiterTest`.
- Vitest: `procedures.test.tsx` (14) and the navigation.

## As built in step 5 (14 Nov 2026)

Migration V34, the register module's proposal from a case, "Approved, to register" (`#/register`), the applicant's
"Registered: DEMO-TF-…".

**The gate: what a CREATION from a case needs** (`register_case_problems()`, checked at the proposal and again at the
signature, under the case's row lock):

| Refusal | When |
| --- | --- |
| `CASE_NOT_FOUND` | no such case, or not at an office where the conservator reads the register |
| `PROCEDURE_NOT_CREATION` | the case's procedure does not register a `CREATION`: procedure B (D28: a mutation keeps its title) and the generic `DEMO_REGISTRATION` (D29) alike, whatever their state |
| `CASE_NOT_APPROVED` | the case is not `APPROVED` |
| `OBJECTION_OPEN` | an objection awaits its decision, received after the approval included (V33) |
| `OBJECTION_UPHELD_AFTER_DECISION` | an objection was upheld after the decision, which the decision authority never weighed (V33) |
| `PROCEDURE_INCOMPLETE` | the procedure's gate is no longer empty, for a reason other than an objection (a parcel corrected since its review, say) |
| `CASE_ALREADY_REGISTERED` | a title was created from the case (`land_title.application_id` is unique) |
| `CREATION_ALREADY_PROPOSED` | a CREATION from it is in progress (a unique index: one in progress or registered per case; a withdrawn or rejected one frees it) |
| `PARCEL_ALREADY_TITLED` | an active title rests on the case's parcel |
| `PARCEL_CREATION_PENDING` | another CREATION on that parcel waits for its signature |
| `CASE_HANDLER_CANNOT_REGISTER` | the conservator applied, received it at intake, was assigned, recommended, decided, recorded a stage, or recorded or decided an objection (`register_case_handler()`); at the proposal **and** at the signature, so a second conservator who handled it cannot register another's proposal either |
| `REGISTER_FORBIDDEN` | no `register.propose` at the case's office (and `register.inscribe` to sign, as before) |
| `PARCEL_CHANGED` | at the signature: the proposal is not on the parcel's current version |

The authorisation is twofold: the endpoint's `@PreAuthorize` (`register.propose` at the case's office) and the
database's own `holds_at` checks, as for every register write.

**The proposal** (`register_propose_from_case()`, `POST /api/v1/register/cases/{id}/creation`, `Idempotency-Key`):
- the parcel at its current version and its area, from the case; the authority `DECISION`, its reference the case's;
  the evidence references the case and the decision, the evidence versions those the decision cited;
- the holder is the applicant, alone, in full ownership: a party of the office linked to their account, so the
  applicant can never register their own title (`ACTOR_HAS_INTEREST`, ADR-040). **One deviation from §4, for the
  owner's review:** §4 says the party is "created from the applicant's identity". SNIGF holds no verified identity of
  an applicant (`identity_verified` cannot be true, C9), so the conservator transcribes the DEMO name, kind and number
  from the case's identity document; the database checks they are DEMO data and refuses anything else
  (`HOLDER_INVALID`). A different holder is ADR-040's CORRECTION afterwards, under dual control, with a reason;
- `inscription.application_id` names the case; set only here, never changed (a trigger), and only on a `CREATION`
  (a `CHECK`). The signed bytes of such a CREATION append the case's id; every other inscription's bytes are
  unchanged, so every earlier signature still verifies. `InscriptionCanonical` does the same, and a title's
  verification proves the two agree.

**Registration** is ADR-040's single-control signature (D2), unchanged for the officer. `register_apply()` checks
the case again first (an objection may have arrived since the proposal), then creates the title with origin
`IMMATRICULATION` and `land_title.application_id`. `ck_land_title_synthetic_only` is replaced by
`ck_land_title_origin`: `SYNTHETIC_DEMO` without a case, `IMMATRICULATION` with one; `MORCELLEMENT` stays refused.

**After registration.** The case stays `APPROVED` (a case is not a title). Its applicant reads `registration`:
the title's number and the time, nothing else (`application_registration()`, `ApplicantPayloadSchema`); the screen
says it is not a land title nor a certificate. An objection is no longer received (`CASE_REGISTERED`). The audit
events of the proposal (`PROCEDURE_REGISTRATION_PROPOSED`) and of the registration (`REGISTER_INSCRIPTION_REGISTERED`)
take the case as a subject beside the title, so both timelines show them (`audit_subjects()` replaced).

**The four acts stay apart (D27, D29).** Registering creates the title's record and nothing else: no title
document, no notice, no delivery. The DEMO notice and the collection are step 7; the official title document is
not in Slice 4.

**Procedure B in step 5.** A CREATION from a procedure B case is refused (`PROCEDURE_NOT_CREATION`), and an
inscription can name a case only as a CREATION. The TRANSFER from a procedure B case onto the title it names, and
the deposit's case checked, are step 6 (V35); until then, ADR-040's TRANSFER through a deposit is unchanged (P1).

**"Approved, to register"** (`register_case_queue()`, `GET /api/v1/register/case-queue`): the approved procedure A
cases at the conservator's offices with no title yet, each with what stops it, whether this conservator may propose,
and its proposal in progress. A view over the cases, with no table of its own.

**Not built, for the owner.**
- A case blocked by `OBJECTION_UPHELD_AFTER_DECISION` stays blocked: `APPROVED` is final (ADR-032), so nothing reopens
  its decision. The way out (a new decision, a revocation, or a new case) needs a decision. *Decided as ADR-043 D41 and
  built in its step 5b (V36): a supervisor reconsiders the case in a new round, ending in a new decision.*
- The applicant's identity is transcribed, not verified (above).

**Tests.**
- pgTAP: `35` (72: every refusal above, the proposal's content, the signed bytes, the audit subjects, the check
  again at signature, the IMMATRICULATION title, the applicant's reading, no objection afterwards, no other origin,
  the privileges).
- `db-test.sh`: on 20 approved cases, the CREATION's signature racing an objection at the counter never ends with both
  (`bench/register_case_setup.sql`); a control case registers alone, then refuses an objection.
- `CaseRegistrationIT` (2): a procedure A case from submission to a signed IMMATRICULATION title that verifies, with
  the refusals through the API; a generic case refused. `InscriptionCanonicalTest` (the case appended),
  `PermissionMatrixIT` (two endpoints).
- Vitest: `register.test.tsx` (the queue and the proposal), `RegistrationNotice.test.tsx`.

## Design of step 6 (16 Nov 2026), decided by the owner

| | Question | Answer |
| --- | --- | --- |
| **D45** | Who transfers in a mutation totale (proposal A) | Exactly every holder of the title at the version verified, each matched to a transferor of the case by identity; anything less is refused |
| **D46** | Signatures of a TRANSFER from a case (proposal B) | Two, as D40: neither signer handled the case or received its counter deposit |
| **D47** | A title changed after verification (proposal C) | Registered against the current version only if its holders are still the verified transferors and nothing blocks; otherwise refused and the case stays held |

§5 was written before ADR-043. Its persons model (D35 to D37) and its rule that the approved ownership statement is the
only source of registered holders (D36) change what step 6 builds. The proposals below replace §5 points 1, 3 and 5
where they differ. Migration V37 (ADR-043's renumbering).

**The persons of a procedure B case (D37).** `case_parties_required()` also covers procedure B. The declaration gains
two roles and a capacity for B:
- `TRANSFEROR`: a holder of the title who transfers; `TRANSFEREE`: a proposed new owner, with the interest and exact
  share the deed gives; `REPRESENTATIVE` as in step 5a. A person never holds both `TRANSFEROR` and `TRANSFEREE`.
- The applicant applies as a transferor, a transferee, or the representative of one or several of them. A mandate
  covers every person the applicant represents. A transferor's consent is the deed, not a mandate.
- `DEMO_MUTATION` v2 replaces v1's `applicantCapacity` field (`BUYER`, `SELLER`) by the declaration. v1 stays readable
  and is no longer offered (ADR-032 versioning).
- At intake, every transferor's and transferee's identity is transcribed and checked as in D38 and D39. The ownership
  statement of a B case is the **transferees'**, recorded from the deed, each share citing it.

**Opening (§5.1).** At submission the database resolves the title number to `application.title_id` at the case's
office and refuses a title it cannot find, a closed one, or one already named by another open B case
(`TITLE_NOT_FOUND`, `TITLE_CLOSED`, `TITLE_CASE_OPEN`).

**The deposit (§5.2).** A deposit that names a case is accepted only if the case is submitted, of procedure B, on the
same title, and named by no other open deposit (`HOLDING` or `IN_CONFLICT`); a later one may name it once the earlier
one is withdrawn or refused. ADR-040 §4's lock and conflicts are unchanged.

**The stages (§5.3).**
- `TITLE_VERIFICATION` by a conservator records the title version read and, for each holder at that version, the
  transferor of the case who matches them (same identity kind and number as the register party, from the transferor's
  checked identity). Refused unless the transferors are exactly the holders at that version
  (`TRANSFERORS_NOT_HOLDERS`, **proposal A**), with no blocking restriction and no open conflict, and the case's
  deposit `HOLDING`.
- `DUTIES_EVIDENCE` cites a `TAX_OR_DUTY` document of the case's own deposit (D24), with its reference and the finding.

**The TRANSFER (§5.5).**
- A conservator proposes it from the case (`register_propose_transfer_from_case`). The holders are the decided
  statement's transferees, from their checked identities, as in ADR-043 §3. The conservator enters no holder. The
  charges are carried as ADR-040 derives them. It completes the case's holding deposit.
- **Signatures, proposal B:** two, as D40 for a CREATION from a case. Neither signer handled the case or received its
  counter deposit.
- **A title changed after verification, proposal C:** registration goes ahead against the current version only if
  its holders are still those verified and nothing blocks. Otherwise it is refused (`TITLE_CHANGED_SINCE_VERIFICATION`),
  and the case stays held, like the open point of step 5b.
- Refused besides: a case not `APPROVED` (also through ADR-040's plain path, for a deposit that names a case); another
  title; persons changed since the decision; a second TRANSFER for the case; a stale proposal (C5's binding, with the
  transferors).

**After it.** The case stays `APPROVED`. The applicant reads the title number, "transfer registered" and the time.
The previous holders stay in the history; the title keeps its number (D28). The notice and delivery are step 7.

**Tests (as the plan).** pgTAP `39`: every refusal above; `db-test.sh`: a case's deposit racing another deposit on the
same title; `CaseRegistrationIT`: a B case through the notary's account to a TRANSFER signed twice; `PermissionMatrixIT`;
`ApplicantPayloadSchema`; Vitest.

## As built in step 6 (16 Nov 2026)

Migration V37, the register module's TRANSFER from a case, the deposit naming a case, procedure B's two stages, the
applicant's "Transfer registered: DEMO-TF-…".

**The persons (D37).** `case_parties_required()` covers procedure B. `case_declaration_problems(declaration, 'B')` takes
the roles `APPLICANT`, `REPRESENTATIVE`, `TRANSFEROR` and `TRANSFEREE`, refuses a person holding both of the last two
(`TRANSFEROR_AND_TRANSFEREE`), requires a transferor and a transferee, gives shares to transferees only, and lets a
mandate cover transferors and transferees; procedure A keeps V35's rules. `case_owner_role()` names whose shares the
ownership statement records: the transferees of a B case. `tap.parties_ready` takes a B case through intake unchanged.
`DEMO_MUTATION` v2 drops v1's `applicantCapacity`.

**Opening (§5.1).** `application_title_resolve()`, a trigger at `DRAFT → SUBMITTED`, resolves `titleNumber` at the case's
office into `application.title_id` and refuses `TITLE_NOT_FOUND`, `TITLE_CLOSED`, `TITLE_NOT_ON_PARCEL` (the title does
not rest on the case's parcel) and `TITLE_CASE_OPEN` (another case on it is examined, or approved and not registered).
Two submissions naming one title are ordered by an advisory lock of their own. The title never changes afterwards. The
applicant reads the refusal as an item of the submission (`ApplicationService` maps the four title codes;
anything else from the declaration's guard stays `DECLARATION_INVALID`).

**The deposit (§5.2).** `transaction_deposit()` and `transaction_deposit_professional()` take an optional case reference
(`caseReference` in both endpoints). `deposit_case_resolve()` accepts a sale for a procedure B case being examined, on
the same title, named by no other open deposit (`CASE_NOT_FOUND`, `CASE_NOT_TRANSFER`, `CASE_TITLE_MISMATCH`,
`CASE_NOT_OPEN`, `CASE_DEPOSIT_OPEN`; a unique index besides). It locks the title, then the case, as registration does.
ADR-040 §4's lock and conflicts are unchanged: a second deposit on the title is recorded in conflict, whichever names the
case. A professional who is a person of the case never deposits for it. The deposit belongs to the case's audit timeline
too.

**The stages (§5.3).**
- `TITLE_VERIFICATION` by a conservator takes no details: the database records the title, its current version, each
  holder with the transferor matched by identity kind and number (`case_transferor_match()`), the unmatched
  transferors and the case's deposit. Completed only when the transferors are exactly the holders (D45,
  `TRANSFERORS_NOT_HOLDERS`), each transcribed and checked, with no blocking restriction, no open conflict and the
  deposit holding.
- `DUTIES_EVIDENCE` cites a `TAX_OR_DUTY` document of the case's own deposit and its reference (D24): no amount.
- **The gate (D47).** The verification holds while the title's holders (party, right, share) are those of the version
  verified and nothing blocks: a mortgage registered in between changes nothing, an opposition holds the case
  (`BLOCKING_RESTRICTION`), a correction of the holders holds it (`TITLE_CHANGED_SINCE_VERIFICATION`); a transferor
  transcribed again after it also holds it. The receipt holds while its deposit does.
- `procedure_case_header()` gives the procedure's readers the case's header, and `procedure_case_deposit()` gives whoever
  holds `procedure.stage.title` the open deposit, its title and its duty receipts. **Deviation, for the owner's
  review:** a conservator records procedure B's stages without `application.read` at the office, so the procedure
  screen read the header through the application's row policy and answered `CASE_NOT_FOUND`. The header and the
  receipts are now read by these two `SECURITY DEFINER` functions; the case's form, parcel and documents stay behind
  the row policy. The alternative, `application.read` for every conservator, would open every application of the
  office to them.

**The TRANSFER (§5.5).** `register_propose_transfer_from_case()` (`POST /api/v1/register/cases/{id}/transfer`,
`Idempotency-Key`, a reason only) proposes it onto the case's title at its current version, completing the case's
holding deposit, the holders the decided statement's transferees, each a register party from their latest checked
identity. Refused besides: `CASE_NOT_APPROVED`, an objection, `PROCEDURE_INCOMPLETE` or a precise gate code,
`PARTIES_INCOMPLETE`, `PARTIES_CHANGED_SINCE_DECISION`, `TRANSFER_ALREADY_PROPOSED`, `CASE_ALREADY_REGISTERED`,
`DEPOSIT_NOT_HOLDING`, `OPEN_CONFLICT`, `BLOCKING_RESTRICTION`, `REPLACEMENT_PENDING`, and a conservator who handled the
case or received its counter deposit (`CASE_HANDLER_CANNOT_REGISTER`). Two signatures (D46,
`inscription_dual_control`), each checked against the case. A title changed between the proposal and its signature
makes it stale (ADR-040's `TITLE_CHANGED`): withdrawn and proposed again at the current version, it registers if the
holders are still those verified (D47). ADR-040's plain TRANSFER never completes a deposit that names a case
(`DEPOSIT_OF_A_CASE`), and a TRANSFER from a case completes that case's deposit on its title only
(`CASE_DEPOSIT_MISMATCH`). A CREATION from a B case stays refused (`PROCEDURE_NOT_CREATION`).

**After it.** The title keeps its number; the transferees hold it; the previous holders stay in its history; the case
stays `APPROVED`. `application_registration()` gives the applicant the title's number, the time and `kind` (`TRANSFER`).
"Approved, to register" lists the transfers with their title; the conservators' evidence adds the transferors, the title
and the deposit. The notice and delivery are step 7.

**The signed bytes.** The TRANSFER's bytes carry, as V34 and V35 made them, the case, the decision, the statement and its
version, the deposit's documents and each new holder's identity version. The transferors are not in them: by D45 they
are exactly the holders at the version the TRANSFER expects, which the title's hash chain binds, and the gate checks
them again at each signature. *For the owner: if the transferors' identity versions should be signed too, it is one
more part appended to `inscription_canonical()`, the earlier bytes unchanged.*

**Corrected while building.** The DEMO_MUTATION v2 hints exceeded the catalogue's 200 characters and stopped the
application at start; shortened. A subquery alias in `application_title_resolve()` shadowed its record variable.

**Tests.**
- pgTAP `39` (77): the declaration; each refusal of the opening; the deposit's case (unknown, another title, a gift, a
  second open deposit); the conservator's reading of the deposit; D45 with a co-owner left out; the verification's
  record; the receipt; D47 after a mortgage, an opposition and a holders' correction; the TRANSFER's refusals, its
  content and holders; D46 (a stage recorder, the receiver of a counter deposit, self-confirmation); a mortgage between
  the proposal and its signature, then a new proposal; the registered title, its history, the applicant's reading;
  the privileges. `35` adapted: its procedure B case names a synthetic title.
- `db-test.sh`: on 20 titles, the case's counter deposit races a notary's deposit naming no case (one holds, the other
  in conflict); on 10 cases, two deposits naming the same case (exactly one accepted).
- `CaseRegistrationIT`: a sale from the applicant's submission (an unknown title refused) through the notary's deposit
  naming the case, both stages, the approval, to a TRANSFER signed twice; a stage recorder refused; the title verifies.
  `PermissionMatrixIT` (one endpoint), `ApplicantPayloadSchema` (`registration.kind`).
- Vitest: the transfer declaration, the transfer in "Approved, to register", the duty receipt's form, the applicant's
  notice.

## Amended by ADR-043 (14 Nov 2026)

- **The holders** of a CREATION from a case are the owners of the ownership statement the decision approved, from
  their checked identities; the conservator transcribes no holder any more (`HOLDER_INVALID` is gone, V35).
- **Two signatures** for a CREATION from a case (D40), each checked against the case; the step 5 single signature was
  ADR-040's D2.
- **Acceptance and approval** wait for the persons of the case to be transcribed and checked by a second officer.
- See [ADR-043](043-case-parties-ownership-and-reconsideration.md).

## Alternatives considered

- **Fixed stages in the state machine.** Simpler, but every change to a procedure would need code, and
  ADR-040 §10 asks for no universal timeline. Rejected by D23.
- **A simulated duty payment.** It would invent an amount and imply that SNIGF collects a tax.
  Rejected by D24.
- **Ending the case at registration.** It leaves the handover unrecorded, and that is where a paper
  title is lost or delivered to the wrong person. Rejected by D25.
- **New application states for each stage.** It multiplies the states and their guards for every
  type. Stage records beside one `UNDER_REVIEW` state keep ADR-032's machine and still let the
  database gate the recommendation.

## Implementation plan for Slice 4

Small steps; each ends with its tests green and is reported for review.

| # | Step | Content | Tests |
| --- | --- | --- | --- |
| 1 | **Recorded** (done) | This ADR, its index, the CHANGELOG | `DocumentationSyncTest` |
| 2 | **Procedures in the catalogue** (done) | V31: the stage catalogue; `requirements.procedure` validated by the database; `DEMO_REGISTRATION` v2 and `DEMO_MUTATION` v1; the seven permissions, the COMMISSION_SECRETARY role and the baseline roles | pgTAP: a type naming an unknown stage, or stages out of order, is refused; v1 cases unchanged; `RoleSeedIT`, `PermissionCatalogueCheckTest`, `PermissionMatrixTest` |
| 3 | **Stage records and the gate** (done) | V32: `procedure_stage_record`, its guard, the gate on recommendation and decision; endpoints and the officers' screens | pgTAP: each stage's officer and permission, the applicant and the reviewer refused, the gate; `ProcedureIT`; Vitest |
| 4 | **Publication and objections** (done) | V33: `procedure_notice`, `procedure_objection`, the period setting, the public notices endpoint and page, the supervisor's decision | pgTAP: the period, the late objection, the recorder never deciding, the block; IT; throttling; Vitest |
| 5 | **Procedure A: registration** (done) | V34: `register_propose_from_case`, origin `IMMATRICULATION`, `land_title.application_id`, the queue "Approved, to register" | pgTAP: every refusal in §4; `RegisterIT` from an approved case to a signed CREATION |
| 6 | **Procedure B: mutation** (done) | V37: `application.title_id`, the deposit's case checked, `TITLE_VERIFICATION` and `DUTIES_EVIDENCE`, TRANSFER needs the approved case | pgTAP: every refusal in §5; `db-test.sh`: a case's deposit racing another deposit; IT through the notary's account |
| 7 | **Notice and delivery** | V36: `DEMO_REGISTRATION_NOTICE`; issuance after registration, idempotent, with a retry endpoint; `procedure_delivery`; the applicant's statuses | pgTAP; `IssuanceIT`; `TrackingIT` and the schema test; Vitest |
| 8 | **Seed and e2e** | DEMO cases of both procedures through the real workflow; e2e journeys A and B, from submission to collection | idempotent seed; e2e |
| 9 | **Docs** | a guide `docs/guides/procedures.md`; the dated notes; manual chapters; the permission matrix; the README | `DocumentationSyncTest` |
| 10 | **Full verification** | backend, pgTAP, frontend, stack rebuild, probes, e2e, secret scan; then CI on the pushed branch | real results only |

### Risks

- **The gate touches ADR-035's guards.** Every existing review and decision test must still pass for
  types without a procedure. `DEMO_REGISTRATION` v1 has none, so existing cases are unaffected.
- **Relaxing `ck_land_title_synthetic_only`.** The replacement `CHECK` ties `IMMATRICULATION` to an
  approved case, and a pgTAP test proves that no other origin can be written.
- **Issuance after commit.** A notice that fails to issue must never block or undo a registration,
  and its retry must produce exactly one notice. An IT tests both with the issuance key unavailable.
