# Changelog

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Every notable change to SNIGF/NILGS, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once the first release (R0) is tagged.
Each entry names the decision record (ADR) and the guide (`docs/guides/`) that explain it.

## [Unreleased]

### Project owner's decisions D5, D6, D14 and ADR-024; recovery procedure verified

Records: [`docs/permission-matrix-review.md`](docs/permission-matrix-review.md) (decisions),
[ADR-027](docs/adr/027-office-context-on-audit-events.md),
[ADR-024](docs/adr/024-no-totp-fallback.md) (accepted),
[account recovery procedure](docs/technical/08-operations-and-troubleshooting.md#account-recovery-procedure).

#### Added

- **ADR-027 (D6):** audit events get an office with the case module, taken from the record
  at the time of the event and hashed; never assigned retrospectively from a user's current
  office; until then office-scoped audit reading stays closed (`AuditReadingScopeTest`).
- **Migration V9 (D5):** one checkpoint per chain position. pgTAP test `11_checkpoints`.
- **The account recovery procedure** (chapter 8): one key lost, both keys lost, everything
  lost; what the system enforces and what remains procedure; the audit query; two gaps to
  close before go-live.
- **End-to-end:** the auditor signs a checkpoint; recovery with a single-use code (reuse
  refused) and a new key; a supervised reset by another administrator (self-reset refused),
  old codes dead, enrolment again, `MFA_RESET` found by the auditor.
- `FileCheckpointArchiveTest`.

#### Changed

- ADR-024 (no TOTP fallback) is **accepted**, with its go-live conditions.
- The permission matrix records D5 (auditor checkpoints on demand), D6 (office-scoped audit
  reading only after ADR-027) and D14 (no parcel reading for finance and administration).

#### Security

- **A checkpoint file can no longer be replaced by a racing writer.** It was published with
  an atomic rename after an existence check; on POSIX a rename replaces the target, so two
  writers could overwrite one another. It is now published with a hard link, which fails
  atomically if the name exists, and the temporary file is always removed.
- Recovery verification found that **lost keys stay registered after a recovery-code
  sign-in** (until a reset) and that **the identity confirmation of a reset is free text**;
  both are documented with proposed fixes, not yet implemented.

### Frontend structure and the SNIGF logo

Manual: [chapter 10, frontend architecture](docs/technical/10-frontend-architecture.md);
map and debugging table: [`frontend/src/README.md`](frontend/src/README.md);
logo files: [`frontend/BRAND.md`](frontend/BRAND.md).

#### Added

- **The SNIGF logo:** the emblem in the header, the full logo above the sign-in and
  registration forms, favicons and a home-screen icon. Derived from the official
  `public/SNIGF Integrated Land Governance Logo.png` (kept unchanged): 27 KB instead of
  823 KB on the sign-in page. End-to-end test `branding` checks that each image loads.
- **Structure check** `frontend/scripts/check-architecture.mjs`, run by `npm run lint`:
  layer rules, features imported only through their `index.ts`, every feature with `api.ts`
  and `index.ts`, every file signed.

#### Changed

- **The frontend is organised by layer and feature** (`app/`, `features/{auth,accounts,
  roles,grants,audit}/`, `shared/{api,i18n,session,ui}/`, `styles/`), as the back end is by
  module. The 360-line `App.tsx` became a shell plus one file per screen; the single
  `api.ts` became one `api.ts` per feature on a shared client; the stylesheet is split into
  tokens, base, layout and components. Behaviour is unchanged (end-to-end 9/9).
- **Texts:** `shared/i18n/fr.ts` is the reference and `en.ts` is type-checked against it, so
  a missing English text fails the build (before, a cast hid it).
- The audit panel's checkpoint button reads "Show checkpoints" (it read "Search", like the
  event search).

### Security foundation, step 4e: four eyes on every assignment, enforced by the database; end-to-end tests

Decision: [ADR-026](docs/adr/026-approval-workflow-enforced-by-the-database.md).
Guides: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md),
[`docs/guides/end-to-end-testing.md`](docs/guides/end-to-end-testing.md).
Review: [`docs/permission-matrix-review.md`](docs/permission-matrix-review.md).

#### Added

