# How-to guides

*Copyright (c) 2026 FLYT. Author: Pythagore.*

How each part of SNIGF/NILGS was built, where it lives, and how to redo or extend it.
The [technical manual](../technical/README.md) is the entry point; it summarises each part
and links here for the detail.
Architecture decisions explain **why** ([`docs/adr/`](../adr/README.md)); these guides
explain **how**. [`CHANGELOG.md`](../../CHANGELOG.md) lists **what** changed and when.

| Guide | Covers | Decisions |
| --- | --- | --- |
| [Environment, settings and secrets](environment-and-secrets.md) | `.env`, secret files, ports, adding a setting or a secret, signing files | ADR-014, ADR-015 |
| [Transport security and hardening](transport-security.md) | Private PKI, TLS 1.3 on every hop, mutual TLS, network segmentation, container hardening, the probes | ADR-017, ADR-020 |
| [Key custody with OpenBao](key-custody-openbao.md) | OpenBao service, unsealing, provisioning, keys and policy, `KeyService`, adding or rotating a key | ADR-018 |
| [Authentication and sessions](authentication-and-sessions.md) | Registration, password login, peppered Argon2id, sessions in PostgreSQL, CSRF, lockout, throttling, the WebAuthn second factor, recovery codes, the sign-in screens | ADR-004, ADR-016, ADR-022, ADR-023 |
| [Roles, permissions and administration](roles-and-administration.md) | Permission catalogue, baseline and custom roles, four-eyes role assignments, special grants, the approval workflow enforced by the database, separation of duties, second-factor reset, the auditor's API | ADR-024, ADR-025, ADR-026 |
| [End-to-end testing in a real browser](end-to-end-testing.md) | Chromium in Compose against the running stack, the development CA, virtual security keys, the four-eyes and special-grant journeys | ADR-016, ADR-020, ADR-026 |
| [Overlap and duplicate detection, review and resolution](conflict-detection.md) | How alerts are raised (database trigger on every version), the rules and thresholds, what an alert holds, supersession, the fixture set, tuning the thresholds; reviewing and resolving with separation of duties, outcomes, codes | ADR-030, ADR-031 |
| [Applications: drafts, submission, completeness, review and decision](applications.md) | The type catalogue (configuration, versions), drafts only their owner sees, row-level security on the actor, the database's lifecycle guard and history, the idempotent submission and its reference, the completeness review (automatic check, acceptance, information requests, responses), review by claim and the simulated decision (separation of duties, the open-alert gate, citations), changing the catalogue | ADR-032, ADR-034, ADR-035 |
| [Simulated payment and reconciliation](payments.md) | **No real money moves.** The DEMO fee catalogue, the applicant's payment with an `Idempotency-Key`, the Compose simulator signing callbacks in OpenBao, the proxy's provider listener, reconciliation by reference and amount in the database, expiry, the finance officer's exceptions, the payment gate on acceptance, adding a provider later | ADR-037 |
| [Documents issued by SNIGF: issuance, verification, incidents](issued-documents.md) | **DEMO acknowledgment only, no legal value.** The registry, the manifest signed by the dedicated OpenBao key, the exact PDF kept, issuance once per submission, public verification by number or file and what each outcome means, printed and scanned copies, statuses and re-issue, the incident queue, key rotation and historical verification, adding a document type later | ADR-039 |
| [Tracking and workload report](tracking-and-workload.md) | The applicant's projection (state, since when, whose turn, parties never people) and its schema test; the supervisor's report computed by database functions gated on `report.workload`, from the transition table only; DEMO stage targets; adding a figure | ADR-036 |
| [Documents: upload, storage, download](documents.md) | The upload pipeline (staging, recognition from the bytes, PDF and image checks), random-key storage, the hash check on download, orphan cleanup, the limits on every layer, moving to object storage | ADR-033 |
| [Map data for the geographic and satellite views](map-data.md) | Building OpenStreetMap vector tiles, importing licensed imagery, synthetic test imagery, manifests, installing into `map-data`, licences | ADR-029 |
| [Satellite imagery: download, build and install](satellite-imagery.md) | Operator steps for the national Sentinel-2 mosaic and aerial details: trial, national download (resumable), build, install, checks, updates, troubleshooting, cleanup | ADR-029 |
| [Demonstration accounts](demo-accounts.md) | One account per role with passwords, two demo offices, security keys in the browser, what to try with whom (development only) | ADR-025, ADR-026 |
| [Audit trail with signed checkpoints](audit-trail.md) | Recording events, Java chain verification, OpenBao-signed checkpoints, offline verification, the subjects of each event and the case or parcel timeline read by office (E-18), DDD packaging of a module | ADR-007, ADR-021, ADR-027, ADR-038 |

## How every guide is written

Each guide has the same parts, so you can jump straight to the one you need:

1. **What and why**: the guarantee, in one paragraph, with the decision record.
2. **Where**: every file involved and its role.
3. **How it works**: the sequence at runtime.
4. **How to do it again**: numbered steps, for this project or a similar one.
5. **How to verify**: commands and the expected result.
6. **Pitfalls we hit**: real failures met while building it, their cause and the fix.
7. **Production differences**: what changes beyond development.

When you change a part, update its guide and add a `CHANGELOG.md` entry in the same change.
