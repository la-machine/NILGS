# ADR-028: Owners remove lost keys; a reset needs the supervisor's confirmation in the application

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-22 | Project owner ("proceed with the code"), Pythagore (FLYT) |

Closes the two gaps found when verifying the recovery procedure of
[ADR-024](024-no-totp-fallback.md).

## Context

1. **Lost keys stayed registered.** After a recovery-code sign-in the person added a new
   key, but the lost ones still worked for whoever found them and knew the password, until an
   administrator reset everything (the end-to-end test showed three keys at reset time).
2. **The identity confirmation of a reset was free text.** One administrator typed who had
   confirmed the identity and reset at once; nothing proved the supervisor was involved.

## Decision

**Owners manage their keys.** With every factor proven, a staff member lists their keys
(name, registered, last used; never key material) and removes a lost one
(`GET`/`DELETE /api/v1/auth/mfa/keys`). The last key cannot be removed; below the required
two, the screen asks for a new one. A removed key is no longer offered at sign-in and no
longer verifies. Every removal is audited (`MFA_KEY_REMOVED`). After a recovery-code sign-in
the interface says to add a key, then remove the lost ones.

**A reset is a request confirmed by the person's supervisor.**
1. An administrator holding `admin.mfa.reset` requests it with a reason
   (`MFA_RESET_REQUESTED`); nothing happens yet.
2. A supervisor holding the new permission **`identity.reset.confirm`** at an office where the
   person holds a role checks the identity out of band and confirms in the application,
   saying how ("in person, national ID card checked"), or rejects it.
3. The confirmation performs the reset **in the database, in the same statement**: every key
   removed, unused recovery codes invalidated, enrolment cleared; then every session ends.
   `MFA_RESET` names the supervisor as actor, the requester, the identity check, the keys
   removed and the sessions ended.

Requester, confirmer and person are three different people. A supervisor of another office
does not see the request (404).

**`identity.reset.confirm` is a business permission, office-scoped, never delegable.**
Supervisors hold audit permissions; administration and audit never meet (ADR-025), so the
confirmation cannot be an administration permission.

**The database enforces it (migration V10):**
- the last key of an enrolled account cannot be deleted;
- a reset request is inserted pending, by a holder of `admin.mfa.reset`, for a staff account;
- only a holder of `identity.reset.confirm` at an office of the person decides it, never the
  requester or the person, within 30 days, and a confirmation must say how the identity was
  checked;
- only that confirmation (a SECURITY DEFINER trigger) or the superuser clears an enrolment:
  the application's own account cannot.

## What this does not cover

- The identity check itself is human: the supervisor still meets or calls the person. The
  system now proves *who* confirmed and records *how*, not that the check was good.
- A compromised application can still name another real supervisor as confirmer, and can
  insert credentials directly (it needs that right to register keys). Signed approvals
  (future ADR) address the first.
- Session revocation after a reset is done by the application (Spring Session), not by the
  database.

## Alternatives considered

- **A second administrator confirms the reset:** two people, but neither knows the person;
  the supervisor is the one who can check identity.
- **Supervisor confirmation outside the system (signed form):** no proof in the audit trail.
- **Removing lost keys automatically after a recovery-code sign-in:** the system cannot know
  which key was lost; the owner can.

## Consequences

- `POST /admin/users/{id}/mfa-reset` answers 202 with a `requestId` (its body has only
  `reason`); supervisors use `/api/v1/identity/reset-confirmations`.
- Each office needs a supervisor holding `identity.reset.confirm` for its staff to be
  recoverable; an office without one cannot reset (fails safe).