- **Four eyes on every role assignment:** a role is requested by one administrator and
  approved or rejected by another (`/api/v1/admin/assignment-requests`); neither the
  requester nor the beneficiary approves; approval creates the assignment in the same
  statement. New permissions `admin.assignment.read` and `admin.assignment.approve`
  (SYSTEM_ADMIN). Audit actions `ROLE_ASSIGNMENT_REQUESTED`, `ROLE_ASSIGNMENT_REJECTED`.
- **Migration V8, the approval workflow enforced by the database:** the application's
  database account can no longer assign a role without an approved request, insert a request
  or grant already approved, approve without holding the approval permission, approve for
  itself, write role definitions (approved changes are applied by the database), move a
  grant outside its allowed transitions, or move uses by more than one. Requests are timed
  by the database and expire after 30 days at most. The superuser remains the bootstrap path.
- **Supervisors verify the audit chain** (`audit.chain.verify`): read-only, no event content.
- **Actions held only through a special grant** are shown with their uses left, never run on
  their own, and asked for confirmation; viewing or cancelling spends nothing
  (`frontend/src/shared/session/access.ts`). The audit panel offers the public keys (`audit.key.read`).
- **End-to-end tests in Chromium** (Compose service `e2e`, profile `e2e`,
  `infra/scripts/e2e.sh`): sign-in with virtual security keys, four-eyes role assignment and
  custom role across two administrators, a special grant spent only by the confirmed
  action, audit verification by auditor and supervisor. Settings `SNIGF_E2E_MEMORY`,
  `SNIGF_E2E_CPUS`, `SNIGF_E2E_PIDS`, `SNIGF_E2E_SHM`.
- **Permission matrix review** against the Baseline: 17 differences with security and
  business implications, three decisions requested.
- pgTAP test `10_approval_workflow` (30 checks).

#### Changed

- `POST /api/v1/admin/users/{id}/roles` answers **202** with a `requestId` (was 201 with an
  assignment): the role exists only once approved.
- `admin.role.assign` now means "request a role assignment".
- A database refusal the service did not foresee is answered 409 `REFUSED_BY_DATABASE` and
  logged as a warning.
- Tests seed roles as the superuser, the only path without a request
  (`IdentityIntegrationTest.insertStaff`, pgTAP `02`, `09`).
- The audit panel stays open for the visit once shown, so the result of a grant's last use
  remains visible.

#### Security

- Closes the gaps found in the 4d review: a single administrator could assign any role, and
  the application's database account could bypass every approval. Remaining: a compromised
  application can name another real administrator as approver (signed approvals, planned).

### Security foundation, step 4d: fine-grained permissions, administrator-defined roles, special grants

Decision: [ADR-025](docs/adr/025-fine-grained-permissions-and-special-grants.md).
Guide: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md).
Matrix: [`docs/permission-matrix.md`](docs/permission-matrix.md).

#### Added

- **Permission catalogue** (`Permission`, 33 permissions): one per function, each with a
  family (administration, audit, business), whether it may be delegated, the scopes it
  allows and descriptions in English and French. Seeded by V7 into `permission`;
  `PermissionCatalogueCheck` refuses to start when the code and the database differ.
- **Roles in the database:** the nine baseline roles (`BaselineRole`) are seeded as
  immutable system roles; administrators create, change and retire custom roles from the
  catalogue (`/api/v1/admin/roles`, `/api/v1/admin/role-requests`, `/api/v1/admin/permissions`).
- **Special grants** (`/api/v1/admin/grants`): one permission to one staff member,
  everywhere or at one office, with an optional end date and/or maximum number of uses.
  Each use is taken atomically before the action, given back if it fails, and audited.
  The session view lists the caller's `specialGrants`.
- **Four eyes:** every role change and special grant is a request that a second
  administrator approves; the requester, the beneficiary and holders of the role changed
  cannot approve. Requests expire after `SNIGF_ADMIN_REQUEST_TTL` (new setting, 7 days).
- **Separation of duties across families:** no role and no person holds administration
  and audit permissions together; administration permissions are never delegable.
