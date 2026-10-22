# Authentication and sessions

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

People prove who they are with a password, and staff also with a second factor, before
they can touch a land record. The following must all hold:

- **Passwords:** never recoverable from the database, even with the database dump.
- **No account discovery:** registration and login reveal nothing about which accounts
  exist.
- **Brute force:** guessing is throttled per client address, and an account locks after
  repeated failures.
- **Revocation:** sessions live on the server and can be revoked at once.
- **CSRF:** another site cannot make a logged-in browser act.
- **Audit:** every attempt, successful or not, lands in the audit trail.

Decisions: [ADR-004](../adr/004-server-side-sessions.md) (sessions),
[ADR-016](../adr/016-webauthn-staff-authentication.md) (second factor),
[ADR-022](../adr/022-plain-value-sessions.md) (plain-value sessions, cookies, answers),
[ADR-018](../adr/018-openbao-key-management.md) (the pepper key). Rules P5 (session
limits), P6 (lockout and throttling) and P7 (password policy) come from the Slice 1 design.

**Status:**

- **Security step 4a:** password login, sessions, CSRF, lockout, throttling and auditing.
- **Security step 4b:** the WebAuthn second factor for staff (two authenticators), the
  recovery codes and the sign-in screens ([ADR-023](../adr/023-webauthn-second-factor-implementation.md)).
- **Still to come (step 4c):** role administration, the administrator-assisted reset,
  and the decision on a TOTP fallback.

## Where

The identity module follows [ADR-021](../adr/021-ddd-layering-inside-modules.md).

| File | Layer | Role |
| --- | --- | --- |
| `identity/AuthenticatedUser`, `AccountType`, `AuthLevel` | API | The principal of every authenticated request (implements `AuditedPrincipal`) |
| `identity/domain/EmailAddress` | domain | Normalised, validated address |
| `identity/domain/PasswordPolicy` | domain | P7: length, control characters, common passwords, account name |
| `identity/domain/LockoutPolicy` | domain | P6: failures and lock duration |
| `identity/domain/UserAccount`, `UserRepository`, `PasswordHasher` | domain | Account and the ports for storage and hashing |
| `identity/application/RegistrationService`, `LoginService` | application | Use cases; every attempt audited |
| `identity/application/IdentityProperties` | application | `snigf.identity.*` settings |
| `identity/infrastructure/persistence/JdbcUserRepository` | infrastructure | Atomic counters; `ON CONFLICT` registration |
| `identity/infrastructure/crypto/PepperedArgon2PasswordHasher` | infrastructure | OpenBao pepper + Argon2id, bounded concurrency |
| `identity/infrastructure/security/SecurityConfiguration` | infrastructure | The filter chain (default deny, CSRF, 401/403) |
| `identity/infrastructure/security/SessionSecurityContextRepository` | infrastructure | Session values, principal rebuilt per request, absolute limit |
| `identity/infrastructure/security/AuditingAccessDeniedHandler` | infrastructure | 403 and `ACCESS_DENIED` audit |
| `identity/infrastructure/session/SessionSerializationConfiguration` | infrastructure | Plain values only in sessions |
| `identity/infrastructure/config/IdentityConfiguration` | infrastructure | Policies as beans, session cookie, startup checks |
| `identity/infrastructure/web/AuthController`, `AuthExceptionHandler`, `AuthRateLimiter` | infrastructure | Endpoints, error codes, throttling |
| `identity/domain/RecoveryCode`, `RecoveryCodeHasher`, `SecondFactorRepository` | domain | Recovery codes and the ports for their storage and hashing |
| `identity/application/SecondFactorService`, `WebAuthnCeremonies` | application | Enrolment, proof at login, recovery; the WebAuthn port |
| `identity/infrastructure/webauthn/SpringWebAuthnCeremonies`, `CeremonyStore` | infrastructure | Relying party on Spring Security / webauthn4j; single-use server-side ceremonies |
| `identity/infrastructure/crypto/KeyedRecoveryCodeHasher` | infrastructure | HMAC of recovery codes with the OpenBao pepper (domain-separated) |
| `identity/infrastructure/persistence/JdbcSecondFactorRepository` | infrastructure | Enrolment flag and recovery codes |
| `identity/infrastructure/web/MfaController` | infrastructure | `/api/v1/auth/mfa/*` |
| `db/migration/V6__webauthn.sql` | database | `user_entities`, `user_credentials`, enrolment trigger |
| `frontend/src/features/auth/` (`api.ts`, `webauthn.ts`, `screens/`), `app/App.tsx`, `shared/api/client.ts`, `shared/i18n/` | interface | Sign-in, registration, enrolment, second factor, recovery, codes, session screens (FR/EN); the shell follows the server's `nextStep` |
| `infra/scripts/dev-make-staff.sh` | development | Turns a registered account into staff at a demo office (dev profile only, audited) |
| `shared/security/PublicEndpoint` | shared | Marks deliberately public endpoints; `EndpointAnnotationArchTest` checks every endpoint |
| `resources/identity/common-passwords.txt` (+ `.NOTICE.md`) | data | 29,982 common passwords of 12+ characters, checksum-pinned |
| `db/migration/V5__sessions.sql` | database | Spring Session tables |
| `infra/proxy/Caddyfile` | proxy | Passes the browser's address for throttling and audit |

