# End-to-end testing in a real browser

*Copyright (c) 2026 FLYT. Author: Pythagore.*

The land register's journeys are in `e2e/tests/register.ts`, with two conservators (`officer` and
`surveyor` take that role as a second one, in `e2e.sh`).

## What and why

Unit, integration and database tests prove each layer. Only a real browser proves that the
whole chain works as a person uses it: TLS through the proxy with the development CA, the
CSRF cookie and header, WebAuthn ceremonies with a security key, the screens in English, and
the four-eyes and special-grant journeys across several people. The e2e suite runs Chromium
(Playwright) in a Compose container against the running development stack, with Chrome's
virtual security keys. Decisions: [ADR-026](../adr/026-approval-workflow-enforced-by-the-database.md),
[ADR-016](../adr/016-webauthn-staff-authentication.md), [ADR-020](../adr/020-container-and-network-hardening.md).

## Where

| File | Role |
| --- | --- |
| `compose.yml`, service `e2e` (profile `e2e`) | The container: read-only, no capabilities, shares the proxy's network namespace, mounts `e2e/out` |
| `e2e/Dockerfile` | Playwright 1.64.0 image pinned by digest, NSS tools, the test project; runs as `pwuser` |
| `e2e/run.sh` | Imports the development CA chain into Chromium's NSS database, then runs Playwright |
| `e2e/playwright.config.ts` | Two projects: `accounts` and `flows`; reports and traces to `/out` |
| `e2e/tests/support.ts` | A person = a browser context with a virtual security key; sign-in through the screens; API calls with the CSRF header |
| `e2e/tests/branding.spec.ts` | The logo loads (header emblem, sign-in logo, favicon); saves `e2e/out/sign-in.png`; creates no account |
| `e2e/tests/accounts.spec.ts` | Phase 1: registers the run's six accounts |
| `e2e/tests/flows.spec.ts` | Phase 2: the journeys |
| `infra/scripts/e2e.sh` | Orchestrates: phase 1, promotion with `dev-make-staff.sh`, phase 2 |
| `.env`: `SNIGF_E2E_MEMORY`, `SNIGF_E2E_CPUS`, `SNIGF_E2E_PIDS`, `SNIGF_E2E_SHM` | Container limits |

## How it works

1. **Origin.** WebAuthn credentials are bound to `SNIGF_WEBAUTHN_RP_ID` (`localhost`) and the
   exact origins of `SNIGF_WEBAUTHN_ORIGINS`. The container joins the proxy's network
   namespace (`network_mode: service:web`), so the browser opens
   `https://localhost:47000` exactly as a person on the host does.
2. **Trust.** `run.sh` adds every certificate of `pki_ca_chain_crt` to Chromium's NSS
   database as a TLS trust anchor. Certificate errors are never ignored: a wrong certificate
   fails the run.
3. **Accounts.** Phase 1 registers `e2e-<run>-{applicant,admin1,admin2,officer,auditor,supervisor}@demo.invalid`
   through the registration screen with a random password, kept in
   `e2e/out/state-<run>.json` (mode 0600, deleted when the run ends, never an environment
   variable). `e2e.sh` then makes five of them staff with `dev-make-staff.sh`, the superuser
   bootstrap path, which exists only in development.
4. **Security keys.** Each person has their own browser context and a virtual CTAP2 key that
   verifies the user. Enrolment registers the first key, swaps in a second virtual key
   (the server refuses the same key twice), registers it, and confirms the recovery codes.
   Later sign-ins prove the second key.
5. **Journeys** (phase 2, in order):
   - an applicant signs in with a password and sees no back-office panel;
   - five staff members enrol two keys; one signs out and in again with a key;
   - administrator 1 requests a role for the officer; the request shows "your request";
     approving it through the API is refused (403 `SOD_FOUR_EYES`); the officer does not
     have the role; administrator 2 approves; the officer has it;
   - administrator 1 proposes a custom role; it does not exist until administrator 2
     approves;
   - administrator 1 requests a one-use special grant (`audit.key.read`) for the officer;
     administrator 2 approves; the officer sees it with "1 use(s) left"; reloading and a
     cancelled confirmation spend nothing; the confirmed action shows the keys and spends
     the use; the endpoint then answers 403;
   - the auditor verifies the chain, finds the `PERMISSION_GRANT_USED` event and signs a
     checkpoint (decision D5);
   - the supervisor verifies the chain and cannot search events;
   - recovery (ADR-024, ADR-028): the officer loses both keys, signs in with a recovery code
     (a reused code is refused), adds a new key, removes the two lost ones and proves the new
     one; then loses everything: an administrator requests the reset (for oneself it is
     refused), an administrator's confirmation is refused, the supervisor confirms in Identity
     confirmations saying how the identity was checked, the old codes are dead, the officer
     enrols again, and the auditor finds `MFA_RESET` with both people;
   - parcels and map views (`tests/parcels.ts`, registered by `flows.spec.ts` because a
     virtual key lives in its browser context): the officer, a cadastral officer since the
     approval above, sees a bow-tie geometry refused with its reason, then records a parcel;
     the technical view shows boundary, grid, scale, readout and a measured length; each
     background offered by `GET /map/backgrounds` is either rendered from our archive
     (`data-loaded`, attribution, synthetic warning, 206 range responses) or disabled with
     its reason; switching never calls the parcel API; tiles are refused without a session;
     a correction makes version 2 and the history keeps version 1; the supervisor reads but
     cannot correct; an administrator and an applicant get 403; the auditor finds the
     creation, the refusal and the correction; the browser contacted no other origin and
     logged no CSP violation.
   - documents issued by SNIGF (`tests/applications.ts`, ADR-039): the submitted application's
     DEMO acknowledgment is downloaded, asked for again without a second one, and verified by a
     visitor signed out (a new browser context): the number confirms the record, the exact PDF is
     authentic, the same PDF with bytes appended is altered; the supervisor, also the document
     officer, resolves the incident with a note in Issued documents.
