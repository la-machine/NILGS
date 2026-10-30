# Permission matrix

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Who may do what (ADR-025; Slice 1 design §6; Baseline, "actors and permissions"). Every
endpoint is protected by one fine-grained permission. People get permissions in two ways:

- **Roles.** The nine baseline roles below are code
  (`backend/src/main/java/org/snigf/mvp/identity/domain/BaselineRole.java`), seeded by
  migration V7 as *system* roles that nobody can change at run time. Administrators define
  further *custom* roles in the database, with four-eyes approval.
- **Special grants.** One permission to one staff member for a task, optionally at one
  office, limited by an end date, a number of uses, both or neither. Four-eyes approval too.

Every role assignment is a request approved by a second administrator, and the database
enforces the approval workflow ([ADR-026](adr/026-approval-workflow-enforced-by-the-database.md)).
The comparison with the Baseline's matrix, difference by difference, is in
[permission-matrix-review.md](permission-matrix-review.md). The project owner decided on
22 Oct 2026: auditors may sign checkpoints on demand, never replacing one (D5); supervisors
read audit events of their office only once events carry an office recorded at the time
([ADR-027](adr/027-office-context-on-audit-events.md), D6), which they do since E-18
([ADR-038](adr/038-audit-subjects-and-case-timeline.md)): a supervisor now searches the events and
reads the timelines of their offices; finance and administration roles do not read parcels (D14).

This file is the reviewed copy of the code. `PermissionMatrixTest` fails when the tables
differ from `Permission.java` and `BaselineRole.java`; `PermissionCatalogueCheck` stops the
application when the database catalogue differs; `RoleSeedIT` proves V7 seeded exactly these
roles; `PermissionMatrixIT` checks every staff role against every protected endpoint.

## Catalogue

| Permission | Family | Delegable | Scopes | Description |
| --- | --- | --- | --- | --- |
| `admin.account.list` | administration | no | any | List and search accounts |
| `admin.role.assign` | administration | no | any | Request a role assignment for a staff account |
| `admin.assignment.read` | administration | no | any | Read role assignment requests |
| `admin.assignment.approve` | administration | no | any | Approve or reject role assignment requests |
| `admin.role.revoke` | administration | no | any | Revoke a role from a staff account |
| `admin.mfa.reset` | administration | no | any | Request the reset of a lost second factor |
| `admin.role.read` | administration | no | any | Read roles, the permission catalogue and role change requests |
| `admin.role.define` | administration | no | any | Request the creation, change or retirement of a role |
| `admin.role.approve` | administration | no | any | Approve or reject role change requests |
| `admin.grant.read` | administration | no | any | Read special grants |
| `admin.grant.request` | administration | no | any | Request a special grant for a user |
| `admin.grant.approve` | administration | no | any | Approve or reject special grant requests |
| `admin.grant.revoke` | administration | no | any | Revoke a special grant |
| `audit.event.search` | audit | yes | any, office | Search audit events |
| `audit.chain.verify` | audit | yes | any | Verify the audit chain |
| `audit.checkpoint.create` | audit | yes | any | Sign an audit checkpoint |
| `audit.checkpoint.list` | audit | yes | any | List audit checkpoints |
| `audit.checkpoint.download` | audit | yes | any | Download checkpoint files |
| `audit.key.read` | audit | yes | any | Read the checkpoint public keys |
| `parcel.create` | business | yes | any, office | Create a parcel |
| `parcel.correct` | business | yes | any, office | Correct a parcel (new version) |
| `parcel.read` | business | yes | any, linked, office | Read parcels |
| `application.create` | business | yes | own | Create an application |
| `application.submit` | business | yes | own | Submit an application |
| `application.respond` | business | yes | own | Respond to an information request |
| `application.read` | business | yes | any, assigned, office, own | Read applications |
| `completeness.review` | business | yes | office | Review completeness |
| `completeness.request-info` | business | yes | office | Request missing information |
| `alert.review` | business | yes | office | Review spatial alerts |
| `alert.resolve` | business | yes | office | Resolve spatial alerts |
| `review.recommend` | business | yes | assigned | Record a recommendation |
| `decision.record` | business | yes | assigned | Record a decision |
| `report.workload` | business | yes | any, office | Read workload reports |
| `payment.pay` | business | yes | own | Pay a fee (simulated) |
| `payment.reconcile` | business | yes | any | Reconcile payment exceptions |
| `document.issued.manage` | business | yes | any | Revoke, cancel or re-issue a document issued by SNIGF |
| `document.incident.review` | business | yes | any | Review document verification incidents |
| `identity.reset.confirm` | business | no | office | Confirm the identity of a person whose second factor is reset |

