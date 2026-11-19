# ADR-043: The persons of a case, its ownership statement, and the reconsideration of an approved case

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| **Accepted.** D35 to D44 decided by the project owner on 14 Nov 2026, from the design review of ADR-042 step 5. Step 5a built and corrected (14 Nov 2026), approved; step 5b built (15 Nov 2026) and approved (16 Nov 2026), D41 kept as decided (a person changed after the decision, with no upheld objection, stays held). | 2026-11-14 | Project owner, Pythagore (FLYT) |

## Context

[ADR-042](042-registration-and-transfer-procedures.md) step 5 registers a procedure A case as a CREATION. It left two gaps,
raised with its report:

- **One person stands for everybody.** A case knew only the applicant's account, which holds no identity. The
  conservator transcribed one holder, alone, and one conservator signed the CREATION (ADR-040 D2). Nothing told the
  person who applies from the persons the land is to be registered for, nor recorded a representative's mandate, nor
  checked a transcription against the document it came from.
- **An approved case could be blocked for good.** An objection upheld after the decision stops the registration
  (`OBJECTION_UPHELD_AFTER_DECISION`), and `APPROVED` is final (ADR-032): nothing reopened the case.

The register itself already holds several holders with exact shares and interests (ADR-040). The gaps are on the case
side, and in the signature of a CREATION from a case.

## Decisions of the project owner (14 Nov 2026)

| | Question | Answer |
| --- | --- | --- |
| **D35** | The persons of a case | The case-person model: one or several intended owners; the applicant applies on their own behalf or represents one or several of them; every person has an explicit role; a representative has a recorded mandate and never becomes an owner implicitly; a representative who is also a co-owner holds both roles, each recorded |
| **D36** | Ownership | Interests and shares come from the supporting legal documents; no equal distribution by default. The approved ownership statement is the only source of the registered holders |
| **D37** | Mutation totale | Keeps the existing registered owners (transferors), the proposed new owners (transferees) and representatives apart (step 6) |
| **D38** | Transcription and its check | Identity information is transcribed at intake, before the case is accepted, from the cited document. A second intake officer, or an authorised supervisor, checks it against that document, and is never the person who transcribed it |
| **D39** | What a check means | `MATCHES` says the information entered corresponds to the document presented. It never says the document or the person's identity was authenticated. Every identity stays labelled DEMO, not independently verified |
| **D40** | Signatures of a CREATION from a case | Two independent authorised signatures. Neither signer handled the case; the existing independence rules stay |
| **D41** | Reconsideration | Round-based. For now, only when an objection was upheld after the decision; by an authorised supervisor, independent of the original decision authority and of the supervisor who upheld the objection; with a written justification; the original decision and history immutable; only the affected stages reopened, more with justification; a new publication and period; a new decision by another decision authority; registration blocked until the latest round approves; a refusal in the new round ends the case |
| **D42** | A registered title | Never reopened through a case: ADR-040's correction or cancellation, under dual control |
| **D43** | Contact information | The account's email and a phone number, at least. A postal address is optional |
| **D44** | Sequence | Step 5a: persons, identities, checks, mandates, registration from the ownership statement, two signatures. Step 5b: reconsideration rounds. Then ADR-042's steps 6 (mutation totale, with transferors and transferees) and 7. Each reported and approved before the next |

## Decision

### 1. The persons of a case (D35, D43)

```
application ── declaration (the applicant's, while a draft: capacity, persons, roles, shares, mandates)
   │
   ├── case_person            a person of this case, by the reference the applicant gave ("p1")
   │      ├── case_role        APPLICANT | REPRESENTATIVE | INTENDED_OWNER (TRANSFEROR | TRANSFEREE in step 6)
   │      └── case_person_identity   append-only versions: DECLARED by the applicant, then RECORDED by an officer
   ├── case_ownership_statement  append-only versions, each with its shares (case_ownership_share)
   ├── case_mandate              append-only versions: a representative, whom they represent, the scope
   ├── case_party_file           a document presented at the counter, scanned by the officer, kept with its hash
   └── case_party_check          one check per recorded version, by a second officer
```