6. **Map data.** Before phase 2, `e2e.sh` runs `maps-synthetic-imagery.sh --if-absent`, so
   the satellite view has data (real imagery is never replaced). The geographic view is
   tested as installed or as unavailable, whichever the stack has
   ([map data guide](map-data.md)). Chromium runs with `--enable-unsafe-swiftshader`: the
   container has no GPU, and MapLibre needs WebGL.

## How to do it again

1. Start the stack: `docker compose up -d --wait`.
2. Run `bash infra/scripts/e2e.sh`.
3. Open `e2e/out/report/index.html` for the report; failed tests keep a trace in
   `e2e/out/results/` (open with `npx playwright show-trace <file>`).

To add a journey, add a `test(...)` to `flows.spec.ts` using `Person`: `signIn()`, `home()`,
`open(place, title?)` (a sidebar place; returns the page's content), `api(method, path, body)`,
`respond(answer)` (after the click that opens a dialog: a reason, `true` to confirm, `false`
to cancel). Select elements by role
and English label, as a person reads them; never by CSS class unless there is no label.

## How to verify

```sh
docker compose up -d --wait
bash infra/scripts/e2e.sh     # expected: "1 passed" (branding), "1 passed" (accounts), "18 passed" (flows, the last one skipped unless E2E_TOUR=1)
```

## Design tour

`E2E_TOUR=1 bash infra/scripts/e2e.sh` also captures the main screens at desktop (1440 × 900),
tablet (834 × 1112) and phone (390 × 844) widths into `e2e/out/tour/` (`tests/tour.ts`), with
the run's real people and parcel: dashboard, parcel list, recording, detail in each map view,
correction mode, accounts, roles, audit. Use it to review a visual change before merging.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| The headless browser of the developer tools refused the site (`ERR_CERT_AUTHORITY_INVALID`) and had no WebAuthn | Neither trusted the development CA nor exposed the WebAuthn DevTools domain | This container: CA in Chromium's NSS database, virtual keys through CDP |
| A second registration with the same virtual key fails (`InvalidStateError`) | The server lists the first credential in `excludeCredentials` | Remove the first virtual key and add a new one before the second registration |
| The result of a special grant's last use disappeared at once | The session refresh after the use closed the panel the result was shown in | The audit panel stays open for the visit once shown; only its buttons follow the access |
| `strict mode violation ... resolved to 2 elements` on "Reason" or "Search" | Two controls with the same label in one panel (role request and second-factor reset; event search and the checkpoint list button) | Scope to the form that holds the button; the checkpoint button became "Show checkpoints" |
| A label matched two fields (`Locality` and `Reference or locality`) | `getByLabel` matches substrings | `{ exact: true }` |
| A label never matched exactly (`Office`) | A label that wraps an input includes the input's value in the accessible name (`Office DEMO1`) | `getByRole('textbox', { name: /^Office/ })` |
| A search found two audit rows | Earlier runs left the same reason in the trail | filter by this run's people (their ids) |
| `getByLabel` matched a whole card | A region named by its heading (`aria-labelledby`) is also "labelled" | Sign-in cards are no longer named regions |
| A radio of the map views was "not visible" | Hidden with the clip technique | The radio covers its segment, transparent: clickable, and found by tests |
| `Pattern attribute value ... is not a valid regular expression` in the console | Browsers compile `pattern` with the `v` flag; the field was silently unchecked | Escape `(`, `)` and `-` in classes; `shared/patterns.test.ts` compiles every pattern |
| A new role did not appear for its holder | The session was read only at sign-in | The shell reads it again on navigation and window focus |
| A second run within the hour fails at registration (429) | `SNIGF_RATE_REGISTER_PER_HOUR=10` per address, ten accounts per run | Wait, or in development only restart the application (`docker compose restart app`), whose limiter is in memory |
| Passed locally, failed in CI (`strict mode violation: ... 'New parcel' resolved to 2 elements`; "overlap or duplicate alert(s) open" not found) | Tests relied on earlier runs' data: an empty register shows a second "New parcel" link; the decision test expected alerts only earlier runs had left | Every test creates the state it checks. Reproduce CI with a fresh clone and fresh volumes (`COMPOSE_PROJECT_NAME=snigf-ci`, `dev-setup.sh`, `up`, `e2e.sh`, then `down -v` for that project only) |
| An eleventh account failed at registration in a fresh run ("Too many attempts") | The same limit: a run may register ten accounts at most | A new duty goes to an existing person as a second role: the supervisor is also the document officer (`promote supervisor DOCUMENT_OFFICER`, ADR-039); the limit stays |

## Production differences

- The e2e suite runs only against development (`e2e.sh` refuses another profile): it needs
  the superuser bootstrap script and the development CA.
- Each run creates six accounts; with `SNIGF_RATE_REGISTER_PER_HOUR=10` (development), one
  run per hour from the same machine. Never raise the limit for this outside development.
- In CI the same container runs after `docker compose up`; it needs Docker and about 2 GB of
  memory (`SNIGF_E2E_MEMORY`).
