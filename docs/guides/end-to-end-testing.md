# End-to-end testing in a real browser

*Copyright (c) 2026 FLYT. Author: Pythagore.*

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
   - recovery (ADR-024): the officer loses both keys, signs in with a recovery code (a
     reused code is refused), adds a new key and proves it; then loses everything, an
     administrator resets after the supervisor's confirmation (resetting oneself is refused),
     the old codes are dead, the officer enrols again, and the auditor finds `MFA_RESET`.

## How to do it again

1. Start the stack: `docker compose up -d --wait`.
2. Run `bash infra/scripts/e2e.sh`.
3. Open `e2e/out/report/index.html` for the report; failed tests keep a trace in
   `e2e/out/results/` (open with `npx playwright show-trace <file>`).

To add a journey, add a `test(...)` to `flows.spec.ts` using `Person`: `signIn()`, `home()`,
`panel(title)`, `api(method, path, body)`, `answerNextDialog(answer)`. Select elements by role
and English label, as a person reads them; never by CSS class unless there is no label.

## How to verify

```sh
docker compose up -d --wait
bash infra/scripts/e2e.sh     # expected: "1 passed" (branding), "1 passed" (accounts), "9 passed" (flows)
```

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| The headless browser of the developer tools refused the site (`ERR_CERT_AUTHORITY_INVALID`) and had no WebAuthn | Neither trusted the development CA nor exposed the WebAuthn DevTools domain | This container: CA in Chromium's NSS database, virtual keys through CDP |
| A second registration with the same virtual key fails (`InvalidStateError`) | The server lists the first credential in `excludeCredentials` | Remove the first virtual key and add a new one before the second registration |
| The result of a special grant's last use disappeared at once | The session refresh after the use closed the panel the result was shown in | The audit panel stays open for the visit once shown; only its buttons follow the access |
| `strict mode violation ... resolved to 2 elements` on "Reason" or "Search" | Two controls with the same label in one panel (role request and second-factor reset; event search and the checkpoint list button) | Scope to the form that holds the button; the checkpoint button became "Show checkpoints" |
| A second run within the hour fails at registration (429) | `SNIGF_RATE_REGISTER_PER_HOUR=10` per address, six accounts per run | Wait, or in development only restart the application (`docker compose restart app`), whose limiter is in memory |

## Production differences

- The e2e suite runs only against development (`e2e.sh` refuses another profile): it needs
  the superuser bootstrap script and the development CA.
- Each run creates six accounts; with `SNIGF_RATE_REGISTER_PER_HOUR=10` (development), one
  run per hour from the same machine. Never raise the limit for this outside development.
- In CI the same container runs after `docker compose up`; it needs Docker and about 2 GB of
  memory (`SNIGF_E2E_MEMORY`).
