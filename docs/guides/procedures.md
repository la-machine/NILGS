# Procedures A and B: immatriculation directe and mutation totale

*Copyright (c) 2026 FLYT. Author: Pythagore.*

**DEMO only.** No stage, period, document list or actor here is a validated legal requirement. Each
must be confirmed with MINDCAF and the conservations before real use ([ADR-042](../adr/042-registration-and-transfer-procedures.md),
[ADR-040](../adr/040-land-register.md) §10).

This guide grows with Slice 4. It describes what is built today. The full design, and what comes next,
is in ADR-042.

| Step | Built | Where |
| --- | --- | --- |
| 2 | the procedures in the type catalogue, the stage catalogue, the permissions and the commission secretary role | V31 |
| 3 | stage records, corrections, evidence, the gate on approval, the screens | V32 |
| 4 to 7 | publication and objections, registration (A), mutation (B), notice and delivery | V33 to V36, to come |

## The three DEMO application types

| Code | Name | Procedure | What its approval leads to |
| --- | --- | --- | --- |
| `DEMO_REGISTRATION` | Land registration request (DEMO) | none: the generic case flow of ADR-032 to ADR-037, kept for backward compatibility | nothing in the register (limits below) |
| `DEMO_IMMATRICULATION` | Direct first registration (DEMO) | **A**: cadastral review, field visit, commission's report, publication | a conservator's signed CREATION: a new title (step 5) |
| `DEMO_MUTATION` | Transfer of a whole title by sale (DEMO) | **B**: title verification, duty receipt checked | a conservator's signed TRANSFER on the **same** title (step 6) |

An applicant chooses the type on **New application**. Each type is defined in
`backend/src/main/resources/application-types.json` (`SNIGF_APPLICATION_TYPES`), with its DEMO platform charge
in `payment-fees.json`. Neither charge is a government duty: SNIGF computes and collects no tax or stamp duty
(ADR-042 D24).

## A procedure in the catalogue

A type's `requirements` may carry a `procedure`:

```json
"procedure": {
  "code": "A",
  "stages": ["CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT", "PUBLICATION"],
  "registers": "CREATION",
  "delivery": true
}
```

- It is part of the type's **version**. Changing it creates a new version from now, and a case keeps the
  procedure of the version it was submitted under (ADR-032).
- It is checked twice. The application refuses an invalid one at start, with every reason, and the database
  refuses it on insert (`application_procedure_problems`, `check_violation`).

| Reason | Meaning |
| --- | --- |
| `PROCEDURE_UNKNOWN` | the code is not `A` or `B` |
| `PROCEDURE_REGISTERS_MISMATCH` | A must register `CREATION` and B `TRANSFER`: a mutation never creates a title (D28) |
| `PROCEDURE_NO_STAGES` | the list of stages is empty |
| `STAGE_UNKNOWN:<code>` | a stage outside the catalogue |
| `STAGE_OF_ANOTHER_PROCEDURE:<code>` | a stage of the other procedure |
| `STAGE_TWICE:<code>` / `STAGE_OUT_OF_ORDER:<code>` | a stage listed twice, or before a stage of lower rank |
| `STAGE_NEEDS:<stage><<needed>` | a stage listed without the one that must come before it |
| `PROCEDURE_UNKNOWN_KEY`, `PROCEDURE_DELIVERY_NOT_BOOLEAN` | a misspelt key, or `delivery` not `true` or `false` |

## The stages and who records them

The catalogue is fixed in `procedure_stage` (V31) and in the code (`ProcedureStage`). A test keeps the two
equal.

| Stage | Procedure, rank | Needs before it | Recorded by (permission) | Baseline role |
| --- | --- | --- | --- | --- |
| `CADASTRAL_REVIEW` | A, 10 | | `procedure.stage.cadastral` | CADASTRAL_OFFICER |
| `FIELD_VISIT` | A, 20 | | `procedure.stage.cadastral` | CADASTRAL_OFFICER |
| `COMMISSION_REPORT` | A, 30 | `FIELD_VISIT` | `procedure.commission.report` | COMMISSION_SECRETARY, or a designated member |
| `PUBLICATION` | A, 40 | | `procedure.stage.publish` | INTAKE_OFFICER |
| `TITLE_VERIFICATION` | B, 10 | | `procedure.stage.title` | CONSERVATOR |
| `DUTIES_EVIDENCE` | B, 20 | | `procedure.stage.title` | CONSERVATOR |

