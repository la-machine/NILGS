# ADR-003: PostgreSQL 18 + PostGIS 3.6; spatial logic in SQL; tests on real PostGIS

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Geometry validation, CRS transformation and overlap detection are central and must be correct.

## Decision

PostgreSQL 18 with PostGIS 3.6, image pinned by digest in `compose.yml`. Spatial rules live in SQL (`normalize_parcel_geometry`, overlap queries). Every database test runs against that same image: pgTAP through the `dbtest` service, Java through Testcontainers. `ComposeImageParityTest` fails if the two image references diverge.

## Alternatives considered

- **Geometry logic in Java (JTS only)**: Duplicates PostGIS with weaker guarantees.
- **H2 or another in-memory database for tests**: No PostGIS; spatial results would not match production.

## Consequences

- CI needs Docker.
- Spatial test results match production.
