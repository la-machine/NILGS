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
| Security foundation, step 4b | WebAuthn second factor for staff (two authenticators, user verification required, single-use server-side ceremonies), keyed recovery codes, second-factor failures in the lockout, sign-in screens FR/EN (ADR-023) | 154 backend tests (107 unit, 47 integration, of which 10 WebAuthn flows with emulated authenticators); 96 pgTAP tests + concurrent chain test; security probe 27/27, segmentation probe 4/4; frontend lint and build (21 Oct 2026) |
| Security foundation, step 4c | Permission matrix in code (office scope, checked against `docs/permission-matrix.md`), role administration with separation of duties, administrator-assisted second-factor reset, auditor API (search, verify, checkpoints, downloads), administration and audit screens; no TOTP fallback (ADR-024, proposed) | 171 backend tests (110 unit, 61 integration, including every staff role × every protected endpoint); security probe 27/27, segmentation probe 4/4; frontend lint and build (21 Oct 2026) |
| Security foundation, step 4d | Fine-grained permission catalogue (33, in code and database, checked at startup), nine immutable baseline roles, administrator-defined custom roles and special grants (time and/or use limits, counted atomically, audited), four-eyes approval, administration never with audit (ADR-025); screens FR/EN | 190 backend tests (117 unit, 73 integration, including every staff role × every protected endpoint and concurrent special-grant uses); 120 pgTAP tests; security probe 27/27, segmentation probe 4/4; V7 applied to the running stack; frontend lint and build (21 Oct 2026) |
| Security foundation, step 4e | Four eyes on every role assignment; approval workflow, approver rights and separation of duties enforced by the database (V8), so the application's own account cannot bypass them; supervisors verify the audit chain; grant-only actions confirmed before a use is spent; Chromium end-to-end tests; permission matrix review against the Baseline (ADR-026) | 190 backend tests (117 unit, 73 integration); 150 pgTAP tests; end-to-end 8/8 in Chromium with virtual security keys; security probe 27/27, segmentation probe 4/4; V8 applied to the running stack; frontend lint and build (22 Oct 2026) |
| Decisions D5, D6, D14, ADR-024, ADR-028 | Write-once checkpoint files (hard link) and one checkpoint per position (V9); office-scoped audit reading closed until ADR-027; frontend packaged by feature with the SNIGF logo; owners remove lost keys and resets are confirmed by the supervisor in the application, enforced by the database (V10) | results in the review summary (22 Oct 2026) |
| Slice 1 — parcels and map views | Parcel module (references with check character, PostGIS validation without silent repair, versions and corrections, idempotent creation, audited refusals); maps module with three views over one geometry: technical (offline SVG in UTM), geographic and satellite over self-hosted PMTiles behind `parcel.read`, external providers disabled (ADR-029); map tools in Compose; relative-scope authority fix | 216 backend tests (131 unit, 85 integration); 169 pgTAP tests + concurrent chain test; 13 frontend unit tests (Vitest); end-to-end 19/19 in Chromium (synthetic satellite imagery rendered, no foreign origin, no CSP violation); security probe 27/27, segmentation probe 4/4 (22 Oct 2026). Geographic tiles for Cameroon built and installed on 23 Oct 2026 |
| Interface redesign | Application shell with navigation by permission and addresses, design system (tokens, icon set, components, dialogs), dashboard on real data, parcel workspace (list, three-step recording with preview, detail, correction mode, history) and three-view map workspace with explicit unavailable and offline states; frontend only, no API or security change | 37 frontend unit tests; end-to-end 20/20 in Chromium plus a design tour at three sizes; lint, structure check and build (23 Oct 2026) |
| E-10 — overlap and duplicate detection (MVP-04) | Every parcel version is compared by the database, in the same transaction, with the current versions of active parcels: overlap (≥ 1 m² on the spheroid), possible duplicate (≥ 95 % of each), duplicate reference; version-to-version evidence, one open alert per pair, superseded by corrections, resolution rules guarded for E-11; thresholds from settings, audited; alerts shown after recording and on the parcel for cadastral officers (ADR-030) | 227 backend tests (136 unit, 91 integration, including `ConflictDetectionIT` and `ConflictPolicyTest`); 204 pgTAP tests; 42 frontend unit tests; end-to-end 20/20 in Chromium (24 Oct 2026) |
| E-11 — alert review screens | Alerts of an office, review of one alert (both compared versions and the intersection on one map, the parcels side by side), resolution with an outcome and a note; the author of a compared version refused by the service and the database; either parcel's office; special grants honoured by the guard (ADR-031) | 234 backend tests (139 unit, 95 integration, including `AlertResolutionSoDIT`); 215 pgTAP tests; 47 frontend unit tests; end-to-end 21/21 in Chromium (24 Oct 2026) |
| E-12 — applications: drafts and submission (MVP-02) | The DEMO type catalogue from configuration, versioned; drafts only their owner sees (endpoint, service with 404, and row-level security on the actor named per transaction); the nine states and an append-only history guarded by the database; an idempotent submission that lists every missing item or issues `DEMO-APP-…`; "My applications" for applicants (ADR-032) | 247 backend tests (144 unit, 103 integration, including `ApplicationIT` and `ApplicationTypeConfigIT`); 241 pgTAP tests; 51 frontend unit tests; end-to-end 24/24 in Chromium (24 Oct 2026) |
| E-13 — documents: upload, storage, download | Files recognised from their bytes (PDF, JPEG, PNG; extension agreeing; PDF active content, encryption and oversized images refused), stored under random keys on an app-only volume, append-only versions under row-level security, hash checked before every download (attachment only), orphan cleanup, limits at proxy, servlet, service and database (ADR-033) | 264 backend tests (156 unit, 108 integration, including `DocumentIT`); 261 pgTAP tests; 56 frontend unit tests; end-to-end 24/24 in Chromium (24 Oct 2026) |
| E-14 — completeness review and information requests (MVP-03) | One automatic check in the database for screens, service and guards; append-only records before every move; intake officers of the office (never on their own application) accept only complete cases or request documents with a message; the applicant responds with new versions in the categories asked for; staff case queue and case page (ADR-034) | 266 backend tests (156 unit, 110 integration, including `CompletenessIT`); 287 pgTAP tests; 60 frontend unit tests; end-to-end 25/25 in Chromium (24 Oct 2026) |
| E-15 — review and simulated decision (MVP-05) | Reviewers and decision authorities of the office claim cases at their stage (permissions at scope ASSIGNED, row-level security, 404 otherwise); a recommendation with an internal finding; a decision with grounds and cited documents, never by whoever recommended, blocked while a parcel alert is open; every decision simulated, on screen and in print; the applicant reads the decision, never the review (ADR-035) | 268 backend tests (156 unit, 112 integration, including `ReviewIT`); 314 pgTAP tests; 66 frontend unit tests; end-to-end 26/26 in Chromium (25 Oct 2026) |
| E-16 — tracking and workload report (MVP-06) | The applicant reads where an application stands (state, since when, whose turn, the pending request) through a projection naming parties, never staff nor internal reasons, pinned by a schema test; supervisors read an office's workload (counts per state, cases under way against DEMO stage targets, load per officer, stage durations) from database functions gated on `report.workload` and computed from the transition table only (ADR-036) | 278 backend tests (162 unit, 116 integration, including `TrackingIT` and `WorkloadIT`); 334 pgTAP tests + concurrent chain test; 74 frontend unit tests; end-to-end 27/27 in Chromium (27 Oct 2026) |
| E-17 — simulated payment (MVP-08) | **No real money moves.** DEMO fee of 25,000 XAF from a versioned catalogue; the applicant pays with an `Idempotency-Key`; a Compose simulator signs callbacks with an OpenBao key it never holds and sends them through the proxy's mutual-TLS provider listener; the app checks provider, timestamp and HMAC; the database reconciles by reference and amount under a lock (transaction ids once, mismatches and unknown references kept as exceptions, expiry from `expires_at`); payment required before acceptance; the finance officer resolves exceptions; a replaceable provider port, only the simulator approved (ADR-037) | 306 backend tests (176 unit, 130 integration, including `PaymentIT`: the six outcomes, late and concurrent callbacks); 372 pgTAP tests + concurrent chain test; 80 frontend unit tests; security probe 35/35, segmentation probe 4/4; end-to-end 27/27 in Chromium, paying through the simulator (27 Oct 2026) |
| E-18 — audit timeline (MVP-07) | A case or a parcel reconstructed from the audit trail alone: each event's subjects (the application, its documents and payments, the parcel, both parcels of an alert) with their office at the time, found by the database and hashed (canonical v2; v1 unchanged, no backfill); supervisors search events and read timelines of their offices only, filtered per row by the database; every event shown checked intact; the timeline screen in FR/EN, linked from cases and parcels (ADR-038, implementing ADR-027) | 309 backend tests (177 unit, 132 integration, including `TimelineIT`); 388 pgTAP tests + concurrent chain test; 82 frontend unit tests; security probe 35/35, segmentation probe 4/4; end-to-end 28/28 in Chromium; the live chain of 5,033 events (v1 and v2) verifies after V19 (29 Oct 2026) |
| Slice 1 — documents issued by SNIGF (ADR-039) | **DEMO acknowledgment only, no legal value.** Issued once per submission (PDFBox template, random `SNIGF-` number, QR code); a registry whose manifest binds the exact PDF's SHA-256, signed by a dedicated OpenBao Ed25519 key; public verification by number (record and status, never the paper) or by file (only an identical file is authentic; altered and fabricated files open incidents; files from elsewhere are not accused), minimal answer, throttled; revoke, cancel, re-issue and the incident queue for the new DOCUMENT_OFFICER role; key rotation keeps every version | 324 backend tests (187 unit, 137 integration, including `IssuanceIT`); 420 pgTAP tests + concurrent chain test; 90 frontend unit tests; security probe 37/37, segmentation probe 4/4; end-to-end 29/29 in Chromium; gitleaks history scan clean (30 Oct 2026) |

CI (`.github/workflows/ci.yml`) ran on 28 Oct 2026: every job passed except the secret scan, which failed on two synthetic test passwords (false positives, resolved in Slice 0). It runs again on the next push.

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
  payment-sim/               DEMO payment simulator (ADR-037): no real money moves; signs callbacks in OpenBao
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
