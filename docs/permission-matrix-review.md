# Permission matrix review against the Baseline

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Comparison of [the permission matrix](permission-matrix.md) (code: `Permission.java`,
`BaselineRole.java`, migrations V7 and V8) with *SNIGF NILGS — MVP Technical Baseline v0.1*,
sections "Actors", "Gaps and contradictions found" and "Permission matrix". Each difference
has an identifier, its security and business implications, and a proposed decision for the
project owner to accept, amend or reject. Status: **D5, D6 and D14 decided by the project
owner on 22 Oct 2026** (below); the other differences stand as proposed, with no objection
raised.

## How to read the two matrices

- The Baseline names 9 coarse permissions, several grouped (`parcel.create / parcel.correct`,
  `audit.read / verify`, `user.manage / role.assign`), with cells "yes", "own", "office",
  "assigned", "linked only", "yes (SoD)", "own office", "yes, never to self". It adds:
  "Roles are scoped to an office; 'office' means records whose application is assigned to
  the user's office."
- The code has 35 fine-grained permissions, each with a scope (any, office, assigned, own,
  linked). A Baseline "yes" became **any** unless the actor table or the office note narrows
  it.

## Rows that match

| Baseline permission | Roles | Code |
| --- | --- | --- |
| `parcel.read` | applicant linked only; intake, cadastral, reviewer, decision, supervisor, auditor yes | same: `linked` for the applicant, `any` for the six back-office roles; finance and admin none |
| `application.create / submit / respond` | applicant own | three permissions, `own` |
| `application.read` | own / office / office / assigned / assigned / office / yes | same scopes, auditor `any` |
| `payment.pay` | applicant own | `own` |
| `payment.reconcile` | finance yes | `any` |

## Differences