## Baseline roles

| Permission | APPLICANT | INTAKE_OFFICER | CADASTRAL_OFFICER | REVIEWER | DECISION_AUTHORITY | SUPERVISOR | AUDITOR | FINANCE_OFFICER | DOCUMENT_OFFICER | SYSTEM_ADMIN |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `admin.account.list` | — | — | — | — | — | — | — | — | — | any |
| `admin.role.assign` | — | — | — | — | — | — | — | — | — | any |
| `admin.assignment.read` | — | — | — | — | — | — | — | — | — | any |
| `admin.assignment.approve` | — | — | — | — | — | — | — | — | — | any |
| `admin.role.revoke` | — | — | — | — | — | — | — | — | — | any |
| `admin.mfa.reset` | — | — | — | — | — | — | — | — | — | any |
| `admin.role.read` | — | — | — | — | — | — | — | — | — | any |
| `admin.role.define` | — | — | — | — | — | — | — | — | — | any |
| `admin.role.approve` | — | — | — | — | — | — | — | — | — | any |
| `admin.grant.read` | — | — | — | — | — | — | — | — | — | any |
| `admin.grant.request` | — | — | — | — | — | — | — | — | — | any |
| `admin.grant.approve` | — | — | — | — | — | — | — | — | — | any |
| `admin.grant.revoke` | — | — | — | — | — | — | — | — | — | any |
| `audit.event.search` | — | — | — | — | — | office | any | — | — | — |
| `audit.chain.verify` | — | — | — | — | — | any | any | — | — | — |
| `audit.checkpoint.create` | — | — | — | — | — | — | any | — | — | — |
| `audit.checkpoint.list` | — | — | — | — | — | — | any | — | — | — |
| `audit.checkpoint.download` | — | — | — | — | — | — | any | — | — | — |
| `audit.key.read` | — | — | — | — | — | — | any | — | — | — |
| `parcel.create` | — | — | office | — | — | — | — | — | — | — |
| `parcel.correct` | — | — | office | — | — | — | — | — | — | — |
| `parcel.read` | linked | any | any | any | any | any | any | — | — | — |
| `application.create` | own | — | — | — | — | — | — | — | — | — |
| `application.submit` | own | — | — | — | — | — | — | — | — | — |
| `application.respond` | own | — | — | — | — | — | — | — | — | — |
| `application.read` | own | office | office | assigned | assigned | office | any | — | — | — |
| `completeness.review` | — | office | — | — | — | — | — | — | — | — |
| `completeness.request-info` | — | office | — | — | — | — | — | — | — | — |
| `alert.review` | — | — | office | — | — | — | — | — | — | — |
| `alert.resolve` | — | — | office | — | — | — | — | — | — | — |
| `review.recommend` | — | — | — | assigned | — | — | — | — | — | — |
| `decision.record` | — | — | — | — | assigned | — | — | — | — | — |
| `report.workload` | — | — | — | — | — | office | — | — | — | — |
| `payment.pay` | own | — | — | — | — | — | — | — | — | — |
| `payment.reconcile` | — | — | — | — | — | — | — | any | — | — |
| `document.issued.manage` | — | — | — | — | — | — | — | — | any | — |
| `document.incident.review` | — | — | — | — | — | — | — | — | any | — |
| `identity.reset.confirm` | — | — | — | — | — | office | — | — | — | — |

