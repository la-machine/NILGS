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
| [028](028-key-removal-and-supervised-reset.md) | Owners remove lost keys (never the last); a second-factor reset is requested by an administrator and confirmed in the application by the person's supervisor; enforced by the database |
| [029](029-map-views-and-sovereign-map-data.md) | Three map views (technical, geographic, satellite) over one authoritative geometry; map data hosted by us and served behind the parcel reading permission; external providers disabled |
| [030](030-overlap-and-duplicate-detection.md) | Overlap and duplicate detection by the database on every parcel version written (MVP-04): version-to-version evidence, one open alert per pair, superseded by corrections, thresholds from settings, resolution rules enforced by the database |
| [031](031-alert-review-and-resolution.md) | Alert review and resolution (E-11): list by office, review of one alert (map, both parcels), resolution with an outcome and a note; the author of a compared version refused by the service and by the database; either parcel's office (`canAtAny`); special grants honoured by the database guard (V12) |
| [032](032-applications-drafts-and-submission.md) | Applications (E-12): owner-only drafts enforced by the endpoint, the service (404) and row-level security on the actor named per transaction; lifecycle guarded by the database, history written by it; type catalogue from configuration, versioned; idempotent submission issuing the reference |
| [033](033-documents-upload-and-storage.md) | Documents (E-13): recognised from their bytes (Tika), PDF/JPEG/PNG only, extension agreeing, PDF active content and oversized images refused; stored under random keys, read-only, hash checked before every download; append-only versions under row-level security; orphan cleanup; limits at proxy, servlet, service and database |
| [034](034-completeness-review.md) | Completeness review (E-14): one check in the database (`application_missing_items`), append-only records before every move, the automatic check as the only move without a person, intake officers of the office never handling their own application, files during a request only in the categories asked for |
| [035](035-review-and-simulated-decision.md) | Review and simulated decision (E-15): permissions at scope ASSIGNED opened by claim (one per case and stage, for oneself); whoever recommended never decides, nobody handles their own application; no decision while a parcel alert is open; grounds and cited stored versions required; every decision simulated, on screen and in print; the review hidden from the applicant; enforced by the database |
| [036](036-tracking-and-workload-report.md) | Tracking and workload report (E-16): the applicant reads a projection naming parties, never people nor internal reasons, pinned by a schema test; the supervisor's report computed by database functions gated on `report.workload`, from the transition table only, against DEMO stage targets from settings |
| [037](037-simulated-payment-and-reconciliation.md) | Simulated payment (E-17): DEMO fee catalogue; requests opened by the applicant with an Idempotency-Key, expiring in the database; a Compose simulator signing callbacks with an OpenBao key it never holds, through the proxy's mutual-TLS provider listener; reconciliation by reference and amount in one locked database function, transaction ids once, mismatches kept as exceptions; payment required before acceptance; a replaceable provider port, only the simulator approved |
| [038](038-audit-subjects-and-case-timeline.md) | Audit timeline (E-18), implementing ADR-027: each event's subjects (the application, its documents' and payments' case, the parcel, both parcels of an alert) with their office at the time, found by the database, hashed in canonical v2 (v1 unchanged for older events, no backfill); office-scoped event search and the case or parcel timeline from the audit trail alone, read through `audit_readable`; each event checked intact |
