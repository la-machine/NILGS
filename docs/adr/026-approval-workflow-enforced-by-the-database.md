# ADR-026: Four eyes on every role assignment, enforced by the database

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-22 | Project owner, Pythagore (FLYT) |

Extends [ADR-025](025-fine-grained-permissions-and-special-grants.md).

## Context

The review of step 4d found three gaps:

1. **Role assignment had no four-eyes step.** One administrator alone could give anyone
   (except themselves) any role, SYSTEM_ADMIN and AUDITOR included.
2. **The workflow lived in the application.** The application's database account could
   insert a special grant already APPROVED, or write role definitions without a request.
   A compromised application server could bypass every approval.
3. **The database never checked that an approver was entitled to approve**, only that they
   were a different person.

The review also asked for supervisors to verify the audit chain again
(`audit.chain.verify`), provided the operation stays read-only and shows no event content,
and for a way to find actions held only through a special grant without spending a use by
viewing.

## Decision

**Every role assignment is a request.** One administrator requests a role for a staff
member at an office (`admin.role.assign`); another approves or rejects it
(`admin.assignment.approve`, new; `admin.assignment.read`, new, to list them). The
requester and the beneficiary never approve. Approving creates the assignment **in the same
statement** (a trigger), with the request's id, so an approval without its assignment, or an
assignment without its approval, cannot exist. Revocation stays a one-person action by a
holder of `admin.role.revoke`: taking a right away must be fast.

**The database enforces the workflow for the application's account (migration V8):**

| Object | What `snigf_app` can no longer do |
| --- | --- |
| `role_assignment` | insert any role other than the self-registration role (applicants only, by the system) except for an approved request, once |
| `role_assignment_request`, `role_change_request`, `permission_grant` | insert a request other than PENDING; set its time; decide it without holding the approval permission; decide its own request or (for grants and assignments) one that benefits the decider; change a decision; approve a request older than 30 days |
| `role_definition`, `role_permission` | write at all: an approved role change request is applied by a SECURITY DEFINER trigger owned by the schema owner, which checks the role's permissions, separation of duties for the role and for every holder, and that a retired role is no longer held |
| `permission_grant` | move status other than PENDING → APPROVED, REJECTED or REVOKED, APPROVED → REVOKED; move `uses` by more than one, or up when the grant is not in force |

Who may approve is computed by the database from active role assignments
(`holds_permission`), and nobody may end up holding administration and audit permissions
together (`mixes_duties`), at assignment, grant approval and role change. Timestamps are the
database's.

**Bootstrap:** the PostgreSQL superuser is not bound by the assignment rule. It is the only
way to create the first administrators: the deployment procedure with two people present in
the pilot, `dev-make-staff.sh` in development. The superuser can bypass any trigger anyway
(residual risk documented in `docs/audit.md`).

**Supervisors verify the chain** (`audit.chain.verify`, scope any, added to SUPERVISOR by V8).
The result is "intact" or the position and kind of the first mismatch, plus checkpoint
checks; no event content. The verification appends an `AUDIT_VERIFIED` event and changes
nothing else.

**Finding actions held only through a special grant:** the session lists the grants in force
(costs nothing). The interface opens a panel when a role or a grant allows one of its
actions; through a grant it loads nothing by itself, shows each grant-only action with
"special grant, N uses left", asks for confirmation, and only the confirmed, successful
call spends a use (`frontend/src/shared/session/access.ts`).

## What this does not cover

- **A compromised application can still name another real administrator as approver.** The
  database checks that this person holds the permission and is not the requester, but cannot
  know who clicked. Approvals signed with the approver's security key, verifiable offline,
  close this; they get their own ADR.
- **A compromised application can still refund uses or act directly on business tables.**
  Use counts protect against mistakes and honest overuse, not against an attacker who
  already controls the application: such an attacker does not need a grant.
- Two administrators acting together can defeat four eyes; the audit trail records both.

## Alternatives considered

- **Four eyes only for roles with administration or audit permissions:** fewer approvals,
  but a REVIEWER or DECISION_AUTHORITY role decides land cases; rejected, all roles need it.
- **Four eyes on revocation too:** slower response to an incident; rejected.
- **Application-only checks:** the state before this ADR; rejected for the reasons above.
- **Removing the application's INSERT on `role_assignment` entirely and creating every
  assignment by SECURITY DEFINER:** equivalent protection, but registration (the
  self-registration role) would need a definer function too; the insert guard is simpler.

## Consequences

- `POST /api/v1/admin/users/{id}/roles` answers 202 with a `requestId`, not 201 with an
  assignment; `/api/v1/admin/assignment-requests` lists, approves and rejects.
- Tests and scripts that seeded roles through the application account now seed them as the
  superuser (`IdentityIntegrationTest.insertStaff`, pgTAP 02).
- A database refusal the service did not foresee is answered 409 `REFUSED_BY_DATABASE` and
  logged as a warning: it means a race or an attempt worth looking at.
- The pilot needs at least two administrators, three when one may be a beneficiary.
