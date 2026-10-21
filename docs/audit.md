# Audit trail — design and residual risk

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Status: implemented. Database layer: migrations `V3__audit.sql` and
`V4__function_search_path.sql`, tested by pgTAP (`01_grants.sql`, `05_audit.sql`,
`06_function_hardening.sql`) and the concurrent chain test in `infra/scripts/db-test.sh`.
Application layer: module `org.snigf.mvp.audit` (recorder, Java verifier, checkpoints
signed by OpenBao), tested by `ChainVerifierTest`, `AuditRecorderIT`,
`CanonicalHashParityIT` and `AuditChainIT`. The auditor's HTTP API arrives with
authentication (E-06, security step 4). How it was built: `docs/guides/audit-trail.md`.

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
| Verification does not trust the database's functions | `ChainVerifier` recomputes every hash in Java; the encoding is proven identical to SQL | `ChainVerifierTest`, `CanonicalHashParityIT` |
| A rewrite of the whole chain is detected | The recomputed hash at a checkpoint's position differs from its signed head (`CHECKPOINT_MISMATCH`) | `ChainVerifierTest`, `AuditChainIT` |
| Deleting the newest events is detected | A checkpoint signs a position the chain no longer reaches (`TRUNCATED`), even if its database copy was deleted too | `AuditChainIT` |
| A forged or altered checkpoint is not believed | Each copy must verify with the OpenBao checkpoint key, and database and file copies must be identical (`CHECKPOINT_SIGNATURE`) | `ChainVerifierTest` |
| A tampered chain is never signed | Before signing, the events since the last trusted checkpoint are verified from its signed head; on failure the checkpoint is refused and the refusal audited | `AuditChainIT` |
| The actor cannot be chosen by the caller | Actor, roles, address and correlation id come from the security context and the request; an authenticated principal that cannot be attributed is refused | `AuditRecorderIT` |
| An event commits or rolls back with its business change | `record` requires the caller's READ COMMITTED, writable transaction; denials use `recordIndependently` (own transaction) | `AuditRecorderIT` |
| Functions cannot be misled by the caller's `search_path` | Every function pins `pg_catalog, snigf, public, pg_temp` | `06_function_hardening.sql` |

## Canonical encoding (version `snigf-audit-v1`)

`hash = SHA-256(prev_hash || canonical)`, where `canonical` is the UTF-8 encoding of the
fields below joined with `|`. Each field is written as `<UTF-8 byte length>:<value>`,
or `~` when NULL, so a value can never be mistaken for a separator.

`snigf-audit-v1`, chain_pos, id, occurred_at (UTC, `YYYY-MM-DDTHH:MM:SS.ffffffZ`),
actor_type, actor_id, actor_label, actor_roles (JSON array), action, entity_type,
entity_id, entity_ref, outcome, reason, correlation_id, source_ip (host form),
details (PostgreSQL `jsonb` text output).

`ChainedEvent` reproduces this byte for byte; `CanonicalHashParityIT` compares it with
`audit_canonical` on values chosen to break a naive implementation (multi-byte UTF-8,
emoji, separators inside values, control characters, IPv6, nested JSON).

## Checkpoints (version `snigf-audit-checkpoint-v1`)

A checkpoint states that the event at `chainPos` has chain hash `headHash`. The signed
payload is the UTF-8 encoding of

```text
snigf-audit-checkpoint-v1|f(id)|f(chainPos)|f(headHash as lower-case hex)|f(createdAt)
```

with the same `f` as above and `createdAt` as `YYYY-MM-DDTHH:MM:SS.ffffffZ` (UTC). The
signature is Ed25519 (RFC 8032) with the OpenBao key `audit-checkpoint`; the version of
the key that signed is recorded with it.

Each checkpoint is stored twice: a row in `audit_checkpoint` (key id
`audit-checkpoint:v<version>`, raw 64-byte signature) and a file
`checkpoint-<chainPos>.json` in the checkpoint directory:

```json
{
  "format" : "snigf-audit-checkpoint-v1",
  "id" : "01a15dd6-ca5a-77bf-9356-0a2a7b2a8f3a",
  "chainPos" : 1,
  "headHash" : "de8f0325…",
  "createdAt" : "2026-10-21T09:42:00.026657Z",
  "keyVersion" : 1,
  "signature" : "<base64 of the 64-byte signature>"
}
```

`checkpoint-keys.json` beside it lists every version of the public key (raw and PEM).
The directory is a volume of the application container only; files are written once
(flushed, read-only, renamed into place) and never replaced.

**The auditor's routine.** Copy the directory to a separate machine after each
checkpoint. Pin `checkpoint-keys.json` from the first copy and update it only through a
trusted channel when the key rotates; whoever can replace both a checkpoint and the keys
next to it could forge both. Verify offline:

```sh
bash infra/scripts/verify-checkpoint.sh pinned/checkpoint-keys.json copies/checkpoint-*.json
```

then compare a valid head with the live chain as `snigf_auditor`:
`SELECT encode(hash, 'hex') FROM snigf.audit_event WHERE chain_pos = <pos>;`

**Schedule.** A checkpoint is signed daily (`SNIGF_AUDIT_CHECKPOINT_CRON`, P9) when events
were added since the last one; a full verification runs daily
(`SNIGF_AUDIT_VERIFY_CRON`) and at startup. Results are audited (`AUDIT_VERIFIED`,
`AUDIT_CHECKPOINT_CREATED`), logged, and shown by the health contributor `auditChain`
(DOWN after a failure; outside the readiness group, so monitoring alerts without taking
the service down).

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
| Anyone holding both database administration and the checkpoint signing key | Undetectable rewrite | Not detectable; must be prevented by separating these duties (backlog P-04). The key is in OpenBao (ADR-018): the database host never holds it, and only the application's policy may sign |
| Anyone controlling the application host | Can have OpenBao sign a rewritten head, and rewrite the checkpoint directory | Only by the auditor's off-host copies made before the rewrite; the window is the time since the auditor's last copy |

Between the last checkpoint and a rewrite, events can be altered without detection. With
daily checkpoints (P9) that window is at most 24 hours.

This document was corrected during implementation: the original design named only the
superuser. Testing confirmed the schema owner has the same ability. A second correction
(2026-10-21): the functions of V1–V3 resolved names through the caller's `search_path`,
so verification failed for a session without `snigf` on its path, and a caller could
shadow a table with a temporary one. V4 pins every function's path.