- **The applicant's capacity**: `SELF` (the applicant is the only intended owner, and holds that role explicitly),
  `ON_BEHALF_OF_ONE` or `ON_BEHALF_OF_SEVERAL` (the applicant represents one, or several, intended owners other than
  themselves, and may also be a co-owner, recorded as such).
- **The declaration** is the applicant's, editable while the case is a draft and checked by the database at
  submission. Submitting turns it into the case's persons, roles, a first ownership statement and mandates, all
  `DECLARED`, never changed afterwards.
- **Contact**: the account's email, and a phone number for the applicant (required); a postal address optional.
- **No person is inferred.** A role exists only if it is recorded; a representative is an owner only with an explicit
  `INTENDED_OWNER` role and a share.

### 2. Transcription and its check (D38, D39)

- An intake officer (`application.party.record`, office) records each person's identity from a cited document: one
  scanned at the counter (`case_party_file`, SHA-256 by the server, read-only, retention class `CASE_PARTY_EVIDENCE`,
  downloads audited) or a stored version of the case's documents. Likewise the ownership statement (each share citing
  its supporting document) and each mandate (its mandate document required).
- **Corrections** are new versions naming the one they replace, with a reason; nothing is updated or deleted.
- **The check.** A second officer (`application.party.check`, office: intake officers and supervisors), never the
  recorder, records `MATCHES` or `DOES_NOT_MATCH` for one recorded version, once. A new version needs its own check.
- **The labels.** Every version carries `UNVERIFIED_DEMO`, by a `CHECK` that admits no other value. `MATCHES` is shown
  as "corresponds to the document presented", never as verified.
- **The gate.** The case is accepted only when every person's latest identity, the latest ownership statement and
  every mandate are recorded and checked `MATCHES`, and the structure holds (capacity, roles, mandates covering every
  owner the applicant does not own for, shares totalling one). An approval needs the same; the decision names the
  ownership statement it approved.

### 3. From the ownership statement to the register (D36, D40)

- The proposal from a case takes no holder: the database builds the holders from the decided statement, each owner a
  register party from their latest checked identity (`register_party.source_identity_id`), with the statement's
  interest and exact share. The applicant is a holder only if they are an intended owner; a representative never.
- Registration is refused when a person, the statement or a mandate changed after the decision
  (`PARTIES_CHANGED_SINCE_DECISION`): what was approved is what is registered.
- **Two signatures (D40).** A CREATION from a case follows ADR-040's dual control: the proposer signs and asks for a
  confirmation, a second conservator confirms. Neither may have handled the case, checked again at each signature; the
  proposer never confirms (`SELF_CONFIRMATION`). Synthetic CREATIONs keep one signature (D2).
- The title document (a future workflow) and ADR-042's notice read the holders from the register, so they name the
  intended owners, never the applicant as such.

### 4. Reconsideration rounds (D41, D42), step 5b

Designed in the review of 14 Nov 2026 and decided as D41; built in step 5b after 5a is approved. `decision`,
`review_step` and `case_assignment` gain a round; `case_reconsideration` opens round *n*; `APPROVED` → `UNDER_REVIEW`
only through it; the current decision is the latest round's.

## Implementation plan

| Step | Migration | Content | Tests |
| --- | --- | --- | --- |
| **5a** (done) | V35 | the declaration, `case_person`, roles, identity versions, ownership statements and shares, mandates, counter files, checks; two permissions; the acceptance and approval gates; the decision names its statement; the proposal from a case rebuilt from the statement; dual control for a CREATION from a case; audit events | pgTAP `36` and the earlier procedure files updated; IT from a declaration with two co-owners through a representative to a title signed twice; `PermissionMatrixIT`; Vitest |
| **5b** (done) | V36 | rounds, `case_reconsideration`, `procedure.case.reopen`, the one new transition, the current decision | pgTAP `38`; `db-test.sh`: a reconsideration racing a signature; IT; Vitest |
| 6, 7 | V37, V38 | ADR-042's steps 6 and 7, with transferors and transferees | as ADR-042 |

## As built in step 5a (14 Nov 2026)

