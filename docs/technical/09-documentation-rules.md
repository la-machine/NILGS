# 9. Documentation rules

*Copyright (c) 2026 FLYT. Author: Pythagore.*

A change is finished only when the documents it affects say the same thing as the code,
in the same commit. Nobody should have to read the code to learn that the documents are
wrong.

## Which document changes with what

| You change | Update |
| --- | --- |
| Anything | `CHANGELOG.md` (Unreleased: Added / Changed / Fixed / Security) |
| A setting in `.env.example` | its comment there; [chapter 5](05-configuration-reference.md); the guide of its feature |
| A secret (new file in `infra/secrets/`) | chapter 5 "Secrets"; `dev-setup.sh` or `dev-pki.sh`; `compose.yml` |
| A port | `.env.example`; chapter 1 "Ports"; README "Ports" |
| A Compose service, network or volume | chapter 1; the guide of its feature |
| A migration | chapter 4 "Migrations" (and "Tables" if the schema changed); pgTAP tests |
| A module or a layer inside it | chapter 2; [ADR-021](../adr/021-ddd-layering-inside-modules.md) if the rule changes |
| A health contributor | chapter 2 "Health contributors"; chapter 8 if it can fail |
| An audit action | chapter 4 "Audit actions" |
| A startup refusal or a new error message | chapter 8 "Error catalogue" |
| A security control | chapter 3; the probe; the guide |
| A decision (technology, rule, trade-off) | a new ADR in `docs/adr/` and its index; a changed decision gets a new ADR that supersedes the old one |
| A feature built step by step | its guide in `docs/guides/` (what, where, how it works, how to redo, verify, pitfalls, production) and the guides index |
| A pitfall met while building or debugging | the guide's "Pitfalls we hit" and, if an operator can meet it, chapter 8 |
| Status, test counts | README "Status" |

## Enforced by the build

`DocumentationSyncTest` reads the repository and fails, naming what is missing, when:

- a `SNIGF_*` setting of `.env.example` is not in chapter 5;
- a secret declared in `compose.yml` is not in chapter 5;
- a Compose service is not in chapter 1;
- a migration file is not in chapter 4;
- an application module (`@ApplicationModule`) is not in chapter 2;
- a health contributor bean is not in chapter 2;
- an `AuditAction` is not in chapter 4;
- an ADR file is not in `docs/adr/README.md`;
- a guide is not in `docs/guides/README.md` or in the manual.

The test cannot judge whether a sentence is still true. Reviewers must check that.

## Style

- Plain, short sentences. One fact in one place; link to it elsewhere.
- Every Markdown file starts with its title and `*Copyright (c) 2026 FLYT. Author: Pythagore.*`.
- Commands are copy-pasteable and were run before being written down.
- Error messages are quoted exactly, so they can be searched.
- Say what is **not** done yet, plainly ("Not started", "planned for the pilot").
