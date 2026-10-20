# ADR-001: Modular monolith in one Maven module

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

One small team builds one product on one database. Domains (parcels, applications, audit and others) must stay separate so they can evolve independently, without the operational cost of distributed services.

## Decision

One Spring Boot application, one Maven module (C10). Each direct sub-package of `org.snigf.mvp` is a module; modules call each other only through their top-level public types. Spring Modulith verifies this in `ModularityTest` on every build.

## Alternatives considered

- **Multi-module Maven**: Same isolation at higher build complexity; Modulith gives the boundary check without it.
- **Microservices**: Independent deploys and scaling are not needed; distributed transactions and operations would be.

## Consequences

- Boundary violations fail CI.
- Splitting a module out later is a packaging change, not a redesign.