- **Audit actions:** `ROLE_DEFINITION_REQUESTED`, `ROLE_DEFINITION_APPROVED`,
  `ROLE_DEFINITION_REJECTED`, `PERMISSION_GRANT_REQUESTED`, `PERMISSION_GRANT_APPROVED`,
  `PERMISSION_GRANT_REJECTED`, `PERMISSION_GRANT_REVOKED`, `PERMISSION_GRANT_USED`.
- **Migration V7** with its constraints and triggers; pgTAP test `09_authorization`.
- **Tests:** `RoleSeedIT`, `RoleAdministrationIT`, `SpecialGrantIT` (including concurrent
  uses), `PermissionCatalogueCheckTest`; `EndpointAnnotationArchTest` now fails on any
  permission check that is not `@access.can`/`canAt` or names an unknown permission.
- **Screens:** roles and permission catalogue, role requests and approvals, special grants
  (request, approve, reject, revoke); the user's own special grants on the session page.

#### Changed

- Every endpoint is now protected by a fine-grained permission through
  `@access.can(...)` / `@access.canAt(...)`: `user.manage` became `admin.account.list` and
  `admin.mfa.reset`; `role.assign` became `admin.role.assign` and `admin.role.revoke`;
  `audit.read` and `audit.verify` became `audit.event.search`, `audit.chain.verify`,
  `audit.checkpoint.create`, `audit.checkpoint.list`, `audit.checkpoint.download` and
  `audit.key.read`.
- `role_assignment.role` is a foreign key to `role_definition` instead of a fixed list;
  a retired role cannot be assigned. Assigning a role that would mix administration and
  audit is refused (`SOD_CONFLICT`).
- The SUPERVISOR baseline role keeps office-scoped audit search only (no chain
  verification); SYSTEM_ADMIN holds the eleven administration permissions.
- `Role` and `PermissionGrants` were replaced by `BaselineRole`, `RoleDefinition`,
  `EffectivePermissions` and `AuthorizationService`.

### Security foundation, step 4c: permissions, role administration, second-factor reset, auditor API

Guide: [`docs/guides/roles-and-administration.md`](docs/guides/roles-and-administration.md).
Matrix: [`docs/permission-matrix.md`](docs/permission-matrix.md).

#### Added

- **Permission model** (Slice 1 design §6):
  - the `Permission` enum (20 permissions) and the Baseline's role matrix in code
    (`Role`), with scopes `any`, `office`, `assigned`, `own` and `linked`;
  - grants become authorities, `audit.read` held everywhere or `parcel.create@DEMO1` at
    one office, and only in a FULL session;
  - `@access.allows(authentication, permission, officeCode)` for office-scoped checks;
  - the principal carries its role grants with offices, and the session view lists
    `grants` and `permissions`.
- **`docs/permission-matrix.md`:** `PermissionMatrixTest` fails when it differs from the
  code, and also checks that no role both administers and audits.
- **Role administration** (`/api/v1/admin/users`):
  - an account directory, with search that treats `%` as text;
  - grant and revoke a role at an office, always with a written reason;
  - back-office roles go to staff accounts only;
  - separation of duties: no self grant or revoke (refused by the service and audited as
    DENIED, refused again by the database);
  - changes take effect at the next request.
- **Administrator-assisted second-factor reset:**
  - it needs a reason and the name of whoever confirmed the identity out of band, and is
    refused on one's own account;
  - it removes every key, invalidates the recovery codes, ends every session (Spring
    Session principal index) and forces re-enrolment;
  - it is audited as `MFA_RESET`.
- **Auditor's API** (`/api/v1/audit`, AUDITOR role):
  - search events by entity, actor, action and time, with paging;
  - verify a range or the whole chain;
  - sign a checkpoint (409 `AUDIT_CHAIN_BROKEN` when the chain does not verify);
  - list checkpoints, download the files byte for byte, read the public keys;
  - searches and downloads are audited as `AUDIT_EVENTS_READ`.
- **Screens:** administration (search, grant, revoke, reset) and audit (search, verify,
  checkpoints, downloads), shown by permission, in French and English.
- **Tests:**
  - `PermissionMatrixIT`: every staff role against every protected endpoint, and
    applicants against none;
  - `AdminFlowIT` (7) and `AuditApiIT` (5);
  - `PermissionMatrixTest` (3).
