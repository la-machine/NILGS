# 7. Testing

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## Suites

| Suite | Command | Needs | Proves |
| --- | --- | --- | --- |
| Backend unit (`*Test`) | `cd backend && ./mvnw test` | JDK 25 | domain rules, guards, encodings, layering, module boundaries |
| Backend integration (`*IT`) | `cd backend && ./mvnw verify` | JDK 25 + Docker | the application against real PostGIS, as `snigf_app` |
| Database (pgTAP) | `docker compose --profile test run --rm --build dbtest` | Docker | grants, constraints, triggers, geometry, audit chain, function hardening; concurrent chain test with pgbench |
| Security probe | `docker compose --profile test run --rm --build security-probe` | running stack | every transport and key-custody guarantee, each refusal for its reason |
| Segmentation probe | `docker compose --profile test run --rm segmentation-probe` | running stack | the proxy's network reaches neither the database, OpenBao nor the internet |
| Frontend | `cd frontend && npm run lint && npm run build` | Node 24 | lint, type-check, build |
| Offline checkpoint | `bash infra/scripts/verify-checkpoint.sh keys.json checkpoint-*.json` | bash, OpenSSL 3 | checkpoint files are authentic |

On Windows, set `JAVA_HOME` to a JDK 25 before running Maven. The enforcer rule refuses
older JDKs with "Detected JDK ... is version 17 ... not in the allowed range [25,)".

## Backend tests by area

| Area | Tests |
| --- | --- |
| Architecture | `ModularityTest` (module boundaries), `DddLayeringTest` (layer rules), `ComposeImageParityTest` (test image = Compose image), `DocumentationSyncTest` (the manual lists every setting, secret, migration, module, service, health contributor, audit action, ADR and guide) |
| Configuration | `ProdStartupGuardTest` (environment guard), `AuditConfigurationTest`, `IdentityInfrastructureTest` (identity settings) |
| Web | `CorrelationIdFilterTest`, `ClientCertificateFilterTest`, `ClientAddressTest` |
| Keys | `EphemeralKeyServiceTest`, `OpenBaoKeyServiceTest` (OpenBao HTTP contract with a mock server) |
| Audit domain | `ChainVerifierTest` (every tampering kind against an in-memory chain), `AuditDomainRulesTest` |
| Audit integration | `AuditRecorderIT` (transactions, actors, immutability), `CanonicalHashParityIT` (Java and SQL produce identical bytes), `AuditChainIT` (checkpoints, offline verification, superuser tampering) |
| Identity | `IdentityRulesTest` (password policy, e-mail, lockout), `IdentityInfrastructureTest` (peppered Argon2id, plain-value sessions, pinned password list), `SessionSecurityContextRepositoryTest` (absolute limit, authorities), `AuthFlowIT` (13 end-to-end scenarios), `EndpointAnnotationArchTest` |
| Whole application | `ApplicationSmokeIT` (health, 401 for anonymous calls, least-privilege role) |
| Ids | `UuidV7Test` |

## How the integration tests work

`PostgisDatabase` starts one PostGIS container per JVM:

- from the same image digest and port as `compose.yml`;
- `01-roles.sql` runs as superuser;
- the migrations run as `snigf_migrator`;
- the application connects as `snigf_app`;
- passwords are random per run.

Tests run under the `test` profile, which allows plain TCP and in-memory keys. Transport
and key custody are covered by the probes and the startup self-test instead.

The database is shared by every test in the run, so:

- tests use positions and rows they created themselves;
- tampering tests (`AuditChainIT`) use `superuserConnection()` in a transaction they roll
  back, or restore what they changed in a `finally` block;
- never assume an empty table, and never depend on test order.

## Writing a test

- **Domain rule:** a unit test with an in-memory repository (see `InMemoryChain`). No
  Spring.
- **Adapter or transaction behaviour:** an `*IT` extending the module's integration base
  (`AuditIntegrationTest`).
- **Refusals:** assert the reason (message or code), and keep a positive control next to
  it. A test that passes because something unrelated failed is worse than no test.
- **A new security guarantee:** add a probe check for the running stack, with its reason
  and a control.
- **A database rule:** a pgTAP test in `backend/src/test/sql/t/NN_name.sql`. Change to a
  path without the schema to test what other roles see; keep `tap` on the path.
- **Prove it can fail:** break the code under test once and watch the test fail. V4's
  test was checked by removing V4.
