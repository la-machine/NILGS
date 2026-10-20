# ADR-006: Store geometry in EPSG:4326; accept 4326, 32632, 32633

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

Cameroon spans UTM zones 32N and 33N. The official CRS is still to be confirmed (V1).

## Decision

Store MultiPolygon in EPSG:4326. Accept input in 4326, 32632 or 32633 with an explicit SRID; transform in PostGIS; snap to 1e-7 degrees; compute areas on the spheroid.

## Alternatives considered

- **Store in UTM**: Two zones would mean two storage systems or distortion near the zone boundary.

## Consequences

- One canonical CRS across both zones.
- Accepted input CRS are provisional (P1) until V1 is validated.
