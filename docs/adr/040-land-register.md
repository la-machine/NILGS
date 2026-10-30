# ADR-040: The land register (livre foncier): titles, holders, rights, pending transactions and signed inscriptions

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| **Accepted in principle, revision 3** (decisions D1–D10 of 30 Oct 2026). The implementation plan at the end is **for review**; no change to the register's integrity or authorization model is coded before it is approved. | 2026-10-30 | Project owner, Pythagore (FLYT) |

## Context

SNIGF already knows:

- parcels: geometry and versions (ADR-006, ADR-030);
- applications and their workflow (ADR-032, 034, 035);
- payments (ADR-037);
- the audit trail (ADR-007, ADR-038);
- the documents it issues and verifies (ADR-039).

It does not know **who holds which right on which land**. That is the land register. Every
land procedure ends by writing to it:

- *immatriculation directe* creates a title;
- *mutation totale* changes its holders;
- *morcellement* creates child titles from a parent.

**What happens today.** Before buying, a buyer asks the conservator for a *certificat de
propriété*. Double sales, contested and overlapping titles, and slowness are the failures the
ministry itself names. Between the certificate and the inscription, nothing marks a title as
"being sold", so two buyers can each hold a deed and a recent certificate.

**The owner's decisions (30 Oct 2026).**

- D1: Slice 2 is the register core only.
- D2: the conservator inscribes, with dual control on closures and cancellations.
- D3: notaries and sworn surveyors get individual, verified accounts.
- D4: conflicting transactions are never registered, and never lost.
- D5: synthetic DEMO data only.
- D6: three procedures are the business context, to be validated with the competent
  authorities.
- D7: certificates of ownership become a future service, with digital and physical delivery.
  None is issued in Slice 2 (§11).
- D8: a held title is overdue for review after 30 days, configurable. It creates a review task
  and never expires on its own.
- D9: corrections need a second conservator, and both sign.
- D10: until professional accounts exist, a conservator records counter deposits on behalf of a
  named notary, with strict attribution (§4.7).

## Decision

### 1. Principles

1. **Distinct concepts, distinct tables and APIs.** Each of these is a different thing:

   | Concept | Is not |
   | --- | --- |
   | application | a parcel |
   | parcel | a title |
   | submitted document | an authenticated document |
   | decision (ADR-035) | a registration |
   | payment | proof of ownership |
   | deposited deed or transaction | a change of holder |

2. **The register is the only source of current legal state.** Holders, interests,
   encumbrances, restrictions, area and status are **computed by the database** from
   *registered* inscriptions. No table holds an editable "current owner".
3. **Proposed is not registered.** An inscription is first PROPOSED. Only a REGISTERED one
   counts:
   - it was authorised, confirmed where dual control applies, and signed;
   - proposals, rejections and withdrawals never change the derived state.
4. **DEMO, everywhere.**
   - Every row carries `demo = true`, enforced by a `CHECK`.
   - "Registered" means *registered in the SNIGF DEMO register*, and every screen and extract
     says so.
   - Nothing here is a legal registration, and nothing claims a legal authority SNIGF has not
     been granted.
5. **Nothing registered is deleted or rewritten.** Every change is a new inscription. There is
   no delete operation on register records, for anyone. A trigger rejects `UPDATE` and
   `DELETE` on registered rows, as for the audit trail.

### 2. Vocabulary: exactly what each operation is

