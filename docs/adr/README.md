# Architecture decision records

*Copyright (c) 2026 FLYT. Author: Pythagore.*

One file per decision: context, decision, alternatives considered, consequences. A changed decision gets a new ADR that supersedes the old one; accepted ADRs are not rewritten.

| ADR | Decision |
| --- | --- |
| [001](001-modular-monolith.md) | Modular monolith in one Maven module |
| [002](002-java-25-spring-boot-4-1.md) | Java 25 LTS and Spring Boot 4.1 |
| [003](003-postgresql-postgis.md) | PostgreSQL 18 + PostGIS 3.6; spatial logic in SQL; tests on real PostGIS |
| [004](004-server-side-sessions.md) | Server-side sessions in PostgreSQL |
| [005](005-totp-mfa.md) | TOTP MFA for all staff (superseded by 016) |
| [006](006-storage-crs.md) | Store geometry in EPSG:4326; accept 4326, 32632, 32633 |
| [007](007-audit-hash-chain.md) | Hash-chained audit table with signed off-host checkpoints |
| [008](008-uuidv7-keys.md) | UUIDv7 primary keys; human references separate |
| [009](009-least-privilege-roles.md) | Migrations as snigf_migrator; the app runs as snigf_app |
| [010](010-caddy-same-origin.md) | Caddy reverse proxy, one origin for UI and API |
| [011](011-problem-details.md) | RFC 9457 Problem Details with stable error codes |
| [012](012-openapi-codegen.md) | Code-first OpenAPI; generated TypeScript types |
| [013](013-idempotency-key.md) | Idempotency-Key on create endpoints |
| [014](014-configuration-and-secrets.md) | Settings in .env, secrets as files |
| [015](015-non-default-ports.md) | No default ports |
| [016](016-webauthn-staff-authentication.md) | WebAuthn for staff, TOTP as recovery fallback |
| [017](017-tls-on-every-hop.md) | TLS on every network hop, private PKI, mutual authentication |
| [018](018-openbao-key-management.md) | Key management in OpenBao; the application never holds key material |
| [019](019-officer-signed-records.md) | Officers sign recommendations and decisions with their own key |
| [020](020-container-and-network-hardening.md) | Container and network hardening |
| [021](021-ddd-layering-inside-modules.md) | Domain-driven layering inside each module (domain, application, infrastructure) |
| [022](022-plain-value-sessions.md) | Sessions hold plain values; principal rebuilt per request; `__Host-` cookies; answers that reveal nothing |
| [023](023-webauthn-second-factor-implementation.md) | WebAuthn second factor on Spring Security's relying party; server-side ceremonies; keyed recovery codes; TOTP deferred |
| [024](024-no-totp-fallback.md) | No TOTP fallback: two keys, recovery codes and a supervised administrator reset |
| [025](025-fine-grained-permissions-and-special-grants.md) | Fine-grained permissions; administrator-defined roles over an immutable baseline; four-eyes special grants limited by time and uses |
| [026](026-approval-workflow-enforced-by-the-database.md) | Four eyes on every role assignment; approval workflow, approver rights and separation of duties enforced by the database; supervisors verify the chain |
| [027](027-office-context-on-audit-events.md) | Office on audit events taken from the record at the time of the event, never retrospectively from a user's current office; office-scoped audit reading closed until then |
