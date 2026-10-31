# Demonstration accounts

*Copyright (c) 2026 FLYT. Author: Pythagore.*

This guide covers trying every use case by hand on a development machine. It is for
development only: the script refuses any other profile, and it is not a migration, so these
accounts can never reach staging or production.

## Create them

```bash
docker compose up -d --wait
bash infra/scripts/dev-seed-demo.sh
```

The script:

1. adds the demo offices `YDE1` (Yaoundé) and `DLA1` (Douala);
2. registers each account through the real API, because passwords are Argon2id peppered by
   OpenBao and cannot be written in SQL;
3. makes the staff accounts staff at their office through the superuser bootstrap path, as
   `dev-make-staff.sh` does, and records each role in the audit trail;
4. prints the list. The list is also kept in `infra/secrets/demo_accounts` (git-ignored, 0600):
   one line per account, `email|password|role|office`.

Running it again creates only what is missing and never changes a password. If the hourly
registration limit is reached, the script restarts the application, whose limiter is held in
memory.

## The cast

| Account | Role | Office | Typical use |
| --- | --- | --- | --- |
| `applicant@demo.invalid` | APPLICANT | — | Password only; sees no back-office panel and no parcel |
| `intake.yde@demo.invalid` | INTAKE_OFFICER | YDE1 | Reads parcels (applications come with a later slice) |
| `cadastre.yde@demo.invalid` | CADASTRAL_OFFICER | YDE1 | Records and corrects parcels at Yaoundé; the three map views |
| `cadastre.dla@demo.invalid` | CADASTRAL_OFFICER | DLA1 | The same at Douala only: cannot correct a Yaoundé parcel |
| `surveyor.yde@demo.invalid` | CADASTRAL_OFFICER | YDE1 | A second officer at Yaoundé: resolves the alerts raised by `cadastre.yde`'s parcels, which their author cannot |
| `reviewer.yde@demo.invalid` | REVIEWER | YDE1 | Reads parcels everywhere, changes none; claims cases under review and recommends (E-15) |
| `decision.yde@demo.invalid` | DECISION_AUTHORITY | YDE1 | Reads parcels; claims cases pending decision and records simulated decisions (E-15) |
| `supervisor.yde@demo.invalid` | SUPERVISOR | YDE1 | Reads the workload of Yaoundé; confirms second-factor resets there; verifies the audit chain |
| `auditor@demo.invalid` | AUDITOR | YDE1 | Searches audit events, verifies the chain, signs checkpoints |
| `finance.yde@demo.invalid` | FINANCE_OFFICER | YDE1 | Reconciles the DEMO payments on `#/payments` (simulated: no real money moves): reads the requests and the exceptions, resolves exceptions ([payments guide](payments.md)); no parcel access (decision D14) |
| `admin1@demo.invalid`, `admin2@demo.invalid` | SYSTEM_ADMIN | YDE1 | Role requests, custom roles, special grants: one asks, the other approves. No parcel access (D14) |

The authoritative list of what each role may do is the
[permission matrix](../permission-matrix.md).

## First sign-in of a staff account: two security keys

Staff must register two different authenticators. The quickest way to test is Chrome's
virtual keys:

1. Open https://localhost:47000 (exactly `localhost`; the development CA must be trusted, see
   [chapter 8](../technical/08-operations-and-troubleshooting.md)).
2. Press F12, then ⋮ → More tools → **WebAuthn**, tick "Enable virtual authenticator
   environment", and click **Add** (ctap2, usb, defaults).
3. Sign in with the address and password, then register the first key.
4. Click **Add** again for a second virtual authenticator, and register it.
5. Keep the ten recovery codes shown.

Virtual keys exist only in that tab while DevTools is open. Use one Chrome profile per person
and keep DevTools open to stay signed in. Windows Hello plus a phone or USB security key
works too.

If a demo account loses its keys, use the recovery codes, or the supervised reset with
`admin1` asking and `supervisor.yde` confirming.

The conservators `conservator.yde@demo.invalid` and `conservator2.yde@demo.invalid` keep the DEMO
land register at YDE1. `infra/scripts/dev-seed-register.sh` adds its synthetic data: see the
[land register guide](land-register.md).

## What to try

- **Tracking and workload:** the applicant opens a submitted application and reads where it
  stands and whose turn it is, with no officer named. `supervisor.yde` opens **Workload**: the
  stages of YDE1 against their DEMO targets, who holds what, and how long the stages took.

- **Four eyes:** `admin1` requests a role for `reviewer.yde`; `admin1` cannot approve it;
  `admin2` approves.
- **Parcels:** `cadastre.yde` records a parcel at YDE1, tries a self-intersecting polygon
  (refused with its reason), switches views, then corrects the parcel. `cadastre.dla` finds
  it but cannot correct it. `reviewer.yde` reads it. `admin1` and the applicant see nothing.
- **Overlap alerts (E-11):** `cadastre.yde` records a parcel over an existing one, opens
  **Alerts**, and sees the review but no resolution form (author of a compared version).
  `surveyor.yde` opens the same alert, zooms to the intersection and resolves it with an
  outcome and a note. `cadastre.dla` reaches only alerts involving a Douala parcel.
- **Applications (E-12):** `applicant` opens **My applications**, starts the DEMO type, saves an
  incomplete draft, tries to submit (every missing item is listed), names an active parcel
  (for example one recorded by `cadastre.yde`), completes the form and submits: the reference
  `DEMO-APP-...` appears. Nobody else can open it. Before submitting, upload a PDF, JPEG or PNG
  for each document (a renamed `.html` or `.exe` is refused, whatever its name).
- **Completeness (E-14):** `intake.yde` opens **Cases**, sees the application `applicant`
  submitted and what is missing, requests a document with a message; `applicant` adds it
  and responds; `intake.yde` accepts (impossible while anything required is missing).
- **Review and decision (E-15):** `reviewer.yde` opens **Review**, claims the accepted case
  and recommends with a finding. `decision.yde` opens **Review**, claims it and records a
  simulated decision with grounds and at least one cited document. The decision is impossible
  while an overlap alert on the parcel is open: resolve it first as `surveyor.yde`.
  `applicant` reads the decision, marked "Simulated decision — no legal effect", and prints
  it; never the finding.
- **Audit:** `auditor` finds `PARCEL_CREATED`, `APPLICATION_SUBMITTED`, `CONFLICT_ALERT_RESOLVED`, `CASE_CLAIMED`, `DECISION_RECORDED`, `ROLE_GRANTED` and every refusal, then
  verifies the chain.

A parcel inside the synthetic satellite imagery
(`bash infra/scripts/maps-synthetic-imagery.sh --if-absent`):

```json
{"type":"Polygon","coordinates":[[[11.5,3.87],[11.5003,3.87],[11.5003,3.8703],[11.5,3.8703],[11.5,3.87]]]}
```

## Start again

`docker compose down -v` deletes all data, including these accounts. Then delete
`infra/secrets/demo_accounts`, start the stack, and run the script again. If you delete only
the file, the accounts remain but the script reports them as unusable: their passwords are
lost.