Three more permissions belong to the procedures:
- `procedure.objection.record` (INTAKE_OFFICER): an objection received at the counter;
- `procedure.objection.decide` (SUPERVISOR): its decision, never by its recorder;
- `procedure.delivery.record` (INTAKE_OFFICER): the collection of a notice.

All seven are `BUSINESS`, held at an office, and never professional.

**The commission's report (D26).** The cadastral officer records the cadastral review and the field visit, never
the report. The report belongs to the commission's secretary (the new baseline role `COMMISSION_SECRETARY`). For
a commission without one, a member is designated through a **special grant** of `procedure.commission.report`
at the office. Like every special grant, it is requested by one administrator and approved by another
(ADR-025). It is the only delegable procedure permission: the database refuses a special grant of any of
the other six.

### The limits of `DEMO_REGISTRATION`

It is kept unchanged so the earlier cases and tests keep working. It is **not** procedure A:
- no stage is recorded for it;
- nothing gates its approval;
- its approval creates no title and leads to no notice or delivery.

For a direct first registration, choose **Direct first registration (DEMO)**.

## Recording a stage (step 3)

1. Open the case: from **Procedure stages** (`#/procedures`), the stages waiting for you at your offices, or
   from **Cases**. The procedure panel lists each stage, its records and what stops an approval.
2. **Record this stage** appears only where the server says you may: your permission, at the case's office,
   while it is under review, and you are neither its applicant nor its reviewer or decision authority.
3. Give the outcome and a finding, the stage's details and any files:

| Stage | Details | Notes |
| --- | --- | --- |
| Cadastral review | none | the database records the parcel and its version; "completed" waits for open overlap alerts to be resolved |
| Field visit | the date; the members present, one per line as "name, capacity" | DEMO names only |
| Commission's report | its reference and date; the signatories, one per line | the report itself as a file is required; only after a completed visit; never by the officer of that visit |

**Correcting.** **Correct the latest record** asks for a new finding and a reason. The earlier record stays,
marked "replaced by a later record", and the new one says what it corrects. Nothing is ever overwritten. If
someone corrected it meanwhile, the server answers `STALE_RECORD`: reload.

**Corrections and the report.** Correcting a field visit after its report makes the report stale: the gate
shows `REPORT_ON_SUPERSEDED_VISIT` until the secretary records the report again, on the latest visit. The old
report is kept. Since V33 it reads **To be recorded again** (`OUTDATED`) on the panel, in the secretary's queue
and to the applicant.

**Stage statuses.**

| Status | Meaning |
| --- | --- |
| Not started (`NOT_STARTED`) | no record |
| Not satisfied (`NOT_SATISFIED`) | the latest record says what is missing |
| Completed (`COMPLETED`) | the latest record holds |
| Objection period open (`IN_PROGRESS`) | a publication whose period is not over |
| To be recorded again (`OUTDATED`) | the latest record no longer holds: its visit or parcel changed, an upheld objection named it, or an earlier stage was recorded again after the publication |

## Publication and objections (step 4)

**Publishing.**
1. The intake officer opens the case once every stage before the publication is completed and still holds.
2. **Record this stage** on *Publication and objections*: there is no outcome to choose and no period to give.
   The finding says where and how the notice was posted.
3. The database opens the notice: `DEMO-PUB-<year>-<n>`, the parcel's reference and locality, the office, and
   the end of the period, `SNIGF_PROCEDURE_OBJECTION_PERIOD` after now (`P30D`; `PT5M` in development).
4. The notice appears on the public page **Public notices** (`#/notices`), with only what notification needs.
   It leaves the page when its period ends, or the case leaves review.

**Receiving an objection (at the counter only).**
1. The objector comes to the office in person, with an identity document and the notice's number.
2. The intake officer opens the case and chooses **Record an objection received at the counter**: the
   objector's name, document and number (DEMO data contains "DEMO"), their interest, what they state, and any
   files they hand over.
