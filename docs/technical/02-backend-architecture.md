# 2. Backend architecture

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## A modular monolith

The backend is one Spring Boot application in one Maven module
([ADR-001](../adr/001-modular-monolith.md)). Each direct sub-package of `org.snigf.mvp`
is an **application module** (a bounded context). Spring Modulith verifies on every build
(`ModularityTest`) that modules use each other only through their root package, their
**published API**.

| Module | Package | Kind | State |
| --- | --- | --- | --- |
| Shared | `org.snigf.mvp.shared` | open module: configuration guard, web filters, security baseline, ids | implemented |
| Keys | `org.snigf.mvp.keys` | technical: key custody through OpenBao | implemented |
| Audit | `org.snigf.mvp.audit` | business: audit trail, verification, checkpoints | implemented |
| Identity | `org.snigf.mvp.identity` | business: accounts, password login, sessions, CSRF, throttling, WebAuthn second factor, recovery codes, roles and permissions, administration | security step 4 implemented ([authentication guide](../guides/authentication-and-sessions.md), [roles guide](../guides/roles-and-administration.md)) |
| Parcel | `org.snigf.mvp.parcel` | business: parcels, versions, geometry | slice 1 |
| Application | `org.snigf.mvp.application` | business: land applications | later slices |
| Conflict | `org.snigf.mvp.conflict` | business: overlaps and disputes | later slices |
| Document | `org.snigf.mvp.document` | business: supporting documents | later slices |
| Review | `org.snigf.mvp.review` | business: reviews and decisions | later slices |
| Payment | `org.snigf.mvp.payment` | business: fees and payment callbacks | later slices |
| Reporting | `org.snigf.mvp.reporting` | business: statistics | later slices |

`shared` is declared `Type.OPEN`: every module may use it, and it contains no business
rules.

## Inside a module: DDD layers

Every business module has four packages, and dependencies point inwards only
([ADR-021](../adr/021-ddd-layering-inside-modules.md)):

```text
org.snigf.mvp.<module>                    published API: interfaces + value types
org.snigf.mvp.<module>.domain             rules, entities, value objects, repository interfaces
                                          (plain Java: no Spring, JDBC, Jackson, files, logging)
org.snigf.mvp.<module>.application        use cases, transactions, ports, settings
org.snigf.mvp.<module>.infrastructure.*   adapters: persistence, archive, security, scheduling,
                                          health, config

infrastructure ──► application ──► domain ──► published API
```

`DddLayeringTest` (ArchUnit) enforces the arrows. A domain rule can therefore be
unit-tested in milliseconds with an in-memory repository, and each adapter is
integration-tested against real PostGIS.

### Example: the audit module

| Layer | Classes |
| --- | --- |
| Published API | `AuditRecorder`, `AuditTrail`, `AuditEvent`, `AuditAction`, `AuditOutcome`, `AuditReceipt`, `AuditedPrincipal`, `SystemActor`, `Checkpoint`, `AuditEncoding`, `ChainVerification`, `CheckpointCheck`, `MismatchKind`, `AuditIntegrityException` |
| Domain | `ChainedEvent` (canonical encoding and hash), `ChainVerifier`, `CheckpointEvidence`, `Actor`, `AuditDetails`, `ChainHead`, `UnreadableCheckpoint`, `AuditEventRepository`, `CheckpointRepository` |
| Application | `AuditRecordingService`, `ChainVerificationService`, `AuditTrailService`, ports `AuditRequestContext`, `CheckpointArchive`, `AuditChainMonitor`, settings `AuditProperties` |
| Infrastructure | `persistence/JdbcAuditEventRepository`, `persistence/JdbcCheckpointRepository`, `archive/FileCheckpointArchive`, `security/SecurityAuditRequestContext`, `health/AuditChainHealthIndicator`, `scheduling/AuditSchedule`, `config/AuditConfiguration` |

