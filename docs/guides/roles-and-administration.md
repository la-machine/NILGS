# Roles, permissions and administration

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Every function of the system is protected by its own permission. A person gets
permissions in two ways:

- **Roles**, granted at an office, by someone else, for a written reason. Nine baseline
  roles come with the system and never change at run time; administrators define further
  roles from the permission catalogue.
- **Special grants**: one permission for one person, for a task, limited by an end date,
  a number of uses, both, or neither.

Every change of rights (a role assignment, a custom role, a special grant) needs a second
administrator (four eyes), and is audited. The database enforces the workflow too, so the
application's own database account cannot skip it. Nobody can widen their own rights.
Nobody holds administration and audit together. Sources:

- [ADR-025](../adr/025-fine-grained-permissions-and-special-grants.md) (this design);
- [ADR-026](../adr/026-approval-workflow-enforced-by-the-database.md) (four eyes on every
  assignment, enforcement by the database, supervisors verify the chain);
- the Slice 1 design §5 and §6 (office scope, separation of duties C6c);
- the Baseline's permission matrix ([docs/permission-matrix.md](../permission-matrix.md));
- [ADR-024](../adr/024-no-totp-fallback.md) (recovery without TOTP).

## Where

| File | Role |
| --- | --- |
| `shared/security/Permission.java` | The catalogue: code, family, delegable, allowed scopes, descriptions |
| `identity/domain/BaselineRole.java` | The nine baseline roles (seeded as immutable system roles by V7) |
| `identity/domain/RoleDefinition.java` | A role as stored; `problems(...)` validates a proposed custom role |
| `identity/domain/SeparationOfDuties.java` | Administration and audit never together |
| `identity/domain/EffectivePermissions.java` | Role grants into authorities: `audit.event.search`, `parcel.create@DEMO1` |
| `identity/domain/SpecialGrant.java`, `identity/ActiveGrant.java` | A special grant; the part of it the principal carries |
| `identity/application/AuthorizationService.java` | Builds the principal's permissions and special grants on every request; SoD checks |
| `identity/application/RoleAdministrationService.java` | Custom role requests, approval, rejection |
| `identity/application/SpecialGrantService.java` | Special grant requests, approval, rejection, revocation; use reservation and audit |
| `identity/application/AccountAdministrationService.java` | Directory, role requests, approval and rejection, revocation, second-factor reset |
| `identity/domain/RoleAssignmentRequest.java`, `infrastructure/web/AssignmentController.java` | A requested role assignment; `/api/v1/admin/assignment-requests` |
| `identity/infrastructure/security/Access.java` | `@access.can(...)` and `@access.canAt(...)`: the one check every endpoint uses |
| `identity/infrastructure/security/SpecialGrantUseInterceptor.java` | Keeps or gives back a special-grant use when the request ends |
| `identity/infrastructure/config/PermissionCatalogueCheck.java` | Refuses to start when the database catalogue differs from the code |
| `identity/infrastructure/persistence/JdbcAuthorizationRepository.java` | Roles, requests, grants; atomic use counting |
| `identity/infrastructure/web/AdminController.java`, `RoleController.java`, `GrantController.java` | `/api/v1/admin/...` endpoints |
| `db/migration/V7__roles_and_special_grants.sql` | Tables, seeds, constraints and triggers |
| `db/migration/V8__approval_workflow_enforced.sql` | The approval workflow enforced by the database: `role_assignment_request`, approver checks, transitions, role changes applied on approval |
| `frontend/src/shared/session/access.ts` | Whether the user holds a permission through a role or only through a special grant |
| `docs/permission-matrix.md` | The reviewed copy of the catalogue and baseline roles |
| `frontend/src/features/{accounts,roles,grants,audit}/` | Panels and their endpoints (`api.ts`), opened by permission from `app/Panels.tsx` |
| `infra/scripts/dev-make-staff.sh` | Development only: bootstrap the first administrators and auditor |

## How it works

### From roles and grants to a decision

At every request:

1. `SessionSecurityContextRepository` rebuilds the principal through
   `AuthorizationService`: active role assignments, the definitions of those roles, and
   the special grants in force (approved, not expired, uses left).
2. If, and only if, the session is FULL (every factor proven), the role permissions become
   authorities:
   - **scope `any`:** the bare code, `audit.event.search`;
   - **scope `office`:** the code at the office of the assignment, `parcel.create@DEMO1`;
   - **scopes `assigned`, `own`, `linked`:** the bare code; the service checks the record.