Migration V35; the persons at intake in the `procedure` module (`PartyService`, `PartyController`); the declaration in
the application module; the register's proposal and two signatures.

**One refinement of §2, for the owner's review: the documents of the persons are scanned at the counter.** Each
transcription cites either a document the intake officer scans when the person presents it (`case_party_file`, SHA-256
by the server, kept read-only, its retention class by purpose (C3), downloads audited) or a stored version of the
case's own documents. The applicant uploads no identity document per person online: the case's single
"one document per category" rule (ADR-033) stays as it is, and "transcribed at intake" (D38) is what happens.

**The declaration** (`application.declaration`, the draft's `PUT`): capacity, persons (reference, kind, roles, DEMO
identity, the applicant's phone), shares, mandates. `case_declaration_problems()` is the one set of rules, applied to the
draft as the applicant goes (`declarationProblems`), at submission (`DECLARATION_INVALID`), and to the persons as they
stand at intake (the same function over their latest versions):

| Rule | Code |
| --- | --- |
| a capacity, one applicant, at least one intended owner, at most twenty persons | `CAPACITY_INVALID`, `APPLICANT_REQUIRED`, `OWNER_REQUIRED`, `PERSONS_REQUIRED` |
| every person's DEMO name and identity; each reference once; the applicant's phone (D43) | `PERSON_INVALID:<ref>`, `PERSON_DUPLICATE:<ref>`, `PHONE_REQUIRED` |
| `SELF`: the applicant holds the owner's role, explicitly, and is the only owner; `ON_BEHALF_OF_ONE` / `_SEVERAL`: the applicant is a representative of one / two or more other owners | `CAPACITY_MISMATCH` |
| one mandate per representative, covering intended owners other than themselves; every owner but the applicant covered | `MANDATE_INVALID`, `MANDATE_DUPLICATE:<ref>`, `REPRESENTATIVE_WITHOUT_MANDATE:<ref>`, `MANDATE_MISSING:<ref>` |
| a share for every intended owner and nobody else, exact fractions, totalling one (full ownership, or usufruct and bare ownership each); never a default | `SHARES_REQUIRED`, `SHARE_INVALID`, `SHARES_MUST_TOTAL_ONE`, `OWNER_WITHOUT_SHARE:<ref>` |

Submission turns the declaration into `case_person`, `case_role`, and `DECLARED` versions of each identity, the
statement and the mandates, entered by the applicant; the declaration never changes afterwards.

**Intake** (`application.party.record`: intake officers; `application.party.check`: intake officers and supervisors):
- `case_identity_record`, `case_ownership_record` (each share citing its document), `case_mandate_record` (the mandate
  document required) write `RECORDED` versions naming the version they replace (`STALE_VERSION` otherwise); a
  correction of a recorded version needs a reason (`CORRECTION_REASON_REQUIRED`). Never by the applicant or a person of
  the case; only while the case is open, never once its title is registered (`CASE_REGISTERED`, D42).
- `case_party_check_record`: one check of the latest recorded version, never by its recorder
  (`RECORDER_CANNOT_CHECK`), once (`ALREADY_CHECKED`); a mismatch says why (`CHECK_NOTE_REQUIRED`). The audit event
  records `MATCHES_PRESENTED_DOCUMENT_NOT_AUTHENTICATED` as the meaning of the check (D39).
- Every version carries `verification = 'UNVERIFIED_DEMO'` by a `CHECK`; nothing is updated or deleted.

**The gates.** `case_party_problems()`: the structure as it stands, then each person's latest identity, the latest
statement and each representative's latest mandate recorded and checked `MATCHES`. Acceptance (`COMPLETE`) and an
approved decision are refused with `PARTIES_INCOMPLETE` until it is empty; the decision records the statement it
approved (`decision.ownership_statement_id`).

**The register.**
- `register_propose_from_case(case, reason)`: the holders are the decided statement's owners, each a register party
  made from their latest checked identity (`register_party.source_identity_id`), with the statement's interest and
  share; the applicant's account is linked only to the applicant's own party; a representative is never a holder. The
  evidence references name the statement.
- Refused when anything changed after the decision (`PARTIES_CHANGED_SINCE_DECISION`): an identity or mandate entered
  since, or a statement other than the decided one. Such a case stays held: step 5b reopens a case only after an upheld
  objection (D41; see its open points).
- **Two signatures (D40).** A CREATION from a case is under dual control (`inscription_dual_control()`): one signature
  is refused (`DUAL_CONTROL_REQUIRED`); the proposer signs and asks for confirmation, checked against the case then;
  another conservator confirms (`SELF_CONFIRMATION` for the proposer), checked again; neither may have handled the case,
  persons included, and the proposer is checked again at the second signature. Synthetic CREATIONs keep one signature.
- `register_case_queue()` names the owners and shares the title will carry.

**Who reads the persons.** The case's readers at its office, its assignees and stage officers, and the officers who
record or check persons there (`case_party_readable`). The applicant reads `parties` (reference, roles, name, status,
`UNVERIFIED_DEMO`) through `application_parties()`, never the officers, the files or a check's note. The conservators of
the registration read the approved owners and their evidence, read-only and audited (C2 below).

**Endpoints.** `GET /api/v1/procedures/{id}/parties`; `POST .../parties/files` (multipart, `purpose`);
`GET .../parties/files/{fileId}` (attachment, hash checked, audited); `POST .../parties/persons/{personId}/identity`;
`POST .../parties/ownership`; `POST .../parties/persons/{personId}/mandate`; `POST .../parties/checks`. The draft's
`PUT /api/v1/applications/{id}` takes `declaration`; `POST /api/v1/register/cases/{id}/creation` takes a reason only.

**Screens.** The applicant's draft has "Persons of the case" (capacity, persons and roles, shares with no default,
mandates) and what is still missing; their case shows each person's status, labelled DEMO and unverified. The staff case
has "Persons of the case (DEMO)": the documents scanned, each person with their versions, the statement, the mandates,
the forms to transcribe, correct and check. "Approved, to register" shows whom the title will name; the proposal asks
for a reason; signing a CREATION from a case asks the proposer for the first signature and another conservator for the
second.

