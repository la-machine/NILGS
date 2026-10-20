# ADR-009: Migrations as snigf_migrator; the app runs as snigf_app

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

If the application is compromised it must not be able to change the schema or the audit history.

## Decision

Three database roles: `snigf_migrator` owns the schema, `snigf_app` has per-table and per-column grants, `snigf_auditor` reads audit only. Migrations run in a one-shot `migrate` container (Flyway, the version Spring Boot manages) that alone receives the migrator password; the app container receives only `db_app_password`. `EnvironmentGuard` refuses to start if the app connects as any other role.

## Alternatives considered

- **App owns the schema and runs Flyway at startup**: The app could alter the schema or disable audit triggers.

## Consequences

- Deployments have an explicit migration step.
- Schema-owner credentials are used at deployment time only (`docs/audit.md`).
