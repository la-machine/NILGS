# ADR-025: Fine-grained permissions, administrator-defined roles and special grants

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-21 | Project owner, Pythagore (FLYT) |

## Context

Until now, nine roles were fixed in code (`Role.java`) and endpoints checked four coarse
permissions (`user.manage`, `role.assign`, `audit.read`, `audit.verify`). The project owner
asked for:

1. permissions "as detailed as possible": every function protected by its own permission;
2. roles that bundle permissions, and that **administrators can create** and change;
3. "special orders": one permission handed to one person for a task, for a defined or an
   undefined number of times (answer: limited by time, by uses, both, or neither);
4. a second administrator approving such grants (answer: yes).

## Decision

**A catalogue of permissions in code, mirrored in the database.** `Permission.java` lists
every permission (33 at this step): code (`audit.checkpoint.create`), family
(ADMINISTRATION, AUDIT, BUSINESS), whether it may be delegated, the scopes it allows
(any, office, assigned, own, linked) and descriptions in English and French. Migration V7
seeds the same rows into `permission`. At startup `PermissionCatalogueCheck` refuses to run
when the two differ, because a permission the code checks but the database lacks could never
be granted, and a database row the code ignores would look real while granting nothing.
New permissions therefore arrive with code and a migration together, reviewed.

**Roles are data, with a fixed baseline.** `role_definition` and `role_permission` hold
roles. The nine roles of the Baseline matrix (`BaselineRole.java`) are seeded as *system*
roles: a trigger (`role_definition_guard`) refuses any change to them, so the reviewed
matrix stays what the documentation says. Administrators create, change and retire
*custom* roles at run time, from catalogue permissions and allowed scopes only.

**Four eyes on every change of rights.** Creating, changing or retiring a custom role, and
giving a special grant, are *requests*. A second administrator approves or rejects them. The
approver is never the requester, never the beneficiary of a grant, and never a holder of the
role being changed (nobody widens their own rights). The database enforces the first two
again (`ck_role_request_four_eyes`, `ck_grant_four_eyes`, `ck_grant_not_self_requested`).
Requests expire after `SNIGF_ADMIN_REQUEST_TTL` (1 h to 30 d; 7 d by default).

**Special grants.** One permission, to one staff member, everywhere or at one office, with a
written reason, and optionally an end date (`valid_until`) and a maximum number of uses
(`max_uses`, 1 to 100000). Without either it lasts until revoked. A use is taken atomically
before the action (`UPDATE … SET uses = uses + 1 WHERE … uses < max_uses`), so concurrent
requests can never exceed the limit; it is given back if the action fails; every use is
audited (`PERMISSION_GRANT_USED`). Administration permissions are **never delegable**.

**Separation of duties across families.** No role and no person may hold administration
and audit permissions together. Checked when a role is defined or changed (against every
current holder), when a role is assigned, and when a special grant is approved.

**One check for every endpoint.** `@PreAuthorize("@access.can(authentication, 'code')")` or
`@access.canAt(authentication, 'code', officeCode)`. A role permission wins; otherwise a
special grant in force is used. `EndpointAnnotationArchTest` fails the build when an endpoint
uses anything else or names a permission that is not in the catalogue.

**Roles and grants are read on every request** (as before, ADR-022): a revocation, a
retirement or an exhausted grant takes effect on the next request, not at the next login.

## Why

- **Detailed permissions** make each endpoint's rule obvious and testable
  (`PermissionMatrixIT` runs every role against every endpoint).
- **Catalogue in code** keeps the meaning of a permission next to the check that uses it; an
  administrator cannot invent a permission nothing enforces.
- **Immutable baseline** keeps the reviewed matrix true; flexibility lives in custom roles.
- **Four eyes and self-benefit refusal** stop a single compromised or careless administrator
  from granting rights, including to themselves.
- **Counted, audited uses** answer "who used the exception, when, for what" exactly.

## Alternatives considered

- **Roles fixed in code only** (first proposal, accepted then withdrawn by the project owner):
  simplest to review, but every new job profile needs a release.
- **Everything in the database, including the catalogue:** an administrator could create a
  permission the code never checks; rejected.
- **Grants applied immediately, reviewed afterwards:** faster, but the harm happens before
  the review; rejected for a land registry.
- **Counting uses after the action:** concurrent requests could exceed the limit; rejected.

## Consequences

- A new permission is a code change plus a migration (see `docs/permission-matrix.md`).
- Every administration change needs two administrators: the pilot must have at least two
  people with SYSTEM_ADMIN, and a third if one of them would be the beneficiary.
- Endpoints checked with `can` (no office) are reached only with scope *any*: the
  supervisor's office-scoped `audit.event.search` opens once audit events carry an office.
- Uses are counted per successful HTTP request, so an endpoint should perform one action.
