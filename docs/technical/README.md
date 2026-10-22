# SNIGF/NILGS technical manual

*Copyright (c) 2026 FLYT. Author: Pythagore.*

This manual explains how SNIGF/NILGS is built, why, and how to run, change and debug it.
It is written for a software engineer who has never seen the project. After reading it you
should be able to:

1. understand every component and how a request travels through them;
2. reproduce the same architecture in a new project;
3. find and fix the cause of an error in this one.

SNIGF/NILGS is demonstration software that runs on synthetic data only. It is not
approved or deployed by any government institution.

## Chapters

| # | Chapter | Read it when you want to... |
| --- | --- | --- |
| 1 | [System overview](01-system-overview.md) | see the containers, networks, ports and what runs where |
| 2 | [Backend architecture](02-backend-architecture.md) | understand modules, DDD layers, request and startup sequences |
| 3 | [Security model](03-security-model.md) | know every control, which threat it answers and where it is enforced |
| 4 | [Data model](04-data-model.md) | read the schema, database roles, migrations, triggers and audit events |
| 5 | [Configuration reference](05-configuration-reference.md) | look up any setting, property or secret |
| 6 | [Build it in a new project](06-build-it-in-a-new-project.md) | reproduce this architecture step by step elsewhere |
| 7 | [Testing](07-testing.md) | run, read or write tests; know what each suite proves |
| 8 | [Operations and troubleshooting](08-operations-and-troubleshooting.md) | operate the stack, diagnose an error, respond to an incident |
| 9 | [Documentation rules](09-documentation-rules.md) | change anything: which documents must change with it |
| 10 | [Frontend architecture](10-frontend-architecture.md) | find a screen or an endpoint call, add a feature, debug the interface |

## Where else to look

| Document | Answers |
| --- | --- |
| [`README.md`](../../README.md) | Quick start, status, ports |
| [`docs/adr/`](../adr/README.md) | **Why**: one decision per file, with alternatives |
| [`docs/guides/`](../guides/README.md) | **How**, per feature: files, steps to redo, pitfalls met while building |
| [`docs/audit.md`](../audit.md) | Audit guarantees, checkpoint format, residual risk |
| [`CHANGELOG.md`](../../CHANGELOG.md) | **What** changed and when |
| Slice 1 design (Claude Doc) | Requirements, acceptance criteria, API contracts |

The manual is the entry point. It summarises, then links to the guide or ADR that holds
the detail, so each fact is maintained in one place.

## Reading paths

- **New to the project:** chapters 1, 2, 3, then the guides for the feature you will work on.
- **Reimplementing elsewhere:** chapter 6, which takes you through the guides in build order.
- **Something is broken:** chapter 8. Start with "First five minutes".

## Keeping it true

Every change updates the documents it affects in the same commit. Chapter 9 says which
documents those are. `DocumentationSyncTest` fails the build when one of these is missing
from the manual:

- a setting, secret, migration or module;
- a Compose service or health contributor;
- an audit action;
- an ADR or guide.