3. The objection gets its number, `DEMO-OBJ-<year>-<n>`, to give the objector. One received after the period
   is recorded and marked **Late**.
4. Pressing twice, or retrying after a network failure, records one objection: the form sends one retry key.

**Deciding an objection.**
1. A supervisor of the office finds it under **Objections** (`#/objections`). They never see there one they
   recorded, and they may not decide one if they handled the case as reviewer, decision authority or stage
   officer.
2. **Decide this objection**: *Dismissed*, or *Upheld* with the stages to examine again; grounds always (20
   characters at least). The decision is final.
3. An **upheld** objection never refuses the case. It reopens only the stages it names: they read **To be
   recorded again** and wait for their officers; every other stage keeps its record. A report whose visit is made
   again is reopened in turn. Nothing earlier is changed: each stage is recorded again by a correction.
4. Optionally, **What the applicant must do**: written for the applicant (for example "be present at the new field
   visit"). The applicant sees the stages carried out again, the new publication, and this text; never the
   objection, the objector or the grounds.
5. Once the reopened stages hold again, the intake officer publishes the notice anew: a new number and a whole new
   period. The first notice stays as history. The decision authority decides the case afterwards.

**What objections block.**
- While one is open, nobody can recommend or decide the case, refusal included (`OBJECTION_OPEN`). A return
  for information stays possible.
- Registration from an approved case (step 5) will be blocked by an open objection, or by one upheld after the
  decision (`procedure_registration_problems`).

**Who sees the objector.** The officers who receive or decide objections at the office may open the identity
document, one objection at a time (**Show the identity document**); each opening is audited
(`PROCEDURE_OBJECTOR_IDENTITY_READ`). Other readers of the case see the name, interest and statement; the applicant
sees nothing of the objections; the audit trail names the objection, never the objector's identity.

## The gate on approval

| Problem | Meaning |
| --- | --- |
| `STAGE_MISSING:<stage>` | the stage has no record |
| `STAGE_NOT_SATISFIED:<stage>` | its latest record is not satisfied |
| `REPORT_ON_SUPERSEDED_VISIT` | the report is on a visit corrected since |
| `PARCEL_CHANGED_SINCE_REVIEW` | the parcel was corrected after its cadastral review |
| `OBJECTION_REVIEW_PENDING:<stage>` | an upheld objection named the stage, recorded before the decision |
| `PUBLISHED_BEFORE_CHANGE` | an earlier stage was recorded again after the publication: publish anew |
| `OBJECTION_PERIOD_OPEN` | the publication's period is not over |
| `OBJECTION_OPEN` | an objection awaits its decision |

- While any problem remains, approval can be neither recommended nor decided (`PROCEDURE_INCOMPLETE`).
- A **refusal** is never held back.
- Whoever recorded a stage of a case can neither review nor decide it (`STAGE_RECORDER_CANNOT_REVIEW`); nor can
  whoever recorded or decided one of its objections (`OBJECTION_HANDLER_CANNOT_REVIEW`).
- While an objection is open, no recommendation and no decision at all, refusal included (`OBJECTION_OPEN`).

## What each person sees

| Who | Sees |
| --- | --- |
| The case's officers at its office, its reviewer and decision authority | every record, corrections included, with officers, findings and files; the notices; the objections, without the objector's identity |
| The officers who receive or decide objections at its office | the same, and the objector's identity document |
| The applicant | each stage's status and date, never the officer, the finding, the evidence or an objection |
| Anyone, signed in or not | the open notices: number, procedure, parcel, locality, office, period |
| An officer of another office | nothing |

## The persons of a case (ADR-043, step 5a)

For a procedure A case, the applicant declares, in the draft, **Persons of the case**:
- in what capacity they apply: on their own behalf, for another person, or for several intended owners;
- every person, each with explicit roles (applicant, representative, intended owner), a DEMO name and identity, and the
  applicant's phone;
- each intended owner's interest and exact share, from the supporting documents. Nothing is split equally by default;
- whom each representative represents.

The draft says what is still missing; the case cannot be submitted until nothing is.

At intake:
1. The intake officer scans each document presented at the counter (identity documents, ownership evidence, mandates)
   in **Persons of the case (DEMO)**.
2. They transcribe each person's identity from the document, the ownership statement (each share citing its
   document) and each mandate (citing the mandate). A correction is a new version with a reason; the earlier one stays.
