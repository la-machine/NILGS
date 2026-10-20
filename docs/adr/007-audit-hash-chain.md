# ADR-007: Hash-chained audit table with signed off-host checkpoints

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Administrators must not be able to rewrite history silently (MVP-07, C7).

## Decision

Append-only `audit_event` with an in-database SHA-256 chain assigned under an advisory lock; daily and on-demand Ed25519-signed checkpoints kept off the host. See `docs/audit.md`.

## Alternatives considered

- **Separate audit database**: More operations for the same guarantee.
- **External ledger or blockchain**: Far higher cost for tamper evidence the chain plus checkpoints already give.

## Consequences

- Tampering is detectable up to the last checkpoint.
- Residual risk from infrastructure administrators is documented, not eliminated.
