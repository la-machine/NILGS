# 4. Data model

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Database, schema, roles

One database, `snigf`, with one application schema, `snigf`. PostGIS lives in `public`.
`infra/db/init/01-roles.sql` runs once as superuser on an empty volume. It is called by
`infra/db/docker-init-roles.sh` with passwords read from secrets.

| Role | Logs in | Owns / may | Used by |
| --- | --- | --- | --- |
| `postgres` (superuser) | local socket only, as the `postgres` OS user (`peer`) | everything | operators, never the application |
| `snigf_migrator` | TLS + certificate CN `snigf_migrator` + password | owns schema `snigf`; runs DDL | `migrate` service only, at deploy time |
| `snigf_app` | TLS + certificate CN `snigf_app` + password | per-table and per-column grants; READ COMMITTED by default | `app` |
| `snigf_auditor` | TLS + certificate + password | SELECT on audit tables; `audit_verify_chain` | auditors |

`REVOKE CREATE ON SCHEMA public FROM PUBLIC` and
`REVOKE CONNECT ON DATABASE FROM PUBLIC`: nobody creates objects or connects by default.

## Migrations

Flyway, in `backend/src/main/resources/db/migration`, applied in order by `migrate` as
`snigf_migrator`. **An applied migration is never edited**; Flyway rejects a changed
checksum. A change is a new migration.

| Migration | Content |
| --- | --- |
| `V1__identity.sql` | `reject_modification()` guard; `office`, `app_user`, `mfa_recovery_code`, `role_assignment` (no self-grant, office scope, revocation rules), `idempotency_record`; grants |
| `V2__parcel.sql` | `reference_counter` and `next_reference_number()`; `parcel` and append-only `parcel_version` (gapless version numbers, current pointer only moves forward); geometry type `geometry_check_result` and `normalize_parcel_geometry()` (CRS, GeoJSON, type, validity, bounds, vertex limit, transformation to EPSG:4326) |
| `V3__audit.sql` | `audit_event` (hash chain assigned by trigger under an advisory lock), `audit_field()`, `audit_canonical()`, `audit_event_chain()`, append-only triggers, `audit_checkpoint`, `audit_verify_chain()`; grants |
| `V4__function_search_path.sql` | Pins `search_path = pg_catalog, snigf, public, pg_temp` on every function of the schema |
| `V5__sessions.sql` | Spring Session JDBC tables `spring_session` and `spring_session_attributes` (ADR-004); the application role alone may read and write them |

Every new function must carry the same `SET search_path`. pgTAP test `06` enforces it.

## Tables