| Operation | What it is | Effect on derived state | Control |
| --- | --- | --- | --- |
| **Inscription** (generic) | a numbered, signed entry in a title's register, recording one legal fact with its authority (the deed, judgment or decision), its evidence (stored document versions), its date and its actors | per kind, below | conservator; signed (§5) |
| `CREATION` | a title comes into existence: first registration (procedure A), a child of a morcellement (C), or a synthetic DEMO title (D5) | the title exists, with its first holders and area | conservator |
| `TRANSFER` | the holders of a whole title change (procedure B); it names the pending transaction it completes | new holders and shares; the previous holders stay in the history | conservator; requires the holding transaction (§4) |
| `CHARGE` / `CHARGE_RELEASE` | a mortgage, privilege, servitude or other registered charge, and its release | encumbrances listed until released | conservator |
| `RESTRICTION` / `RESTRICTION_RELEASE` | a pre-notation (*prénotation*), opposition, judicial seizure or administrative restriction, and its lifting | restrictions listed; some block transfers (§4) | conservator |
| `AREA_REDUCTION` | the parent's area taken by child titles (procedure C, later) | area reduced; links to the children | conservator; slices 4+ |
| **Closure** (`CLOSURE`) | the title ends (fully divided, merged, or ended by an authorised decision); it stays readable for ever | status `CLOSED`; no further inscription except a cancellation of the closure | **dual control** |
| **Cancellation** (`CANCELLATION`) | declares one earlier registered inscription void (for example by a final judgment). The original is kept and marked "cancelled by no. N" | from that point, the derived state is computed as if the cancelled inscription had no effect | **dual control** |
| **Correction** (`CORRECTION`) | replaces the content of one earlier registered inscription after a material error (a misspelt name, a wrong area digit), never a change of right. The original is kept and marked "corrected by no. N" | computed from the corrected content, reproducibly from the history | **dual control** (D9): proposed with a reason and evidence by one conservator, confirmed by a second; both sign |
| Rejection / withdrawal of a *proposal* | a PROPOSED inscription that will not be registered | none: it was never registered | conservator; recorded |

### 3. Data model (new module `register`, migration V21, PostgreSQL + PostGIS)

| Table | Holds | Mutability (for `snigf_app`) |
| --- | --- | --- |
| `land_title` | the title's identity: a DEMO number (`DEMO-TF-<conservation>-<seq>-<check>`), its conservation (an `office`), origin (`IMMATRICULATION`, `MORCELLEMENT`, `SYNTHETIC_DEMO`), the creation inscription, `demo` | insert by a CREATION registration only; never updated |
| `register_party` | a holder or other party as the register knows them: a natural or legal person, identity references (kind and number of the identity document, or a registration number), optional link to a SNIGF account. The account is not the person; a person may have no account | insert; never updated; corrections go through inscriptions |
| `inscription` | one entry: title, number in the title (assigned by the database, gap-free), kind, **status** (`PROPOSED` → `CONFIRMATION_PENDING` → `REGISTERED`, or `REJECTED` / `WITHDRAWN`), the expected title version it was prepared against, authority (type and reference of the deed, judgment or decision), reason, evidence (document versions), the pending transaction it completes, the inscription it cancels or corrects, proposer, confirmer, registrar, times, canonical bytes, officer signature, server signature, previous-hash and hash (per-title chain), `demo` | insert as PROPOSED; status moves only forward through the registration functions; registered rows frozen by trigger |
| `inscription_holder` | for CREATION and TRANSFER: the parties and their interest (`OWNERSHIP`, `CO_OWNERSHIP`, `USUFRUCT`, `BARE_OWNERSHIP` ...) and share as an exact fraction | insert with its inscription only |
| `inscription_charge` | for CHARGE and RESTRICTION: kind, beneficiary party, amount when relevant, whether it blocks a transfer, end date if any | insert with its inscription only |
| `title_version` | the **derived** state after each registered inscription: version number, holders, charges, restrictions, area, status, the parcel version it rests on | **written only by the database** in the registering transaction; never by the application; a check function recomputes any version from the inscriptions and compares |
| `title_parcel` | which parcel version (ADR-006) a title version rests on. A parcel is not a title: a parcel may have no title, and a title's parcel may be re-surveyed | written with the version |
| `title_relation` | parent → child titles (morcellement), with the inscriptions on both sides | modelled now, used from slice 4 |
| `pending_transaction` | a deposited transaction on a title (or, from slice 4, on a surveyed part of it): kind, scope, depositor (account and register party), the application it belongs to, the evidence deposited, status (`HOLDING`, `IN_CONFLICT`, `REGISTERED`, `WITHDRAWN`, `REFUSED`), `deposited_at`, `review_due_at` | insert; status moves only through the transaction functions |
| `transaction_conflict` | two transactions on the same title or land: which was holding, which arrived, status (`OPEN`, `RESOLVED`), the authorised decision that resolved it (who, when, why, evidence) | insert; resolved once |
| `register_migration_batch` | modelled for later (D5): provenance, source references, verification and approval of real paper titles | **no row may be written in Slice 2**: a `CHECK` allows only `SYNTHETIC_DEMO` |

