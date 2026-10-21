# 3. Security model

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Principles

1. **Trust nothing, including the internal network.** Every hop uses TLS 1.3, and every
   internal hop is mutually authenticated.
2. **Least privilege everywhere**: database roles, OpenBao policy, container
   capabilities, networks.
3. **Fail closed.** A missing setting, an unsafe combination, an unavailable key or a
   failed self-test stops the application. It never falls back to a weaker mode.
4. **Tamper evidence where prevention is impossible**: the hash-chained audit trail
   with checkpoints signed outside the database.
5. **Every control is tested for its reason.** A refusal must fail for the expected
   reason, next to a positive control that succeeds.

## Controls by layer

| Layer | Control | Enforced by | Verified by | Decision |
| --- | --- | --- | --- | --- |
| Browser → proxy | TLS 1.3 only, HSTS, CSP, no plain-HTTP listener | `infra/proxy/Caddyfile` | security probe | [ADR-017](../adr/017-tls-on-every-hop.md) |
| Proxy → app | Mutual TLS 1.3; only the proxy's CN admitted | Tomcat SSL bundle `snigf-server` (`client-auth: need`), `ClientCertificateFilter` | probe, `ClientCertificateFilterTest` | ADR-017 |
| Client address | Proxy overwrites the address header; app believes it only from the admitted proxy; IP literals only | Caddyfile `header_up`, `ClientAddress` | `ClientAddressTest` | ADR-017 |
| App / migrate → db | TLS 1.3 `verify-full`, client certificate whose CN equals the role, SCRAM password; superuser on the local socket only | `pg_hba.conf`, `start-postgres.sh`, JDBC settings | probe (8 checks) | ADR-017, [ADR-009](../adr/009-least-privilege-roles.md) |
| Database roles | `snigf_migrator` owns the schema (deploy only); `snigf_app` has per-table and per-column grants; `snigf_auditor` reads audit tables only | `01-roles.sql`, migrations | pgTAP `01_grants`, `ApplicationSmokeIT` | ADR-009 |
| Database functions | Fixed `search_path = pg_catalog, snigf, public, pg_temp` | V4 | pgTAP `06_function_hardening` | [ADR-007](../adr/007-audit-hash-chain.md) |
| App → OpenBao | Mutual TLS 1.3; login with the app certificate; policy limited to sign, read public keys, data keys, HMAC, self-lookup | OpenBao listener, `app-policy.hcl.template`, cert auth role | probe (8 checks) | [ADR-018](../adr/018-openbao-key-management.md) |
| Keys | Never exportable, deletable or backed up in plaintext; one key per purpose; rotation by setting | `bao-init.sh` | probe, `KeyServiceSelfTest` | ADR-018 |
| OpenBao custody | Shamir seal (5 shares, threshold 3); no stored root token; temporary root token revoked after provisioning | `bao-init.sh` | bao-init log, lifecycle test in the key custody guide | ADR-018 |
| Envelope encryption | AES-256-GCM per value, data key wrapped by OpenBao, context bound as AAD and derivation context | `CustodyKeyService` | `EphemeralKeyServiceTest`, self-test | ADR-018 |
| Audit trail | Append-only grants and triggers, database-assigned chain, Java verification, OpenBao-signed checkpoints off the database | V3, audit module | pgTAP `05_audit`, `ChainVerifierTest`, `AuditChainIT` | ADR-007 |
| Application startup | Environment guard, audit settings check, key self-test | `EnvironmentGuard`, `AuditConfiguration`, `KeyServiceSelfTest` | `ProdStartupGuardTest`, `AuditConfigurationTest` | [ADR-014](../adr/014-configuration-and-secrets.md) |
| Authorization | Default deny; full session required under `/api/v1`; every endpoint has `@PreAuthorize` or `@PublicEndpoint` | `SecurityConfiguration`, method security | `AuthFlowIT`, `EndpointAnnotationArchTest` | [ADR-016](../adr/016-webauthn-staff-authentication.md) |
| Passwords | P7 policy (12+ characters, 29,982 common passwords, no account name); OpenBao pepper + Argon2id at the OWASP minimum or above; bounded concurrent hashing | `PasswordPolicy`, `PepperedArgon2PasswordHasher`, `IdentityConfiguration` | `IdentityRulesTest`, `IdentityInfrastructureTest`, `AuthFlowIT` | [ADR-022](../adr/022-plain-value-sessions.md) |
| Login | Same 401 `AUTH_FAILED` and the same work for every failure; lockout after 5 failures for 15 min; 20 attempts per minute per address; registration answers 201 regardless | `LoginService`, `RegistrationService`, `AuthRateLimiter` | `AuthFlowIT`, security probe | ADR-022 |
| Sessions | Server-side (PostgreSQL); new id at login; idle 30 min and absolute 8 h; `__Host-` cookie, Secure, HttpOnly, SameSite Strict; plain values only, with a deserialization filter; principal rebuilt per request | Spring Session, `SessionSecurityContextRepository`, `SessionSerializationConfiguration` | `AuthFlowIT`, `SessionSecurityContextRepositoryTest`, pgTAP `07_sessions` | [ADR-004](../adr/004-server-side-sessions.md), ADR-022 |
| CSRF | Token in a `__Host-` cookie, echoed in a header on every state-changing call, including login | `SecurityConfiguration` (SPA mode) | `AuthFlowIT`, security probe | ADR-004 |
| Second factor | Staff stop at level `PASSWORD` and can call nothing but the second-factor, session and logout endpoints | `LoginService`, `SecurityConfiguration` | `AuthFlowIT` | ADR-016 (WebAuthn: step 4b) |
| Containers | Read-only root filesystem, `cap_drop: ALL`, `no-new-privileges`, non-root users, resource limits, images pinned by digest | `compose.yml` `x-hardened` | `docker inspect` | [ADR-020](../adr/020-container-and-network-hardening.md) |
| Networks | `edge` → `app-net` → `data-net` / `key-net`; internal networks have no internet | `compose.yml` | segmentation probe | ADR-020 |
| Secrets | Files only (Docker secrets), never environment variables or Git; scripts read `.env` as data | `.gitignore`, gitleaks in CI, `load_env` | CI secret scan | ADR-014 |