3. The endpoint asks `@access.can(authentication, 'audit.event.search')` (or `canAt` with
   an office). `Access` allows when an authority matches. Otherwise it looks for a special
   grant covering the permission (everywhere, or at that office) and **takes one use**
   with a single conditional `UPDATE`. If the grant has no use left, or expired a moment
   ago, the update changes nothing and the answer is 403.
4. When the response is complete, `SpecialGrantUseInterceptor` keeps the use and records
   `PERMISSION_GRANT_USED` (SUCCESS) for a 2xx or 3xx answer, or gives it back and records
   FAILED for an error. So a grant of three uses allows three successful actions, even
   under concurrent requests.

A revoked role, a retired role, a revoked grant or a disabled account counts at the next
request, because nothing is cached in the session (ADR-022).

### Role assignments (four eyes)

1. Administrator A requests a role for a staff member at an office, with a reason
   (`POST /admin/users/{id}/roles`, answer 202 with a `requestId`). Nothing is granted yet.
   Refused: a role for oneself, a role already held there, an open request for the same role,
   office and person, a role that would give the person administration and audit together.
2. Administrator B, who is neither A nor the person, approves it
   (`/admin/assignment-requests/{id}/approve`). The database checks that B holds
   `admin.assignment.approve`, that the request is pending and at most 30 days old (the
   application applies `SNIGF_ADMIN_REQUEST_TTL` first), and separation of duties; then it
   creates the assignment, with the request's id, in the same statement.
3. The person has the role at their next request. `ROLE_ASSIGNMENT_REQUESTED`,
   `ROLE_GRANTED` (with the requester in the details) or `ROLE_ASSIGNMENT_REJECTED` are in the
   audit trail.

Revocation is one administrator's act (`admin.role.revoke`, checked by the database too):
taking a right away must be fast.

### What the database refuses to the application's own account (V8)

Even with the application's password, nobody can:

- insert a role assignment without an approved request (only the self-registration role, for
  an applicant account, by the system);
- insert a request or a grant already approved, or set its time;
- approve without holding the approval permission through an active role, approve one's own
  request, or approve a role or grant for oneself;
- write a role definition or its permissions: an approved role change is applied by the
  database itself;
- revive a revoked grant, change a decision, or move a grant's uses by more than one.

The PostgreSQL superuser is the one exception, for bootstrap (below).

### Custom roles (four eyes)

1. Administrator A asks to **create**, **change** or **retire** a role, with a reason of at
   least 10 characters (`POST /admin/role-requests`). The proposal is validated now
   (catalogue permissions, allowed scopes, no administration with audit) and audited
   (`ROLE_DEFINITION_REQUESTED`).
2. Administrator B approves or rejects it. Approval is refused when:
   - B is A (`SOD_FOUR_EYES`, audited DENIED);
   - B holds the role being changed (`SOD_SELF_BENEFIT`, audited DENIED);
   - the request is older than `SNIGF_ADMIN_REQUEST_TTL` (`REQUEST_EXPIRED`);
   - the change would give a current holder administration and audit (`SOD_CONFLICT`);
   - the role is still held, for a retirement (`ROLE_IN_USE`).
3. On approval the change applies at once; holders see it at their next request.

Baseline roles cannot be changed (`SYSTEM_ROLE_IMMUTABLE`; the database trigger
`role_definition_guard` refuses it too). Retired roles stay in the database for history
and can no longer be assigned.

### Special grants (four eyes)

1. Administrator A requests a grant for staff member C: permission, optional office,
   reason, optional `validUntil`, optional `maxUses` (`POST /admin/grants`). A cannot be
   C. Administration permissions are refused (`PERMISSION_NOT_DELEGABLE`).
2. Administrator B, who is neither A nor C, approves. Approval re-checks separation of
   duties against C's roles and grants.
3. The grant is in force until the first of: its end date, its last use, its revocation
   (`POST /admin/grants/{id}/revoke`, by anyone but C).

| Limit | `validUntil` | `maxUses` |
| --- | --- | --- |
| A time window | set | empty |
| A number of actions | empty | set |
| Both, whichever ends first | set | set |
| Until revoked | empty | empty |

### Endpoints

