# Audit trail — design and residual risk

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Status: implemented at the database layer (migration `V3__audit.sql`), tested by
`backend/src/test/sql/t/05_audit.sql`, `01_grants.sql` and the concurrent chain test
in `infra/scripts/db-test.sh`. Checkpoint signing and the Java verifier are not yet
implemented (slice 1, application layer).

## What is guaranteed

| Property | Mechanism | Test |
| --- | --- | --- |
| The application can only append | `snigf_app` has `SELECT, INSERT` only; no `UPDATE`, `DELETE`, `TRUNCATE` | `01_grants.sql` |
| Even the schema owner cannot update or delete by accident | Row and statement triggers raise `restrict_violation` | `01_grants.sql` test 14 |
| The client cannot choose position, time or hash | `BEFORE INSERT` trigger overwrites `chain_pos`, `occurred_at`, `prev_hash`, `hash` | `05_audit.sql` |
| Positions are contiguous and follow commit order under concurrency | Transaction-scoped advisory lock; chain position assigned under the lock | `db-test.sh` (8 clients, ~10% rollbacks) |
| An edited event is detected | `audit_verify_chain` recomputes each hash | `05_audit.sql` (`CONTENT_HASH`) |
| An edited event whose hash was recomputed is detected | The next event's `prev_hash` no longer matches | `05_audit.sql` (`PREV_HASH`) |
| A deleted event is detected | Positions must be contiguous | `05_audit.sql` (`GAP`) |

## Canonical encoding (version `snigf-audit-v1`)

`hash = SHA-256(prev_hash || canonical)`, where `canonical` is the UTF-8 encoding of the
fields below joined with `|`. Each field is written as `<UTF-8 byte length>:<value>`,
or `~` when NULL, so a value can never be mistaken for a separator.

`snigf-audit-v1`, chain_pos, id, occurred_at (UTC, `YYYY-MM-DDTHH:MM:SS.ffffffZ`),
actor_type, actor_id, actor_label, actor_roles (JSON array), action, entity_type,
entity_id, entity_ref, outcome, reason, correlation_id, source_ip (host form),
details (PostgreSQL `jsonb` text output).

The Java verifier must reproduce this byte for byte; a parity test is required before
it is used.

## Operational requirement

Audit inserts must run under `READ COMMITTED` (set as the default for `snigf_app` by
`01-roles.sql`). Under `REPEATABLE READ` a stale read yields a duplicate position, which
the primary key rejects: the transaction fails, the chain never forks.

## Residual risk

Tamper *evidence* is not tamper *prevention*. These actors can still alter history:

| Actor | Capability | Detected by |
| --- | --- | --- |
| PostgreSQL superuser | Disable triggers, rewrite rows and recompute every hash | Only by comparison with signed checkpoints held off-host |
| Schema owner `snigf_migrator` | Owners can disable triggers on their own tables | Same. This role's credentials must be used only during deployments and kept by a different person than the audit-key holder |
| Anyone holding both database administration and the checkpoint signing key | Undetectable rewrite | Not detectable; must be prevented by separating these duties (backlog P-04) |

Between the last checkpoint and a rewrite, events can be altered without detection. With
daily checkpoints (P9) that window is at most 24 hours.

This document was corrected during implementation: the original design named only the
superuser. Testing confirmed the schema owner has the same ability.
