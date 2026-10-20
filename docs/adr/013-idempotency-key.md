# ADR-013: Idempotency-Key on create endpoints

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Retries over unreliable connections must not create duplicate parcels, applications or payments.

## Decision

Create endpoints require an `Idempotency-Key` header, stored per user with the request hash and response (`idempotency_record`). Same key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`.

## Alternatives considered

- **Client-side retry suppression only**: Does not survive a lost response.

## Consequences

- Retries are safe.
- Records need periodic cleanup.
