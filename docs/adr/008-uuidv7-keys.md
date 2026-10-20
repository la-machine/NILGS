# ADR-008: UUIDv7 primary keys; human references separate

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Keys appear in URLs and must not be guessable or countable; people need readable references.

## Decision

UUIDv7 primary keys. Human references (`DEMO-PCL-...`) are separate unique columns with an ISO 7064 check character.

## Alternatives considered

- **Sequential integer keys**: Guessable, and they reveal volumes.

## Consequences

- The reference scheme can change (V2) without touching keys.