Relationships:

- application → pending transaction → inscription, never directly application → title;
- an inscription references its evidence (document versions, ADR-033) and, where one exists, the
  decision (ADR-035) or issued document (ADR-039) it relies on. The reference proves nothing by
  itself: the conservator's registration does.

### 4. Pending transactions and conflicts (D4)

1. **Deposit.** A qualifying deposit (a sale, gift or inheritance file) on a title records a
   `pending_transaction`. If none is holding, it becomes `HOLDING`: the title is locked
   against any other TRANSFER.
2. **A second deposit is recorded, never refused.**
   - It is stored with all its evidence as `IN_CONFLICT`, and a `transaction_conflict` opens.
   - It is routed to the conservators' conflict queue, visibly, to the second depositor too.
   - It does **not** wait in line: when the first ends, the second does not become valid. Its
     fate needs an authorised decision on the conflict.
3. **No registration through a conflict.** A TRANSFER can be registered only when all of
   these hold:
   - it completes the HOLDING transaction;
   - no conflict on the title is OPEN;
   - no blocking restriction is registered;
   - the title is at the version the transfer was prepared against.

   The database checks all of them in the registering transaction.
4. **Releasing the lock.** Only an authorised, audited transition releases it: registration,
   withdrawal by the depositor, refusal by a conservator with a reason, or the resolution of a
   conflict. Each is a recorded state change with its actor.
5. **No lock for ever (D8).**
   - Every holding transaction has a `review_due_at`: `deposited_at` plus
     `SNIGF_REGISTER_LOCK_REVIEW_AFTER` (default `P30D`), a setting changed without code.
   - Past it, a scheduled job records `REGISTER_LOCK_OVERDUE` once, and a review task appears in
     the conservators' queue.
   - Nothing expires or is released automatically. Only an authorised, audited decision
     releases or changes the lock, recording its reason, actor, date and outcome.
   - Expiry, withdrawal and escalation rules must be approved before any is applied to a case.
6. **Scope.** The lock prevents conflicting transactions **inside SNIGF**. It cannot stop an
   informal transaction outside the platform, and the screens say so.
7. **Counter deposits on behalf of a notary (D10, temporary until slice 3).** A
   `pending_transaction` recorded at the counter stores two distinct actors:
   - the **receiving officer**: the conservator's own account, who authenticated the record;
   - the **submitting professional**: a `register_party` of kind `NOTARY`, with their name,
     chamber, registration number and jurisdiction, and the evidence of the submission.

   The notary is never recorded as an authenticated actor, and the conservator never as the
   submitter. The deposited documents are kept. The action is audited as
   `REGISTER_TRANSACTION_DEPOSITED` with `channel = COUNTER`. It gives the notary no right in
   SNIGF. From slice 3, a notary's own account deposits with `channel = PROFESSIONAL_ACCOUNT`.

### 5. Signing: confirmed key purpose and flow (ADR-018, ADR-019)

- **Keys.**
  - The server signature uses the `RECORD` key (`SNIGF_KEY_RECORD_SIGNING`, Ed25519, OpenBao),
    which ADR-018 reserves for "server-side record signing". It is already provisioned, and no
    code uses it yet.
  - It is distinct from the issuance key (ADR-039) and the checkpoint key.