| Table | Purpose | Mutability for `snigf_app` |
| --- | --- | --- |
| `office` | Demo offices (`is_demo` must be true until gate G1) | SELECT |
| `app_user` | Accounts: email, display name, password hash, account type, MFA fields, lockout counters | SELECT, INSERT, UPDATE of listed columns |
| `mfa_recovery_code` | One-use recovery codes (hashed) | SELECT, INSERT, UPDATE `used_at`, `invalidated_at` |
| `role_assignment` | Role grants with office scope and reasons; revocation is final | SELECT, INSERT, UPDATE of revocation columns |
| `idempotency_record` | Responses replayed for a repeated `Idempotency-Key` | SELECT, INSERT, DELETE |
| `reference_counter` | Counter per (prefix, office, year) | SELECT, INSERT, UPDATE (used by `next_reference_number()`) |
| `parcel` | Stable parcel identity and current version pointer | SELECT, INSERT, UPDATE of `status`, `current_version_no`, `version`; never DELETE |
| `parcel_version` | Immutable geometry history | INSERT, SELECT |
| `audit_event` | Hash-chained audit events | SELECT, INSERT only |
| `audit_checkpoint` | Database copy of signed checkpoints | SELECT, INSERT only |
| `spring_session` | Server-side sessions: id (the cookie value), times, `principal_name` (the user id, indexed so all of a user's sessions can be revoked) | SELECT, INSERT, UPDATE, DELETE; the auditor has no access |
| `spring_session_attributes` | Session values: only `snigf.auth.user`, `snigf.auth.level`, `snigf.auth.at` (plain values, see chapter 3) | SELECT, INSERT, UPDATE, DELETE |

The geometry error codes returned by `normalize_parcel_geometry()` are listed in the
Slice 1 design ("Error format").

## The audit chain

For every inserted event, the trigger `audit_event_chain()`:

1. takes the advisory lock `hashtextextended('snigf.audit_event.chain', 0)`;
2. reads the last row;
3. sets `chain_pos = last + 1`, `occurred_at = clock_timestamp()` and
   `prev_hash = last.hash` (32 zero bytes for the first event);
4. sets `hash = sha256(prev_hash || audit_canonical(row))`.

The client's values for these four columns are ignored.

The canonical encoding (`snigf-audit-v1`) and the checkpoint payload
(`snigf-audit-checkpoint-v1`) are specified in [`docs/audit.md`](../audit.md). The Java
implementation is `ChainedEvent` and `AuditEncoding`; `CanonicalHashParityIT` proves both
sides produce identical bytes.

### Columns of `audit_event`

| Column | Source |
| --- | --- |
| `chain_pos`, `occurred_at`, `prev_hash`, `hash` | database trigger |
| `id` | application, UUIDv7 |
| `actor_type`, `actor_id`, `actor_label`, `actor_roles` | security context or `SystemActor`; constraint `ck_actor` keeps them coherent |
| `action` | `AuditAction` (closed list) |
| `entity_type`, `entity_id`, `entity_ref` | the service |
| `outcome` | `SUCCESS`, `DENIED` or `FAILED` |
| `reason` | the actor's justification (at most 1000 characters) |
| `correlation_id` | server-generated per request (`CorrelationIdFilter`) or per scheduled task |
| `source_ip` | browser address from the proxy, believed only from the admitted proxy |
| `details` | JSON object of plain values, at most `SNIGF_AUDIT_MAX_DETAILS_BYTES` |

### Audit actions

| Action | Emitted when | Module |
| --- | --- | --- |
| `USER_REGISTERED` | an applicant account is created | identity |
| `LOGIN_SUCCEEDED` | a login completes | identity |
| `LOGIN_FAILED` | a login attempt fails | identity |
| `ACCOUNT_LOCKED` | an account is locked after failures | identity |
| `MFA_ENROLLED` | a second factor is registered | identity |
| `MFA_VERIFIED` | a second factor is verified | identity |
| `MFA_RECOVERY_CODE_USED` | a recovery code is consumed | identity |
| `MFA_RESET` | an administrator resets a user's second factor | identity |
| `LOGOUT` | a session ends on request | identity |
| `ROLE_GRANTED` | a role is assigned | identity |
| `ROLE_REVOKED` | a role is revoked | identity |
| `PARCEL_CREATED` | a parcel and its first version are created | parcel |
| `PARCEL_CREATE_REJECTED` | a parcel fails validation | parcel |
| `ACCESS_DENIED` | an authenticated call is refused by an access rule | shared security |
| `AUDIT_EVENTS_READ` | someone reads audit events | audit |
| `AUDIT_VERIFIED` | a chain verification ran (details hold the result) | audit |
| `AUDIT_CHECKPOINT_CREATED` | a checkpoint was signed, or refused (`FAILED`) | audit |

### Verification findings

| Kind | Meaning |
| --- | --- |
| `GAP` | a position is missing: an event was deleted |
| `PREV_HASH` | an event does not link to the previous hash |
| `CONTENT_HASH` | an event's content no longer matches its hash |
| `CHECKPOINT_SIGNATURE` | a checkpoint's signature does not verify, or its copies disagree |
| `CHECKPOINT_MISMATCH` | the chain's hash at a checkpoint differs from the signed head |
| `TRUNCATED` | a checkpoint signs a position the chain no longer reaches |
| `CHECKPOINT_UNREADABLE` | a checkpoint file cannot be parsed |
