# Audit trail with signed checkpoints

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Nobody, administrators included, may rewrite the history of a land record without it
being detected. Every audited action is appended to a hash chain the application can only
extend. The chain is verified by recomputing it in Java, so a replaced database function
cannot hide anything. Each day, its head is signed with a key held by OpenBao, which the
database never sees. The signed checkpoints are stored outside the database, and the
auditor keeps copies on another machine. Decisions: [ADR-007](../adr/007-audit-hash-chain.md)
(the design), [ADR-018](../adr/018-openbao-key-management.md) (the signing key),
[ADR-021](../adr/021-ddd-layering-inside-modules.md) (the packaging). Guarantees and
residual risk: [`docs/audit.md`](../audit.md).

## Where

The module follows ADR-021: a published API, then `domain`, `application` and
`infrastructure`.

| File | Layer | Role |
| --- | --- | --- |
| `audit/AuditRecorder`, `AuditTrail` | API | What other modules call: record an event; verify, checkpoint, list, download |
| `audit/AuditEvent`, `AuditAction`, `AuditOutcome`, `AuditReceipt` | API | An event as a service describes it: no actor, time or hash fields |
| `audit/AuditedPrincipal`, `SystemActor` | API | How the actor is known: the authenticated principal, or a named scheduled job |
| `audit/Checkpoint`, `AuditEncoding`, `ChainVerification`, `CheckpointCheck`, `MismatchKind` | API | Checkpoints, the byte encodings (also used by offline verifiers), verification results |
| `audit/domain/ChainedEvent` | domain | A stored event and the Java `audit_canonical` (`snigf-audit-v1`) |
| `audit/domain/ChainVerifier` | domain | Recomputes the chain and confronts it with checkpoint evidence; pure Java |
| `audit/domain/CheckpointEvidence` | domain | Merges database and file copies; a checkpoint counts only if every copy is validly signed and identical |
| `audit/domain/Actor`, `AuditDetails` | domain | Actor coherence and safe role names; details hold plain values only |
| `audit/domain/AuditEventRepository`, `CheckpointRepository` | domain | Repository interfaces (append and read only) |
| `audit/application/AuditRecordingService` | application | Records inside the caller's READ COMMITTED transaction, or independently for denials |
| `audit/application/ChainVerificationService` | application | Runs the verifier on one read-only REPEATABLE READ snapshot |
| `audit/application/AuditTrailService` | application | Verification and checkpoint use cases; audits and reports every run |
| `audit/application/AuditRequestContext`, `CheckpointArchive`, `AuditChainMonitor` | application | Ports to the request, the file archive and monitoring |
| `audit/application/AuditProperties` | application | `snigf.audit.*` settings |
| `audit/infrastructure/persistence/Jdbc*Repository` | infrastructure | SQL as `snigf_app` |
| `audit/infrastructure/archive/FileCheckpointArchive` | infrastructure | `checkpoint-<pos>.json` and `checkpoint-keys.json`, written once, atomically, read-only |
| `audit/infrastructure/security/SecurityAuditRequestContext` | infrastructure | Actor from Spring Security or `SystemActor`; address and correlation id from the request |
| `audit/infrastructure/health/AuditChainHealthIndicator` | infrastructure | Health contributor `auditChain` |
| `audit/infrastructure/scheduling/AuditSchedule` | infrastructure | Daily checkpoint and verification; verification at startup |
| `audit/infrastructure/config/AuditConfiguration` | infrastructure | Enables scheduling; refuses disabled jobs outside tests |
| `shared/id/UuidV7`, `shared/web/ClientAddress` | shared | Event ids (ADR-008); the browser address, believed from the proxy only |
| `db/migration/V3__audit.sql`, `V4__function_search_path.sql` | database | Chain trigger, append-only grants and triggers, SQL verifier, pinned `search_path` |
| `infra/scripts/verify-checkpoint.sh` | auditor | Offline verification with bash and OpenSSL |
| `audit/infrastructure/web/AuditController` | infrastructure | The auditor's API: search, verify, checkpoint, download ([roles guide](roles-and-administration.md#endpoints)) |
| `compose.yml` (volume `audit-checkpoints`), `backend/Dockerfile` | runtime | Checkpoint directory, mounted into the application only |
| `test/.../DddLayeringTest` | build | Enforces the layer rules of ADR-021 |

