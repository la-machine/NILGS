# ADR-019: Officers sign recommendations and decisions with their own key

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

A recommendation or decision on a land case must be attributable to one officer, and
nobody (including a database administrator) may alter it afterwards without detection.

## Decision

- Recording a recommendation or a decision requires a WebAuthn assertion (ADR-016) whose
  challenge is derived from the SHA-256 of the record's canonical form (versioned encoding,
  like the audit chain) plus a server nonce.
- The server verifies the assertion against the officer's registered public key before
  committing, and stores the canonical bytes, the authenticator data, the client data and
  the signature with the record.
- Anyone holding the officer's public key can re-verify the record later, independently
  of the application.
- The server additionally signs the stored record with its OpenBao key (ADR-018), so the
  record proves both "this officer approved it" and "this system recorded it".

## Alternatives considered

- **Server signature only**: proves the system recorded it, not that the officer approved it.
- **Decisions only**: recommendations would rely on the audit chain alone.

## Consequences

- Recording a decision needs the officer's authenticator present; this is intended.
- The canonical encoding gets a parity test like the audit chain.
- Delivered with E-15 (review and decision).
