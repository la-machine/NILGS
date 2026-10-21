# ADR-022: Sessions hold plain values; the principal is rebuilt on every request

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted, implemented | 2026-10-21 | Pythagore (FLYT) |

## Context

ADR-004 keeps sessions in PostgreSQL with Spring Session. By default:

- Spring Security stores the whole `SecurityContext` (an object graph) in the session.
- Spring Session writes it with Java serialization.
- Spring Session reads every attribute back with Java deserialization.

Two consequences follow:

- **Code execution from the database.** Anyone who can write to the session table could
  plant a serialized "gadget" chain and have it executed by the application.
- **Stale rights.** A session keeps the roles it was created with until it ends, so a
  revoked role or a disabled account keeps working for up to the session's lifetime.

Implementation also showed that Spring Boot 4 no longer applies
`server.servlet.session.cookie.*` to Spring Session. The cookie came out as a plain
`SESSION`, without `Secure`, `HttpOnly` or `SameSite`.

## Decision

1. **The session holds three plain values:** the user id, the authentication level and
   the login time, plus Spring Session's principal index (the user id).
   `SessionSecurityContextRepository` writes them at login and rebuilds the principal from
   the database on every request. Account state and roles are therefore always current.
2. **Deserialization is restricted to `String`, `Long`, `Integer` and `Boolean`:**
   - an `ObjectInputFilter` with depth, reference and size limits;
   - an explicit 4 KB cap;
   - the writer refuses other types.

   A session with a refused value is ended (401) and the event is logged.
3. **Cookies:**
   - The session cookie is defined in code, always `Secure`, `HttpOnly` and path `/`.
   - Its name must carry the `__Host-` prefix outside tests.
   - `SameSite` is `Strict` by default (`Lax` allowed). This is stricter than ADR-004's
     `Lax`; the UI and API share one origin.
   - The CSRF cookie also carries the `__Host-` prefix.
4. **Login:**
   - The session id is replaced at every login (no session fixation).
   - The CSRF token is renewed.
   - An absolute session limit is enforced on top of Spring Session's idle limit.
5. **Answers that reveal nothing:**
   - **Login:** every failure answers 401 `AUTH_FAILED` after the same hashing work; the
     audit trail keeps the real reason.
   - **Registration:** answers 201 whether or not the address existed.
   - **Anonymous calls** to protected endpoints get 401 `AUTH_REQUIRED`. The design said
     403, but 401 tells the interface to log in without revealing more.
6. **Refusals of authenticated users are audited** (`ACCESS_DENIED`). Anonymous refusals
   are not: anyone on the internet could otherwise fill the serialised audit chain. They
   are logged and rate-limited instead. This narrows the Slice 1 design's "every denied
   call".

## Alternatives considered

- **JSON session serialization (Jackson modules):** removes Java deserialization but
  still stores an object graph, and still freezes roles inside the session.
- **Global JVM serial filter:** affects every library in the process; the per-repository
  filter is scoped to sessions.
- **Stateless tokens (JWT):** rejected by ADR-004 (no instant revocation).

## Consequences

- One small database read per authenticated request (account and active roles).
- Revoking a role, disabling an account or resetting a second factor takes effect on the
  next request; deleting a user's sessions (indexed by user id) logs them out everywhere.
- Session ids are stored in clear in `spring_session` (Spring Session's design): read
  access to that table equals session theft until expiry. Only the application role can
  read it; hashing session ids would need a custom repository (pilot backlog).