## How it works

### Recording

1. A service calls `recorder.record(AuditEvent.of(PARCEL_CREATED, SUCCESS).withEntity(...))`
   inside its transaction. `record` refuses to run without a transaction, in a read-only
   one, or under an isolation stricter than READ COMMITTED (the chain trigger needs to see
   the latest committed head).
2. `AuditDetails` refuses anything but plain values in `details`, so no domain object (and
   no password hash or geometry inside it) is serialised by accident. The JSON must stay
   under `SNIGF_AUDIT_MAX_DETAILS_BYTES`.
3. `SecurityAuditRequestContext` supplies the actor:
   - `SystemActor` label for scheduled work, else
   - the `AuditedPrincipal` of the security context with its roles, else
   - ANONYMOUS when nobody is authenticated.

   An authenticated principal it cannot attribute is an error. It also supplies the
   server-generated correlation id and the browser address.
4. The INSERT names no position, time or hash. The database trigger takes the chain lock
   and assigns them.
5. Denied or failed attempts use `recordIndependently` (a new transaction), so the record
   survives the rollback of the attempt.

### The browser address

The proxy overwrites `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` with the TCP peer's address
(`header_up` in the Caddyfile), whatever the browser sent. `ClientCertificateFilter` marks a
request as coming from the proxy only after admitting the proxy's certificate; only then
is the header believed (`ClientAddress`). Anything that is not an IP literal is
discarded, and no DNS lookup is ever made.

### Verifying

`ChainVerificationService` opens a read-only REPEATABLE READ transaction so events and
checkpoints come from one snapshot. It then:

1. builds the checkpoint evidence, merging database rows and archive files by id and
   verifying each copy's signature with the OpenBao public keys;
2. runs `ChainVerifier`, which walks the chain in order and checks, for every event,
   position (`GAP`), link (`PREV_HASH`) and content (`CONTENT_HASH`), and compares the hash
   at every checkpoint's position with the signed head (`CHECKPOINT_MISMATCH`);
3. in a full verification, reports a checkpoint beyond the end of the chain as `TRUNCATED`;
4. reports the lowest problem first.

### Signing a checkpoint

Inside one READ COMMITTED transaction:

1. take the checkpoint lock;
2. read the head;
3. verify every event since the newest **trusted** checkpoint, starting from its signed
   head, not from the database;
4. on failure, roll back, audit the refusal in its own transaction, mark `auditChain` DOWN
   and throw;
5. otherwise sign the payload with OpenBao and verify the signature;
6. insert the row and write the file (both before commit);
7. record `AUDIT_CHECKPOINT_CREATED`.

## How to do it again

### Record an event from a module

```java
@Service
class ParcelService {
    private final AuditRecorder audit;   // the published interface, never a class from audit.*

    @Transactional
    UUID create(CreateParcel command) {
        UUID id = ...;                    // business change
        audit.record(AuditEvent.of(AuditAction.PARCEL_CREATED, AuditOutcome.SUCCESS)
                .withEntity("PARCEL", id)
                .withEntityRef(reference)
                .withDetails(Map.of("officeCode", command.officeCode(), "areaM2", area)));
        return id;
    }
}
```

- Add new actions to `AuditAction` with the feature that emits them.
- Put identifiers and short facts in `details`; never secrets, codes, tokens or full
  geometries.
- For a refusal (`ACCESS_DENIED`, `LOGIN_FAILED`), call `recordIndependently` so the record
  survives the rollback.
- The identity module's principal must implement `AuditedPrincipal`.

### Add a module with the same packaging

1. Create `org.snigf.mvp.<module>` with `package-info.java` (`@ApplicationModule`) and the
   published interfaces and value types.
2. Put rules and repository interfaces in `<module>.domain`, plain Java only.
3. Put use cases, transaction boundaries, ports and settings in `<module>.application`.
4. Put adapters in `<module>.infrastructure.<kind>`.
5. Unit-test the domain without Spring (in-memory repositories, as `InMemoryChain`), and
   the adapters with integration tests on PostGIS.
6. `ModularityTest` and `DddLayeringTest` must pass.

### Change the schedule or the checkpoint directory

