# ADR-020: Container and network hardening

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

A compromised container must be able to do as little as possible and reach as little as
possible.

## Decision

- **Network segmentation**: `edge` (published proxy port only), `app-net` (proxy ↔ app),
  `data-net` (app and migrations ↔ database). `app-net` and `data-net` are internal: no
  route to the internet. The proxy cannot reach the database.
- **Containers**: read-only root filesystem with explicit `tmpfs` mounts, all Linux
  capabilities dropped (the database gets back only the five it needs to start),
  `no-new-privileges`, non-root users, memory, CPU and process limits taken from `.env`.
- **Images**: pinned by digest, built from pinned bases; scanning, SBOM and signing are
  added to CI.
- A `security-probe` Compose service (profile `test`) asserts these properties against the
  running stack: plaintext refused, TLS below 1.3 refused, unauthenticated or wrongly
  authenticated clients refused, internal services unreachable from the wrong network.

## Alternatives considered

- **Default container settings**: writable filesystems, default capabilities, flat network.

## Consequences

- Services that need to write get explicit `tmpfs` or volume mounts, which documents
  exactly where each writes.
- The probe runs in CI, so a regression in isolation fails the build.