**Audit.** `CASE_PARTY_FILE_ADDED`, `CASE_PARTY_FILE_DOWNLOADED`, `CASE_PARTY_IDENTITY_RECORDED`,
`CASE_PARTY_IDENTITY_CORRECTED` (the reason), `CASE_OWNERSHIP_RECORDED`, `CASE_MANDATE_RECORDED`, `CASE_PARTY_CHECKED`,
all on the case; never an identity number (tested).

**Tests.**
- pgTAP `36` (92): the declaration's rules, submission and its records, transcription, the check and its four-eyes
  rule, corrections kept, `UNVERIFIED_DEMO` unwritable otherwise, the gates, who reads, the decided statement, holders
  from it and never the representative, a change after the decision, two signatures, `CASE_REGISTERED`, privileges.
  `33`, `34`, `35` (77) take their cases through intake (`tap.parties_ready`) and `35` signs twice; `09` (63
  permissions); `31` (six classes).
- `db-test.sh`: the second signature races an objection; the fixture goes through intake.
- `CaseRegistrationIT`: two heirs, two thirds and one third, applied for by a representative who owns nothing; the
  declaration's problems, acceptance refused until intake is done, the recorder refused the check, unequal shares that
  do not total one refused; the title proposed from the statement, refused with one signature, refused to the proposer
  as confirmer, signed by two conservators, both signatures verifying, holding exactly the two heirs. `ProcedureIT`
  through intake; `PermissionMatrixIT` (seven endpoints).
- Vitest: `parties.test.tsx`, `DeclarationEditor.test.tsx`, `register.test.tsx` (the proposal, two signatures).

## Corrections of the owner's review (14 Nov 2026)

The owner reviewed step 5a and approved five corrections, made in V35 itself (never applied to a lasting database).
Where they differ from "As built in step 5a" above, they prevail.

### C1. Rights: sole ownership, co-ownership, usufruct and bare ownership

