# ADR-002: Java 25 LTS and Spring Boot 4.1

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-20 | Pythagore (FLYT) |

## Context

The platform must stay on supported versions through the pilot.

## Decision

Java 25 (LTS) and Spring Boot 4.1.x (4.1.1 at scaffold; open-source support to 30 June 2027), Spring Modulith 2.1.x. The Maven Enforcer plugin requires Java 25+ and Maven 3.9+; the Maven wrapper pins Maven 3.9.16 by SHA-256.

## Alternatives considered

- **Spring Boot 4.0**: Open-source support ends 31 December 2026, before a pilot could start.

## Consequences

- Upgrade to the next Boot minor before June 2027.
- Developers need JDK 25 locally; images use pinned Temurin 25.