- **Documents:**
  - ADR-024 (proposed): no TOTP fallback. Recovery uses the second key, the recovery
    codes, or a supervised administrator reset.
  - The roles and administration guide.

#### Changed

- Integration tests share one application context (`PostgisIntegrationTest`).
- `AuthenticatedUser` carries `grants` (role and office) instead of role names;
  `roles()` remains.
- ADR-016's TOTP fallback is replaced by ADR-024 (proposed).

#### Fixed (found by the new tests before release)

- **Integration tests in different Spring contexts disagreed on checkpoint signatures.**
  Each context generated its own in-memory test keys, so they could not verify each
  other's checkpoints (a test-only artefact, since production has one OpenBao key).

### Security foundation, step 4b: WebAuthn second factor, recovery codes, sign-in screens (ADR-016, ADR-023)

Guide: [`docs/guides/authentication-and-sessions.md`](docs/guides/authentication-and-sessions.md).

#### Added

- **WebAuthn second factor for staff,** on Spring Security 7.1's relying-party operations
  (`spring-security-webauthn`, verification by webauthn4j).
  - **Per ceremony:** user verification required, non-discoverable credentials,
    attestation `none` by default, exact origins on the relying-party id.
  - **At login:** only the account's own keys are offered, and the credential's owner
    must be the account proving it.
  - **Ceremony state:** kept on the server (`CeremonyStore`), single use, bound to
    account, kind and timeout; the session holds only a random id (ADR-022).
- **Enrolment:**
  - a staff password session registers `SNIGF_WEBAUTHN_MIN_AUTHENTICATORS` (2) keys;
  - enrolment then completes with ten recovery codes shown once, and the session
    becomes FULL with a new id;
  - once enrolled, keys can be added only from a FULL session.
- **Recovery codes:**
  - 16 Crockford base32 characters (80 bits), single use, forgiving input;
  - stored as domain-separated HMACs with the OpenBao pepper key, the key version
    recorded;
  - after use, the interface asks for a replacement key.
- **Failures:** wrong assertions and recovery codes count toward the lockout, are audited
  (`LOGIN_FAILED` with reason, `ACCOUNT_LOCKED`), and end the session when the account
  locks. Successes are audited as `MFA_ENROLLED`, `MFA_VERIFIED` and
  `MFA_RECOVERY_CODE_USED`.
- **Endpoints** `/api/v1/auth/mfa/webauthn/registration[/options]`,
  `/webauthn/assertion[/options]` and `/recovery`. Error codes: 401
  `SECOND_FACTOR_INVALID`, 403 `MFA_NOT_ALLOWED`, 422 `WEBAUTHN_REJECTED`.
  `GET /auth/session` now returns `nextStep`.
- **Migration `V6__webauthn.sql`:**
  - `user_entities` (one per account, a foreign key through a generated column) and
    `user_credentials` (public keys, labelled);
  - the trigger `app_user_mfa_enrolment_guard`, under which an account is enrolled only
    with a credential or a TOTP secret.
- **Sign-in screens** (React, French and English, no new dependency):
  - sign in, registration, key enrolment with progress, second factor, recovery code;
  - the recovery codes shown once, with an acknowledgement;
  - the session view, adding a key, and sign-out;
  - WebAuthn JSON conversion in `webauthn.ts`, and the CSRF token handled by `api.ts`.
- **Dev server:** the Vite proxy now passes the browser's address like Caddy.
- **`infra/scripts/dev-make-staff.sh`** (development profile only, audited) turns a
  registered account into staff at a demo office until role administration exists.
- **Settings:** 7 new settings (`SNIGF_WEBAUTHN_*`, `SNIGF_MFA_RECOVERY_CODES`), checked at
  startup:
  - origins are scheme://host[:port] on the relying-party id;
  - https outside tests;
  - at least 2 authenticators outside tests.
- **Tests:**
  - `MfaFlowIT`: 10 end-to-end flows with webauthn4j authenticator emulators (genuine
    attestations and signatures), covering phishing origin, replay, another account's
    key, recovery, lockout and ceremony binding;
  - `CeremonyStoreTest`, `RecoveryCodeTest`, settings and hasher tests;
  - pgTAP test `08_webauthn`.
- **Documents:** ADR-023.

