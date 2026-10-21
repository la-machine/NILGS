# ADR-017: TLS on every network hop, private PKI, mutual authentication

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Nothing is trusted, including the internal network. A compromised container or a host
with network access must not be able to read traffic or impersonate a service.

## Decision

- **Private PKI**: a root CA whose key stays offline, and an intermediate CA that signs
  service certificates. ECDSA P-384 for the CAs, P-256 for service certificates. Lifetimes
  are configuration (`SNIGF_PKI_*` in `.env`). For development, `infra/scripts/dev-pki.sh`
  creates the hierarchy; staging and production certificates are issued by the release
  manager from a separately held root.
- **Browser → proxy**: TLS 1.3 only, HSTS, also on developer machines (no plain HTTP anywhere).
- **Proxy → app**: mutual TLS 1.3. The app requires a client certificate and accepts only
  the subjects listed in `snigf.security.transport.allowed-client-common-names`.
- **App and migrations → database**: TLS 1.3 with `sslmode=verify-full` and a client
  certificate whose CN must equal the database role (`clientcert=verify-full`), plus the
  SCRAM password. Non-TLS connections and network logins by the superuser are rejected
  (`infra/db/pg_hba.conf`).
- **Management endpoints** (health, metrics) bind to the app container's loopback only and
  are unreachable from any network.
- `EnvironmentGuard` refuses to start in any environment except `test` unless server TLS,
  client-certificate authentication and `verify-full` are configured.

## Alternatives considered

- **TLS at the edge only**: internal traffic readable by anything on the Docker network.
- **Service mesh**: same guarantees with far more infrastructure.
- **Certificates issued by OpenBao's PKI engine with automatic renewal**: the target for
  the pilot; needs an agent per service. Recorded as a follow-up to ADR-018.

## Consequences

- Developers trust the development root CA in their browser, or accept a warning.
- `docker compose exec db psql` must run as the `postgres` OS user (`-u postgres`), because
  the superuser logs in only through the local socket with peer authentication.
- Java integration tests (Testcontainers) still connect without TLS; transport security is
  verified by the stack security probe instead.
