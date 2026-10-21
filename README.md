# SNIGF / NILGS — MVP

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Système National Intégré de Gouvernance Foncière / National Integrated Land Governance System.

> **Demonstration software.** This is a proposed system using synthetic data only. It is
> not approved, deployed or endorsed by MINDCAF or any other government institution. It
> does not establish ownership or issue legally valid titles or decisions.

**Start here: the [technical manual](docs/technical/README.md)**: architecture, security
model, data model, every setting, how to rebuild it in a new project, testing, and
troubleshooting with an error catalogue.

Design documents: the *SNIGF/NILGS — MVP Technical Baseline* doc (tabs *Baseline*,
*Slice 1 design*, *Release plan*). Repository docs under `docs/` take precedence once code
exists; architecture decisions are in [`docs/adr/`](docs/adr/README.md), step-by-step
how-to guides in [`docs/guides/`](docs/guides/README.md), and every change in
[`CHANGELOG.md`](CHANGELOG.md).

## Status

| Increment | Scope | Evidence (20 Oct 2026, local) |
| --- | --- | --- |
| 1 — database layer | Roles, migrations V1–V3: identity, parcels, geometry normalization, audit chain | 81 pgTAP tests + concurrent chain test pass on PostgreSQL 18.6 / PostGIS 3.6 |
| E-01 — skeleton | Compose stack, backend skeleton with module boundaries, frontend shell, CI | 20 backend tests pass (16 unit, 4 integration on PostGIS); frontend lint, type-check and build pass |
| E-23 — environment guard | Profiles dev, test, staging, prod; startup refused in unsafe configurations | `ProdStartupGuardTest` (7 tests) |
| Security foundation, step 1 | Private PKI, TLS 1.3 on every hop, mutual TLS, network segmentation, container hardening (ADR-017, ADR-020) | Security probe and segmentation probe against the running stack |
| Security foundation, step 2 | OpenBao key custody: transit keys per purpose, certificate login, `KeyService` module, startup self-test (ADR-018) | 38 backend tests (34 unit, 4 integration); security probe 24/24 and segmentation probe 4/4 against the running stack |
| Security foundation, step 3 | Audit module in DDD layers (ADR-021): recorder, Java chain verifier, daily checkpoints signed by OpenBao and archived off-database, offline verifier script, V4 function hardening (ADR-007) | 95 backend tests (71 unit, 24 integration on PostGIS); 85 pgTAP tests + concurrent chain test; security probe 24/24 and segmentation probe 4/4; OpenBao-signed checkpoint verified offline with OpenSSL (21 Oct 2026) |
| Security foundation, step 4a | Identity module (DDD): registration, password login with OpenBao-peppered Argon2id, P7 policy with 29,982 common passwords, server-side sessions with plain values only, `__Host-` cookies, CSRF (including login), uniform 401, lockout, throttling, audited denials (ADR-022) | 135 backend tests (98 unit, 37 integration); 89 pgTAP tests + concurrent chain test; security probe 27/27, segmentation probe 4/4; browser journey through the proxy verified (21 Oct 2026) |
| Security foundation, steps 4b–4c | WebAuthn second factor, TOTP fallback, recovery codes, sign-in screens; role administration and auditor API | Not started |

CI (`.github/workflows/ci.yml`) has **not yet run**: the repository has not been pushed.

## Security model in one table

| Hop | Protection |
| --- | --- |
| Browser → proxy | TLS 1.3 only, HSTS; no plain-HTTP listener, even locally |
| Proxy → app | Mutual TLS 1.3; the app admits only the proxy's certificate name |
| App, migrations → database | TLS 1.3, `verify-full`, client certificate whose CN must equal the role, plus SCRAM password |
| App → OpenBao (keys) | Mutual TLS 1.3; login with the app's certificate; a policy limited to sign, wrap, HMAC; keys never exportable |
| Database superuser | Local socket as the `postgres` OS user only; never over the network |
| Health and metrics | Container loopback only; unreachable from any network |
| Networks | `edge` → `app-net` → `data-net` / `key-net`; internal networks have no internet; the proxy reaches neither the database nor OpenBao |
| Keys | In OpenBao only (Shamir seal, no stored root token); the app refuses to start if a key is missing, exportable or fails its self-test |
| Audit trail | Append-only hash chain, recomputed in Java; daily Ed25519 checkpoints signed by OpenBao, stored in the database and on an app-only volume, verifiable offline with OpenSSL; a tampered chain is never signed |
| Containers | Read-only root filesystem, all capabilities dropped, `no-new-privileges`, non-root, resource limits |
| Accounts and sessions | Passwords: OpenBao pepper + Argon2id; identical answer for every login failure; lockout and throttling; sessions in PostgreSQL holding plain values only, `__Host-` Secure HttpOnly SameSite=Strict cookie, CSRF on every change; staff stop at the second factor |
| Configuration | Every value in `.env`; secrets only as files; the app refuses to start without complete transport security (except in automated tests) |

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Docker with Compose v2 | recent | Everything below; images are pulled on first use |
| Bash and OpenSSL | 3.x | `dev-setup.sh` (Git Bash on Windows works) |
| JDK | 25 | Backend development and tests (`backend/mvnw` downloads Maven itself) |
| Node.js | 24 | Frontend development |

## Run the stack

```sh
bash infra/scripts/dev-setup.sh        # once: .env, passwords, development PKI (all git-ignored)
docker compose up -d --build --wait    # db, migrate, openbao, bao-init, app, web
```

Open <https://localhost:47000>. Stop with `docker compose down` (add `-v` to delete the data).

