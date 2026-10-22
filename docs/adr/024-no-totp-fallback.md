# ADR-024: No TOTP fallback; recovery codes and an administrator reset instead

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-22 (proposed 2026-10-21) | Project owner, Pythagore (FLYT) |

## Context

[ADR-016](016-webauthn-staff-authentication.md) kept TOTP "only as a recovery fallback".
[ADR-023](023-webauthn-second-factor-implementation.md) deferred it to security step 4c,
which now also delivers the administrator-assisted reset. Three ways remain for a staff
member to get back in after losing an authenticator:

1. their second registered authenticator (two are required);
2. one of their ten single-use recovery codes;
3. the administrator reset, after their supervisor confirms their identity out of band.

## Decision

**No TOTP fallback.** Recovery is covered by the three paths above. The columns V1
created for TOTP (`mfa_secret_enc`, `mfa_last_used_step`) stay unused. They are kept
because removing columns is a migration of its own, and the enrolment trigger of V6
already accepts either factor.

## Why

- **Phishing:** TOTP codes can be phished and relayed in real time. A phishable fallback
  weakens an unphishable factor, because an attacker simply chooses the fallback.
- **Secrets:** a TOTP secret is a long-lived shared secret the server must hold
  (encrypted). Recovery codes are one-time, and only their keyed hashes are stored.
- **Usability:** the two-authenticator rule already covers the common case (one lost key).
  Losing both keys and all codes is rare enough for a supervised administrator reset,
  which is audited and protected by separation of duties.

## Acceptance conditions (22 Oct 2026)

The project owner accepted this decision on condition that, before go-live, the complete
recovery procedure is verified and documented, including the loss of both authenticators
and all recovery codes, and that identity verification, audit logging and separation of
duties are confirmed. The procedure, what the system enforces, what remains procedural,
and the evidence are in
[chapter 8, account recovery procedure](../technical/08-operations-and-troubleshooting.md#account-recovery-procedure).
Two gaps found by that verification are listed there and must be closed or accepted
before go-live.

## Alternatives considered

- **TOTP as a second-class fallback** (ADR-016 as written): more code, a phishable path,
  and a stored secret, for a case the other paths cover.
- **TOTP for staff without a FIDO2 device:** contradicts ADR-016's requirement that every
  officer holds keys. If device procurement fails for the pilot, revisit this ADR.

## Consequences

- **Procurement:** every officer needs two authenticators, which belongs in the pilot
  plan.
- **Administrator reset:** it must be practised. The procedure is in
  `docs/technical/08-operations-and-troubleshooting.md` ("Reset a lost second factor").
- **Revisiting:** if the project owner prefers TOTP, a new ADR supersedes this one. The
  database is already able to hold a TOTP secret.