`holder_rights_problem(jsonb)` (`IMMUTABLE`) is the one rule, used by the declaration, by `case_ownership_record`, by
`register_propose` (synthetic CREATIONs and corrections) and so by `register_propose_from_case`;
`holder_shares_total_one` is now `holder_rights_problem(p) IS NULL`.

| Holders | Rule | Code |
| --- | --- | --- |
| none | at least one | `HOLDERS_REQUIRED` |
| any | exact fractions, `0 < num <= den` | `SHARE_INVALID` |
| `OWNERSHIP` | sole ownership: one holder, `1/1`, alone | `SOLE_OWNERSHIP_INVALID` |
| `CO_OWNERSHIP` | at least two holders, totalling one, with no other interest | `CO_OWNERSHIP_INVALID`, `SHARES_MUST_TOTAL_ONE` |
| `USUFRUCT` + `BARE_OWNERSHIP` | both groups present, each totalling one; nothing else | `PARTIAL_DISMEMBERMENT_NOT_SUPPORTED` (one group alone, or a group short of one) |
| a usufruct | its term: `LIFE`, or `UNTIL` with a date (a past date refused at transcription); no other interest has one | `USUFRUCT_TERM_INVALID` |
| any other mix | | `RIGHTS_INCONSISTENT` |

The database tells sole ownership from co-ownership by the interest itself: `OWNERSHIP` is 1/1 alone, `CO_OWNERSHIP`
needs two holders. The term is kept on `case_ownership_share` and `inscription_holder` (`usufruct_term`,
`usufruct_until`, `ck_share_usufruct`, `ck_holder_usufruct`), copied by materialisation, by the proposal and into
`title_state` (only when present, so earlier states recompute unchanged), and signed (C5).

### C2. The conservators examine the approved ownership and its evidence

`register_case_evidence(case)` is restricted, read-only and audited. It answers only a reader holding `register.propose`,
`register.inscribe` or `register.confirm` at the case's office, for an approved procedure A case (`NULL` otherwise; the
API answers 403 before that). It returns the decision, the decided statement and its check, and for each owner: name,
identity kind and number, `validUntil`, `expiredAtTranscription`, the exception if any, interest, share and term, the
identity version (id and number), `UNVERIFIED_DEMO`, who transcribed it and when, its check, the cited source and basis
documents; the mandates; the files. **Never a phone number or a postal address** (tested). It writes nothing.
`register_case_evidence_file(case, file)` serves a counter file of the case, or a stored version cited as a source or
basis, and nothing else.

`GET /api/v1/register/cases/{id}/evidence` (audit `CASE_PARTIES_READ_FOR_REGISTRATION`) and
`GET .../evidence/files/{fileId}` (hash checked, audit `CASE_PARTY_FILE_DOWNLOADED`, purpose `REGISTRATION`). The screen
shows it on request in "Approved, to register" and on a CREATION from a case waiting for a signature.

### C3. Retention classes and holds

- Counter files: `CASE_IDENTITY_DOCUMENT`, `CASE_MANDATE`, `CASE_OWNERSHIP_EVIDENCE`, from the file's purpose
  (`CASE_PARTY_EVIDENCE` is gone).
- Documents the applicant uploads: `document_version.retention_class`, set by the database from the category
  (`document_category_retention`): `APPLICATION_IDENTITY_DOCUMENT` (`DEMO_IDENTITY`), `APPLICATION_OWNERSHIP_EVIDENCE`
  (`DEMO_PROOF_OF_RIGHT`, `DEMO_DEVELOPMENT_EVIDENCE`), `APPLICATION_SUPPORTING_DOCUMENT` (`DEMO_SITE_PLAN` and any
  other); existing versions backfilled; never changed (`retention_class_fixed`).
- Holds gain the subject kinds `CASE`, `APPLICATION_DOCUMENT`, `CASE_PARTY_FILE`, `PROCEDURE_STAGE_FILE`,
  `PROCEDURE_OBJECTION_FILE`. A `CASE` hold covers every document of the case (its uploaded versions, counter files,
  stage and objection files), **those added after the hold included**, because `record_holds` resolves the case of a
  record when it is asked (`record_case_of()`).