3. Another intake officer, or a supervisor, checks each one: **Corresponds to the document**, or **Does not match**
   with a note. Nobody checks their own transcription.
4. The case is accepted only when nothing remains. The decision approves the ownership statement as it then stands.

Every entry reads **DEMO, not independently verified**: a check says the entry corresponds to the document presented,
never that the document or the person was authenticated. The applicant sees each person's status, never the officers or
the notes.

**Rights.** Sole ownership is one owner at 1/1. Co-ownership is at least two co-owners whose shares total one. Usufruct
and bare ownership come together, each totalling one, and a usufruct says its term: for life, or until a date. A
partial dismemberment is refused (`PARTIAL_DISMEMBERMENT_NOT_SUPPORTED`).

**An expired identity document.** The officer may enter the date printed on the document ("Document valid until"). If
it had already expired, the case waits (`IDENTITY_DOCUMENT_EXPIRED`) and the applicant reads "Identity document
expired". Either a valid document is presented and transcribed as a new version, or a supervisor who did not
transcribe it authorises an exception under the DEMO policy, giving a reason of at least 20 characters. Whether such
an exception is lawful is for MINDCAF to confirm; this is DEMO only.

**Retention.** Counter documents are kept under `CASE_IDENTITY_DOCUMENT`, `CASE_MANDATE` or
`CASE_OWNERSHIP_EVIDENCE`; documents the applicant uploads under a class by category. A legal hold may cover a whole
case, including documents added after it was placed. No period is set and nothing is disposed of.

## Registration of procedure A (step 5)

An approved procedure A case waits in **Land register → Approved, to register**, for the conservators of its
office. Each entry says what holds it, if anything.

1. A conservator who never handled the case (never applied, received, reviewed, decided, recorded a stage or an
   objection on it) opens **Propose the CREATION**.
2. They give a reason. Everything comes from the case: the parcel at its current version and its area, the decision
   as the authority, the versions it cited as the evidence, and the holders: the owners and shares of the approved
   ownership statement (ADR-043), never the applicant as such, never a representative.
3. The proposal joins **Proposals waiting for a signature**. It needs two conservators who never handled the case: the
   proposer signs and asks for confirmation, another conservator confirms. The database checks the case at each
   signature. Both may first **Examine the approved owners and their evidence**: the owners, rights, terms,
   identities as transcribed and checked, validity and exceptions, mandates and cited documents, read-only, every
   reading recorded, never a phone or an address.
   The signatures cover the decision, the statement and its version and each owner's identity version: if any of
   them, or the parcel, changes in between, the signature is refused with `STALE_PROPOSAL`; withdraw or reject the
   proposal and propose again.
4. The title is created with origin `IMMATRICULATION`, naming the case. The case stays **Approved**; its applicant
   reads "Registered: DEMO-TF-…", and that it is not a land title.

**Refused:** a case not approved; a procedure B case or the generic type (`PROCEDURE_NOT_CREATION`); an objection
open, or upheld after the decision; a procedure no longer complete; a second CREATION for the case; a parcel already
under an active title or awaiting another CREATION; a conservator who handled the case. Once the title is registered,
no objection is received (`CASE_REGISTERED`).

**Registering issues nothing.** No title document, no notice, no delivery: those are separate acts (below).

## Reconsideration of an approved case (ADR-043, step 5b)

An objection upheld after the decision stops the registration (`OBJECTION_UPHELD_AFTER_DECISION`): the decision
authority never weighed it. Only a reconsideration reopens the case.

1. If a CREATION from the case awaits its signatures, a conservator withdraws or rejects it first, with a reason.
2. A supervisor of the office who never applied, reviewed, decided, recorded a stage of the case, nor upheld the
   objection, opens **Reconsideration → Open a reconsideration…** in the case's procedure card. They write why (20
   characters at least) and may add stages to examine again; never fewer than the objections named.
3. The case is under review again, in round 1 (then 2, …). The stages the objections named, those added, and the
   publication read **To be recorded again**; each is recorded again by a correction, and the notice is published
   anew with a whole new period. Nothing earlier is changed: the first decision, the records and the objections stay.