The keys module is technical: it adapts OpenBao and has no domain model, so it keeps a
single `internal` package (`OpenBaoClient`, `OpenBaoKeyService`, `EphemeralKeyService`,
`KeyServiceSelfTest`, `KeysHealthIndicator`, `KeysConfiguration`).

## Startup sequence

What happens between `java -jar` and the first request. A failure at any step stops the
process with the message shown, before the web server accepts a connection.

1. **Entry point** (`backend/docker-entrypoint.sh`, container only). It copies the
   database client key to the tmpfs `/run/snigf-keys` with mode 0600, then starts Java.
   The PostgreSQL driver refuses group- or world-readable keys, and Docker Desktop mounts
   secrets as 0777.
2. **Configuration.** Spring reads `application.yaml`, the active profile file and its
   profile group:
   - `dev`, `staging` and `prod` each add `transport-security` and `key-custody`;
   - `test` adds nothing.

   It then imports `.env` (when run from the IDE) and the secrets directory as a config
   tree, and binds and validates `snigf.*`. A missing or invalid value fails here.
3. **`EnvironmentGuard`**, which checks every unsafe combination: profile, database
   role, TLS, mutual TLS, management address, OpenBao URL and TLS, key provider,
   duplicate key names, password presence. Failure: `Unsafe configuration, refusing to
   start:` followed by one line per violation.
4. **`AuditConfiguration`** refuses disabled audit schedules outside tests and a relative
   checkpoint directory outside development. Failure: `Unsafe audit configuration,
   refusing to start:`. **`IdentityConfiguration`** refuses, outside tests, weak Argon2
   cost, a password minimum under 12, cookies without the `__Host-` prefix, a `SameSite`
   other than Strict or Lax, and an idle limit above the absolute limit. Failure:
   `Unsafe identity configuration, refusing to start:`.
5. **`KeysConfiguration`** builds the OpenBao client: a JDK HTTP client restricted to
   the `snigf-openbao` SSL bundle (TLS 1.3, client certificate, host name check, no
   redirects).
6. **`KeyServiceSelfTest`** logs in to OpenBao with the certificate. For every key it
   checks the type and custody flags, then signs and verifies, seals and opens, MACs and
   verifies, and confirms each operation rejects tampered input. Failure:
   `KeyServiceException: key custody self-test failed: ...`.
7. **`FileCheckpointArchive`** creates and restricts the checkpoint directory (0700)
   and checks that it is writable.
8. **Web server** starts on `SNIGF_APP_PORT` with the `snigf-server` SSL bundle (TLS 1.3,
   client certificate required). Management starts on `127.0.0.1:SNIGF_MANAGEMENT_PORT`.
9. **Ready.** `ApplicationReadyEvent` fires and, if `SNIGF_AUDIT_VERIFY_ON_STARTUP`, a
   virtual thread verifies the whole audit chain. The readiness group (`readinessState`,
   `db`, `keys`) turns UP, the container health check passes, and Compose starts `web`.

## Request lifecycle

A browser call to `GET https://localhost:47000/api/v1/...`:

1. **Caddy** terminates TLS 1.3 and adds the security headers (HSTS, CSP, frame-ancestors,
   ...).
2. **Caddy → app.** For `/api/*`, Caddy opens mutual TLS 1.3 to `app` with the
   `web_client` certificate. It verifies the app's certificate against the SNIGF CA and
   the name `app`, and writes the browser's address into
   `SNIGF_PROXY_CLIENT_ADDRESS_HEADER`, overwriting any client value.
3. **Tomcat** requires a client certificate that chains to the SNIGF CA; without one,
   the TLS handshake fails.
4. **`CorrelationIdFilter`** (first filter) generates a server-side UUID and puts it in
   the logging context (MDC `correlationId`), a request attribute and the response header
   `X-Correlation-Id`. A client-supplied id is never used.
5. **`ClientCertificateFilter`** admits only allowed certificate CNs. Otherwise it
   answers 403 `TRANSPORT_CLIENT_REJECTED`. On admission it marks the request so
   `ClientAddress` may believe the proxy's address header.
