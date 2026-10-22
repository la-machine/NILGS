# 10. Frontend architecture

*Copyright (c) 2026 FLYT. Author: Pythagore.*

The interface is React 19 + TypeScript built by Vite, served by the proxy (Caddy) on the
same origin as the API, so the session cookie and the CSRF cookie work without CORS.

## Layers

| Layer | Folder | May import | Holds |
| --- | --- | --- | --- |
| Shell | `frontend/src/app/` | features (through `index.ts`), shared | screen flow (`App.tsx`), layout, back-office panels by permission |
| Features | `frontend/src/features/<name>/` | shared, own files, other features through `index.ts` | one per back-end capability: `api.ts` (endpoints), screens and panels, `index.ts` (public face) |
| Shared | `frontend/src/shared/` | nothing above it | HTTP client with CSRF, session types and access, texts FR/EN, small UI pieces |
| Styles | `frontend/src/styles/` | | tokens, base elements, layout, components |

Features today: `auth`, `accounts`, `roles`, `grants`, `audit`, matching the identity and
audit modules of the back end ([chapter 2](02-backend-architecture.md)).

The full map, the rules and a debugging table are in
[`frontend/src/README.md`](../../frontend/src/README.md). The rules are checked by
`frontend/scripts/check-architecture.mjs`, run by `npm run lint`, as `DddLayeringTest`
does for the back end. The logo files and how they were derived:
[`frontend/BRAND.md`](../../frontend/BRAND.md).

## Principles

- **The server decides.** Screens follow the server's `nextStep`; panels follow the
  session's `permissions` and `specialGrants`; every call is checked again by the server and
  the database. Hiding a button is convenience, never security.
- **One HTTP client.** `shared/api/client.ts` sends cookies, echoes the CSRF token on every
  state-changing call and turns RFC 9457 problems into `ApiError` with the stable code.
- **No hard-coded sentence.** Texts live in `shared/i18n/fr.ts` (reference) and `en.ts`; the
  compiler refuses an English set with a missing key.
- **Special grants are never spent by viewing.** `shared/session/access.ts` says whether a
  permission comes from a role or a grant; grant-only actions ask for confirmation (ADR-026).
- **Verified in a browser.** The end-to-end suite drives these screens in Chromium
  ([guide](../guides/end-to-end-testing.md)).
