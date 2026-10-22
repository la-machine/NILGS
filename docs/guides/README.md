# How-to guides

*Copyright (c) 2026 FLYT. Author: Pythagore.*

How each part of SNIGF/NILGS was built, where it lives, and how to redo or extend it.
The [technical manual](../technical/README.md) is the entry point; it summarises each part
and links here for the detail.
Architecture decisions explain **why** ([`docs/adr/`](../adr/README.md)); these guides
explain **how**. [`CHANGELOG.md`](../../CHANGELOG.md) lists **what** changed and when.

| Guide | Covers | Decisions |
| --- | --- | --- |
| [Environment, settings and secrets](environment-and-secrets.md) | `.env`, secret files, ports, adding a setting or a secret, signing files | ADR-014, ADR-015 |
| [Transport security and hardening](transport-security.md) | Private PKI, TLS 1.3 on every hop, mutual TLS, network segmentation, container hardening, the probes | ADR-017, ADR-020 |
| [Key custody with OpenBao](key-custody-openbao.md) | OpenBao service, unsealing, provisioning, keys and policy, `KeyService`, adding or rotating a key | ADR-018 |
| [Authentication and sessions](authentication-and-sessions.md) | Registration, password login, peppered Argon2id, sessions in PostgreSQL, CSRF, lockout, throttling, the WebAuthn second factor, recovery codes, the sign-in screens | ADR-004, ADR-016, ADR-022, ADR-023 |
| [Roles, permissions and administration](roles-and-administration.md) | Permission catalogue, baseline and custom roles, four-eyes role assignments, special grants, the approval workflow enforced by the database, separation of duties, second-factor reset, the auditor's API | ADR-024, ADR-025, ADR-026 |
| [End-to-end testing in a real browser](end-to-end-testing.md) | Chromium in Compose against the running stack, the development CA, virtual security keys, the four-eyes and special-grant journeys | ADR-016, ADR-020, ADR-026 |
| [Audit trail with signed checkpoints](audit-trail.md) | Recording events, Java chain verification, OpenBao-signed checkpoints, offline verification, DDD packaging of a module | ADR-007, ADR-021 |

## How every guide is written

Each guide has the same parts, so you can jump straight to the one you need:

1. **What and why**: the guarantee, in one paragraph, with the decision record.
2. **Where**: every file involved and its role.
3. **How it works**: the sequence at runtime.
4. **How to do it again**: numbered steps, for this project or a similar one.
5. **How to verify**: commands and the expected result.
6. **Pitfalls we hit**: real failures met while building it, their cause and the fix.
7. **Production differences**: what changes beyond development.

When you change a part, update its guide and add a `CHANGELOG.md` entry in the same change.
