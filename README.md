# SNIGF / NILGS — MVP

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Système National Intégré de Gouvernance Foncière / National Integrated Land Governance System.

> **Demonstration software.** This is a proposed system using synthetic data only. It is
> not approved, deployed or endorsed by MINDCAF or any other government institution. It
> does not establish ownership or issue legally valid titles or decisions.

Design documents: the *SNIGF/NILGS — MVP Technical Baseline* doc (tabs *Baseline*,
*Slice 1 design*, *Release plan*). Repository docs under `docs/` take precedence once code
exists; architecture decisions are in [`docs/adr/`](docs/adr/README.md).

## Status

| Increment | Scope | Evidence (20 Oct 2026, local) |
| --- | --- | --- |
| 1 — database layer | Roles, migrations V1–V3: identity, parcels, geometry normalization, audit chain | 81 pgTAP tests + concurrent chain test (1,807 events) pass on PostgreSQL 18.6 / PostGIS 3.6; earlier run on PostgreSQL 16.15 / PostGIS 3.4.2 with mutation check |
| E-01 — skeleton | Compose stack (db, migrate, app, web), backend skeleton with module boundaries, frontend shell, CI | 12 backend tests pass (9 unit, 3 integration against PostGIS); frontend lint and build pass; stack starts from a clean volume |
| E-23 — environment guard | Profiles dev, test, staging, prod; startup refused in unsafe configurations | `ProdStartupGuardTest` (6 cases) |
| Slice 1 features (E-02 to E-08) | Login, MFA, roles, parcel API, audit API, screens | Not started |

CI (`.github/workflows/ci.yml`) has **not yet run**: the repository has not been pushed.

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Docker with Compose v2 | recent | Everything below; images are pulled on first use |
| JDK | 25 | Backend development and tests (`backend/mvnw` downloads Maven itself) |
| Node.js | 24 | Frontend development |

## Run the stack

```sh
infra/scripts/dev-setup.sh           # once: .env from .env.example + passwords in infra/secrets/
docker compose up -d --build --wait  # db, migrate, app, web
```

Open <http://localhost:47000>. Stop with `docker compose down` (add `-v` to delete the data).

- `.env` holds settings only (profile, ports, site address). Passwords are files in
  `infra/secrets/`, mounted as Docker secrets, never environment variables
  ([ADR-014](docs/adr/014-configuration-and-secrets.md)). Both are git-ignored.
- The roles script `infra/db/init/01-roles.sql` runs on the first start of an empty volume;
  `migrate` then applies every migration as `snigf_migrator` and exits
  ([ADR-009](docs/adr/009-least-privilege-roles.md)).
- Inspect the database with `docker compose exec db psql -U postgres -d snigf`.

## Ports

The project never uses default ports ([ADR-015](docs/adr/015-non-default-ports.md)).

| Port | Service | Reachable from |
| --- | --- | --- |
| 47000 | Web proxy (UI + `/api`) | Host, `127.0.0.1` only |
| 47080 | Backend API | Compose network only |
| 47081 | Backend health and metrics | Compose network only; never proxied |
| 47432 | PostgreSQL | Compose network only; host `127.0.0.1` with `compose.dev.yml` |
| 47173 / 47174 | Vite dev server / preview | Host, during frontend development |

## Develop

Backend from the IDE or the command line, against the Compose database:

```sh
docker compose -f compose.yml -f compose.dev.yml up -d db migrate
cd backend
SNIGF_DB_URL=jdbc:postgresql://localhost:47432/snigf SNIGF_SECRETS_DIR=../infra/secrets \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Frontend with hot reload (proxies `/api` to the backend on 47080):

```sh
cd frontend && npm ci && npm run dev   # http://localhost:47173
```

Exactly one profile (`dev`, `test`, `staging`, `prod`) must be active; the application refuses
to start otherwise, or if `prod` runs with demo mode or a placeholder password.

## Test

```sh
cd backend && ./mvnw verify                             # unit + integration (needs Docker)
cd frontend && npm run lint && npm run build            # lint, type-check, build
docker compose --profile test run --rm --build dbtest   # pgTAP + concurrent audit chain
```

Integration tests start PostGIS from the same image as `compose.yml`, apply the roles script as
superuser and the migrations as `snigf_migrator`, and connect as `snigf_app`, like a
deployment. The database suite also runs directly on an Ubuntu host with PostgreSQL, PostGIS
and pgTAP installed (`sudo infra/scripts/db-test.sh`).

## Layout

```text
backend/                     Spring Boot application (one Maven module, ADR-001)
  src/main/java/org/snigf/mvp/
    shared/                  environment guard, correlation id, security baseline
    identity/ parcel/ audit/ conflict/ application/ document/ review/ payment/ reporting/
                             business modules (boundaries verified by ModularityTest)
  src/main/resources/        application*.yaml per profile; db/migration (Flyway SQL)
  src/test/java/             unit (*Test) and integration (*IT) tests
  src/test/sql/              pgTAP tests, helpers, pgbench scripts
frontend/                    React + TypeScript (Vite)
infra/
  db/                        roles script, Compose init hook, database test image
  proxy/                     Caddyfile and web image
  scripts/                   dev setup, database tests
docs/
  adr/                       architecture decision records
  audit.md                   audit trail design and residual risk
compose.yml                  local and demo stack
compose.dev.yml              development overlay (database port on localhost)
.env.example                 settings template, copied to .env
```

## Authorship

SNIGF/NILGS is developed by **FLYT**; author **Pythagore**. Every source file carries this
notice in its header.
