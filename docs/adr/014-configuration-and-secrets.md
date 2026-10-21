# ADR-014: Settings in .env, secrets as files

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

The stack needs per-machine settings (profile, ports, site address) and secrets (database passwords, later the MFA and checkpoint keys).

## Decision

- **Every tunable value is a setting in `.env`**: ports, hostnames, certificate lifetimes, resource limits, timeouts, thresholds. `.env.example` documents each one with its default. Compose passes them to the services as `SNIGF_*` environment variables, and Spring binds them to typed, validated `snigf.*` properties. A value with no safe default has none, so a missing setting stops startup.
- Non-secret settings go in `.env` (git-ignored, copied from the committed `.env.example` by `infra/scripts/dev-setup.sh`). Secrets are files mounted as Docker secrets and read by Spring through `configtree`; they never appear in `.env`, environment variables or Git. Gitleaks scans every CI run.

## Alternatives considered

- **Secrets in .env or environment variables**: Visible through `docker inspect`, process listings and crash reports.

## Consequences

- Two setup artefacts, both created by one script.
- Staging and production secrets are provisioned by the release manager (P-11).