- `.env` holds every setting (profile, ports, host name, certificate lifetimes, resource
  limits); `.env.example` documents each one. Compose refuses to start if one is missing.
  Passwords and keys are files in `infra/secrets/`, mounted as Docker secrets, never
  environment variables ([ADR-014](docs/adr/014-configuration-and-secrets.md)).
- The browser warns until you trust the **development** root CA, `infra/secrets/pki_root_ca_crt`.
  On Windows, for your user only: `certutil -user -addstore Root infra\secrets\pki_root_ca_crt`.
  Remove it with `certutil -user -delstore Root "SNIGF Development Root CA"`. Never install a
  development CA on a machine used for real work, and never use it beyond development.
- Re-issue all service certificates: `bash infra/scripts/dev-pki.sh --rotate`.
- Inspect the database: `docker compose exec -u postgres db psql -d snigf`.
- OpenBao starts sealed after every restart; `docker compose up -d` runs `bao-init` again,
  which unseals it. Details, including the root-token ceremony and rotation, in
  [`docs/guides/key-custody-openbao.md`](docs/guides/key-custody-openbao.md).
- Re-running `bash infra/scripts/dev-setup.sh` adds settings introduced since your `.env`
  was created, without changing existing values.

## Ports

The project never uses default ports ([ADR-015](docs/adr/015-non-default-ports.md)).

| Port | Service | Protocol | Reachable from |
| --- | --- | --- | --- |
| 47000 | Web proxy (UI + `/api`) | HTTPS (TLS 1.3) | Host, `127.0.0.1` only |
| 47080 | Backend API | Mutual TLS 1.3 | `app-net` only |
| 47081 | Backend health and metrics | HTTP on loopback | Inside the app container only |
| 47432 | PostgreSQL | TLS 1.3 + client certificate | `data-net` only; host `127.0.0.1` with `compose.dev.yml` |
| 47820 | OpenBao API | Mutual TLS 1.3 | `key-net` only; host `127.0.0.1` with `compose.dev.yml` |
| 47821 | OpenBao cluster (Raft) | TLS | Inside `key-net` only |
| 47173 / 47174 | Vite dev server / preview | HTTPS (TLS 1.3) | Host, during frontend development |

## Develop

Backend from the IDE or the command line, against the Compose database. It reads the
repository's `.env` and uses the same certificates as the container:

```sh
docker compose -f compose.yml -f compose.dev.yml up -d --wait db migrate openbao bao-init
cd backend
# Absolute path, without "..": Spring's config tree silently reads nothing otherwise.
SNIGF_SECRETS_DIR="$(cd ../infra/secrets && (pwd -W 2>/dev/null || pwd))" \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows, `dev-pki.sh` restricts private-key files to your user (the PostgreSQL driver
refuses keys that administrators can read).

Frontend with hot reload. It serves HTTPS and calls the backend over mutual TLS with the
proxy's identity, exactly like the deployed proxy:

```sh
cd frontend && npm ci && npm run dev   # https://localhost:47173
```

Exactly one profile (`dev`, `test`, `staging`, `prod`) must be active. Outside `test`, the
application refuses to start without complete transport security; in `prod` also with demo
mode on or a placeholder password.

## Test

```sh
cd backend && ./mvnw verify                                       # unit + integration (needs Docker)
cd frontend && npm run lint && npm run build                      # lint, type-check, build
docker compose --profile test run --rm --build dbtest             # pgTAP + concurrent audit chain
docker compose --profile test run --rm --build security-probe     # transport and key-custody checks (stack running)
docker compose --profile test run --rm segmentation-probe         # isolation checks (stack running)
```

Integration tests start PostGIS from the same image and port as `compose.yml`, apply the
roles script as superuser and the migrations as `snigf_migrator`, and connect as
`snigf_app`. They run without TLS and with in-memory keys (profile `test`); transport
security and key custody are verified against the running stack by the probes, which
also confirm the reason for every refusal, and by the application's startup self-test.

## Layout

```text
backend/                     Spring Boot application (one Maven module, ADR-001)
  src/main/java/org/snigf/mvp/
    shared/                  environment guard, correlation id, client-certificate filter, security baseline
    keys/                    KeyService: signing, envelope encryption, HMAC via OpenBao (ADR-018)
    identity/ parcel/ audit/ conflict/ application/ document/ review/ payment/ reporting/
                             business modules (boundaries verified by ModularityTest); each one
                             is a published API + domain/ application/ infrastructure/
                             (ADR-021, verified by DddLayeringTest)
  src/main/resources/        application*.yaml per profile (+ transport-security, key-custody); db/migration (Flyway SQL)
  docker-entrypoint.sh       container entry point (private copy of the database client key)
  src/test/java/             unit (*Test) and integration (*IT) tests
  src/test/sql/              pgTAP tests, helpers, pgbench scripts
frontend/                    React + TypeScript (Vite)
infra/
  db/                        roles script, pg_hba.conf, start wrapper, init hook, database test image
  openbao/                   OpenBao image, configuration template, application policy, provisioning job
  proxy/                     Caddyfile and web image
  probe/                     security and segmentation probes
  scripts/                   dev-setup.sh, dev-pki.sh, db-test.sh, verify-checkpoint.sh (auditor)
docs/
  technical/                 technical manual (start here)
  adr/                       architecture decision records
  guides/                    how each part was built and how to redo it
  audit.md                   audit trail design and residual risk
compose.yml                  stack: services, networks, secrets, hardening
compose.dev.yml              development overlay (database port on localhost)
.env.example                 every setting with its documented value, copied to .env
CHANGELOG.md                 every change, newest first
```

## Authorship

SNIGF/NILGS is developed by **FLYT**; author **Pythagore**. Every source file carries this
notice in its header.