| Method and path | Permission | Request | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/users?q=&page=` | `admin.account.list` | none | 200 accounts with active assignments | 403 |
| `POST /api/v1/admin/users/{id}/roles` | `admin.role.assign` | `role`, `officeCode`, `reason` | 202 `{requestId}`, `Location` of the request | 403 `SOD_SELF_ASSIGNMENT`; 404 `USER_NOT_FOUND`, `OFFICE_NOT_FOUND`; 409 `ROLE_ALREADY_ACTIVE`, `REQUEST_ALREADY_OPEN`, `SOD_CONFLICT`; 422 `ROLE_NOT_ASSIGNABLE`, `ACCOUNT_NOT_STAFF`, `REASON_REQUIRED` |
| `GET /api/v1/admin/assignment-requests?status=` | `admin.assignment.read` | none | 200 requests, newest first | 403 |
| `POST /api/v1/admin/assignment-requests/{id}/approve` | `admin.assignment.approve` | `reason` | 204; the assignment exists, with the request's id | 403 `SOD_FOUR_EYES` (the requester or the beneficiary); 404 `REQUEST_NOT_FOUND`; 409 `REQUEST_NOT_PENDING`, `REQUEST_EXPIRED`, `ROLE_ALREADY_ACTIVE`, `SOD_CONFLICT`, `REFUSED_BY_DATABASE`; 422 `ROLE_NOT_ASSIGNABLE` |
| `POST /api/v1/admin/assignment-requests/{id}/reject` | `admin.assignment.approve` | `reason` | 204 | 403 `SOD_FOUR_EYES` (the beneficiary); 409 `REQUEST_NOT_PENDING` |
| `DELETE /api/v1/admin/users/{id}/roles/{assignmentId}` | `admin.role.revoke` | `reason` | 204 | 403 `SOD_SELF_ASSIGNMENT`; 404 `ASSIGNMENT_NOT_FOUND`; 409 `ASSIGNMENT_ALREADY_REVOKED` |
| `POST /api/v1/admin/users/{id}/mfa-reset` | `admin.mfa.reset` | `reason` (10+), `confirmedBy` | 204 | 403 `SOD_SELF_ACTION`; 422 `REASON_REQUIRED`, `CONFIRMATION_REQUIRED`, `ACCOUNT_NOT_STAFF` |
| `GET /api/v1/admin/permissions` | `admin.role.read` | none | 200 the catalogue | 403 |
| `GET /api/v1/admin/roles` | `admin.role.read` | none | 200 every role with its permissions and scopes | 403 |
| `GET /api/v1/admin/role-requests?status=` | `admin.role.read` | none | 200 requests, newest first | 403 |
| `POST /api/v1/admin/role-requests` | `admin.role.define` | `kind` (CREATE, UPDATE, RETIRE), `roleCode`, `nameFr`, `nameEn`, `description`, `permissions` (`{code: scope}`), `reason` (10+) | 201 `{requestId}` | 403 `SYSTEM_ROLE_IMMUTABLE`; 404 `ROLE_NOT_FOUND`; 409 `ROLE_EXISTS`, `ROLE_RETIRED`, `REQUEST_ALREADY_OPEN`, `SOD_CONFLICT`; 422 `ROLE_CODE_INVALID`, `ROLE_NAME_INVALID`, `ROLE_WITHOUT_PERMISSION`, `PERMISSION_UNKNOWN`, `SCOPE_NOT_ALLOWED`, `REASON_REQUIRED` |
| `POST /api/v1/admin/role-requests/{id}/approve` | `admin.role.approve` | `reason` | 204 | 403 `SOD_FOUR_EYES`, `SOD_SELF_BENEFIT`; 404 `REQUEST_NOT_FOUND`; 409 `REQUEST_NOT_PENDING`, `REQUEST_EXPIRED`, `ROLE_IN_USE`, `SOD_CONFLICT` |
| `POST /api/v1/admin/role-requests/{id}/reject` | `admin.role.approve` | `reason` | 204 | 404, 409 `REQUEST_NOT_PENDING` |
| `GET /api/v1/admin/grants?userId=&status=` | `admin.grant.read` | none | 200 grants, newest first | 403 |
| `POST /api/v1/admin/grants` | `admin.grant.request` | `userId`, `permission`, `officeCode`, `reason` (10+), `validUntil`, `maxUses` | 201 `{grantId}` | 403 `SOD_SELF_ASSIGNMENT`; 404 `USER_NOT_FOUND`, `OFFICE_NOT_FOUND`; 422 `PERMISSION_UNKNOWN`, `PERMISSION_NOT_DELEGABLE`, `SCOPE_NOT_ALLOWED`, `VALID_UNTIL_IN_PAST`, `MAX_USES_INVALID`, `ACCOUNT_NOT_STAFF`, `REASON_REQUIRED` |
| `POST /api/v1/admin/grants/{id}/approve` | `admin.grant.approve` | `reason` | 204 | 403 `SOD_FOUR_EYES` (the requester or the beneficiary); 404 `GRANT_NOT_FOUND`; 409 `REQUEST_NOT_PENDING`, `REQUEST_EXPIRED`, `SOD_CONFLICT` |
| `POST /api/v1/admin/grants/{id}/reject` | `admin.grant.approve` | `reason` | 204 | 403 `SOD_FOUR_EYES` (the beneficiary); 409 `REQUEST_NOT_PENDING` |
| `POST /api/v1/admin/grants/{id}/revoke` | `admin.grant.revoke` | `reason` | 204 | 403 `SOD_SELF_ASSIGNMENT` (the beneficiary); 404 `GRANT_NOT_FOUND`; 409 `GRANT_NOT_ACTIVE` |
| `GET /api/v1/audit/events?...` | `audit.event.search` | none | 200 `{events, page, size, more}`; the search is audited | 422 |
| `POST /api/v1/audit/verify` | `audit.chain.verify` | optional `fromPos`, `toPos` | 200 verification result | 422 |
| `POST /api/v1/audit/checkpoints` | `audit.checkpoint.create` | none | 201 checkpoint + `Location` | 409 `AUDIT_CHAIN_BROKEN`, `CHECKPOINT_UNAVAILABLE` |
| `GET /api/v1/audit/checkpoints` | `audit.checkpoint.list` | none | 200 list | none |
| `GET /api/v1/audit/checkpoints/{pos}/file` | `audit.checkpoint.download` | none | 200 the file (download audited) | 404 |
| `GET /api/v1/audit/checkpoint-keys` | `audit.key.read` | none | 200 public keys | none |

`GET /api/v1/auth/session` returns the caller's `permissions` and `specialGrants` (with
end date and uses left) once the session is FULL; the interface shows panels from them.

### Actions held only through a special grant

Reading the session costs nothing; a use is spent only when the protected call succeeds. The
interface (`shared/session/access.ts`) opens a panel when a role or a grant allows one of its actions.
Through a grant, the panel loads nothing by itself; each grant-only action is a button that
says "special grant, N uses left" and asks for confirmation; cancelling spends nothing. After
the action the session is reloaded, so the uses left are current. If an action needs a
supporting read (a parcel before correcting it), that read uses its own permission: grant it
as well, or rely on a role that holds it.

### Separation of duties

- **Self-action (C6c):** nobody grants, revokes, or resets their own second factor, or
  requests a special grant for themselves. Refused by the service (audited DENIED) and by
  the database (`ck_no_self_grant`, `ck_no_self_revoke`, `ck_grant_not_self_requested`).
- **Four eyes:** the requester never approves (`ck_role_request_four_eyes`,
  `ck_grant_four_eyes`, `ck_assignment_request_four_eyes`); the beneficiary never approves a
  role or a grant for themselves, nor revokes their own grant; the approver must hold the
  approval permission (V8).
- **No self-benefit:** an administrator who holds a role cannot approve its change.
- **Administration and audit never meet,** in a role or in a person.
- **Administration is never delegable** by special grant.

## How to do it again

### Protect a new endpoint

1. Pick the permission from `Permission`. If none fits, add one (code, family, delegable,
   scopes, descriptions) **and** a migration inserting the same `permission` row; the
   application will not start otherwise.
2. Annotate the method: `@PreAuthorize("@access.can(authentication, 'code')")`, or
   `canAt(authentication, 'code', #body.officeCode())` for an office.
   `EndpointAnnotationArchTest` fails on anything else and on unknown codes.
3. Add the endpoint to `PermissionMatrixIT.ENDPOINTS`.
4. For `assigned`, `own` or `linked` scopes, check the record in the service and answer 404
   when the caller may not see it (existence is not leaked).
5. An endpoint reachable by special grant should do one action per request: one use is
   counted per successful request.

### Define a custom role (no code change)

1. Administrator A: Administration → Roles → New role. Pick permissions and scopes, give
   a reason.
2. Administrator B: Administration → Requests → Approve.
3. Assign it like any role (Administration → Accounts).

### Give a special grant

1. Administrator A: Administration → Special grants → New. Pick the person, the
   permission, the office or "everywhere", and the limits.
2. Administrator B (not the person) approves.
3. The person sees it on their session page, with uses left and end date.

### Change a baseline role or the catalogue

1. Edit `BaselineRole.java` / `Permission.java`.
2. Add a migration that changes the seeded rows the same way.
3. Run `./mvnw test -Dtest=PermissionMatrixTest`; paste the printed tables into
   `docs/permission-matrix.md`.
4. Update the changelog and have the change reviewed.

### Bootstrap the first administrators and auditor (development)

Four eyes needs two administrators. The superuser is the only path that assigns a role
without a request (ADR-026). Register three accounts in the interface, then:

```sh
bash infra/scripts/dev-make-staff.sh admin1@demo.invalid SYSTEM_ADMIN
bash infra/scripts/dev-make-staff.sh admin2@demo.invalid SYSTEM_ADMIN
bash infra/scripts/dev-make-staff.sh auditor@demo.invalid AUDITOR
```

Each logs in and enrols two keys. From then on everything goes through the interface.
In a pilot, the first administrators are created by the deployment procedure under
four-eyes control, never by a script anyone can run.

### Reset a lost second factor (procedure)

The complete procedure (one key lost, both keys lost, everything lost), what the system
enforces and the gaps to close before go-live are in
[chapter 8](../technical/08-operations-and-troubleshooting.md#account-recovery-procedure).
In short:

1. The staff member reports the loss of both keys and all recovery codes.
2. Their supervisor confirms their identity out of band and tells an administrator.
3. The administrator (not the staff member) resets with a reason and the confirmation.
4. The staff member logs in and enrols two new keys. `MFA_RESET` in the audit trail holds
   the reason, who confirmed, how many keys were removed and how many sessions ended.

## How to verify

```sh
cd backend && ./mvnw verify
docker compose --profile test run --rm --build dbtest   # pgTAP 09_authorization: V7 constraints and triggers
```

These tests prove:

- **`PermissionMatrixTest`, `PermissionCatalogueCheckTest`, `RoleSeedIT`:** the document,
  the code and the database hold the same catalogue and baseline roles; the startup check
  names every difference.
- **`PermissionMatrixIT`:** each of the 8 staff roles reaches exactly the endpoints the
  matrix grants it; applicants none.
- **`AdminFlowIT`:** a role exists only after a second administrator approves it and takes
  effect at the next request; requester and beneficiary cannot approve; the application's
  database account cannot assign, forge an approved request or approve without the
  permission; the request rules hold; a reset removes keys and ends sessions.
- **pgTAP `10_approval_workflow`:** 30 checks of what V8 refuses to the application's account.
- **e2e `flows`** ([guide](end-to-end-testing.md)): the same journeys in Chromium, with
  security keys, across two administrators, an officer, an auditor and a supervisor.
- **`RoleAdministrationIT`:** a custom role is created only after a second administrator
  approves, then opens exactly its endpoints; four eyes, self-benefit, immutable baseline,
  retirement while held and administration-with-audit are refused.
- **`SpecialGrantIT`:** a grant opens one endpoint for one person; an end date and a use
  limit end it; concurrent requests never exceed the limit; a failed action gives the use
  back; every use is audited; revocation and the non-delegable rule hold.
- **`AuditApiIT`:** searches are audited; a checkpoint verifies with the published key; a
  tampered chain is never signed; only auditors read the trail.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| `AuditChainIT` failed only in the full run, with `CHECKPOINT_SIGNATURE` | Two test classes ran in two Spring contexts, each with its own in-memory test keys | One shared base, `PostgisIntegrationTest` |
| `No value specified for parameter 2` in a test query | PostgreSQL's JSON operator `?` is a JDBC placeholder | Use `details->>'key' IS NOT NULL` |
| A list parameter in `= ANY(CAST(:ids AS uuid[]))` breaks | Spring expands collection parameters | Write `IN (:ids)` |
| `max_uses` read as 0 for an unlimited grant | `ResultSet.wasNull()` checked after another column was read | `rs.getObject("max_uses", Integer.class)` |
| Maven said "Nothing to compile" after a class was deleted, then tests failed oddly | Stale incremental build | `./mvnw clean test-compile` after removing classes |

## Production differences

- **Approver identity:** the database checks that the named approver may approve, not that
  they clicked; signed approvals (planned ADR) will prove it.
- **Administration:** at least two administrators, three when one may be a beneficiary.
  The first ones are created by the deployment procedure with two people present;
  `dev-make-staff.sh` refuses to run outside development.
- **Supervisors:** they verify the chain (no event content shown). The baseline also grants
  them audit search for their own office; that needs an office on audit events and arrives
  with the case module; until then the search stays closed to them.
- **Staff accounts:** created by the institution's onboarding procedure. An
  account-creation endpoint (with a one-time enrolment link) is backlog.