- **Flow, for each registration and each dual-control confirmation:**
  1. The server builds the inscription's canonical form, `snigf-inscription-v1`: a versioned,
     length-prefixed encoding of every field, holders and charges included.
  2. The server issues a WebAuthn challenge: SHA-256 of the canonical form plus a single-use
     server nonce.
  3. The officer's security key signs it.
  4. The server verifies the assertion against the officer's registered credential.
  5. In the same database transaction, the server signs the canonical form with `RECORD` and
     writes the inscription as REGISTERED, with the canonical bytes, the authenticator data,
     the client data, both signatures and the per-title chain hash.
- **Failures.** If any step fails, nothing is registered. If OpenBao is down, nothing is
  registered unsigned.
- **Verification.** Anyone holding the officer's public key and the `RECORD` public keys can
  re-verify an inscription, independently of the application.

### 6. Roles and permissions

| Role | Permissions (new) | May not |
| --- | --- | --- |
| CONSERVATOR (per conservation office) | `register.read`, `register.propose`, `register.inscribe`, `register.confirm` (dual control), `register.transaction.decide` (refuse a deposit, resolve a conflict), at their office | confirm their own proposal; act on a title they hold an interest in (database rule, as in ADR-035 and ADR-039); administer accounts |
| NOTARY, SWORN_SURVEYOR (external professionals, D3; accounts from slice 3) | deposit files and evidence for the cases they are assigned; read those cases; answer requests for evidence | inscribe, confirm or decide anything in the register; hold an administrative, approval or registration permission (rejected by the role catalogue and the database) |
| Everyone else | registered titles' public extract, if decided (D7) | the register's internals |

**External professional accounts (D3).**

- **Account type.** A new type, `PROFESSIONAL`, beside `APPLICANT` and `STAFF`, with the
  profession, the professional credential (chamber, registration number, jurisdiction), and the
  verification evidence.
