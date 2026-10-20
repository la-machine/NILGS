# ADR-011: RFC 9457 Problem Details with stable error codes

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

The UI is bilingual; the API should not be.

## Decision

Errors are Problem Details with stable codes (`GEOMETRY_INVALID` and so on). The front end translates codes into French and English.

## Alternatives considered

- **Server-rendered localized messages**: Couples the API to languages; parity is hard to test.

## Consequences

- FR/EN parity is testable (`I18nParityTest`, E-07).
