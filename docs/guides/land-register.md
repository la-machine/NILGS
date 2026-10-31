# The land register: titles, signed inscriptions, deposits and conflicts

*Copyright (c) 2026 FLYT. Author: Pythagore.*

> **DEMONSTRATION. NO LEGAL EFFECT.** "Registered" means registered in the SNIGF DEMO register.
> All data is synthetic, nothing here establishes ownership, and no certificate of ownership
> exists. The on-screen extract is labelled "NOT A CERTIFICATE — DEMONSTRATION DATA".

## What and why

A parcel, an application, a decision and a payment do not change who holds land. Only a
registered inscription does. The register keeps that distinction in the database:

- **Append-only.** Every change is a new inscription. Nothing registered is ever rewritten or
  deleted; a cancelled or corrected entry stays readable and is marked as such.
- **Derived state.** Holders, charges, restrictions, area and status are computed from the
  registered inscriptions. No table holds an editable "current owner".
- **Signed twice.** The officer's security key signs each registration, and the `RECORD` key in
  OpenBao seals it, so it proves both "this officer approved it" and "this system recorded it".
- **Lock and conflict.** A qualifying deposit locks a title against other transfers. A second
  deposit is recorded in conflict, never refused and never queued; its fate needs a decision.

Decisions: [ADR-040](../adr/040-land-register.md) and
[ADR-019](../adr/019-officer-signed-records.md).

## Where

| What | Where |
| --- | --- |
| Schema, invariants, functions, row-level security | `backend/src/main/resources/db/migration/V21__land_register.sql` |
| Officer signing ceremony | `identity`: `OfficerSignatures`, `SpringWebAuthnCeremonies`, `SignatureEvidenceVerifier` |
| Signature and seal, shared service | `records`: `SignedRecords`, `CanonicalForm`, `RecordSeal` |
| Use cases and endpoints | `register`: `RegisterService`, `OverdueLocks`, `RegisterController` |
| Screens | `frontend/src/features/register` (`#/register`, `#/register/titles/<id>`) |
| A deposit's documents | `V22__deposit_documents.sql`; `document`: `DocumentFiles`, `DocumentFileService`; `frontend/src/features/register/DepositDocuments.tsx` |
| Tests | pgTAP `23_land_register`, `bench/register_deposit*`, `OfficerSignatureIT`, `RegisterIT`, `InscriptionCanonicalTest`, `e2e/tests/register.ts` |
| Synthetic data | `infra/scripts/dev-seed-register.sh` |

## How it works

### An inscription's life

| Kind | Control | Path |
| --- | --- | --- |
| `CREATION`, `TRANSFER`, `CHARGE`, `CHARGE_RELEASE`, `RESTRICTION`, `RESTRICTION_RELEASE` | one conservator signs | `PROPOSED` → `REGISTERED` |
| `CLOSURE`, `CANCELLATION`, `CORRECTION` | two conservators each sign | `PROPOSED` → `CONFIRMATION_PENDING` → `REGISTERED` |

Any proposal can end as `WITHDRAWN` (by its proposer) or `REJECTED` (by a conservator), with a
reason, and has no effect. Numbers are taken at registration, under the title's row lock, so they
have no gaps. `AREA_REDUCTION` is modelled for slice 4 and refused today.

### Signing

1. The server asks the database for the inscription's canonical bytes (`snigf-inscription-v1`).
   They include the title version it was prepared against and that version's chain hash.
2. It starts a WebAuthn ceremony whose challenge is SHA-256(canonical ‖ a new 32-byte nonce). User
   verification is required, and only the officer's own credentials are allowed.
3. The officer's key signs. The server verifies the assertion and that the canonical bytes are
   unchanged, then signs canonical bytes and officer evidence with `RECORD`.
4. One database function checks everything again and writes: permission at the office, separation
   of duties, the bytes against its own encoding, the challenge, the key belonging to the actor,
   the title version, and for a transfer the lock, conflicts and blocking restrictions.

