# ADR-016: WebAuthn for staff, TOTP as recovery fallback

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted (supersedes ADR-005) | 2026-10-20 | Pythagore (FLYT) |

## Context

Staff accounts can change land records. TOTP codes (ADR-005) can be phished in real time,
and they cannot prove what an officer approved. The project applies maximum security to
land-title workflows.

## Decision

- Every staff account authenticates with WebAuthn (FIDO2 security key or platform passkey)
  as its second factor. User verification (PIN or biometric) is required.
- Each staff account registers at least two authenticators, so losing one does not lock
  the officer out.
- TOTP remains only as a recovery fallback; using it is audited and prompts registration of
  a replacement authenticator.
- Admin-assisted reset keeps the ADR-005 procedure (separation of duties, written reason,
  sessions revoked).
- Relying-party ID, allowed origins, attestation policy and timeouts are configuration
  (`snigf.security.webauthn.*`, values in `.env`).

## Alternatives considered

- **TOTP only (ADR-005)**: phishable; no officer-held key to sign decisions.
- **WebAuthn only**: strongest, but a lost key would need an admin reset every time;
  kept as a possible later policy once every officer has two keys.

## Consequences

- Each officer needs a FIDO2 key or a passkey-capable device; procurement belongs in the
  pilot plan.
- Enables officer-signed recommendations and decisions (ADR-019).
- Delivered in E-03.