4. A new review and a new decision follow: anyone may review again, but the decision authority must never have
   decided the case, and the supervisor who reopened it neither reviews nor decides it.
5. A new **approval** makes the case registrable again (two conservators, as above); a new **refusal** ends it.

**Refused:** without an upheld objection after the decision; once the title is registered (a registered title is
corrected or cancelled in the register, under two signatures, never reopened through its case); while another
reconsideration is open. The applicant reads that the case is reconsidered, which stages are carried out again, and
that a new decision will follow; never why, nor the objection or who reopened it.


## Procedure B, the mutation totale (step 6)

A sale of a whole title, through `DEMO_MUTATION` v2. DEMO only: no sale, deed, duty or holder is real, and SNIGF computes
and collects no tax.

1. **The applicant** (the buyer, the seller, or the representative of either) opens the case, names the title's DEMO
   number and the parcel it rests on, and declares the persons: each **transferor** (a holder of the title who
   transfers) and each **transferee** (a new owner, with the right and exact share the deed gives), never both for
   one person. At submission the database finds the title at the parcel's office; it refuses a title unknown there,
   closed, not resting on the parcel, or named by another open transfer case.
2. **Intake** as for procedure A: the documents scanned at the counter, each transferor's and transferee's identity
   transcribed and checked by a second officer; the ownership statement is the transferees', from the deed.
3. **The notary** deposits the sale through their account, the deed and the duty receipt among the documents, with the
   case's reference in **Transfer case (reference)**. A counter deposit may name it too. One open deposit per case, on
   the case's title. The deposit locks the title as any deposit does; a second deposit on the title is in conflict.
4. **A conservator** records **Title verification**: the database reads the title at its current version and matches
   each holder to a transferor by identity kind and number. It is complete only when the transferors are exactly the
   holders, each checked, with no blocking restriction, no open conflict, and the case's deposit holding. Then
   **Duty receipt checked**: choose the receipt of the case's deposit and give its reference.
5. **Review and decision** as for any case.
6. **The TRANSFER**: in **Land register → Approved, to register**, the case reads "Transfer of title DEMO-TF-…". A
   conservator who never handled the case nor received its deposit opens **Propose the TRANSFER** and gives a reason.
   The database puts it on the title at its current version, completing the case's deposit; the holders are the
   approved statement's transferees. A second conservator who never handled the case confirms it.
7. The title keeps its number; the transferees hold it; the earlier holders stay in its history. The case stays
   **Approved**; the applicant reads "Transfer registered: DEMO-TF-…", and that it is not a land title.

**When the title changes in between (D47).** A charge registered after the verification changes nothing; the proposal
it made stale (`TITLE_CHANGED`) is withdrawn and proposed again at the new version. A blocking restriction, or a change
of the holders, holds the case (`BLOCKING_RESTRICTION`, `TITLE_CHANGED_SINCE_VERIFICATION`): it stays held, as an
approved case with an objection upheld after its decision does.

**Refused:** a TRANSFER through the register's plain form completing a deposit that names a case
(`DEPOSIT_OF_A_CASE`); a CREATION from a procedure B case (`PROCEDURE_NOT_CREATION`); a second TRANSFER; persons changed
since the decision; a conservator who handled the case or received its counter deposit.

## The four acts (D29)

These four acts are kept apart:
1. registering in the register (step 5 for procedure A's CREATION, step 6 for procedure B's TRANSFER, both done);
2. generating the official title document (a future, legally authorised workflow; none today);
3. issuing the DEMO notice of registration (step 7);
4. the physical delivery (step 7).

None implies another.

## How to do it again

1. Add a stage: one row in a new migration (`procedure_stage` is append-only) and one constant in
   `ProcedureStage`, with the same procedure, rank, prerequisite and permission. `ApplicationTypeConfigIT`
   fails until both match.
2. Add the stage to a type in `application-types.json`. At the next start, the type gets a new version.
3. Give its permission to a role in a migration, with the same change in `BaselineRole`. Then regenerate
   the tables of [the permission matrix](../permission-matrix.md): `PermissionMatrixTest` prints them.