#### Changed

- The V1 check `ck_mfa_enrolled_has_secret` (TOTP-only) is replaced by the V6 trigger.
- The identity integration tests share `IdentityIntegrationTest` (test browser with
  cookies and CSRF).
- ADR-016 is marked implemented by ADR-023. The TOTP fallback is deferred to step 4c;
  recovery codes are the fallback for now.

#### Fixed (found by the new tests before release)

- Starting a registration ran in a read-only transaction, but it creates the WebAuthn
  user entity.

### Security foundation, step 4a: password login, sessions, CSRF, throttling (ADR-004, ADR-022)

Guide: [`docs/guides/authentication-and-sessions.md`](docs/guides/authentication-and-sessions.md).

#### Added

- **Identity module** (`org.snigf.mvp.identity`) in DDD layers.
  - **Published API:** `AuthenticatedUser`, the principal of every request (implements
    `AuditedPrincipal`), plus `AccountType` and `AuthLevel`.
  - **Domain:** e-mail address, password policy P7, lockout policy P6, account, and the
    repository and hasher ports.
  - **Application:** registration and login use cases, every attempt audited.
- **Endpoints** `/api/v1/auth/csrf`, `/register`, `/login`, `/session` and `/logout`.
  - **No account discovery:** every login failure answers 401 `AUTH_FAILED` after the
    same hashing work; registration answers 201 whether or not the address existed.
  - **Error codes:** 422 `PASSWORD_WEAK` or `VALIDATION_FAILED`, 429 `RATE_LIMITED`,
    503 `BUSY`.
- **Password hashing:**
  - HMAC with the OpenBao `password-pepper` key, then Argon2id at the configured cost
    (OWASP minimum enforced outside tests);
  - the pepper version is recorded, and hashes are upgraded at login;
  - concurrent hashing is bounded.
- **Common-password list:** 29,982 passwords of 12+ characters from SecLists, pinned by
  SHA-256, with a provenance notice.
- **Sessions in PostgreSQL** (Spring Session JDBC, migration `V5__sessions.sql`).
  - **What is stored:** only the user id, level and login time; the principal is rebuilt
    from the database on every request, so disabled accounts and revoked roles take
    effect at once.
  - **Deserialization:** limited to plain values (filter plus size cap); a tampered
    session is ended.
  - **Lifetime:** new session id at login; idle 30 min and absolute 8 h limits.
  - **Cookie:** `__Host-snigf-session`, Secure, HttpOnly, SameSite Strict.
- **CSRF** on every state-changing call, including login: a `__Host-snigf-xsrf` cookie
  and the `X-XSRF-TOKEN` header.
- **Lockout and throttling:**
  - 5 wrong passwords lock an account for 15 min (atomic counter);
  - 20 logins per minute and 10 registrations per hour per client address (Bucket4j);
  - the client address is taken from the proxy only.
- **Audited denials:** refusals of authenticated users are recorded as `ACCESS_DENIED`.
  The audit trail now receives `USER_REGISTERED`, `LOGIN_SUCCEEDED`, `LOGIN_FAILED`,
  `ACCOUNT_LOCKED` and `LOGOUT`.
- **Staff** stop at authentication level `PASSWORD` until the second factor (step 4b).
- **Code changes:**
  - `@PublicEndpoint`, and `EndpointAnnotationArchTest`, which fails the build for an
    endpoint without an access rule;
  - `KeyService.mac(key, message, version)` for recomputing a MAC with a recorded key
    version;
  - a shared `Clock` bean.
- **Settings:** 19 new settings (`SNIGF_PASSWORD_*`, `SNIGF_LOGIN_*`, `SNIGF_RATE_*`,
  `SNIGF_SESSION_*`, `SNIGF_CSRF_*`).
- **Probe:** 3 new checks (CSRF cookie attributes, login CSRF, uniform 401). pgTAP test
  `07_sessions`.
- **Tests:** 32 new backend tests, of which 13 are end-to-end flows in `AuthFlowIT`.
- **Documents:** ADR-022 and the authentication guide.

#### Changed

- Anonymous calls to protected endpoints answer 401 `AUTH_REQUIRED` instead of 403. The
  smoke test, the probe and CI were updated.
