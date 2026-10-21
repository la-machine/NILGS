# ADR-007: Hash-chained audit table with signed off-host checkpoints

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted, implemented | 2026-10-20 (implemented 2026-10-21) | Pythagore (FLYT) |

## Context

Administrators must not be able to rewrite history silently (MVP-07, C7).

## Decision

Append-only `audit_event` with an in-database SHA-256 chain assigned under an advisory lock; daily and on-demand Ed25519-signed checkpoints kept off the host. See `docs/audit.md`.

## Implementation (2026-10-21)

- The database assigns position, time and hashes (V3__audit.sql); every function pins its
  `search_path` (V4).
- The application recomputes the chain in Java (`ChainVerifier`), independently of the
  database's functions, and trusts a checkpoint only when its Ed25519 signature verifies.
- Checkpoints are signed by OpenBao with the `audit-checkpoint` key (ADR-018), which the
  database never sees, and are stored twice: in `audit_checkpoint` and as read-only files
  on a volume mounted into the application only. A tampered chain is never signed.
- Daily checkpoint and full verification are scheduled; the auditor verifies checkpoint
  files offline with `infra/scripts/verify-checkpoint.sh` (bash and OpenSSL only).
- Packaging follows ADR-021. Details: `docs/audit.md`, `docs/guides/audit-trail.md`.

## Alternatives considered

- **Separate audit database**: More operations for the same guarantee.
- **External ledger or blockchain**: Far higher cost for tamper evidence the chain plus checkpoints already give.

## Consequences

- Tampering is detectable up to the last checkpoint.
- Residual risk from infrastructure administrators is documented, not eliminated.