## Trust boundaries

```text
Internet ──TLS 1.3──► web ──mTLS 1.3 (CN allow-list)──► app ──┬─TLS 1.3 + cert + SCRAM──► db
                                                               └─mTLS 1.3 + cert login────► openbao
```

- **The browser** is untrusted. It never chooses an actor, a correlation id, an address
  or an audit field.
- **The proxy** is trusted only for the browser's address, and only once its
  certificate has been admitted.
- **The app** is trusted to use keys for their purposes, never to read them.
- **The database** is trusted to store data, not to vouch for history: the app
  recomputes the audit chain, and checkpoints are signed outside it.
- **Administrators**: the schema owner and the superuser can alter rows. That is
  detected, not prevented. See [residual risk](../audit.md#residual-risk).

## Known limits

These are recorded in the ADRs and in `docs/audit.md`.

- **Development certificates** come from a local CA without revocation. The pilot uses
  short-lived certificates from OpenBao PKI with CRL or OCSP.
- **Unseal shares** sit on a development volume. The pilot gives them to named
  custodians or uses an HSM or KMS seal.
- **Host access:** whoever controls the application host can have OpenBao sign a
  rewritten chain head. Only the auditor's earlier off-host copies reveal it.
- **Staff second factor** (WebAuthn, with a TOTP fallback) is security step 4b. Until
  then, staff sessions stop at level `PASSWORD` and can call nothing else.
- **Throttling** is in memory, for one application instance.
- **Session ids** are stored in clear in `spring_session`. Only the application role can
  read it (ADR-022).