| # | Topic | Baseline | Code | Security implication | Business implication | Proposed decision |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | Granularity | 9 grouped permissions | 35 permissions, one per function (ADR-025) | Least privilege per function; every endpoint checked against one code, enforced by the build | Roles can be tailored without code; matrix is longer to review | **Accept** (requested by the project owner) |
| D2 | Scope of cadastral, intake and supervisor "yes" | "yes", with roles office-scoped | `parcel.create`, `parcel.correct`, `completeness.*`, `alert.*`, `report.workload` at **office** | Narrower: an officer acts only for the office of their assignment | Officers working for two offices need two assignments | **Accept** (follows the Baseline's office note) |
| D3 | `review.recommend`, `decision.record` | "yes", "yes (SoD)" | **assigned** | Narrower: only on cases assigned to the person | Needs case assignment, delivered with the case module | **Accept**; assignment rules to define with E-15 |
| D4 | Audit permissions split | `audit.read / verify` | `audit.event.search`, `audit.chain.verify`, `audit.checkpoint.create`, `.list`, `.download`, `audit.key.read` | Each audit action granted separately; checkpoint signing (a write to the audit store) is separate from reading | Lets the institution give verification without event reading | **Accept** |
| D5 | Auditor checkpoint signing | not mentioned (the Baseline sends the daily head "to the auditor") | AUDITOR may sign a checkpoint on demand | Adds a write to the audit store (never to business data) by the auditor; signing is by OpenBao, audited | Auditor can seal the chain before an inspection | **Accept**, or restrict to the daily job if the owner prefers |
| D6 | Supervisor audit reading | "own office" | `audit.event.search` at **office**, but **not usable yet**: audit events carry no office | None (closed) | **Gap**: the Baseline acceptance "a supervisor can reconstruct a case's timeline from audit alone" and the case and parcel reconstruction screen for auditor and supervisor (E-18, "Audit timeline view") cannot be met until events carry the office | **Decide**: add the office to audit events with the case module (proposed), or accept that supervisors use the auditor |
| D7 | Supervisor chain verification | "own office" | `audit.chain.verify` **any** (V8, ADR-026) | Read-only; the result names positions and kinds of mismatch, never event content; an `AUDIT_VERIFIED` event is appended | More independent verifiers; "own office" has no meaning for one global hash chain | **Accept** (requested 22 Oct 2026) |
| D8 | Administration split | `user.manage / role.assign` "yes, never to self" | 13 permissions: account list, role assign (request), assignment read and approve, revoke, MFA reset, role read, define, approve, grant read, request, approve, revoke | Each administrative act separate; none delegable | An institution can give account support without role approval | **Accept** |
| D9 | Four eyes | only "never to self" | every role assignment, custom role change and special grant needs a second administrator; enforced by the database (V8, ADR-026) | Stricter than the Baseline: one compromised or careless administrator cannot grant rights | **At least two administrators** (three when one may be the beneficiary); granting is slower | **Accept** |
| D10 | Custom roles | not in the Baseline (fixed actors) | administrators define roles at run time from the catalogue (ADR-025) | Larger privilege surface; mitigated by four eyes, catalogue-only permissions, immutable baseline roles, separation of duties checked by the database | New job profiles without a release | **Accept** (requested 21 Oct 2026) |
| D11 | Special grants | not in the Baseline | one permission, one person, time and/or use limits, four eyes, audited uses (ADR-025) | Controlled exception path instead of over-broad roles; never administration | Temporary tasks without new roles | **Accept** (requested 21 Oct 2026) |
| D12 | Administration and audit never together | not stated | refused in a role and in a person, by service and database | Removes the "admin covers own tracks" scenario short of the database superuser | An administrator cannot read the audit trail; investigations go through the auditor | **Accept** |
| D13 | Administrator and audit | admin "cannot edit audit records" | admin has **no** audit permission at all | Stricter | Same as D12 | **Accept** |
| D14 | "Any back-office role reads" parcels (MVP-01) | MVP-01 says any back-office role reads; the matrix gives finance and admin nothing | follows the **matrix**: finance and admin cannot read parcels | Narrower | If finance needs parcel references to reconcile, add `parcel.read` to FINANCE_OFFICER | **Decide**: the Baseline contradicts itself; matrix proposed |
| D15 | Separation-of-duties rules C6a, C6b | provisional: decision ≠ recommending reviewer; alert resolver ≠ author of a compared version | **not enforced yet** (no case or alert module); C6c (no self-assignment) enforced | Open until those modules exist | Must be built with the decision and alert features | **Accept the plan**: enforce with E-15 (decision) and the alert slice, by service and database |
| D16 | Office on roles with "any" permissions | roles are office-scoped | every staff assignment names an office, even for roles whose permissions are `any` (AUDITOR, SYSTEM_ADMIN) | None; the office is ignored for `any` | The office field is mandatory but meaningless for those roles | **Accept**, revisit for the pilot |
| D17 | Second factor (related) | TOTP for officers, decision, auditor and admin; applicants optional | WebAuthn for every staff account, two authenticators, recovery codes, no TOTP (ADR-016, ADR-024) | Phishing-resistant; stronger | Staff need two security keys or passkeys | **Accept** (ADR-024 still "proposed") |

## Decisions of the project owner (22 Oct 2026)

| # | Decision | Condition | Implemented by |
| --- | --- | --- | --- |
| D5 | **Accepted**: the AUDITOR may create a checkpoint on demand, signed by OpenBao and recorded in the audit trail | existing archived checkpoints are never silently overwritten or replaced | archive files published with a hard link that fails if the name exists, even for racing writers (`FileCheckpointArchive`, `FileCheckpointArchiveTest`); one checkpoint per position (V9, pgTAP `11`); `audit_checkpoint` append-only (V3); e2e: the auditor signs a checkpoint, `AUDIT_CHECKPOINT_CREATED` names them |
| D6 | **Accepted with an implementation condition**: office context on audit events with the case module | office-scoped search and case timeline reconstruction stay unavailable until the event data is reliably recorded; historical events are never given an office from a user's current office | [ADR-027](adr/027-office-context-on-audit-events.md); `AuditReadingScopeTest` keeps every audit endpoint at scope any; `PermissionMatrixIT` and e2e: a supervisor gets 403 on event search |
| D14 | **Matrix accepted**: finance and administration roles do not read parcels | a finance need for parcel references is a separate permission decision | `BaselineRole` unchanged; `PermissionMatrixIT` |
| ADR-024 | **Accepted**: no TOTP fallback | before go-live, verify and document the complete recovery procedure and confirm identity verification, audit logging and separation of duties | [account recovery procedure](technical/08-operations-and-troubleshooting.md#account-recovery-procedure), with two gaps to close or accept |

Signed approvals stay a separate, future ADR (ADR-026, "What this does not cover").
