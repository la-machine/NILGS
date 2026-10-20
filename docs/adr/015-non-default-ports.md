# ADR-015: No default ports

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Project decision (Pythagore, 20 October 2026): no service uses its well-known default port.

## Decision

Ports come from one block: PostgreSQL 47432, API 47080, management 47081, web proxy 47000 (bound to 127.0.0.1 locally), Vite 47173 and 47174. Integration tests use the same PostgreSQL port. The README lists them.

## Alternatives considered

- **Default ports**: Rejected by the project; they also collide with services already installed on developer machines.

## Consequences

- Less noise from automated scanning. This is not a security control: the controls remain that only the proxy is published and everything else stays on the internal network.
- Open question for P-11: the public HTTPS endpoint on staging and production, since browsers expect 443 and certificate issuance uses 80/443.
