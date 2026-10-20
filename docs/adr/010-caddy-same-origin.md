# ADR-010: Caddy reverse proxy, one origin for UI and API

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

The browser UI and the API need TLS, security headers and no CORS surface.

## Decision

Caddy serves the built front end and proxies `/api/*` to the app. It sets HSTS, CSP, frame-ancestors, nosniff and referrer policy, and never proxies the management port.

## Alternatives considered

- **Nginx**: More manual TLS and configuration.
- **Separate API domain**: Requires CORS.

## Consequences

- No CORS configuration.
- Automatic certificates on staging and production.