- Eleven classes, every period unset; `record_disposal_allowed` stays false without an approved schedule and under a
  hold.

### C4. The validity of an identity document

- `case_person_identity.valid_until` (optional) and `expired_at_transcription` (set by the database:
  `valid_until < current_date` when transcribed; `ck_identity_validity`). Validity is distinct from accuracy: a check
  still says only that the transcription corresponds to the document.
- An expired document never passes silently: `case_party_problems` adds `IDENTITY_DOCUMENT_EXPIRED:<ref>`, so
  acceptance, approval and registration wait, and the applicant reads `DOCUMENT_EXPIRED`. Two ways out: a valid document
  transcribed as a new version, or an exception.
- The exception (`case_party_exception`, append-only): one per identity version, under the one defined policy
  `DEMO_EXPIRED_IDENTITY_DOCUMENT`, with a reason of 20 to 2000 characters, by a holder of the new permission
  `application.party.exception` at the office (`SUPERVISOR`), never the version's recorder. Refusals: `PARTY_FORBIDDEN`,
  `STALE_VERSION`, `EXCEPTION_NOT_APPLICABLE`, `POLICY_INVALID`, `RECORDER_CANNOT_AUTHORISE`, `REASON_REQUIRED`,
  `ALREADY_AUTHORISED`. Audit `CASE_PARTY_EXCEPTION_AUTHORISED`. `POST /api/v1/procedures/{id}/parties/exceptions`.
- No general legal rule refusing expired documents is made: that waits for MINDCAF's confirmation (open question).

### C5. What the two signatures bind

- `inscription.decision_id`, `ownership_statement_id`, `ownership_statement_version`, `binds_parties`
  (`ck_inscription_case_binding`, immutable by `inscription_case_guard`); `register_party.source_identity_version`.
- `inscription_canonical` appends, only when present, so earlier inscriptions keep their bytes:
  `decision`, its id, `statement`, its id and version; `usufruct` and each holder's term; and when `binds_parties`,
  `parties`: for each holder, by party and interest, the party id, interest, kind, full name, identity kind and number,
  and the source identity's id and version; then the beneficiary of a charge, the same way. `InscriptionCanonical`
  (Java) mirrors it and title verification recomputes it.
- `register_case_proposal_stale(inscription)`: the decision's statement changed, the statement is no longer the
  latest, the parcel version is no longer current, or a holder's source identity is no longer the latest or no longer
  checked `MATCHES`. Then both signatures are refused with **`STALE_PROPOSAL`** (409). A stale proposal is withdrawn
  or rejected and a new one proposed; if the persons changed after the decision, that new proposal is refused with
  `PARTIES_CHANGED_SINCE_DECISION` until step 5b. A first signature is never reused: it was over the old bytes, so it
  fails `CANONICAL_MISMATCH` on any new inscription.
- A party changed directly in the database (bypassing the functions) changes the recomputed bytes, so title
  verification reports the signature invalid (tested, then restored).

### Tests of the corrections

- pgTAP `37_registration_integrity.sql` (79): every rights code; a synthetic usufruct registration with its term in the
  bytes and the state; the expired document, its problem, the exception and every refusal; the evidence (who reads, no
  phone or address, the files); retention classes and case holds on documents added later; `STALE_PROPOSAL` after an
  identity correction, a statement correction, a parcel correction and the decision's statement changed; a first
  signature refused on a new inscription; party tampering detected; privileges. `09` (64 permissions), `23`, `31`
  (eleven classes), `33` to `36` updated.
- `db-test.sh`: on ten approved cases, an identity correction races the second signature: no round ends with both the
  title registered and the correction recorded (registered 0, corrected 10, both 0 in the last run).
- Java: `InscriptionCanonicalTest` (bound bytes); `CaseRegistrationIT` (both signers read the evidence, no phone, 403
  for intake, the file, audit counts, tampering detected; a stale proposal through the API: 409 `STALE_PROPOSAL`,
  withdrawal, then `PARTIES_CHANGED_SINCE_DECISION`); `PermissionMatrixIT` (three more endpoints); `RetentionIT`.
