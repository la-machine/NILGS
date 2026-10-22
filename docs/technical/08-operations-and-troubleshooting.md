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
| Make a registered account staff (development only; also bootstraps the first `SYSTEM_ADMIN` and `AUDITOR`) | `bash infra/scripts/dev-make-staff.sh <email> [ROLE]` |
| Reset a lost second factor | [account recovery procedure](#account-recovery-procedure): supervisor confirms identity out of band, an administrator who is not the person resets |

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
| 422 `REASON_REQUIRED` / `CONFIRMATION_REQUIRED` | missing reason (10+ characters for a reset) or out-of-band confirmation | supply them; they are kept in the audit trail |
| 409 `AUDIT_CHAIN_BROKEN` when creating a checkpoint | the chain does not verify since the last checkpoint | audit incident procedure below |
| 409 `CHECKPOINT_UNAVAILABLE` | empty chain, or another checkpoint is being created | retry |
| Staff member sees no administration or audit panel | the session is not FULL, or no role or special grant gives `admin.account.list` / `audit.event.search` | check `permissions` and `specialGrants` in `GET /api/v1/auth/session` |
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
2. **Add an authenticator** on the session screen, then a second one as soon as possible.
3. Report the loss. The lost keys stay registered (see the gaps below) until an
   administrator resets the second factor.

### C. Every key and every recovery code lost: supervised reset

1. The staff member reports to their **supervisor**, who confirms their identity **out of
   band**: in person with an identity document, or by a call to a number already on file.
   Never by e-mail or by a call from the person.
2. The supervisor asks an **administrator who is not the staff member**. In Administration
   → the person's card → **Reset the second factor**, the administrator enters the reason
   (10+ characters) and who confirmed the identity and how ("A. Supervisor, in person, ID
   card checked").
3. The reset removes every registered key, invalidates every unused recovery code, ends
   every session, and clears the enrolment.
4. The staff member signs in with the password and enrols two new keys; ten new recovery
   codes are shown once.
5. The supervisor or an auditor checks the audit trail (below).

### What the system enforces, and what remains procedure

| Control | Enforced by | Evidence |
| --- | --- | --- |
| Codes are single use; a used or wrong code is refused | service; stored as keyed hashes | e2e "recovery code, once"; `MfaFlowIT`, `RecoveryCodeTest` |
| Wrong codes count toward the lockout (5, then 15 min) | service | `MfaFlowIT` |
| A reset needs a reason (10+) and the name of who confirmed the identity | service | `AdminFlowIT`; e2e reset journey |
| Nobody resets their own second factor (403 `SOD_SELF_ACTION`, audited DENIED) | service | `AdminFlowIT`; e2e (`SOD_SELF_ACTION` through the API) |
| Only a holder of `admin.mfa.reset` resets | endpoint permission | `PermissionMatrixIT` |
| The reset removes every key and every unused code, ends every session | service, in one transaction | e2e: the old code is refused afterwards, the person enrols again; `AdminFlowIT` |
| Audit: `MFA_RESET` with the administrator as actor, reason, confirmer, keys removed, sessions ended; `MFA_RECOVERY_CODE_USED`, `MFA_ENROLLED` | audit chain (ADR-007) | e2e: the auditor finds the reset with the confirmer and `authenticatorsRemoved` |
| **Identity verification** | **procedure**: the supervisor's out-of-band check. The system records only the confirmer's name and means as text | — |

Check a reset in the audit trail:

```sql
SELECT occurred_at, actor_id, reason, details->>'confirmedBy' AS confirmed_by,
       details->>'authenticatorsRemoved' AS keys_removed, details->>'sessionsRevoked' AS sessions_ended
  FROM snigf.audit_event WHERE action = 'MFA_RESET' ORDER BY chain_pos DESC LIMIT 10;
```

### Gaps found by the verification (to close or accept before go-live)

1. **Lost keys stay registered after path B.** Recovering with a code does not remove the
   lost keys; whoever finds one and knows the password can still sign in, until a reset.
   Proposed fix: let the person list their keys (label, created, last used) and remove the
   lost ones from the session screen, offered right after a recovery-code sign-in.
2. **The identity confirmation is not checked by the system.** The confirmer is free text,
   and one administrator performs the reset. Proposed fix: the person's supervisor confirms
   the reset in the application (a second person, recorded as an account, not as text), as
   role assignments are confirmed (ADR-026).
3. Separation of duties for resets is enforced by the service only; the database does not
   know who resets (the reset is a deletion of keys and codes).

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