- **Lifecycle.**
  - Requested by the professional.
  - Verified and approved by two administrators (ADR-026's four-eyes workflow), after the
    credential is checked with the professional body.
  - Suspended and revoked through the same workflow, with a reason.
  - Every submission is attributable to the individual account.
  - No delegation in the first version: a clerk needs their own account.
- **Sign-in.** WebAuthn, as for staff (ADR-016).
- **Not always required.** No procedure assumes a notary or surveyor; each application type
  states which professions it involves, if any.

### 7. Transaction boundaries and concurrency

- **One statement per legal step.** Every register operation is one database transaction
  through a `SECURITY DEFINER` function (`register_propose`, `register_confirm`,
  `register_register`, `transaction_deposit`, `transaction_withdraw`, `transaction_refuse`,
  `conflict_resolve`). Each:
  - locks the title row (`SELECT ... FOR UPDATE`);
  - checks the actor's permission at the conservation (`holds_at`) and the separation of
    duties;
  - checks the expected title version (optimistic: a stale proposal is refused with
    `TITLE_CHANGED`);
  - writes the inscription or the state change, the new `title_version`, and the chain hash.
- **Concurrent deposits.** Two deposits racing on the same title are serialised by the same
  row lock. Exactly one becomes HOLDING: a partial unique index allows one `HOLDING` per title.
  The other is recorded `IN_CONFLICT`. Surveyed parts (slice 4) use the same lock and a
  PostGIS intersection test against the holding scopes.
- **Numbering.** Inscription numbers are assigned under the lock, gap-free, per title.
- **Retries.** Idempotency keys (ADR-013) on every operation; a retried call returns the same
  inscription or transaction.

### 8. Audit (ADR-007, ADR-027, ADR-038)

- **Actions:**
  - `REGISTER_INSCRIPTION_PROPOSED`, `_CONFIRMED`, `_REGISTERED`, `_REJECTED`, `_WITHDRAWN`;
  - `REGISTER_TRANSACTION_DEPOSITED`, `_WITHDRAWN`, `_REFUSED`;
  - `REGISTER_CONFLICT_OPENED`, `_RESOLVED`;
  - `REGISTER_LOCK_OVERDUE`;
  - `REGISTER_REFUSED` (every refused operation, with its reason code).
- **Subjects.** Events take a new subject type, `LAND_TITLE`, with the conservation office,
  found by `audit_subjects()`. A title's timeline is therefore readable by office, like a case.
- **Two independent records.** The register's own hash chain and the audit chain are separate.
  Each detects tampering with the other.

### 9. Migration strategy

- **Code.**
  - Flyway V21 is additive: new tables, functions, policies and permissions, and no change to
    existing tables.
  - It adds the `PROFESSIONAL` account type to `app_user`'s check when slice 3 needs it, in its
    own migration.
- **Data (D5).**
  - Slice 2 creates **synthetic DEMO titles only**, through a development seed script, never
    through a migration.
  - Parties are named "DEMO", identity numbers are visibly synthetic, and parcels are the
    synthetic fixture set.
  - No real title, name or reference is entered.
- **Real paper titles.** They are a separate, future migration, decided with the competent
  institutions. It must define:
  - the source documents and their provenance;
  - verification against the authoritative paper register;
  - how duplicates, gaps and conflicting claims are resolved;
  - how parcels are matched to titles;
  - independent review and approval;
  - preservation of original references;
  - the migration history;
  - the authority that confirms the result.

  `register_migration_batch` is modelled so that this has a place. In Slice 2 a `CHECK` forbids
  any origin other than `SYNTHETIC_DEMO`. A transcribed title is never presented as verified
  or as registered by SNIGF.

### 10. Procedures (D6): business context, validated later

The three procedures share the infrastructure above:

- identity and roles;
- applications and case history;
- parcels;
- documents and their verification status;
- tasks, deadlines and objections;
- decisions;
- register inscriptions;
- payments.

Each keeps its own state transitions, configured per application type (ADR-032), with no
universal timeline hard-coded.

| Procedure | Stages (owner's operational model) | Where it touches the register |
| --- | --- | --- |
| **A. Immatriculation directe** | application; intake; cadastral review; fieldwork and commission; publication and objections; administrative review; authorised decision; **registration**; title issuance and delivery | only at *registration*: a CREATION inscription by the conservator. An application, a field report or a decision is not a title |
| **B. Mutation totale par vente** | open the transfer case; verify the title; certificates; notarial formalities; tax, stamp and payment formalities (government duties distinct from platform charges); transfer review; **authorised mutation**; updated title and delivery | the deposit creates the pending transaction (§4); the *authorised mutation* is a TRANSFER against the current title version. The previous holders stay in the history. A deed, a title image, a payment or a notary submission never changes a holder |
| **C. Morcellement / partial sale** | a separate procedure: authorised cadastral work, approvals, then a registration sequence (the parent's AREA_REDUCTION, the children's CREATIONs, `title_relation`) | data model ready in Slice 2; workflow in a later slice |

The eligibility criteria, documents, copies, stamps, publication method, objection periods,
actors and signatories must be validated with MINDCAF and the conservations before being
configured.

### 11. Future module: the certificate of ownership (D7, designed, not built)

A **Certificate of Ownership** is one official service with two delivery channels. Both
channels share one request, one validation, one authorization and one audit workflow:

| Step | Digital channel (only where legally authorised) | Physical channel |
| --- | --- | --- |
| Request | the applicant asks; SNIGF checks their standing to ask, the title, and the registered state | same |
| Charges | paid through an approved channel (versioned fee assessment, government duties distinct from platform charges) | same |
| Processing | a processing officer's queue; the officer verifies the title and the facts to be certified | same; the officer generates and downloads the certificate |
| Signature and stamp | the authorised official applies the legally accepted digital signature and electronic stamp | printed, then physically signed and stamped by the authorised official, who records the issuance details |
| Delivery | secure download once officially issued | `READY_FOR_COLLECTION`, the applicant notified, collection recorded with date and the recipient's identity |

Rules designed in now:

- **Processing is not signing.** The processing officer never acquires the authority to sign or
  issue. That needs a distinct permission held only by authorised officials, with separation of
  duties enforced by the database.
- **One registry.** Each certificate has a unique reference and a verification record in the
  ADR-039 registry: document hash, template version, issuance status.
- **Validity.** Issuance date, effective date and expiry date where applicable, plus the rule
  governing validity, are stored with each certificate. The validity rule is configured from the
  approved legal or administrative rule; none is invented.
- **Statuses** `VALID`, `EXPIRED`, `REVOKED` and `SUPERSEDED`. Verification distinguishes "a
  genuine certificate, issued on this date" from "valid now".
- **A QR code or a digital signature never replaces** a legally required physical stamp or
  signature.
- **The digital channel stays disabled** by setting, and by the database, until electronic
  issuance is legally authorised. The architecture keeps it ready.

No part of this is built in Slice 2.

## Slice 2 scope (D1) and acceptance

**Delivered:**

- the module `register` and V21 (§3);
- the CONSERVATOR role and its permissions (§6);
- propose, confirm, register, reject and withdraw, signed per §5 (this implements ADR-019 for
  the first time);
- the derived state and its recomputation check;
- deposits, conflicts and the overdue-locks queue (§4), with deposits recorded **by a
  conservator at the counter** on behalf of a named depositor, until professional accounts
  arrive (slice 3);
- charges and restrictions;
- the conservator's screens: title, history, proposals, conflicts, overdue locks;
- an on-screen ownership extract, labelled **"NOT A CERTIFICATE — DEMONSTRATION DATA"**,
  never a downloadable or printable certificate, and with no reference or QR code that could
  pass for one;
- the synthetic DEMO seed;
- audit (§8).

**Not delivered:**

- any procedure workflow;
- professional accounts;
- any certificate of ownership, even DEMO (D7; §11 designs the future service);
- real-title migration;
- tariffs.

**Acceptance:**

- every invariant above has a pgTAP test, including concurrent deposits and stale-version
  refusals;
- an integration test exercises each operation, including both dual-control refusals and the
  signature checks (a tampered canonical form and a wrong credential);
- derived state is recomputed and compared for every title version;
- the permission matrix covers every endpoint;
- e2e covers a conservator registering a transfer through the lock, and a conflict resolved by
  a second conservator;
- CI is green.

## Conflicts with existing ADRs and proposed amendments

| ADR | Conflict or effect | Proposed amendment |
| --- | --- | --- |
| **ADR-019** | Its consequences say "delivered with E-15", but E-15 recorded decisions **without** officer signatures, and the `RECORD` key is provisioned but unused. This is an existing, silent contradiction | Amend: not yet implemented; first implemented by Slice 2 for register inscriptions; review decisions (ADR-035) adopt it in a separate change |
| **ADR-039** | Its registry has the statuses VALID, SUPERSEDED, REVOKED and CANCELLED, and no validity period. A certificate of ownership (D7) needs EXPIRED, a validity rule, an official template, a signatory distinct from the processing officer, and a physical channel | Clarified in ADR-039: the certificate is a future document type of the same registry and verification, extended as §11 describes. Nothing is issued until a separate slice is authorised |
| ADR-016, ADR-025 | Account types are only `APPLICANT` and `STAFF` | Amend in slice 3: a `PROFESSIONAL` type, WebAuthn sign-in, and permissions that can never include administration, approval or registration |
| ADR-024 | The TOTP-for-administrators request | Not affected by Slice 2. Officers who sign inscriptions need security keys whatever is decided for administrators |
| ADR-038 | Audit subjects are applications and parcels | Extend: subject type `LAND_TITLE` with its conservation office |
| ADR-030 | Parcel overlaps only | Extend from slice 4: deposits on surveyed parts test intersections with holding scopes |
| ADR-032, 034, 035, 037 | none | The procedures will be new application types; a decision stays distinct from a registration; tariffs stay out |

## Alternatives considered

- **Editable holder fields on the title**: a rewrite leaves no trace, and the stored and derived
  values can drift. Rejected.
- **Refusing a second deposit outright** (rev. 1): it loses the second buyer's evidence and the
  conflict's history. Replaced by recording it as a conflict (D4).
- **Queueing the second deposit behind the first**: it would become valid automatically, which
  D4 forbids.
- **Automatic expiry of locks**: it frees a title without a decision. Only a review queue,
  until rules are approved.
- **A blockchain register**: the guarantees come from the database guards, the per-title chain,
  OpenBao and the officers' keys, without a new trust model.

## Sources

- MINDCAF, *Les procédures d'accès à la terre sur le domaine national*: https://mindcaf.gov.cm/media/filer_public/78/22/7822bc15-0504-4c13-a523-681236ca484e/procedures_dimmatriculation_sur_le_domaine_national.pdf
- MINDCAF FAQ: https://mindcaf.gov.cm/fr/faq/
- Ordinance 74-1 of 6 Jul 1974; Decree 76/165 of 27 Apr 1976 as amended by Decree 2005/481
  (summary): https://www.memoireonline.com/06/09/2187/La-procedure-dimmatriculation-des-terrains-en-droit-camerounais-selon-le-decret-n2005481-du-16-.html
- Opposition window: https://fr.allafrica.com/stories/201309070559.html
- Mutation and the notary: https://www.camer.be/73284/33:30/cameroon-bon-a-savoir-la-mutation-dun-titre-foncier-au-cameroun.html
- Morcellement: https://www.camer.be/amp/59503/33:30/l39obtention-d39un-titre-foncier-par-morcellement-au-cameroun-cameroon.html
- Certificat de propriété: https://www.camer.be/amp/74622/33:30/point-du-droit
- Digitalisation and double sales: https://fr.journalducameroun.com/acces-a-la-terre-digitalisation-reformes-luttes-anticorruption-les-quatre-chantiers-du-mindcaf-pour-2026/,
  https://journals.ug.edu.gh/index.php/cjas/article/view/4875

These sources are secondary or dated, and some conflict. None of their fees or deadlines is
used.

## Implementation plan for Slice 2 (for review)

Each step ends with its tests green; nothing touches the register's integrity or authorization
model before this plan is approved.

| # | Step | Content | Tests |
| --- | --- | --- | --- |
| 0 | **CI back to green** (blocking) | find the first failing errors of the backend and e2e jobs (they have failed on every run since 28 Oct, including before Slice 1) and fix the cause, never by weakening a test or a check | the GitHub Actions run passes, secret scan included |
| 1 | **Signing ceremony spike** (security dependency) | an inscription-signing WebAuthn ceremony on the existing stack (`spring-security-webauthn`, webauthn4j 0.31.9): a challenge set to SHA-256(canonical form ‖ server nonce), user verification required, the officer's own credentials only, single use, separate from sign-in. Plus `RECORD`-key signing and local verification | unit and integration tests with the emulated authenticator: a valid signature; a tampered canonical form; another officer's key; a replayed challenge; an expired challenge |
| 2 | **ADR-019 implemented as a shared service** | a `SignedRecord` port: canonical bytes, officer assertion, server signature, verification. Used by the register; ADR-035 decisions adopt it in a separate change | parity test of the canonical encoding (Java and SQL), like the audit chain |
| 3 | **V21 schema and invariants** | the tables of §3; the functions of §7 (`SECURITY DEFINER`, pinned `search_path`); row-level security; triggers freezing registered rows; `CHECK (demo)`; `SYNTHETIC_DEMO` the only origin; the permissions and the CONSERVATOR role; `audit_subjects` extended with `LAND_TITLE` | pgTAP `23_land_register`: every invariant of §1–§4 and §7, including no update or delete, gap-free numbering, one HOLDING per title, a second deposit recorded IN_CONFLICT, no TRANSFER through an open conflict or a blocking restriction or a stale version, dual control (self-confirmation refused), the holder rule, the derived-state recomputation; concurrent deposits (pgbench, like the chain test) |
| 4 | **Module `register`** | domain (`Title`, `Inscription`, `Holding`, `PendingTransaction`, `Conflict`, the canonical form), application services, JDBC adapters, controllers; idempotency keys; the overdue-lock job (`SNIGF_REGISTER_LOCK_REVIEW_AFTER`, default `P30D`) | `RegisterDomainTest`; `RegisterIT` (every operation and refusal, the signature failures of step 1 through the API, counter deposits with the receiving officer and the submitting notary kept distinct, overdue tasks, no expiry); `PermissionMatrixIT`; `DddLayeringTest` and `ModularityTest` |
| 5 | **Synthetic DEMO seed** | `dev-seed-register.sh`: titles, holders, charges and transactions, visibly synthetic, on the synthetic parcel fixtures; never a migration | the seed is idempotent; nothing it writes passes for real (`DEMO` in every name and number) |
| 6 | **Frontend** | `features/register`: a title and its history (registered, cancelled or corrected entries shown as such); proposals waiting for confirmation; conflicts; overdue locks; the counter deposit form; the "NOT A CERTIFICATE — DEMONSTRATION DATA" extract (screen only, no download, no print). The signing step uses the officer's security key | Vitest for each view; e2e: a counter deposit, a second deposit recorded as a conflict, its resolution by a second conservator, the transfer registered and signed, a correction confirmed by a second conservator |
| 7 | **Docs** | ADR-040 to Accepted; the ADR-019 amendment applied; guide `docs/guides/land-register.md`; the manual's chapters (02, 03, 04, 05, 07, 08, 10); CHANGELOG; README | `DocumentationSyncTest` |
| 8 | **Full verification** | backend `verify`, pgTAP, frontend, stack rebuild, probes, e2e, secret scan; the diff reviewed for secrets and unintended changes | as the Slice 1 report, with real results only |

### Remaining conflicts, migration risks and security dependencies

**ADR conflicts:** all are listed above with their amendments. None is applied silently.

- ADR-019 is amended when step 2 lands.
- ADR-039 is clarified now, for certificates.
- ADR-016 and ADR-025 change in slice 3, for professional accounts.
- ADR-038 is extended in step 3 (`LAND_TITLE`).
- ADR-030 is extended in slice 4.

**Migration risks:**

- V21 is additive, but `audit_subjects()` is replaced again, as V19 and V20 did. pgTAP `21`
  and `22`, and `TimelineIT`, must stay green, which shows the earlier mappings are unchanged.
- Existing stacks need no data change. The CONSERVATOR role joins the catalogue, so pgTAP `09`'s
  counts move, and `RoleSeedIT` confirms the database equals the code.
- No real title can enter: the origin `CHECK` and the `register_migration_batch` guard enforce
  it, and pgTAP tests both.

**Security dependencies:**

1. **The signing ceremony** (step 1).
   - The current WebAuthn integration serves sign-in. Inscription signing needs a challenge we
     set, and the library allows it through its request-options customisation. Step 1 proves
     it before anything depends on it.
   - Ceremonies are kept in memory (one application instance), a known limit (chapter 03).
2. **The `RECORD` key.** It is provisioned, and the policy already lets the app sign with it.
   The security probe gains a control that the app can sign with `RECORD` and cannot export,
   back up or rotate it, as for the issuance key.
3. **Officers must hold security keys.** Every conservator signs with WebAuthn, independently
   of the open TOTP-for-administrators question (ADR-024).
4. **Dual control in the database.** Self-confirmation and acting on a title one holds an
   interest in are refused by the functions, not only by the service, as in ADR-026 and
   ADR-035.
5. **CI must be green first** (step 0). A slice of this weight is not built on a red pipeline.