- Vitest: `CaseEvidence.test.tsx`, `parties.test.tsx` (expired document, exception, never by the recorder, the term),
  `DeclarationEditor.test.tsx` (the term sent with a usufruct only; `DOCUMENT_EXPIRED`).

## As built in step 5b (15 Nov 2026)

Migration V36; the reconsideration in the `procedure` module (`ProcedureService.reconsider`,
`POST /api/v1/procedures/{id}/reconsideration`); rounds in the review module; the applicant's view.

**Rounds.** The original examination is round 0; each reconsideration opens the next. `application.review_round` is the
current round; `case_assignment`, `review_step` and `decision` carry the round they belong to, set by the database
(`case_round_guard`, whatever the caller gives). One assignment per stage and round, one decision per round; nothing of an
earlier round is changed. `case_decision_current()` is the latest round's decision, and everything that read "the"
decision reads it: the registration checks (`procedure_registration_problems`, `register_case_problems`,
`register_propose_from_case`, `register_case_proposal_stale`), "Approved, to register", the conservators' evidence, the
review view and the applicant. `case_assignee()` and the claimable queue are the current round's; the workload shows a
case once.

**Opening one** (`procedure_reconsider(case, expectedVersion, addedStages, justification)`, under the case's lock; the
same checks as `procedure_reconsider_problem()`, which the screens read):

