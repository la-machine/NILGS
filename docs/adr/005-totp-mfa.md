# ADR-005: TOTP MFA for all staff

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Superseded by [ADR-016](016-webauthn-staff-authentication.md) | 2026-10-20 | Pythagore (FLYT) |

## Context

Back-office accounts can change land records; passwords alone are not enough (C2).

## Decision

RFC 6238 TOTP for every staff account, ten single-use recovery codes, admin-assisted reset under separation of duties. Delivered in E-03.

## Alternatives considered

- **SMS codes**: Cost and SIM-swap risk.
- **WebAuthn**: Device availability; candidate for a later phase.

## Consequences

- Staff need an authenticator app.
- Recovery is a documented, audited procedure.
