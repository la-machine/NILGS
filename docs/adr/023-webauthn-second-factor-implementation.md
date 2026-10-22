# ADR-023: How the WebAuthn second factor is implemented

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted, implemented | 2026-10-21 | Pythagore (FLYT) |

## Context

[ADR-016](016-webauthn-staff-authentication.md) decided:

- WebAuthn as the staff second factor, with user verification and two authenticators
  per officer;
- TOTP as a recovery fallback.

The Slice 1 design specified ten Argon2id-hashed recovery codes. Implementing this
inside the plain-value session model of [ADR-022](022-plain-value-sessions.md) raised
choices the earlier decisions did not settle.

## Decision

1. **Library:** Spring Security 7.1's `spring-security-webauthn`.
   - Verification is done by webauthn4j: challenge, origin, relying-party id, user
     verification, signature and signature counter.
   - Credentials are stored with its JDBC repositories (migration V6), with integrity
     added: one WebAuthn user per account, a foreign key to the account, and a label.
   - Its ready-made login filter is **not** used. It would store an object graph in the
     session and authenticate on its own; our endpoints call the relying-party
     operations and raise the session level ourselves.
2. **Ceremony state:** pending ceremonies are kept in a bounded in-memory store, keyed
   by a random 256-bit id.
   - The session holds only that id (a plain string).
   - A ceremony is used once, by the account and for the kind it was started for,
     within `SNIGF_WEBAUTHN_TIMEOUT`; any failed attempt consumes it.
   - One application instance is assumed, as for throttling.
3. **Policy:**
   - user verification **required**;
   - non-discoverable credentials (a second factor, not a username replacement);
   - attestation `none` until a device trust list exists;
   - exact origins whose host is the relying-party id, https outside tests;
   - at assertion, only the account's own credentials are offered, and the credential's
     owner must be the account proving it.
4. **Enrolment:**
   - **Bootstrap:** a staff password session may register authenticators only until
     `SNIGF_WEBAUTHN_MIN_AUTHENTICATORS` (2) are registered. Enrolment then completes:
     the account is marked enrolled, recovery codes are issued once, and the session
     becomes FULL with a new id.
   - **Afterwards:** adding an authenticator requires a FULL session, so a stolen
     password alone can never add an attacker's key.
   - **Database rule:** a trigger enforces "enrolled only with a credential or a TOTP
     secret". It replaces V1's TOTP-only check.
5. **Recovery codes:**
   - Ten codes, 16 characters of Crockford base32 (80 random bits each), used once.
   - **Hashing:** HMAC-SHA-256 with the OpenBao pepper key over a domain-separated
     message, instead of Argon2id as the design said. With 80 random bits, offline
     guessing is infeasible without the key, which never leaves OpenBao. A code is
     checked without one Argon2 computation per stored code (ten per attempt), and the
     key version is recorded.
   - **After use:** a used code raises the session to FULL, and the interface asks the
     user to register a replacement authenticator.
6. **Failures:** a wrong assertion or recovery code counts toward the lockout (P6) like
   a wrong password. It is audited as `LOGIN_FAILED` with its reason, and the session is
   ended when the account locks.
7. **TOTP fallback is not implemented in this step.** Recovery codes provide the
   fallback ADR-016 asked for, without a phishable shared secret. Whether TOTP is still
   wanted is decided with security step 4c, together with the administrator-assisted
   reset.

## Alternatives considered

- **Spring Security's WebAuthn filters with HTTP-session storage:** less code, but object
  graphs in the session (ADR-022) and its own authentication model beside ours.
- **Yubico `java-webauthn-server`:** sound, but a second WebAuthn stack beside the one
  Spring Security already brings.
- **Argon2id for recovery codes:** as specified, but it adds cost without adding security
  for 80-bit random codes, and needs one hash per stored code at every attempt.
- **Database-backed ceremony store:** needed for several instances; deferred with the
  rate limiter's shared store.

## Consequences

- The relying-party id is fixed per deployment. Changing `SNIGF_WEBAUTHN_RP_ID`
  invalidates every registered authenticator.
- Staff need two authenticators. In development, browser virtual authenticators (Chrome
  DevTools, "WebAuthn" panel) or a platform passkey plus a security key work.
- The flows are tested end to end with webauthn4j's authenticator emulators
  (`MfaFlowIT`): genuine attestations and signatures, including phishing from another
  origin, replay, and another account's key.