## Verify

```bash
docker compose --profile test run --rm --build dbtest     # pgTAP 32 to 39, and the races
cd backend && ./mvnw verify -Dit.test='ApplicationTypeConfigIT,ProcedureIT,ReviewIT,RoleSeedIT,PermissionMatrixIT' \
  -Dtest='ProcedureTest,PermissionMatrixTest,EndpointAnnotationArchTest,NoticeRateLimiterTest'
cd frontend && npx vitest run src/features/procedures
```

## Pitfalls

| Symptom | Cause | Fix |
| --- | --- | --- |
| The application stops at start with `Invalid application type catalogue: ... STAGE_...` | a procedure in `application-types.json` names a stage out of the catalogue, of the other procedure, or out of order | fix the list; the message gives every reason |
| `malformed array literal` from a database function that collects reasons | a text literal appended to `text[]` without a type | write `'REASON'::text` |
| An integration test leaves the two procedures' types unavailable | it applied a catalogue holding only `DEMO_REGISTRATION`: a type left out of a catalogue is made unavailable | apply every type, with the one changed (`withEveryOtherType` in `ApplicationTypeConfigIT`) |
| A stage endpoint answers `ERROR` instead of the reason | the reason was read from PostgreSQL's message `ERROR: <REASON>` without skipping `ERROR:` | match `: ([A-Z_]+)`, as `ProcedureRefused` does |
| `EndpointAnnotationArchTest` refuses an endpoint | its rule computes the permission (`@bean.permissionOf(...)`), or combines checks the pattern does not accept | one mapping per permission, each literal; `@access.canSomewhere(authentication, 'a', 'b')` for any of several |
| A designated member records the report but cannot open the case | they received `procedure.commission.report` only | grant `application.read` at the office too |
| The approval of a procedure A case is refused with `PROCEDURE_INCOMPLETE` although every stage shows completed | the objection period is still open, or a stage is to be recorded again | see the gate's list on the review form |
| **Record this stage** does not appear on the publication | a stage before it is not completed or no longer holds (`EARLIER_STAGES_INCOMPLETE`) | record or correct that stage first |
| A publication answers `DETAILS_INVALID` | the request sent a period or a locality | send none: the period is the setting's, the locality the cadastre's |
| A case can be neither recommended nor refused: `OBJECTION_OPEN` | an objection awaits its decision | a supervisor who did not record it decides it from **Objections** |
| A supervisor cannot decide an objection | they recorded it, review or decide the case, or recorded one of its stages | another supervisor of the office decides it |
| The development journey waits 30 days for the objection period | the local `.env` predates `SNIGF_PROCEDURE_OBJECTION_PERIOD=PT5M` | run `bash infra/scripts/dev-setup.sh`, then recreate the app |
| A test needs the period over | the notice is append-only | as the schema owner, disable `procedure_notice_append_only`, move `opens_at` and `closes_at` back, enable it again (pgTAP `34`, `ProcedureIT`) |
| The application stops at start with `... hint: give both languages or neither (at most 200 characters)` | a field's hint in `application-types.json` is longer than 200 characters | shorten it; put longer guidance in the screens |
| A transfer case is refused at submission: `TITLE_NOT_FOUND`, `TITLE_NOT_ON_PARCEL` or `TITLE_CASE_OPEN` | the number is unknown at the parcel's office, the title rests on another parcel, or another open case names it | correct the number or the parcel; wait for the other case, or withdraw it |
| **Title verification** is refused: `TRANSFERORS_NOT_HOLDERS` | a holder of the title matches no transferor, or a transferor no holder (identity kind and number) | the applicant's declaration names every holder; intake transcribes each identity as the document says |
| **Duty receipt checked** offers no receipt | the case's deposit holds no `TAX_OR_DUTY` document, or no open deposit names the case | the notary adds the receipt to the deposit, or deposits naming the case |
| `procedure.commission.report` cannot be asked for a member | the requester is not a system administrator holding `admin.grant.request`, or asks it for themselves | an administrator asks it for the member; another approves it |

## Production

Nothing here may be used for real cases until the stages, periods, documents and actors are validated with
the competent authorities, and recorded in a new ADR.
