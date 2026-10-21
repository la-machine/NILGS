# ADR-021: Domain-driven layering inside each module

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted, implemented for `audit` | 2026-10-21 | Pythagore (FLYT) |

## Context

ADR-001 makes each direct sub-package of `org.snigf.mvp` an application module, and
Spring Modulith checks that modules use each other only through their root package. It
says nothing about the inside of a module. With one flat `internal` package, rules,
use cases and technology mix: the audit chain's verification logic would sit next to
JDBC code and could only be tested against a database. The project owner asked for
domain-driven design packaging.

## Decision

Each business module is a bounded context with four packages, and dependencies point
inwards only:

```text
org.snigf.mvp.<module>                    published API: interfaces and value types other
                                          modules may use (the module's published language)
org.snigf.mvp.<module>.domain             entities, value objects, domain services, repository
                                          interfaces. Plain Java: no Spring, JDBC, JSON
                                          library, file system or logging
org.snigf.mvp.<module>.application        use cases implementing the published interfaces;
                                          transaction boundaries; ports (interfaces) for
                                          everything outside the domain; settings
org.snigf.mvp.<module>.infrastructure.*   adapters: persistence, archive, security context,
                                          scheduling, health, configuration

infrastructure ──► application ──► domain ──► published API
```

- The published API depends on nothing inside its own module.
- The domain may use the published API of its own module and of the `keys` and `shared`
  modules, never `application` or `infrastructure`.
- The application layer reaches the outside world through domain repositories and its
  own ports, never through an adapter class.
- `DddLayeringTest` (ArchUnit) enforces these rules for every module on every build;
  `ModularityTest` keeps enforcing module boundaries.

`keys` is a generic, technical module with no domain model (it adapts OpenBao); it keeps
its `internal` package until it grows rules of its own.

## Alternatives considered

- **One `internal` package per module**: simplest, but nothing stops a rule from depending
  on JDBC or a use case from depending on an adapter.
- **One Maven module per layer or per context**: stronger compile-time isolation, but
  ADR-001 chose a single Maven module for the MVP's size; ArchUnit gives the same guarantee
  as a test.
- **Package by layer across the application** (`domain/`, `application/` at the top):
  breaks module boundaries and Spring Modulith's verification.

## Consequences

- Domain rules are unit-tested without Spring or a database (for example the audit
  chain verifier against an in-memory chain), and integration tests cover the adapters.
- Classes in `application` and `infrastructure` are public in Java terms, so adapters in
  other packages can implement ports; Spring Modulith still hides them from other modules.
- New modules start with the same four packages; the guide `docs/guides/audit-trail.md`
  shows the pattern in full.
