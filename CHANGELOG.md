# Changelog

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Every notable change to SNIGF/NILGS, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once the first release (R0) is tagged.
Each entry names the decision record (ADR) and the guide (`docs/guides/`) that explain it.

## [Unreleased]

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