APPLICANT is not assignable: it is what a self-registered account is, not a role an
administrator grants.

## Scopes

| Scope | Meaning | Checked by |
| --- | --- | --- |
| any | everywhere, whatever office the role was granted at | authority `<permission>`, `@access.can(authentication, '<permission>')` |
| office | records of the office the role was granted at | authority `<permission>@<OFFICE>`, `@access.canAt(authentication, '<permission>', officeCode)`; a record of two offices (an alert): `@access.canAtAny(authentication, '<permission>', offices)` |
| assigned | records assigned to the user | authority `<permission>#assigned`, `@access.canAssigned(authentication, '<permission>')`; the service, per record (assigned to the caller, or waiting to be claimed at their stage; 404 for any other), and row-level security (`case_visible_to_assignee`, ADR-035) |
| own | the user's own records | authority `<permission>#own`, `@access.canOwn(authentication, '<permission>')`; the service, per record (404 for any other), and row-level security on the actor named per transaction (applications, ADR-032) |
| linked | records linked to the user's own records | authority `<permission>#linked`; the service, per record |
| — | not granted | |

A relative scope (assigned, own, linked) never counts as *any* or as an office: `can`,
`canAt` and `canSomewhere` ignore `#` authorities, so an applicant's `parcel.read` (linked)
opens no parcel or map endpoint (`PermissionMatrixTest.relativeScopesNeverCountAsEverywhere`).
`canSomewhere` (held at some office or everywhere) guards what is not tied to one office,
such as the map backgrounds.

An endpoint checked with `can` (no office) is reached only with scope **any**: a supervisor's
`audit.event.search` at office scope will open once audit events carry an office.

## Rules that come with it

- **Separation of duties (C6c):** nobody grants or revokes their own roles, resets their own
  second factor, requests a special grant for themselves, approves their own request,
  approves a role or a grant for themselves, or approves a change to a role they hold. Refused by the service and audited as DENIED; the
  database refuses again (`ck_no_self_grant`, `ck_no_self_revoke`, `ck_grant_not_self_requested`,
  `ck_grant_four_eyes`, `ck_role_request_four_eyes`, `ck_assignment_request_four_eyes`, and the
  V8 triggers that check the approver holds the approval permission).
- **Administration and audit never meet:** no role (baseline or custom) and no person may hold
  permissions of both families. Checked when a role is defined or changed, when a role is
  assigned, and when a special grant is approved.
- **A second-factor reset needs the person's supervisor:** an administrator requests it, a
  holder of `identity.reset.confirm` at an office of the person confirms it in the
  application ([ADR-028](adr/028-key-removal-and-supervised-reset.md)). The permission is a
  business one, so supervisors keep their audit permissions without mixing duties.
- **Administration is never delegable:** a special grant can carry business or audit
  permissions only (`ck_administration_not_delegable`, `permission_grant_delegable`).
- **Back-office roles go to staff only:** an applicant account never receives one.
- **Permissions count only at level FULL:** a staff session without its second factor holds
  no permission and no special grant.
- **Every special-grant use is counted and audited:** one use is taken atomically before the
  action, given back if it fails, and recorded as `PERMISSION_GRANT_USED`.
- **Not yet enforced (later slices):** C6a (the decision authority is not the recommending
  reviewer) and C6b (an alert resolver authored neither compared version) come with their
  modules.

## Changing it

- **A custom role:** through the administration screens (request, then a second administrator
  approves). No code change. See `docs/guides/roles-and-administration.md`.
- **A baseline role or the catalogue** (a new permission, a new scope):
  1. Change `Permission.java` and/or `BaselineRole.java`.
  2. Add a migration that inserts or updates the `permission`, `role_definition` and
     `role_permission` rows the same way (the application refuses to start otherwise).
  3. Paste the tables `PermissionMatrixTest` prints into this file.
  4. Protect the endpoint with `@access.can` / `@access.canAt` and add it to
     `PermissionMatrixIT.ENDPOINTS`.
  5. Add a `CHANGELOG.md` entry; the change needs review like any security change.