- The security filter chain moved from `shared.security.SecurityConfig` to the identity
  module (`identity.infrastructure.security.SecurityConfiguration`).
- ADR-004 is marked implemented and refined by ADR-022 (`SameSite` Strict by default,
  `__Host-` cookies).

#### Fixed (found by the new tests before release)

- **Session cookie unprotected.** Spring Boot 4 no longer applies
  `server.servlet.session.cookie.*` to Spring Session, so the cookie would have been a
  plain `SESSION` without Secure, HttpOnly or SameSite. It is now defined in code.
- **Size limit bypass.** The deserialization filter's size limit does not apply to plain
  strings, so values above 4 KB are now refused explicitly.
- **Tampered sessions.** A refused session value now ends the session (401) instead of
  failing every request.

### Technical manual

#### Added

- `docs/technical/`, the entry point for engineers. It has nine chapters:
  1. system overview;
  2. backend architecture (modules, DDD layers, startup and request sequences, health);
  3. security model;
  4. data model;
  5. configuration reference (every setting, variable, property and secret);
  6. how to rebuild the architecture in a new project;
  7. testing;
  8. operations and troubleshooting (error catalogue, audit incident procedure,
     diagnostic commands);
  9. documentation rules.
- `DocumentationSyncTest` fails the build when the manual is missing any of the
  following, and names each missing item:
  - a setting, secret or migration;
  - a module, Compose service or health contributor;
  - an audit action;
  - an ADR or guide.

#### Fixed

- CI's stack smoke test sourced `.env` as shell code, which would fail on the audit
  schedule values. It now reads the two values it needs as data.

### Security foundation, step 3: audit trail with signed checkpoints (ADR-007, ADR-021)

Guide: [`docs/guides/audit-trail.md`](docs/guides/audit-trail.md).

#### Added

- Audit module (`org.snigf.mvp.audit`) packaged in DDD layers:
  - **published API:** `AuditRecorder`, `AuditTrail`, `AuditEvent`, `AuditAction`,
    `Checkpoint`, `ChainVerification`, `SystemActor`, `AuditedPrincipal`;
  - **`domain`:** chain, verifier, evidence and repository interfaces, in plain Java;
  - **`application`:** use cases and ports;
  - **`infrastructure`:** JDBC, file archive, security context, scheduling, health.
- Recording:
  - **Transactions:** events are written inside the caller's READ COMMITTED transaction.
    Refused without one, in a read-only one, or under a stricter isolation. Denials and
    failures are recorded in their own transaction, so they survive the rollback.
  - **Who and where:** the actor, roles, correlation id and browser address come from the
    security context and the request, never from the caller. An unattributable
    authenticated principal is refused.
  - **Details:** limited to plain values and to `SNIGF_AUDIT_MAX_DETAILS_BYTES`.
- Java chain verifier, independent of the database's functions. It reports the first
  problem with its position and kind:
  - `GAP`, `PREV_HASH`, `CONTENT_HASH`: an event deleted, unlinked or edited;
  - `CHECKPOINT_MISMATCH`: the chain was rewritten after a checkpoint;
  - `TRUNCATED`: the newest events were deleted;
  - `CHECKPOINT_SIGNATURE`: a checkpoint is forged, or its copies disagree;
  - `CHECKPOINT_UNREADABLE`: a checkpoint file cannot be read.
- Checkpoints:
  - Ed25519, signed by OpenBao with the `audit-checkpoint` key.
  - Stored in `audit_checkpoint` and as read-only files on the new volume
    `audit-checkpoints`, mounted into the application only, with `checkpoint-keys.json`
    holding the public keys.
  - The events since the last trusted checkpoint are verified first. A tampered chain is
    never signed; the refusal is audited and reported.
- Schedules:
  - Daily checkpoint and daily full verification, plus a verification at startup. All
    settings are in `.env`.
  - Each run is audited (`AUDIT_VERIFIED`, `AUDIT_CHECKPOINT_CREATED`) and reported by the
    new health contributor `auditChain`. It is outside readiness, so monitoring alerts
    without taking the service down.
- `infra/scripts/verify-checkpoint.sh`: the auditor verifies checkpoint files offline with
  bash and OpenSSL only.
