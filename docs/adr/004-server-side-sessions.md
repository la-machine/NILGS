# ADR-004: Server-side sessions in PostgreSQL

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Staff sessions must be revocable immediately (logout, MFA reset, account disable).

## Decision

Spring Session JDBC, cookie `SNIGF_SESSION` (HttpOnly, Secure, SameSite=Lax), CSRF token on every state-changing call. Delivered in E-03.

## Alternatives considered

- **JWT access and refresh tokens**: Cannot be revoked instantly without a deny-list; tokens end up in browser storage.

## Consequences

- Instant revocation.
- One extra table pair; session lookups hit the database.