6. **Spring Session** loads the session named by the `__Host-snigf-session` cookie from
   PostgreSQL. **Spring Security** then applies `SecurityConfiguration`:
   - `SessionSecurityContextRepository` reads the user id, level and login time (plain
     values only), enforces the absolute limit, and rebuilds the principal
     (`AuthenticatedUser`) from the database;
   - the CSRF filter checks the `X-XSRF-TOKEN` header on every state-changing call;
   - access rules apply: public health, CSRF, login and registration; any session for
     `/auth/session` and `/auth/logout`; a **full** session for everything else under
     `/api/v1`, plus the method's `@PreAuthorize`, which is always
     `@access.can(authentication, '<permission>')` or `@access.canAt(..., officeCode)`
     (ADR-025): a role permission, or else a special grant in force, whose use `Access`
     takes before the controller runs; everything else denied.

   Without a session the answer is 401 `AUTH_REQUIRED`. A refused authenticated call
   gets 403 `ACCESS_DENIED` (or `CSRF_INVALID`) and an `ACCESS_DENIED` audit event.
7. **Controller → service.** The service runs the business transaction and calls
   `AuditRecorder.record(...)` inside it. The actor, roles, address and correlation id
   come from the security context and the request.
8. **Special-grant uses** are settled by `SpecialGrantUseInterceptor` when the response is
   complete: kept and audited on success, given back and audited on failure.
9. **Errors** are RFC 9457 problem details with stable codes. Stack traces, messages
   and exception names are never returned to clients.

## Cross-cutting conventions

| Concern | Convention | Where |
| --- | --- | --- |
| Identifiers | UUIDv7 (time-ordered, random tail) | `shared.id.UuidV7`, [ADR-008](../adr/008-uuidv7-keys.md) |
| Time | Database clock for anything recorded (`clock_timestamp()`), UTC everywhere | V3 trigger |
| Transactions | Services own them; audit events join them (READ COMMITTED) | `AuditRecorder` |
| Errors | RFC 9457, stable `code`, no internals | [ADR-011](../adr/011-problem-details.md) |
| Endpoints | Controllers in `<module>.infrastructure.web`; each method has `@PreAuthorize` or `@PublicEndpoint` | `EndpointAnnotationArchTest` |
| Principal | `@AuthenticationPrincipal AuthenticatedUser`, rebuilt per request; never stored in the session | [ADR-022](../adr/022-plain-value-sessions.md) |
| Logging | Plain text in `dev` and `test`; ECS JSON in `staging` and `prod`; never secrets, codes, session ids or full GeoJSON | `application*.yaml` |
| Keys | Only through `KeyService`, by purpose (`SigningKey`, `EncryptionKey`, `MacKey`) | [key custody guide](../guides/key-custody-openbao.md) |
| Settings | `.env` → `SNIGF_*` → validated `snigf.*` records; no silent defaults | [chapter 5](05-configuration-reference.md) |
| Virtual threads | Enabled (`spring.threads.virtual.enabled`) | `application.yaml` |

## Health contributors

All are served on the loopback management port only.

| Contributor | Meaning | In readiness | Bean |
| --- | --- | --- | --- |
| `readinessState` | Spring's own state | yes | Spring Boot |
| `db` | A connection to PostgreSQL as `snigf_app` works | yes | Spring Boot |
| `keys` | OpenBao answers and the token is valid (token self-lookup) | yes | `KeysHealthIndicator` |
| `auditChain` | Latest full verification of the audit chain; UNKNOWN before one ran, DOWN after a failure | **no**: a broken chain is an incident, not a reason to go offline | `AuditChainHealthIndicator` |
| `diskSpace`, `ping`, `livenessState` | Spring Boot defaults | liveness/none | Spring Boot |

Details are hidden (`show-details: never`); diagnose with the commands in
[chapter 8](08-operations-and-troubleshooting.md).