Edit `SNIGF_AUDIT_CHECKPOINT_CRON`, `SNIGF_AUDIT_VERIFY_CRON` and
`SNIGF_AUDIT_SCHEDULE_ZONE` in `.env`. They are Spring cron expressions with six fields
(second first). Then run `docker compose up -d`. Outside tests, `-` (disabled) is
refused at startup. The container always writes to `/var/lib/snigf/audit-checkpoints`
(volume `audit-checkpoints`); `SNIGF_AUDIT_CHECKPOINT_DIR` is for a backend started from
the IDE.

### Add a SQL function

Every function in the `snigf` schema must carry
`SET search_path = pg_catalog, snigf, public, pg_temp`. pgTAP test `06_function_hardening`
fails otherwise.

## How to verify

```sh
cd backend && ./mvnw verify          # ChainVerifierTest, AuditDomainRulesTest, DddLayeringTest,
                                     # AuditRecorderIT, CanonicalHashParityIT, AuditChainIT
docker compose --profile test run --rm --build dbtest     # pgTAP incl. 05_audit, 06_function_hardening
docker compose logs app | grep -E "Audit chain verified|Audit checkpoint"
docker compose cp app:/var/lib/snigf/audit-checkpoints/. ./audit-copy/
bash infra/scripts/verify-checkpoint.sh ./audit-copy/checkpoint-keys.json ./audit-copy/checkpoint-*.json
```

To see a checkpoint without waiting for the night, start a separate project with a
checkpoint every minute:

```sh
SNIGF_AUDIT_CHECKPOINT_CRON="0 * * * * *" docker compose -p snigf-try up -d --build --wait
```

Stop your main stack's `web` first, because the two projects publish the same port, and
remove the project afterwards with `docker compose -p snigf-try down -v`.

`AuditChainIT` tampers as the PostgreSQL superuser with the append-only triggers disabled:

- it edits an event;
- it edits an event and recomputes its hash;
- it deletes an event;
- it rewrites the whole chain after a checkpoint;
- it deletes the newest events together with their checkpoint row;
- it edits an event, then asks for a checkpoint.

Each case must be detected at the expected position and with the expected kind.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| A checkpoint file could be replaced by a second writer racing the first | `Files.move(..., ATOMIC_MOVE)` is a POSIX `rename()`, which silently replaces an existing target; the existence check before it left a window | Publish with `Files.createLink(target, temp)`, which fails atomically if the name exists (decision D5, `FileCheckpointArchiveTest`) |
| `audit_verify_chain()` as `postgres`: `type "audit_event" does not exist` | The V1–V3 functions resolved names through the caller's `search_path`; pgTAP sets a path containing `snigf`, so tests never saw it | V4 pins `pg_catalog, snigf, public, pg_temp` on every function; pgTAP test 06 checks it and was proven to fail without V4 |
| A pinned path still let a temporary table shadow a real one | PostgreSQL searches the caller's temporary schema **first** unless `pg_temp` is named in the path | Put `pg_temp` last, explicitly |
| `dev-setup.sh`: `.env: line 88: 0: command not found` | The scripts sourced `.env` as shell code; a cron value with spaces became a command (and any line could have run code) | `dev-pki.sh` reads `SNIGF_*=value` lines as data (`load_env`) |
| `illegal unicode escape` at compile time | A Javadoc comment contained the characters backslash-u followed by `00xx`; Java decodes Unicode escapes even inside comments | Describe the escape in words |
| A configuration test passed on Linux and failed on Windows | `/var/lib/...` is not an absolute path on Windows | Build absolute paths with `Path.toAbsolutePath()` in tests |
| A test of the checkpoint refusal failed only when run first | The chain was still empty, so there was nothing to sign | Tests create the events they need; the database is shared |

## Production differences

- The auditor's copies go to independent storage: write-once object storage or an
  external institution. A copy on a laptop is enough only for the demonstration.
- The application host and the database host are administered by different people, and
  neither holds OpenBao's unseal shares (P-04).
- Monitoring alerts on `auditChain` DOWN and on log lines starting with
  `AUDIT CHAIN VERIFICATION FAILED` or `AUDIT CHECKPOINT REFUSED`.
- Checkpoint frequency (P9) may be raised to hourly. That shortens the window during
  which recent events can be rewritten without detection.