- Migration `V4__function_search_path.sql` (see Fixed).
- Browser address for audit records:
  - the proxy overwrites `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` with the TCP peer;
  - the app believes the header only on the proxy's admitted certificate;
  - non-IP values are discarded.
- `UuidV7` generator (ADR-008).
- 8 new settings: `SNIGF_AUDIT_*` (checkpoint directory, two schedules, zone, startup
  verification, details limit, fetch size) and `SNIGF_PROXY_CLIENT_ADDRESS_HEADER`.
- ADR-021 (domain-driven layering inside modules) and `DddLayeringTest`, which enforces it
  on every build.
- Tests:
  - **Unit (37):** 17 for the chain verifier, plus domain rules, layering, client
    address, UUIDv7, audit settings and the new guard rule.
  - **Integration (20, against PostGIS):**
    - `AuditRecorderIT` (11): transactions, actors, immutability;
    - `CanonicalHashParityIT` (2): Java and SQL produce identical bytes;
    - `AuditChainIT` (7): signing, offline verification, and every superuser tampering
      scenario.
  - **pgTAP:** test `06_function_hardening`.

#### Changed

- `EnvironmentGuard` also requires the proxy's client-address header outside tests; the
  audit module refuses disabled schedules outside tests.
- ADR-007 records the implementation; `docs/audit.md` documents the checkpoint format, the
  auditor's routine and two residual-risk corrections.
- The test support (`PostgisDatabase`) offers a superuser connection for tampering tests.

#### Fixed

- **Database functions used the caller's `search_path`** (latent since V1). Audit
  verification failed for a session without `snigf` on its path, and a caller could
  shadow a table, even with a temporary one. V4 pins
  `pg_catalog, snigf, public, pg_temp` on every function, and pgTAP checks it.
- **Shell scripts sourced `.env` as code.** A value with spaces broke `dev-setup.sh`, and
  any line could have run a command. `dev-pki.sh` now reads `SNIGF_*` settings as data.

### Security foundation, step 2: key custody in OpenBao (ADR-018)

#### Added

- OpenBao 2.7.1 in the stack (`openbao` service), pinned by digest, on a new internal
  network `key-net` that only the application and the provisioning job join. API on
  47820 and Raft cluster on 47821, mutual TLS 1.3 only; audit log to the container log
  and to the `bao-audit` volume (`infra/openbao/`).
- `bao-init` provisioning job: initialises OpenBao (Shamir, 5 shares, threshold 3 by
  default), unseals it after every restart, and when its inputs change provisions the
  transit keys, the application policy and the certificate login, using a temporary root
  token generated from a quorum of unseal shares and revoked before it exits. No root or
  administrator token is stored anywhere.
- Six transit keys, one per purpose, none exportable, deletable or backed up in
  plaintext: audit checkpoint and record signing (Ed25519), data-key wrapping
  (AES-256-GCM, derived per context), payment callback, password pepper and blind index
  (HMAC-SHA-256). Automatic rotation periods are settings.
- Application policy limited to sign, read public keys, generate and unwrap data keys,
  HMAC-SHA-256 and token self-lookup (`infra/openbao/app-policy.hcl.template`).
- Backend module `keys` (`org.snigf.mvp.keys`): one `KeyService` interface for signing,
  local signature verification, envelope encryption bound to a context, and HMAC; value
  types `Signature`, `Mac`, `SealedData`, `SigningPublicKey`. OpenBao provider with
  certificate login and automatic token renewal; in-memory provider for automated tests only.
- Startup self-test of key custody: every key's type and custody settings are checked
  and every operation is round-tripped (and must reject tampering) before the application
  accepts requests.
- Health contributor `keys`; readiness now includes the database and key custody.
- Development certificates for OpenBao (`bao_tls`), the application's login identity
  (`app_bao_client`), the provisioning job (`bao_admin_client`) and the health check
  (`bao_health_client`).
- 28 new settings in `.env.example` (`SNIGF_BAO_*`, `SNIGF_KEY_*`, OpenBao resource limits).
- Security probe: 8 new checks on OpenBao (TLS 1.2 refused, no certificate refused,
  foreign CA refused, another service's certificate refused with its reason, keys not
  exportable or deletable, 14 operations outside the policy refused, positive controls).
  Segmentation probe: the proxy's network cannot reach OpenBao.