## How it works

### Endpoints (`/api/v1/auth`)

| Method and path | Access | Request | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET /csrf` | public | none | 200 `{cookieName, headerName}`, sets the token cookie | none |
| `POST /register` | public, throttled | `email`, `displayName`, `password`, `preferredLanguage` (`fr`/`en`) | 201, whether or not the address existed | 422 `PASSWORD_WEAK` or `VALIDATION_FAILED` with `violations`; 429 `RATE_LIMITED`; 503 `BUSY` |
| `POST /login` | public, throttled | `email`, `password` | 200 `{nextStep: DONE \| MFA_ENROLMENT \| MFA_CHALLENGE}`, new session | 401 `AUTH_FAILED` (always the same); 429; 503 |
| `GET /session` | any session | none | 200 user id, name, type, roles, `authLevel`, `nextStep`, `identityVerified: false` | 401 `AUTH_REQUIRED` |
| `POST /logout` | any session | none | 204, session deleted on the server | 401 |
| `POST /mfa/webauthn/registration/options` | staff: password session before enrolment, FULL after | none | 200 WebAuthn creation options (JSON) | 403 `MFA_NOT_ALLOWED` |
| `POST /mfa/webauthn/registration` | same | `{"publicKey": {"credential": <PublicKeyCredential JSON>, "label": "..."}}` | 200 `{registered, required, enrolmentComplete, nextStep, recoveryCodes?}`; at completion a new FULL session | 422 `WEBAUTHN_REJECTED`; 403 |
| `POST /mfa/webauthn/assertion/options` | staff password session, enrolled | none | 200 WebAuthn request options (JSON) | 403 |
| `POST /mfa/webauthn/assertion` | same, throttled | `<PublicKeyCredential JSON>` | 200 `{nextStep: DONE}`, new FULL session | 401 `SECOND_FACTOR_INVALID` (session ended if the account locks); 429 |
| `POST /mfa/recovery` | same, throttled | `{code}` | 200 `{nextStep, remainingRecoveryCodes, replaceAuthenticator: true}`, new FULL session | 401 `SECOND_FACTOR_INVALID`; 429 |

Every state-changing call needs the CSRF header. Other `/api/v1` paths need a **full**
session and their own `@PreAuthorize`; anything else is denied.

### A login

1. **CSRF token.** The page calls `GET /csrf`. The answer sets
   `__Host-snigf-xsrf` (Secure, path `/`, SameSite Strict, readable by the page).
2. **Request.** The page posts `/login` with the header `X-XSRF-TOKEN: <cookie value>`.
   Without it: 403 `CSRF_INVALID`, which also prevents login CSRF.
3. **Throttle.** `AuthRateLimiter` takes a token from the bucket of the client address
   (20 per minute). The address is the one the proxy wrote, never the browser's own
   header. When the bucket is empty: 429 with `Retry-After`.
4. **Checks.** `LoginService` normalises the address and loads the account.
   - **Unknown address, disabled or locked account:** it spends the same hashing time
     on a decoy hash, answers failure, and audits `LOGIN_FAILED` with the real reason.
   - **Wrong password:** one atomic UPDATE counts the failure. At the fifth it locks the
     account for 15 minutes and resets the counter, and `ACCOUNT_LOCKED` is audited.
   - **Correct password:**
     - the counter is cleared;
     - the hash is upgraded if its parameters or pepper version are outdated;
     - the level is `FULL` for applicants and `PASSWORD` for staff;
     - `LOGIN_SUCCEEDED` is audited with the user as actor.
5. **New session.** `SessionSecurityContextRepository.start` invalidates any old session
   and creates a new one (new id). It stores three plain values, and the CSRF token is
   renewed.
6. **Session cookie.** Spring Session writes the row and sets `__Host-snigf-session`
   (Secure, HttpOnly, path `/`, SameSite Strict).

### The second factor (staff)

**Enrolment.** It happens the first time a staff member logs in, with `nextStep`
`MFA_ENROLMENT`.

1. The page asks for creation options. The server starts a registration ceremony, keeps
   its options in `CeremonyStore`, and puts only the ceremony id in the session. The
   options require user verification, exclude the account's existing keys, and name
   the user by account id.
2. The browser calls `navigator.credentials.create`. The authenticator checks the
   PIN or biometric and creates a key pair; the private key never leaves it.
3. The page posts the credential and a label. webauthn4j verifies challenge, origin,
   relying-party id, user verification and the attestation format, and the public key
   is stored. `MFA_ENROLLED` is audited.
4. When `SNIGF_WEBAUTHN_MIN_AUTHENTICATORS` keys are registered:
   - `app_user.mfa_enrolled_at` is set (the V6 trigger checks a credential exists);
   - ten recovery codes are generated and returned once (only their HMACs are stored);
   - the session is replaced by a FULL one, and the CSRF token is renewed.

**Every later login.** `nextStep` is `MFA_CHALLENGE`.

1. The request options allow only the account's credentials and require user
   verification.
2. `navigator.credentials.get` signs the challenge.
3. The server verifies the signature, the origin, user verification and the counter,
   checks that the credential's owner is the account, and audits `MFA_VERIFIED`.
4. The session becomes FULL with a new id.

**Recovery.** The user types a code. Case, spaces, dashes and look-alikes are forgiven.
The code is matched by HMAC, consumed atomically and audited as
`MFA_RECOVERY_CODE_USED`, and the interface asks for a replacement authenticator.

**Failures.** A wrong assertion or code counts toward the lockout, is audited as
`LOGIN_FAILED` (reason `SECOND_FACTOR_INVALID` or `RECOVERY_CODE_INVALID`), and ends
the session when the account locks.

### Every later request

`SessionSecurityContextRepository` runs once per request:

1. reads the three values (refusing anything that is not a plain value);
2. checks the absolute limit (8 h since login);
3. reloads the account and its active roles.

A disabled or deleted account ends the session. Authorities:

| Level | Authorities |
| --- | --- |
| `PASSWORD` (staff, second factor due) | `AUTH_LEVEL_PASSWORD` only |
| `FULL` | `AUTH_LEVEL_FULL` and `ROLE_<role>` per active role |

Spring Session expires idle sessions (30 min) and deletes expired rows on
`SNIGF_SESSION_CLEANUP_CRON`.

### Password hashing

`stored = "$snigf-pepper$v=" + v + "$" + Argon2id(base64(HMAC-SHA256(pepper_v, password)))`

- The pepper is the OpenBao key `password-pepper`. The application sends the password
  over mutual TLS and receives the MAC; the key never leaves OpenBao.
- Argon2id runs with a random 16-byte salt, a 32-byte output and the cost from `.env`
  (development and the OWASP minimum: 19,456 KiB, 2 passes, 1 lane).
- At most `SNIGF_PASSWORD_MAX_CONCURRENT_HASHES` run at once, so a flood of logins cannot
  exhaust memory. Callers wait up to `SNIGF_PASSWORD_HASH_QUEUE_TIMEOUT`, then get 503.

A database dump alone is useless for guessing, because the pepper is missing. OpenBao
alone holds no hash.

## How to do it again

### Add a protected endpoint

```java
@RestController
@RequestMapping(SecurityConfiguration.API + "/parcels")
class ParcelController {
    @PostMapping
    @PreAuthorize("hasRole('CADASTRAL_OFFICER')")     // or a permission check (security step 4c)
    ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedUser user, ...) { ... }
}
```

- **Placement:** controllers live in `<module>.infrastructure.web`
  (`EndpointAnnotationArchTest`).
- **Access rule:** no `@PreAuthorize` and no `@PublicEndpoint` fails the build.
- **Public endpoints** need both the annotation (with a reason) and a `permitAll()` rule
  in `SecurityConfiguration`.
- **Auditing:** record events in the service (`AuditRecorder`). The principal is
  attributed automatically.

### Tune the rules

All are settings ([configuration reference](../technical/05-configuration-reference.md)):

- `SNIGF_PASSWORD_*`
- `SNIGF_LOGIN_*`
- `SNIGF_RATE_*`
- `SNIGF_SESSION_*`
- `SNIGF_CSRF_*`

Outside tests the application refuses to start with:

- Argon2 below the OWASP minimum;
- a minimum length under 12;
- cookies without `__Host-`;
- `SameSite` other than Strict or Lax;
- an idle limit above the absolute limit.

### Try the second factor in a browser (development)

1. Trust the development CA once (README "Run the stack"), then open
   <https://localhost:47000>.
2. Register an account in the interface, then make it staff:
   `bash infra/scripts/dev-make-staff.sh you@demo.invalid CADASTRAL_OFFICER`.
3. Provide two authenticators, for example:
   - Chrome DevTools, "More tools", "WebAuthn", "Enable virtual authenticator
     environment", then add two authenticators (ctap2, user verification supported);
   - or Windows Hello and a security key.
4. Log in: register both authenticators and store the recovery codes. Then log out, log
   in again and use either key, or a recovery code.

### Refresh the common-password list

Follow `backend/src/main/resources/identity/common-passwords.NOTICE.md`, then update the
pinned checksum and count in `IdentityInfrastructureTest`.

### Reuse in another project

Copy the identity module and keep its layers:

- the domain rules;
- the `PasswordHasher` port with the peppered Argon2id adapter;
- the plain-value session repository and its deserialization filter;
- the code-defined session cookie;
- the CSRF SPA configuration;
- the uniform 401;
- the throttle;
- the startup checks.

Replace `KeyService` with your key manager's HMAC.

## How to verify

```sh
cd backend && ./mvnw verify        # AuthFlowIT (13), IdentityRulesTest, IdentityInfrastructureTest,
                                   # SessionSecurityContextRepositoryTest, EndpointAnnotationArchTest
