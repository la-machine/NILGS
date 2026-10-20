# ADR-012: Code-first OpenAPI; generated TypeScript types

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Client and server contracts must not drift silently.

## Decision

springdoc generates OpenAPI from the code; CI generates TypeScript types and fails on drift. Delivered with the first API (E-05).

## Alternatives considered

- **Hand-written client types**: Drift is found only at runtime.

## Consequences

- One pull request changes API and client together (single repository).