A copy of the public key is kept with the signature, because an officer may later remove a lost
key (ADR-028) and what they signed must stay verifiable.

### A deposit's documents

Each file goes through the checks of [ADR-033](../adr/033-documents-upload-and-storage.md) through the
`DocumentFiles` port, then `deposit_document_add` records it. That function writes only that row: it
inscribes nothing and moves no status. There is no delete. A correction is a new file naming the one it
replaces, with a reason, and the original stays listed and downloadable. A closed deposit takes nothing
more and keeps what it has. A `TRANSFER`'s signature names every document's id and hash, so adding one
during a signature makes it fail.

**If you add another owner of files in this storage, extend `document_storage_keys_known` in the same
migration.** The orphan cleanup deletes whatever that function does not name.

### Deposits

`HOLDING` is unique per title (a partial index). Racing deposits serialise on the title's row lock:
one holds and the rest are `IN_CONFLICT`, each with a `transaction_conflict`. Only registration,
withdrawal, refusal or a conflict decision changes a lock. A conservator who received either
deposit cannot decide that conflict.

Each lock has a `review_due_at`. The job records it once as `REGISTER_LOCK_OVERDUE` and lists it for
review. **Nothing expires on its own.**

At the counter, the conservator is the receiving officer and the notary only a named party, never
an authenticated actor.

## How to do it again

```sh
bash infra/scripts/dev-seed-demo.sh        # the offices, and conservator.yde / conservator2.yde
bash infra/scripts/dev-seed-register.sh    # DEMO parties, a notary, a parcel, a title proposal
```

1. Sign in as `conservator.yde@demo.invalid` (two security keys at first sign-in), open
   **Land register (DEMO)** and choose **Register with my security key** on the proposal.
2. Open the title. Record a deposit and add a PDF to it under **Supporting documents**. Record a second
   deposit: it appears in conflict.
3. As `conservator2.yde@demo.invalid`, record the decision on the conflict.
4. As the first conservator, propose the `TRANSFER` and register it.
5. Propose a `CORRECTION`, sign it, and confirm it as the second conservator.
6. Choose **Verify again**.

The seed registers nothing, because it holds no key. For another signed record type, build its
canonical bytes with `CanonicalForm`, call `SignedRecords.begin` and `seal`, and store what `seal`
returns with the record. Keep a parity test between any SQL and Java encoding.

## Verify

```sh
docker compose --profile test run --rm --build dbtest   # pgTAP 23 and the concurrent deposits
cd backend && ./mvnw verify -Dit.test=RegisterIT,OfficerSignatureIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
```

`GET /api/v1/register/titles/{id}/verification` compares each row with its signed bytes, verifies
both signatures, recomputes the chain and every derived version. On screen: **Verify again**.

## Pitfalls

| Symptom | Cause |
| --- | --- |
| 409 `TITLE_CHANGED` | Another inscription was registered first. Withdraw it and propose again on the current version |
| 409 `OPEN_CONFLICT` on a transfer | Decide the conflict first; this is the rule, not a fault |
| 403 `RECEIVER_CANNOT_DECIDE` or `SELF_CONFIRMATION` | Another conservator must act |
| 422 `REGISTER_INVALID` on a party | Names and identity numbers must contain `DEMO` |
| 409 `TRANSACTION_CLOSED` on a document | The deposit is registered, withdrawn or refused: it keeps its documents and takes no more |
| 422 `SIGNATURE_INVALID` after adding a document | The signature no longer covers every document: sign again |
| 403 on a record that exists | Without `register.read` at its office, the record is not reachable; an unknown id also answers 403 |

## Production

Not production-ready, by design:

- Real paper titles are blocked by constraints. Their migration needs its own decision (ADR-040 §9).
- An inscription's evidence is a list of references, optionally naming an application's stored versions.
- Deposit documents are never removed: no retention period has been decided (ADR-040).
- Uploads are not scanned for malware until the pilot (P-02), as for every document.
- Signing ceremonies are kept in memory, so they assume one application instance.
- Professional accounts, procedures, certificates and morcellement belong to later slices.
