# 8. Operations and troubleshooting

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Daily operations

| Task | Command |
| --- | --- |
| First setup | `bash infra/scripts/dev-setup.sh` |
| Start or update | `docker compose up -d --build --wait` |
| Status | `docker compose ps -a` |
| Logs of one service | `docker compose logs --tail 200 <service>` |
| Stop | `docker compose down` (data kept) |
| Reset everything | `docker compose down -v` (deletes data **and** keys together) |
| New settings after `git pull` | `bash infra/scripts/dev-setup.sh` (appends, never changes values) |
| Renew certificates | `bash infra/scripts/dev-pki.sh` (expiring ones), `--rotate` (all), then `docker compose up -d --force-recreate` |
| Unseal OpenBao after a restart | `docker compose up -d` (re-runs `bao-init`) |
| Database shell | `docker compose exec -u postgres db psql -d snigf` |
| Backend from the IDE | see [README "Develop"](../../README.md#develop) |
| Real satellite imagery for the whole country (Sentinel-2 mosaic and aerial details) | `bash infra/scripts/maps-build-satellite.sh` (resumable; [step-by-step guide](../guides/satellite-imagery.md)) |
| Demonstration accounts, one per role, with passwords (development only) | `bash infra/scripts/dev-seed-demo.sh`, then read `infra/secrets/demo_accounts` ([guide](../guides/demo-accounts.md)) |
| Make a registered account staff (development only; also bootstraps the first `SYSTEM_ADMIN` and `AUDITOR`) | `bash infra/scripts/dev-make-staff.sh <email> [ROLE]` |
| Reset a lost second factor | [account recovery procedure](#account-recovery-procedure): an administrator who is not the person requests it; the person's supervisor checks the identity out of band and confirms in the application |

## First five minutes when something is wrong

1. **Which container is unhappy?** Run `docker compose ps -a`. Read the column for each
   kind of service:

   | Service | Expected | Means trouble |
   | --- | --- | --- |
   | `migrate`, `bao-init` | `Exited (0)` | any other exit code |
   | long-running services | `Up ... (healthy)` | `unhealthy`, `Restarting`, `Exited` |

2. **Read its log from the first error, not the last line:**
   `docker compose logs <service> | grep -nE "ERROR|FATAL|refusing|Exception" | head`.
   - **Dependencies stop at the first failure.** If `migrate` or `bao-init` failed,
     `app` never starts. Fix the earliest failing service first.
   - **Log format:** plain text in `dev`; one JSON object per line (ECS) in `staging`
     and `prod`. Filter those by `correlationId` or `log.level`.
3. **App running but unhealthy?** Ask it directly. The health port is loopback-only:
   `docker compose exec app bash -c 'exec 3<>/dev/tcp/127.0.0.1/$SNIGF_MANAGEMENT_PORT; printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3; cat <&3'`.
   Details are hidden by design, so read the app log for the contributor that failed:
   `db`, `keys` or `auditChain`.
4. **Network or certificate problem?** Run the security probe. It names the failing hop
   and the reason.
5. **Find the request.** Every API response carries `X-Correlation-Id`. Search the logs
   and `audit_event.correlation_id` for that id.

## Error catalogue

Each entry: symptom (what you see), cause, fix.

### Compose and setup

| Symptom | Cause | Fix |
| --- | --- | --- |
| `required variable SNIGF_X is missing a value: set in .env` | `.env` predates the setting, or it was deleted | `bash infra/scripts/dev-setup.sh` |
| `.env: line N: 0: command not found` | an old script sourced `.env` as shell code | use `load_env` (`dev-pki.sh`); never `. .env` |
| `bind: address already in use` | another process, or a second Compose project, uses the port | change `SNIGF_*_PORT` in `.env`, or stop the other project |
| Certificate subjects like `C:/Program Files/Git/O=FLYT` | Git Bash rewrote an argument that looked like a path | `MSYS2_ARG_CONV_EXCL` is set in `dev-pki.sh`; run the script with bash, not by hand |
| Database refuses a correct password | CRLF from OpenSSL on Windows in the password file | re-run `dev-setup.sh` (it strips CR/LF) |

### Database (`db`, `migrate`)

| Symptom | Cause | Fix |
| --- | --- | --- |
| `migrate` exits 1: `Validate failed: Migration checksum mismatch` | an applied migration was edited | restore the original file; put the change in a new migration |
| `migrate` exits 1: `connection refused` / `SSL error` | db not ready, or the certificate expired | `docker compose logs db`; `bash infra/scripts/dev-pki.sh` then recreate |
| `FATAL: certificate authentication failed for user` | client certificate CN differs from the role | issue the right certificate (CN = role) |
| `FATAL: no pg_hba.conf entry ... no encryption` | a client tried plain TCP | use TLS with `sslmode=verify-full` |
| `Peer authentication failed for user "postgres"` | superuser shell as the wrong OS user | `docker compose exec -u postgres db psql -d snigf` |
| `type "audit_event" does not exist` from a function | database at V3: functions used the caller's `search_path` | apply V4 (`docker compose up -d migrate`) |
| `table audit_event is append-only: UPDATE is not permitted` | something tried to change history | expected; find who (logs, `audit_event`), never disable the trigger |

### OpenBao (`openbao`, `bao-init`)

| Symptom | Cause | Fix |
| --- | --- | --- |
| `openbao` unhealthy, `bao status` says `Sealed: true` | it restarted; it always starts sealed | `docker compose up -d` (re-runs `bao-init`, which unseals) |
| `bao-init: ERROR: OpenBao unreachable at ... after Ns` | OpenBao not started, TLS failure, or wrong port | `docker compose logs openbao`; check `bao_tls_*` and `SNIGF_BAO_PORT` |
| `bao-init: ERROR: fewer than 3 unseal shares in /bao-keys` | the `bao-keys` volume was deleted or changed | development: `docker compose down -v` (everything encrypted is lost); production: custodians unseal |
| `bao-init: ERROR: key X exists with another type` | a key name in `.env` points to an existing key of another type | use a new name; never repurpose a key |
| `unrendered placeholder` | a new `@PLACEHOLDER@` in a template without a value | add the setting and pass it to the service |
| Server log: `unknown or unsupported field` | configuration option in the wrong block or removed in OpenBao 2.x | check the key custody guide's pitfalls |

### Application (`app`)

Startup refusals are deliberate. Read the list of violations: each line names the
setting to fix.

| Message | Cause | Fix |
| --- | --- | --- |
| `Unsafe configuration, refusing to start:` followed by one line per violation | `EnvironmentGuard`; violations include `exactly one of the profiles`, `server TLS must be enabled`, `server.ssl.client-auth must be 'need'`, `must enable exactly TLSv1.3`, `client-address-header`, `management.server.address must be loopback`, `sslmode must be verify-full`, `keys must be held by OpenBao`, `snigf.keys.openbao.url must use https`, `each key purpose needs its own key`, `the database password is missing`, `demo-mode must be false in prod` | correct the named setting; never weaken the guard |
| `Unsafe audit configuration, refusing to start:` | an audit schedule set to `-`, or a relative checkpoint directory outside dev | set `SNIGF_AUDIT_*_CRON`; absolute directory |
| `the database password is missing: it is read from the secrets directory` (IDE run) | `SNIGF_SECRETS_DIR` contains `..` or is wrong; the config tree silently reads nothing | pass an absolute path (`pwd -W` in Git Bash) |
| `private key file has group or world access` | key file permissions (Docker Desktop 0777) or Windows ACL | container: the entry point copies it to 0600 (check `/run/snigf-keys` tmpfs); host: re-run `dev-pki.sh` (ACL) |
| `key custody self-test failed: ...` | a key is missing, exportable, deletable or of the wrong type, or an operation does not round-trip | `docker compose logs bao-init`; re-provision with `docker compose up -d`; never relax the self-test |
| `KeyServiceException` with a path and HTTP 403 | the token expired and re-login failed, or the policy lacks a path | check `SNIGF_BAO_APP_CLIENT_CN` matches the certificate; check `app-policy.hcl.template` |
| `Failed to bind properties under 'snigf.audit'` | invalid value (zone, cron, range) | fix the value in `.env` |
| `Unsafe identity configuration, refusing to start:` | Argon2 below 19456 KiB or 2 passes, minimum length under 12, a cookie name without `__Host-`, `SameSite` not Strict or Lax, idle limit above the absolute limit, WebAuthn origins, or `SNIGF_ADMIN_REQUEST_TTL must be between 1h and 30d` | correct the named `SNIGF_PASSWORD_*`, `SNIGF_SESSION_*`, `SNIGF_CSRF_*`, `SNIGF_WEBAUTHN_*` or `SNIGF_ADMIN_REQUEST_TTL` setting |
| `Permission catalogue differs from the database, refusing to start:` followed by `<code> is missing from the permission table`, `<code> differs: database ..., code ...` or `<code> is in the database but not in the code` | `Permission.java` changed without a migration, or a migration without the code (ADR-025) | add the migration that inserts or updates the `permission` row exactly as the code says; never edit rows by hand |
| Startup `Unsupported class file major version` / enforcer `[25,)` | wrong JDK | use JDK 25 (`JAVA_HOME`) |

### Requests

| Symptom | Cause | Fix |
| --- | --- | --- |
| Browser certificate warning | development root CA not trusted | `certutil -user -addstore Root infra\secrets\pki_root_ca_crt` (development only) |
| 502 from the proxy | app down, or the proxy cannot verify `app` (CA, name, expiry) | `docker compose logs web app`; renew certificates |
| 403 `{"code":"TRANSPORT_CLIENT_REJECTED"}` | the caller's certificate CN is not in the allow-list | expected for anything but the proxy; check `SNIGF_PROXY_CLIENT_CN` |
| 401 `AUTH_REQUIRED` | no session, or it expired (30 min idle, 8 h absolute), or the account was disabled | log in again; check `app_user.enabled` |
| 401 `AUTH_FAILED` at login | unknown address, wrong password, locked or disabled account (deliberately indistinguishable) | read the reason in the audit trail: `SELECT details->>'reason' FROM snigf.audit_event WHERE action = 'LOGIN_FAILED' ORDER BY chain_pos DESC LIMIT 5` |
| 403 `CSRF_INVALID` | missing or stale `X-XSRF-TOKEN` header; the token changes at login and logout | call `GET /api/v1/auth/csrf` again and send the `__Host-snigf-xsrf` cookie value in the header |
| 403 `ACCESS_DENIED` | the session is not at the required level (staff before the second factor) or lacks the role | complete the second factor; check roles in `GET /api/v1/auth/session` |
| 422 `PASSWORD_WEAK` with `violations` | P7 policy (`TOO_SHORT`, `COMMONLY_USED`, `CONTAINS_ACCOUNT_NAME`, ...) | choose another password |
| 429 `RATE_LIMITED` with `Retry-After` | more than `SNIGF_RATE_LOGIN_PER_MINUTE` logins (or registrations per hour) from one address | wait; behind a shared address, raise the limit deliberately |
| 503 `BUSY` | all `SNIGF_PASSWORD_MAX_CONCURRENT_HASHES` slots in use for `SNIGF_PASSWORD_HASH_QUEUE_TIMEOUT` | retry; if frequent, size the slots and Argon2 memory to the host |
| Log `Session ended: a stored attribute was refused by the deserialization filter` | a session row contained something other than a plain value: someone wrote to `spring_session_attributes` | treat as a security incident: who has write access to the database? |
| Account locked | 5 wrong passwords or second factors: `app_user.locked_until` | it unlocks by itself after 15 minutes; no manual unlock in slice 1 |
| 403 `SOD_SELF_ASSIGNMENT` / `SOD_SELF_ACTION` | an administrator acted on their own roles or second factor | another administrator must do it (the attempt is audited as DENIED) |
| 409 `ROLE_ALREADY_ACTIVE` / `ASSIGNMENT_ALREADY_REVOKED` | the grant exists, or the revocation already happened | refresh the directory |
| 422 `ACCOUNT_NOT_STAFF` | back-office roles go to staff accounts only | the account must be created as staff (onboarding) |
| 422 `REASON_REQUIRED` | missing reason (10+ characters for a reset request) | supply it; it is kept in the audit trail |
| 409 `AUDIT_CHAIN_BROKEN` when creating a checkpoint | the chain does not verify since the last checkpoint | audit incident procedure below |
| 409 `CHECKPOINT_UNAVAILABLE` | empty chain, or another checkpoint is being created | retry |
| Staff member sees no administration or audit panel | the session is not FULL, or no role or special grant gives `admin.account.list` / `audit.event.search` | check `permissions` and `specialGrants` in `GET /api/v1/auth/session` |
| 202 on `POST /admin/users/{id}/mfa-reset`, but nothing changes | resets are requests (ADR-028) | the person's supervisor confirms in Identity confirmations |
| 404 `REQUEST_NOT_FOUND` when a supervisor confirms a reset | the person holds no role at the supervisor's offices | a supervisor of the person's office confirms |
| 422 `IDENTITY_CHECK_REQUIRED` | the confirmation does not say how the identity was checked | write the means, for example "in person, ID card checked" |
| 409 `LAST_KEY` | removing the only remaining key | add another key first |
| 404 `KEY_NOT_FOUND` | the key is not one of the caller's (or was already removed) | refresh the key list |
| 403 `SOD_FOUR_EYES` | the requester (or, for a role assignment or a grant, the beneficiary) tried to approve | another administrator approves (the attempt is audited as DENIED) |
| 202 on `POST /admin/users/{id}/roles`, but the person has no new role | role assignments are requests (ADR-026) | another administrator approves it in Administration → pending role assignments (`/admin/assignment-requests`) |
| 409 `REFUSED_BY_DATABASE`, log `Administrative change refused by the database: ...` | the database refused what the service allowed: a concurrent decision, or an attempt the service did not foresee (approver without the permission, separation of duties) | read the logged message; if it is not a race, treat it as a security event |
| 403 `SOD_SELF_BENEFIT` | the approver holds the role being changed, or is the beneficiary of the grant | an administrator who does not hold the role, and is not the beneficiary, approves |
| 403 `SYSTEM_ROLE_IMMUTABLE` | a change to one of the nine baseline roles | baseline roles change only in code with a migration (see the permission matrix); define a custom role instead |
| 409 `SOD_CONFLICT` | the role, assignment or grant would give someone administration and audit permissions together | split the duties between two people |
| 409 `ROLE_IN_USE` | retiring a custom role that someone still holds | revoke the assignments first |
| 409 `REQUEST_EXPIRED` / `REQUEST_NOT_PENDING` / `REQUEST_ALREADY_OPEN` | older than `SNIGF_ADMIN_REQUEST_TTL`, already decided, or another request for the same role is waiting | reject it and request again; decide the open request first |
| 422 `ROLE_NOT_ASSIGNABLE` / `PERMISSION_NOT_DELEGABLE` / `SCOPE_NOT_ALLOWED` / `PERMISSION_UNKNOWN` | retired or unknown role, administration permission in a special grant, a scope the catalogue does not allow, or a code not in the catalogue | see `GET /api/v1/admin/permissions` |
| 422 `VALID_UNTIL_IN_PAST` / `MAX_USES_INVALID` | special grant limits: an end date in the past, uses outside 1..100000 | correct the limits |
| A special grant stopped working | its end date passed, its uses ran out, or it was revoked | `SELECT status, valid_until, max_uses, uses FROM snigf.permission_grant WHERE user_id = '...'`; every use is in the audit trail (`PERMISSION_GRANT_USED`) |
| 401 `SECOND_FACTOR_INVALID` | the assertion did not verify (another site, a key of another account, a cancelled or replayed ceremony) or the recovery code is wrong or used | retry with a registered key; the audit trail holds the reason; five failures lock the account |
| 403 `MFA_NOT_ALLOWED` | an applicant called a second-factor endpoint, an enrolled staff member tried to add a key before proving one, or the second factor was already proven | prove the existing key first, then add one |
| 422 `WEBAUTHN_REJECTED` | the registration did not verify, the label is invalid, or no registration was started in this session (options expire after `SNIGF_WEBAUTHN_TIMEOUT`) | start again from the options |
| Browser: "The relying party ID is not a registrable domain suffix" or `SecurityError` | the page's host differs from `SNIGF_WEBAUTHN_RP_ID` | open the site on the host named by the relying-party id |
| Browser: `NotAllowedError` | the user cancelled, the timeout passed, or the key does not match the allowed credentials | retry; use a key registered for this account |
| Browser: `InvalidStateError` at registration | this authenticator is already registered for the account | use another authenticator |
| Registration refused with origin mismatch | the page's origin is not in `SNIGF_WEBAUTHN_ORIGINS` (exact scheme, host and port) | add the origin, then restart the app |
| TLS handshake failure on 47080 | no client certificate, or TLS 1.2 | expected; only the proxy connects |
| 422 `GEOMETRY_*` with a `reason` | the geometry is malformed, not a (Multi)Polygon, invalid, too complex, outside the accepted area, or invalid once transformed; never silently repaired | read `reason` (PostGIS's explanation); check longitude/latitude order and the CRS; the refusal is audited (`PARCEL_CREATE_REJECTED`, `PARCEL_CORRECT_REJECTED`) |
| 422 `CRS_MISSING` / `LOCALITY_INVALID` / `EXTERNAL_REFERENCE_INVALID` / `QUERY_TOO_LONG` | input rules of the parcel API | correct the field |
| 422 `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_REUSED` | creating a parcel needs an `Idempotency-Key`; a key may be reused only with the identical request | send a new key per new parcel; resend the same request with the same key after a timeout |
| 409 `IDEMPOTENCY_IN_PROGRESS` | the same request is still being processed | retry shortly; the answer then carries `Idempotent-Replayed: true` |
| 409 `STALE_VERSION` | the parcel was corrected since it was read | reload it, then correct |
| 404 `PARCEL_NOT_FOUND` / `VERSION_NOT_FOUND` / `OFFICE_NOT_FOUND` | unknown reference, version or office (also answered when the reference is malformed) | check the reference and its check character |
| 409 `ITEMS_MISSING` on accepting a case | the automatic check still finds a required field or document missing (`items` lists them) | request the information; the case page shows the same list |
| 409 `OPEN_ALERT` on a decision | an overlap or duplicate alert on the application's parcel is open | a cadastral officer of the office resolves it (`#/alerts`); then decide |
| 409 `ALREADY_ASSIGNED` on a claim | someone claimed the case at that stage first | nothing to do: it is theirs; reassignment comes with E-22 |
| 403 `RECOMMENDER_CANNOT_DECIDE` | the person recommended on the case and also holds the decision role | another decision authority of the office claims and decides it |
| 403 `NOT_ASSIGNED` | the action needs the case assigned to the caller at that stage | claim it first, if it is still waiting |
| 422 `GROUNDS_REQUIRED` / `CITATIONS_REQUIRED` / `FINDING_REQUIRED` / `REASON_REQUIRED` | the text is under 10 or over 4000 characters, or no stored document version is cited | complete the form; cite at least one stored version |
| 422 `STATE_NOT_LISTED` on the workload queue | the state asked for is not one a case waits in (an ended state, or a draft) | ask for a working state, or none; ended applications are in the counts |
| A case shown overdue at once, or never | the stage targets (`SNIGF_REPORTING_TARGET_*`) are not what was meant: they are ISO-8601 durations (`P5D`, `PT12H`), and `SUBMITTED` has none | correct `.env`, restart the app; the report uses the new targets for every stay ([guide](../guides/tracking-and-workload.md)) |
| The workload report is empty for an office | nothing was submitted there yet, or the caller does not hold `report.workload` at that office (the database answers nothing rather than an error) | check the office and the person's roles |
| 404 on `#/reviews/<id>` | the case is assigned to someone else, or not at the caller's stage: assignees see only their own cases and those waiting to be claimed | expected; office readers see the review on `#/cases/<id>` |
| 409 `CATEGORY_NOT_REQUESTED` on an upload | the application waits for information, and this category was not asked for | expected: only the requested categories take files until the response |
| 422 `TYPE_NOT_ALLOWED` / `TYPE_MISMATCH` / `PDF_ACTIVE_CONTENT` / `PDF_ENCRYPTED` / `PDF_MALFORMED` / `IMAGE_TOO_LARGE` on an upload | the file's bytes are not an accepted PDF, JPEG or PNG, its name says otherwise, or it carries active content | expected; the audit event `DOCUMENT_REJECTED` names the code and what the bytes were ([documents guide](../guides/documents.md)) |
| 413 `FILE_TOO_LARGE`, or 400 "Failed to read request" on a large upload | over `SNIGF_DOCUMENT_MAX_BYTES`; above `SNIGF_PROXY_MAX_BODY_BYTES` the proxy cuts the body and the app sees an incomplete request | expected; raise both settings together if the limit must change |
| 500 `DOCUMENT_INTEGRITY_FAILED` | the stored file no longer matches its SHA-256 (altered, or restored from another backup than the database) | treat as an incident: `DOCUMENT_INTEGRITY_FAILED` in the audit trail; restore `document-data` and the database from the same backup |
| Map view disabled: "data not installed" / "data installed but invalid" / "tile file missing" | `NOT_INSTALLED`, `MANIFEST_INVALID` (app log: `Map background ... unavailable: manifest problems [...]`), `FILE_MISSING` | [map data guide](../guides/map-data.md); the technical view and parcels keep working |
| App refuses to start: `snigf.maps.external-providers` | a value other than `none` | external providers need their own decision (ADR-029) |
| 409 `PAYMENT_REQUIRED` on an acceptance | the payment gate is on and the application's DEMO fee is not paid | the applicant pays on their application (simulated); the case shows the fee as paid, then accept ([payments guide](../guides/payments.md)) |
| 503 `PROVIDER_UNAVAILABLE` when paying | the app could not reach `payment-sim` (container down, certificate or name wrong) | `docker compose ps payment-sim`, `docker compose logs payment-sim`; the request waits without a charge, and paying again starts the same one |
| A payment stays pending, then expires | the scenario was `NO_RESPONSE`, or no callback reached the app: look for `payment-sim: callback ... -> HTTP` in its log, and for `PAYMENT_CALLBACK_REJECTED` in the audit trail | a `401` names why (`SIGNATURE_INVALID`, `TIMESTAMP_OUT_OF_WINDOW`: check the clocks, `PROVIDER_NOT_RECOGNISED`: `SNIGF_PAYMENT_PROVIDER_CLIENT_CNS` and the simulator's certificate); an expired payment can be paid again |
| `payment-sim: OpenBao refused the certificate login` | `bao-init` has not provisioned the simulator's role (an old fingerprint), or its certificate name differs from `SNIGF_PAYMENT_SIM_CLIENT_CN` | `docker compose up bao-init` (it re-provisions when its inputs change), then restart `payment-sim` |
| App refuses to start: `snigf.payment.simulator.channel=IN_PROCESS is for the test profile only`, or `snigf.payment.provider` | the in-process channel outside tests, or a provider other than `DEMO_SIMULATOR` | a real provider needs its adapter, a migration and an ADR (ADR-037); never switch one on by setting |
| An exception on `#/payments` | a provider report matched no pending request of the same amount (`AMOUNT_MISMATCH`, `UNMATCHED_REFERENCE`, `AFTER_EXPIRY`, `ALREADY_SETTLED`) | expected for those DEMO scenarios; the finance officer resolves it (simulated refund or nothing to do), which never pays a request |

### Audit trail

| Symptom | Cause | Fix |
| --- | --- | --- |
| `auditChain` DOWN; log `AUDIT CHAIN VERIFICATION FAILED: first mismatch at position N (KIND)` | history changed, or evidence broken | **follow the incident procedure below** |
| `AUDIT CHECKPOINT REFUSED: chain broken at position N` | same, detected before signing | incident procedure |
| `IllegalTransactionStateException: ... read-only transaction` / `require READ COMMITTED` | a service recorded an event in a read-only or REPEATABLE READ transaction | record in a writable READ COMMITTED transaction |
| `No existing transaction found for transaction marked with propagation 'mandatory'` | `record()` called outside a transaction | call it inside the service's `@Transactional` method |
| `authenticated principal X does not implement AuditedPrincipal` | the identity module's principal type is missing the interface | implement `AuditedPrincipal` |
| `audit details exceed N bytes` / `unsupported type` | too much or non-plain data in `details` | record identifiers and short facts only |
| `another checkpoint is being created` | two checkpoint requests at once | retry later; the lock is per transaction |
| `checkpoint file checkpoint-N.json already exists; files are never replaced` | a file for that position exists (for example left by a checkpoint whose transaction then failed) | investigate; never delete or replace it: compare it with the database copy and the auditor's off-host copy |
| `cannot publish checkpoint file ... without replacing (the directory must support hard links)` | the checkpoint volume's file system has no hard links | use a local file system (ext4, xfs) for `SNIGF_AUDIT_CHECKPOINT_DIRECTORY`; never work around it with a replacing write |

## Account recovery procedure

ADR-024 (accepted 22 Oct 2026): no TOTP fallback. A staff member who cannot prove a
security key has three ways back, tried in this order. Verified end to end in Chromium
(`e2e/tests/flows.spec.ts`, the two recovery journeys) and by `MfaFlowIT` and `AdminFlowIT`.

### A. One key lost

1. Sign in with the other registered key (two are required at enrolment, ADR-016).
2. On the session screen, **Add an authenticator** to be back to two.
3. Report the lost key to the supervisor.

### B. Both keys lost, recovery codes available

1. Sign in with the password, choose **I lost my keys: use a recovery code**, enter one
   code. Each code works once; the screen shows how many remain.
2. **Add an authenticator** on the session screen.
3. In **Your security keys**, **Remove (lost)** each lost key: it is no longer offered at
   sign-in and no longer verifies (ADR-028). The last key cannot be removed.
4. Add a second key as soon as possible (the screen warns while there are fewer than two),
   and report the loss to the supervisor.

### C. Every key and every recovery code lost: supervised reset (ADR-028)

1. The staff member reports to their **supervisor**.
2. An **administrator who is not the staff member** requests the reset: Administration → the
   person's card → **Reset the second factor**, with a reason (10+ characters). Nothing
   happens yet; the request waits for the supervisor.
3. The **supervisor**, holding `identity.reset.confirm` at an office where the person holds a
   role, checks the identity **out of band**: in person with an identity document, or by a
   call to a number already on file; never by e-mail or by a call from the person. Then, in
   **Identity confirmations**, they enter how ("In person, national ID card checked") and
   **Confirm and reset**, or reject.
4. The confirmation performs the reset in the database: every key removed, every unused
   recovery code invalidated, the enrolment cleared; every session ends.
5. The staff member signs in with the password and enrols two new keys; ten new recovery
   codes are shown once.
6. The supervisor or an auditor checks the audit trail (below).

### What the system enforces, and what remains procedure

| Control | Enforced by | Evidence |
| --- | --- | --- |
| Codes are single use; a used or wrong code is refused | service; stored as keyed hashes | e2e "recovery code, once"; `MfaFlowIT`, `RecoveryCodeTest` |
| Wrong codes count toward the lockout (5, then 15 min) | service | `MfaFlowIT` |
| A lost key can be removed by its owner; a removed key no longer signs in; the last key stays | service and database (V10) | `SecurityKeysIT`; pgTAP `12`; e2e (both lost keys removed) |
| A reset is requested by a holder of `admin.mfa.reset`, with a reason, never for oneself (`SOD_SELF_ACTION`) | service and database | `SecondFactorResetIT`; pgTAP `12`; e2e |
| Only the person's supervisor confirms: `identity.reset.confirm` at an office of the person; never the requester or the person; another office's supervisor does not see it | service and database | `SecondFactorResetIT`; pgTAP `12`; e2e (an administrator's confirmation is refused) |
| The confirmation says how the identity was checked | service and database (`ck_reset_identity_checked`) | `SecondFactorResetIT`; pgTAP `12` |
| The application's own account cannot clear an enrolment or delete a last key | database (V10) | `SecondFactorResetIT`; pgTAP `12` |
| The reset removes every key and every unused code, clears the enrolment, ends every session | database trigger; sessions by the service | e2e: the old code is refused, the person enrols again |
| Audit: `MFA_RESET_REQUESTED` (administrator), `MFA_RESET` (supervisor, with requester, identity check, keys removed, sessions ended) or `MFA_RESET_REJECTED`; `MFA_KEY_REMOVED`; `MFA_RECOVERY_CODE_USED`; `MFA_ENROLLED` | audit chain (ADR-007) | e2e: the auditor finds the reset with both people and the identity check |
| **The identity check itself** | **procedure**: the supervisor meets or calls the person; the system proves who confirmed and records how | — |

Check resets in the audit trail:

```sql
SELECT occurred_at, actor_id AS confirmed_by, details->>'requestedBy' AS requested_by,
       details->>'identityCheck' AS identity_check, details->>'authenticatorsRemoved' AS keys_removed
  FROM snigf.audit_event WHERE action = 'MFA_RESET' ORDER BY chain_pos DESC LIMIT 10;
```

### Gaps found by the verification

Both gaps found on 22 Oct 2026 are closed by [ADR-028](../adr/028-key-removal-and-supervised-reset.md):
lost keys are removed by their owner, and a reset needs the supervisor's confirmation in the
application. Remaining, by design: the identity check is human; a compromised application
could name another real supervisor (signed approvals, future ADR).

## Audit incident procedure

When the chain fails verification:

1. **Do not restart, reset or "fix" anything.** The evidence is the database, the
   checkpoint directory and the logs.
2. Copy the evidence:
   - `docker compose cp app:/var/lib/snigf/audit-checkpoints ./incident-<date>/`;
   - `docker compose logs app db > ./incident-<date>/logs.txt`;
   - a `pg_dump` as the `postgres` OS user.
3. Re-check outside the application:
   - verify the copied files with the auditor's **pinned** keys:
     `bash infra/scripts/verify-checkpoint.sh pinned-keys.json incident-<date>/checkpoint-*.json`;
   - as `snigf_auditor`, run `SELECT * FROM snigf.audit_verify_chain();` and compare each
     valid checkpoint's head with
     `SELECT encode(hash,'hex') FROM snigf.audit_event WHERE chain_pos = <pos>`.
4. Interpret the kind (table in [chapter 4](04-data-model.md#verification-findings)):

   | Kind | Points at |
   | --- | --- |
   | `CONTENT_HASH`, `PREV_HASH`, `GAP` | an edit or a deletion at that position |
   | `CHECKPOINT_MISMATCH`, `TRUNCATED` | a rewrite or a deletion of the newest events by someone able to recompute hashes |
   | `CHECKPOINT_SIGNATURE` | a forged or altered checkpoint file or row |

5. Escalate to the system owner and the auditor. Who held database or host access during
   the window is the question ([residual risk](../audit.md#residual-risk)).

## Diagnostic commands by component

```sh
# Database
docker compose exec -u postgres db psql -d snigf -c "SELECT * FROM snigf.flyway_schema_history ORDER BY installed_rank"
docker compose exec -u postgres db psql -d snigf -c "SELECT * FROM snigf.audit_verify_chain()"
docker compose exec -u postgres db psql -d snigf -c "SELECT chain_pos, occurred_at, actor_type, action, outcome FROM snigf.audit_event ORDER BY chain_pos DESC LIMIT 20"

# OpenBao
docker compose exec openbao bao status
docker compose logs bao-init
docker compose exec openbao sh -c 'tail -n 5 /openbao/audit/audit.log'

# Certificates
openssl x509 -in infra/secrets/app_tls_crt -noout -subject -enddate
openssl verify -CAfile infra/secrets/pki_ca_chain_crt infra/secrets/app_tls_crt

# Application
docker compose logs app | grep -E "self-test|Audit|refusing|ERROR"
docker inspect snigf-app-1 --format '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.Config.User}}'

# Whole stack guarantees
docker compose --profile test run --rm --build security-probe
docker compose --profile test run --rm segmentation-probe
```

## Debugging the backend

- **From the IDE:** start the dependencies with the development overlay, which publishes
  db and OpenBao on loopback. Then run `SnigfApplication` with profile `dev` and
  `SNIGF_SECRETS_DIR` set to the absolute path of `infra/secrets` (README "Develop").
  Breakpoints work normally.
- **Logs:** raise one package's level without rebuilding, for example
  `LOGGING_LEVEL_ORG_SNIGF_MVP_AUDIT=DEBUG` in the IDE run configuration.
- **SQL:** `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_CORE=DEBUG` logs statements, but never
  enable it in shared environments: it logs parameter values.
- **Reproduce in a test:** most failures can be reproduced in an `*IT` against
  Testcontainers. Write the failing test first, then fix.

## Production notes

The guides' "Production differences" sections are authoritative. In short:

- **PKI:** issued by the pilot PKI with revocation.
- **OpenBao custody:** unseal shares held by named custodians, or an HSM or KMS seal;
  Raft snapshots on a schedule.
- **Audit copies:** kept off-host by the auditor in independent storage.
- **Monitoring:** alerts on `auditChain`, `keys` and `db`, and on log lines starting
  with `AUDIT`.
- **Separation of duties:** database and host administration are separated from key
  custody.