- Unit tests for the key module (14) and the new guard rules (4).
- `CHANGELOG.md` and the how-to guides in `docs/guides/`.

#### Changed

- `EnvironmentGuard` also refuses: in-memory keys outside tests, an OpenBao URL without
  https, an OpenBao SSL bundle not restricted to TLS 1.3, one key name for two purposes,
  and (outside tests) a missing database password.
- `dev-setup.sh` appends settings added to `.env.example` to an existing `.env`, without
  changing existing values.
- `dev-pki.sh` restricts private-key files to the current user on Windows (ACL).
- ADR-018 records the implementation: certificate login instead of AppRole, local
  verification, Shamir custody, no `mlock` in OpenBao 2.x.
- `.gitattributes` keeps LF for `*.hcl`, `*.template` and `*.yaml`.

#### Fixed

- The application container could not open a database connection: Docker Desktop mounts
  secrets with mode 0777 and the PostgreSQL driver refuses such a private key. Hidden
  until readiness included the database. The entry point now copies the key to a private
  tmpfs with mode 0600 (`backend/docker-entrypoint.sh`).
- Running the backend from the IDE with `SNIGF_SECRETS_DIR=../infra/secrets` silently
  loaded no secrets (Spring's config tree reads nothing from a path containing `..`).
  The documented command uses an absolute path, and the guard now stops startup with a
  clear message.
- ADR-017 named a property that does not exist (`allowed-client-subjects`).

### Security foundation, step 1: transport security and hardening (ADR-016 to ADR-020)

#### Added

- ADR-016 (WebAuthn for staff, TOTP fallback; supersedes ADR-005), ADR-017 (TLS on every
  hop), ADR-018 (OpenBao key custody), ADR-019 (officer-signed records), ADR-020
  (container and network hardening).
- Development PKI (`infra/scripts/dev-pki.sh`): root and intermediate CA (P-384), service
  certificates (P-256), renewal before expiry, `--rotate`.
- TLS 1.3 on every hop: browser → proxy (HSTS, no plain-HTTP listener), proxy → app
  (mutual TLS, client allow-list in `ClientCertificateFilter`), app and migrations →
  database (`verify-full`, client certificate whose CN equals the role, SCRAM).
- `pg_hba.conf`: superuser only through the local socket; every network login over TLS
  with a client certificate.
- Networks `edge`, `app-net`, `data-net` (internal); containers read-only, all
  capabilities dropped, `no-new-privileges`, non-root, resource limits from `.env`.
- Security probe (16 checks) and segmentation probe (3 checks), each refusal checked for
  its reason; both run in CI.
- HTTPS Vite development server that calls the backend over mutual TLS.

#### Changed

- Every tunable value is a setting in `.env` (ADR-014 extended); Compose refuses to start
  when one is missing.
- `EnvironmentGuard` refuses any environment except test without complete transport
  security.

#### Fixed

- The backend accepted TLS 1.2: with an SSL bundle, `server.ssl.enabled-protocols` is
  ignored. The protocol list moved to the bundle options; the guard checks that setting.
- Password files generated on Windows ended with `\r`; `dev-setup.sh` normalises them.
- Caddy could not start with all capabilities dropped; the image removes the binary's
  file capability (ports above 1024 need none).

## [0.1.0-SNAPSHOT] - 2026-10-20 (commit `d77998a`, "Project Initialisation")

### Added

- Monorepo: `backend/` (Spring Boot 4.1, Java 25, Spring Modulith), `frontend/` (React 19,
  TypeScript, Vite), `infra/`, `docs/`.
- Database layer: roles `snigf_migrator`, `snigf_app`, `snigf_auditor`; migrations V1–V3
  (identity, parcels, geometry normalisation, hash-chained audit); pgTAP suite and a
  concurrent audit-chain test.
- Compose stack with images pinned by digest, non-default ports (47xxx), settings in
  `.env`, secrets as files.
- Environment profiles dev, test, staging, prod and `EnvironmentGuard` (E-23).
- CI: backend, frontend and whole-stack jobs with a secret scan.
- ADR-001 to ADR-015.