| Refused when | Code |
| --- | --- |
| the reader lacks `procedure.case.reopen` at the office (`SUPERVISOR`, not delegable) | `PROCEDURE_FORBIDDEN` |
| the case has no procedure / is not `APPROVED` (so one open at a time) | `RECONSIDERATION_NOT_APPLICABLE` / `CASE_NOT_APPROVED` |
| a title is registered (D42: ADR-040's correction or cancellation, under dual control) | `CASE_REGISTERED` |
| a CREATION is proposed or awaiting its second signature (a conservator withdraws or rejects it first; never closed automatically) | `CREATION_IN_PROGRESS` |
| no objection was upheld after the current decision (the only grounds, D41) | `GROUNDS_MISSING` |
| the reader applied, or is a person of the case | `APPLICANT_CANNOT_REOPEN` |
| the reader decided the case in any round | `DECISION_AUTHORITY_CANNOT_REOPEN` |
| the reader was its reviewer or decision authority in any round | `ASSIGNEE_CANNOT_REOPEN` |
| the reader recorded a stage of it | `STAGE_RECORDER_CANNOT_REOPEN` |
| the reader decided one of the objections it answers (one upholds, another reopens) | `OBJECTION_DECIDER_CANNOT_REOPEN` |
| the case changed since the reader saw it | `STALE_VERSION` |
| no justification of 20 to 4,000 characters | `JUSTIFICATION_REQUIRED` |
| an added stage is not of the case's procedure, not available, or named twice | `REOPEN_STAGE_INVALID` |

It writes `case_reconsideration` (append-only, one per decision reconsidered): the round, the decision reconsidered,
every objection upheld after it, the stages reopened (those the objections named, those added, and the publication when
the procedure has one, in the procedure's order; never fewer) and which were added, the justification, who and when.
Then the one new transition, `APPROVED` to `UNDER_REVIEW` (`application_transition_legal` and `application_guard`
replaced: only with a reconsideration of the new round by the same actor; the round moves with it and nothing else
moves it). Audit `CASE_RECONSIDERATION_OPENED`, the justification as its reason, the round, stages and objection numbers
as details.

**The new round.** Each reopened stage reads `RECONSIDERATION_PENDING:<stage>` (`OUTDATED`) until recorded again,
by a correction; the publication is recorded again, so a new notice opens with a whole new period, public while it
runs, and objections are received to it. Then a new review and decision:
- a decision follows a recommendation of its own round (`RECOMMENDATION_OF_EARLIER_ROUND`);
- a decision authority who decided an earlier round never claims the case again (`EARLIER_DECIDER_CANNOT_DECIDE`);
- whoever opened a reconsideration of the case never reviews or decides it (`RECONSIDERATION_OPENER_CANNOT_REVIEW`);
- whoever was assigned to the case in any round never records a stage of it, receives or decides one of its objections
  (`case_ever_assigned`; before V36 only the current assignees were kept out);
- the persons are checked again for an approval; the new decision records the statement as it then stands.

A new approval makes the case registrable again (the upheld objection is now before the decision); a new refusal ends
it (`REFUSED` is final). The registration is blocked while the round is open (`CASE_NOT_APPROVED`).

**Who reads it.** Staff who read the procedure: every round, the decision reconsidered and its outcome, the objection
numbers, stages, justification, who opened it, and the round's outcome (`procedure_reconsiderations`). The applicant
(`application_reconsideration`, `reconsiderations` in their payload, pinned by `ApplicantPayloadSchema`): the round,
the stages carried out again, since when, and whether the new decision is made; never the justification, the objections,
the objectors or who opened it. While the round is open the applicant reads no decision; afterwards, the new one.

**Screens.** The staff case's procedure card has "Reconsideration": the rounds, and "Open a reconsideration…" for a
supervisor the server allows (with the reason why not, when it is one they can act on). The review record shows the
round and the earlier decisions. The applicant's stages say "Your case is reconsidered (round n)".

**Tests.**
- pgTAP `38_reconsideration.sql` (73): no grounds; a CREATION in progress; every independence refusal; the
  justification, version and stages; opening round 1 (state, round, stages, the decision named, the history); the
  original decision, records and objection unchanged; append-only; written only by the function; one open at a time;
  registration blocked; the transition and the round refused around the function; the reopened stages; the applicant's
  and the staff's reads; an earlier assignee kept from the stages; recorded again, published anew (a second notice, a
  new period); the opener and the earlier decision authority kept out; one assignment per stage and round; the workload;
  the new approval, both decisions kept, the current one; a registration from it; no reconsideration once registered; a
  case with an added stage refused in round 1, ended; privileges. `09` (65 permissions), `18`, `32` updated.
- `db-test.sh`: two supervisors open the same case's reconsideration at once, on ten cases: each opened exactly once
  (opened_once 10, opened_twice 0).
- Java: `CaseRegistrationIT` (an objection upheld after the approval, refusals through the API, the reconsideration
  opened, the applicant's view, round 1 recorded, reviewed and decided, then registered by two conservators; no
  reconsideration afterwards); `ApplicationDomainTest`; `PermissionMatrixIT` (the endpoint).
- Vitest: `reconsideration.test.tsx` (the form, the rounds, the reasons, the applicant), `reviews/rounds.test.tsx`.

**Open points for the owner.**
- *A person changed after the decision, without an upheld objection.* Registration stays refused
  (`PARTIES_CHANGED_SINCE_DECISION`): D41 makes an upheld objection the only grounds of a reconsideration, so nothing
  reopens such a case. Step 5a's text expected 5b to resolve it; it does not, by D41. Broader grounds (for example a
  correction of a person after the decision) need a decision.
- *Opening a reconsideration and signing a CREATION* cannot race to both: one needs a CREATION in progress refused, the
  other a CREATION in progress, and both take the case's lock. The concurrency check tests the race that can go wrong:
  two supervisors opening at once.

## Consequences

- ADR-040 D2 is amended for a CREATION from a case (two signatures).
- ADR-042 step 5's transcribed holder (`HOLDER_INVALID`) is replaced by the ownership statement.
- The DEMO_IMMATRICULATION type's `applicantCapacity` field (occupant, heir) is the basis of the claim; the capacity of
  the applicant is the declaration's.
- Every procedure A case now needs its persons checked before acceptance: the tests and fixtures of steps 3 to 5 do so.
- ADR-040 §2 (holders) and §5 (signed bytes) are amended by C1 and C5; ADR-041 D18's retention by C3.
- Whether an expired identity document may ever be accepted, and on what legal basis, is for MINDCAF to confirm; until
  then only the DEMO policy exists.
- ADR-032 (states), ADR-035 (one decision per case) and ADR-042 (`OBJECTION_UPHELD_AFTER_DECISION` held for good) are
  amended by step 5b.
