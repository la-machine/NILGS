# Front-end source layout

*Copyright (c) 2026 FLYT. Author: Pythagore.*

React 19 + TypeScript, built by Vite, served by the proxy on the same origin as the API.
The layout mirrors the back end's modules: an application shell, a shared layer, and one
folder per feature. `npm run lint` checks the rules below (`scripts/check-architecture.mjs`)
and fails naming each breach. The visual language (tokens, components, layout rules) is
described in [chapter 10](../../docs/technical/10-frontend-architecture.md#design-system).

```text
src/
├── main.tsx                 entry: global styles + <App/>
├── app/                     the shell: wires features, owns no business screen
│   ├── App.tsx              sign-in steps (the server's nextStep), then the signed-in shell;
│   │                        run() = busy state + error message; re-reads the session on navigation
│   ├── AuthLayout.tsx       sign-in frame: the institution on one side, the step on the other
│   ├── AppShell.tsx         signed-in frame: sidebar, top bar, account menu, mobile drawer
│   ├── DemoBanner.tsx       the demonstration notice, both languages, every screen
│   ├── navigation.ts        the places and who sees them (permissions, special grants)
│   ├── Pages.tsx            which page the address shows; "no access" otherwise; pages load lazily
│   └── screens.ts           the sign-in steps as a type
├── shared/                  used by everyone, imports nothing above it
│   ├── api/client.ts        the only HTTP client: same origin, CSRF header, ApiError
│   ├── i18n/                fr.ts (reference), en.ts (same keys, checked), t(), fill()
│   ├── router/router.ts     addresses by fragment (#/parcels/REF?view=satellite), no library
│   ├── session/             Session, PageContext; access.ts: can, canAt, canSomewhere, reach, accessTo
│   └── ui/                  Icon (own set), Button, Card + PageHeader, Field, Feedback (Alert,
│                            Badge, Spinner, Skeleton, EmptyState), Dialog (+ dialogContext),
│                            Section (sign-in card), errorMessage()
├── features/                one folder per back-end capability
│   ├── dashboard/           the first page: what waits, what to start, latest parcels, map data, security
│   ├── auth/                sign-in steps, WebAuthn, recovery codes, My account (profile, roles, keys)
│   ├── accounts/            pending role assignments, directory, role requests, revocation, reset requests
│   ├── roles/               permission catalogue, roles by family and scope, change proposals and decisions
│   ├── grants/              special grants: list by status, request (with person lookup), decisions
│   ├── recovery/            the supervisor's identity confirmations
│   ├── audit/               chain verification, checkpoints, public keys, event search
│   ├── applications/        the applicant's applications: list, draft form drawn from the type, submission
│   ├── documents/           an application's documents: one row per category, versions, upload, download
│   ├── cases/               submitted applications for staff: office queue, case page, completeness decision
│   ├── reviews/             review and simulated decision (MVP-05): the assignee's queue, claim, recommendation,
│   │                        return, decision with grounds and citations (ReviewPanel), the review record
│   ├── parcels/             list, record (three steps, preview), detail, correction mode, version history
│   ├── conflicts/           overlap and duplicate alerts (MVP-04): a parcel's alerts, the office list,
│   │                        the review (AlertMap, both parcels, ResolutionPanel), who may resolve (rules.ts)
│   └── maps/                MapView (three views), TechnicalView (SVG, UTM), TileView (MapLibre, lazy),
│                            GeometryPreview, geometry helpers (display only)
└── styles/                  tokens, base elements, layout (frames, grids, breakpoints), components
```

## Rules

| Rule | Why |
| --- | --- |
| `shared/` never imports `app/` or `features/` | the bottom layer stays reusable and free of cycles |
| A feature imports another feature only through its `index.ts` | each feature's `index.ts` is its public face; its other files can change freely |
| A feature never imports `app/` | the shell depends on features, not the reverse |
| Every feature has `api.ts` (its endpoints) and `index.ts` (its exports) | to find a call, open the feature's `api.ts` |
| Every endpoint call goes through `shared/api/client.ts` `call()` | one place for CSRF, cookies and error decoding |
| No sentence in a component: texts come from `shared/i18n` | French and English stay complete; the compiler checks English against French |
| Colours, sizes and durations only from `styles/tokens.css` | one place changes the look; nothing drifts |
| Icons only from `shared/ui/Icon.tsx`, always next to a text or with a `label` | one stroke weight; never an icon without a name |
| Confirmations and reasons through `useDialog()`, never `window.prompt` or `confirm` | the consequence is stated and the reason checked as it is typed |
| A server refusal appears next to the field it concerns (`features/parcels/errors.ts`) | the person sees what to correct; what they typed is kept |
| Every file starts with the FLYT / Pythagore signature | project rule |
| Unit tests sit beside the code as `*.test.ts(x)` (Vitest, jsdom, `npm test`) | logic is tested without a server; journeys are in `e2e/` |

## Where to look when something is wrong

| Symptom | Start here |
| --- | --- |
| A message "Something went wrong" | `shared/ui/errorMessage.ts`: the API code has no text; add it to both `fr.ts` and `en.ts` (`errors`) |
| 403 `CSRF_INVALID` | `shared/api/client.ts` `csrfHeader()`; the token changes at login and logout |
| The wrong screen after sign-in | `app/App.tsx` `follow()`: the server's `nextStep` decides |
| A security key is refused | `features/auth/webauthn.ts` (base64url conversions), then the server log |
| A menu entry is missing, or "Not authorized" | `app/navigation.ts` and `shared/session/access.ts`: compare with `permissions` and `specialGrants` in `GET /api/v1/auth/session` |
| A new role does not show | the session is read again on each navigation and when the window regains focus (`app/App.tsx`); otherwise check that the role was approved |
| A special-grant action spent a use unexpectedly | `features/audit/AuditPage.tsx` `perform()`: only confirmed, successful calls spend one |
| A parcel is refused | the message and PostGIS's reason next to the boundary (`features/parcels/errors.ts`); `features/parcels/geometry.ts` only reads the text |
| A map view says unavailable | `GET /api/v1/map/backgrounds`: the `unavailableReason`; see `docs/guides/map-data.md` |
| The geographic or satellite view stays blank | browser console (WebGL, CSP), then `features/maps/TileView.tsx` and `style.ts`; the technical view never depends on them |
| A field's format is not checked | its `pattern` must compile with the `v` flag (`shared/patterns.test.ts`) |
| No resolution form on an alert | `features/conflicts/rules.ts` `resolveAccess()` and the server's `resolverIsAuthor`: the author of a compared version never resolves |
| An application is "not found" | it is someone else's, or the session is not the owner's: the server answers 404 on purpose (ADR-032); the database shows a row only to the actor named |
| An upload is refused | the code on the row (`TYPE_NOT_ALLOWED`, `PDF_ACTIVE_CONTENT` ...) comes from the server, which reads the bytes; `features/documents/files.ts` only pre-checks size and extension |
| A layout problem | `styles/layout.css` (frames, grids, breakpoints) or `styles/components.css` |

## Adding a page

1. `features/<name>/api.ts`: types and endpoints with `call()`.
2. `features/<name>/<Name>Page.tsx`: a `PageHeader`, then `Card`s; it receives `PageContext`.
3. `features/<name>/index.ts`: export the page (and what other features need).
4. `app/navigation.ts`: the place, its icon and who sees it; `app/Pages.tsx`: load it lazily.
5. Texts in `shared/i18n/fr.ts` and `en.ts`; a journey in `e2e/tests/flows.spec.ts`.