docker compose --profile test run --rm --build dbtest           # 07_sessions
docker compose --profile test run --rm --build security-probe   # CSRF cookie, login CSRF, uniform 401
docker compose exec -u postgres db psql -d snigf -c \
  "SELECT action, outcome, details->>'reason' FROM snigf.audit_event ORDER BY chain_pos DESC LIMIT 10"
```

`AuthFlowIT` proves, through the real filter chain and Spring Session on PostgreSQL:

- the CSRF cookie attributes, and that CSRF is required even for login;
- the full applicant journey with the session cookie attributes;
- a new session id at login, so a planted session is useless;
- registration that reveals nothing;
- weak passwords refused with their reasons;
- identical answers for every failure, with the real reasons kept in the audit trail;
- lockout after 5 failures, even with the right password;
- peppered Argon2id storage;
- staff stopped at the second factor, with an audited 403;
- a disabled account losing its session at the next request;
- throttling per address;
- a planted serialized object refused, ending the session;
- 401 for anonymous API calls.

`MfaFlowIT` drives emulated FIDO2 authenticators (webauthn4j-test) through the real
endpoints. It proves that:

- enrolment of two keys completes with recovery codes and a FULL session;
- either key works at the next login;
- a response from another origin (phishing) or a replayed response is refused and counts
  toward the lockout;
- another account's key is refused even when it signs the right challenge;
- a recovery code works once;
- five wrong second factors lock the account and end the session;
- a password session cannot add keys once enrolled;
- applicants have no second factor;
- a ceremony belongs to the session that started it;
- only public keys are stored, and options require user verification.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| Session cookie named `SESSION`, without Secure, HttpOnly or SameSite | Spring Boot 4 no longer applies `server.servlet.session.cookie.*` to Spring Session | Define the `CookieSerializer` bean in code (`IdentityConfiguration.sessionCookie`); Secure and HttpOnly are not settings |
| The CSRF token returned in the body was refused in the header | In single-page-application mode the request-attribute token is BREACH-masked; the header must carry the raw cookie value | `GET /csrf` returns the cookie and header names; the page reads the cookie |
| A planted session value caused a 500 on every request | The deserialization filter refused it (correct) but the exception escaped | Catch it while reading the session, end the session, answer 401 |
| A 10,000-character session string passed the filter's 4 KB limit | `ObjectInputFilter`'s `maxbytes` is not consulted for plain strings | Refuse values larger than 4 KB before deserializing |
| `loadDeferredContext` does not compile | Spring Security 7 returns `DeferredSecurityContext`, not `Supplier` | Implement `DeferredSecurityContext` (memoised) |
| The mock response shows no `SameSite` on the CSRF cookie | `MockHttpServletResponse` does not print the attribute | Assert `getCookie(...).getAttribute("SameSite")`; the probe checks the real header |
| `cannot execute UPDATE in a read-only transaction` when starting a registration | The first registration creates the WebAuthn user entity | `beginRegistration` runs in a writable transaction |
| `violates check constraint "ck_mfa_enrolled_has_secret"` | V1 required a TOTP secret for any enrolment | V6 replaces it with a trigger accepting a WebAuthn credential or a TOTP secret |
| Spring Security has no WebAuthn classes in `spring-security-web` 7.x | WebAuthn moved to the module `spring-security-webauthn` | Add that dependency (webauthn4j comes with it) |
| `webauthn4j-test` breaks dependency convergence | It brings Bouncy Castle 1.85 while the application uses 1.86 | Exclude its Bouncy Castle and add `bcpkix-jdk18on` at the application's version, test scope |
| The headless browser refuses `https://localhost:47000` (`ERR_CERT_AUTHORITY_INVALID`) | The development CA is not trusted on the machine | Trust it for your user (README), or test the ceremonies with the emulators |
| The 10k common-password list adds nothing | Only 10 of its entries have 12+ characters | Use the 1M list filtered to 12 to 128 characters (29,982 entries) |

## Production differences

- **Throttling** is in memory per instance. With several instances, use Bucket4j's
  PostgreSQL or Redis back end.
- **Session ids** are stored in clear by Spring Session. Restrict database read access
  (ADR-022); hashing them is a pilot backlog item.
- **Argon2 cost:** raise it if the hosts allow (measure at about 0.5 s per hash), and
  size `SNIGF_PASSWORD_MAX_CONCURRENT_HASHES` × memory within the heap.
- **Staff onboarding** needs administrator-created accounts (security step 4c). Staff
  never self-register; `dev-make-staff.sh` exists for development only.
- **WebAuthn:**
  - Set `SNIGF_WEBAUTHN_RP_ID` to the public domain before the first enrolment;
    changing it later invalidates every key.
  - Consider attestation `direct` with an approved-device list.
  - Keep ceremonies in the database when running several instances.
