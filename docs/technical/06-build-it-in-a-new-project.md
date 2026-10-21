# 6. Build it in a new project

*Copyright (c) 2026 FLYT. Author: Pythagore.*

This chapter takes you through rebuilding the same foundation in a new repository, in
the order that works. Each step points to the guide that holds the exact files,
commands and pitfalls. Do the steps in order: each one relies on the previous one, and
each ends with a check that must pass before you continue.

## 0. Decide the rules first

Write these down as ADRs before writing code (see [`docs/adr/`](../adr/README.md) for the
format):

- **Architecture:** a modular monolith with DDD layers inside each module
  ([ADR-001](../adr/001-modular-monolith.md), [ADR-021](../adr/021-ddd-layering-inside-modules.md)).
- **Configuration:** settings in `.env`, secrets as files, no silent defaults
  ([ADR-014](../adr/014-configuration-and-secrets.md)).
- **Ports:** no default ports; pick a block such as 47xxx
  ([ADR-015](../adr/015-non-default-ports.md)).
- **Transport:** TLS 1.3 on every hop, mutual TLS inside
  ([ADR-017](../adr/017-tls-on-every-hop.md)).
- **Keys:** in a key manager, never in the application
  ([ADR-018](../adr/018-openbao-key-management.md)).
- **Hardening:** hardened containers and segmented networks
  ([ADR-020](../adr/020-container-and-network-hardening.md)).

## 1. Repository skeleton

1. Create `backend/` (Spring Boot with the Maven wrapper), `frontend/` (Vite), `infra/`,
   `docs/`, `compose.yml`, `.env.example`, `.gitignore` (`.env`, `infra/secrets/`,
   `var/`, build output) and `.gitattributes` (LF for `*.sh`, `*.sql`, `*.yaml`, `*.hcl`,
   `*.template`).
2. Sign every file with the project notice, and add a `CHANGELOG.md` and this manual's
   structure from day one.
3. Backend: Java 25, Spring Boot 4.1 and Spring Modulith. Add `ModularityTest` and
   `DddLayeringTest`, copied from `backend/src/test/java/org/snigf/mvp/`.

**Check:** `./mvnw verify` passes. `maven-enforcer` refuses JDKs older than 25.

## 2. Settings and secrets

Follow [environment and secrets](../guides/environment-and-secrets.md):

- **`.env.example`:** every setting, documented.
- **`dev-setup.sh`:** creates `.env`, appends new settings, generates passwords and
  strips the CR/LF that OpenSSL adds on Windows.
- **Compose:** `${NAME:?set in .env}` everywhere.
- **Spring:** imports `.env` and the secrets directory as a config tree, and binds to
  validated records.
- **Scripts:** read `.env` with `load_env`, never by sourcing it.

**Check:** `docker compose config -q`. Removing any setting from `.env` must make it fail.

## 3. Private PKI and TLS on every hop

Follow [transport security](../guides/transport-security.md):

1. `dev-pki.sh`: a P-384 root and intermediate CA, and P-256 leaves with `serverAuth` or
   `clientAuth`.
2. Caddy with `protocols tls1.3`; HSTS and CSP; no HTTP listener.
3. Spring Boot SSL bundle with `options.enabled-protocols: TLSv1.3` and
   `client-auth: need`.
4. A filter that admits only the proxy's certificate CN.
5. PostgreSQL with `ssl_min_protocol_version = TLSv1.3` and `hostssl` lines only, using
   `scram-sha-256 clientcert=verify-full`.
6. A guard that refuses to start without all of it (`EnvironmentGuard`).

**Check:** the security probe passes, and each refusal is asserted with its reason.

## 4. Database roles and migrations

Follow [the data model](04-data-model.md) and ADR-009:

1. `01-roles.sql`: owner, app and auditor roles; revoke the defaults from `PUBLIC`; READ
   COMMITTED for the app.
2. Flyway migrations applied by a one-shot `migrate` service as the owner.
3. Per-table and per-column grants; append-only triggers for history.
4. `SET search_path = pg_catalog, <schema>, public, pg_temp` on every function.
5. pgTAP tests in a throwaway server (`dbtest`), and integration tests with Testcontainers
   on the real image and port.

**Check:** `docker compose --profile test run --rm dbtest` passes, and the app cannot run
DDL (`ApplicationSmokeIT`).

## 5. Key custody

Follow [key custody with OpenBao](../guides/key-custody-openbao.md):

1. OpenBao on its own internal network, with a mutual TLS 1.3 listener and audit devices.
2. A one-shot provisioning job: Shamir initialisation, unseal, keys that are not
   exportable or deletable, a least-privilege policy, certificate login, and a temporary
   root token that is then revoked.
3. A `KeyService` interface by purpose, an OpenBao provider and an in-memory provider for
   tests.
4. A startup self-test, and a health contributor in readiness.

**Check:** the probe's OpenBao section passes, and the app logs
`Key custody self-test passed`.

## 6. Hardening and segmentation

Follow ADR-020 and the transport guide:

- the `x-hardened` anchor;
- non-root users with fixed UIDs;
- tmpfs for writable paths;
- internal networks;
- resource limits from `.env`;
- images pinned by digest.

**Check:** the segmentation probe passes.

## 7. The audit trail

Follow [audit trail](../guides/audit-trail.md):

1. The database chain: an advisory lock, a trigger assigning position, time and hashes,
   and append-only grants.
2. A module in DDD layers: a recorder in the caller's transaction, actors from the
   context, a Java verifier with a parity test against SQL, checkpoints signed by the key
   manager and archived outside the database, daily schedules, and a health contributor.
3. An offline verifier script for auditors.

**Check:** `AuditChainIT` detects every superuser tampering scenario, and
`verify-checkpoint.sh` validates a real checkpoint.

## 8. Authentication and sessions

Follow [authentication and sessions](../guides/authentication-and-sessions.md):

1. Spring Session JDBC tables in a migration.
2. A plain-value session repository, a deserialization filter and the session cookie
   defined in code.
3. CSRF in single-page-application mode with `__Host-` cookies.
4. A peppered Argon2id hasher with bounded concurrency.
5. Password policy and lockout in the domain.
6. A uniform 401 for every login failure, and throttling per client address.
7. An audited 403 for refused authenticated calls.

**Check:** `AuthFlowIT` passes, and so does the security probe's session section.

The WebAuthn second factor (security step 4b) builds on this.

## 9. Continuous integration

Use `.github/workflows/ci.yml` as the model:

- **backend:** `./mvnw verify` (needs Docker for Testcontainers).
- **frontend:** lint and build.
- **stack:** `dev-setup.sh`, `dbtest`, `compose up --wait`, a smoke test, both probes and
  a secret scan.

## What to change for your project

| Item | Here | Change to |
| --- | --- | --- |
| Package and module names | `org.snigf.mvp.*` | your domain's bounded contexts |
| Port block | 47xxx | a free block on your hosts |
| Names and identities | `SNIGF_*`, CNs `snigf-*` | your prefix |
| Key purposes | six keys (ADR-018) | one per purpose your system needs |
| Audit actions | `AuditAction` | your events |
| Time zone | `Africa/Douala` | yours |
| Development CA | `dev-pki.sh` | for the pilot: your PKI or OpenBao PKI with revocation |
