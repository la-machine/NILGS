# Front-end source layout

*Copyright (c) 2026 FLYT. Author: Pythagore.*

React 19 + TypeScript, built by Vite, served by the proxy on the same origin as the API.
The layout mirrors the back end's modules: an application shell, a shared layer, and one
folder per feature. `npm run lint` checks the rules below (`scripts/check-architecture.mjs`)
and fails naming each breach.

```text
src/
├── main.tsx                 entry: global styles + <App/>
├── app/                     the shell: wires features, owns no business screen
│   ├── App.tsx              which screen (the server's nextStep), run() = busy state + error message
│   ├── Layout.tsx           demonstration banner, header with logo and language switch
│   ├── Panels.tsx           back-office panels opened by the session's permissions
│   └── screens.ts           the sign-in screens as a type
├── shared/                  used by everyone, imports nothing above it
│   ├── api/client.ts        the only HTTP client: same origin, CSRF header, ApiError
│   ├── i18n/                fr.ts (reference), en.ts (same keys, checked), t(), fill()
│   ├── session/             Session types; access.ts: role or special grant for a permission
│   └── ui/                  Section, Brand (logo), errorMessage()
├── features/                one folder per back-end capability
│   ├── auth/                sign-in, registration, WebAuthn, recovery codes, session screen
│   ├── accounts/            directory, role requests and four-eyes decisions, revocation, reset
│   ├── roles/               permission catalogue, roles, role change requests
│   ├── grants/              special grants
│   └── audit/               event search, chain verification, checkpoints, public keys
└── styles/                  tokens, base elements, layout, components (plain CSS)
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
| Every file starts with the FLYT / Pythagore signature | project rule |

## Where to look when something is wrong

| Symptom | Start here |
| --- | --- |
| A message "Something went wrong" | `shared/ui/errorMessage.ts`: the API code has no text; add it to both `fr.ts` and `en.ts` (`errors`) |
| 403 `CSRF_INVALID` | `shared/api/client.ts` `csrfHeader()`; the token changes at login and logout |
| The wrong screen after sign-in | `app/App.tsx` `follow()`: the server's `nextStep` decides |
| A security key is refused | `features/auth/webauthn.ts` (base64url conversions), then the server log |
| A panel does not appear | `app/Panels.tsx` and `shared/session/access.ts`: compare with `permissions` and `specialGrants` in `GET /api/v1/auth/session` |
| A special-grant action spent a use unexpectedly | `features/audit/AuditPanel.tsx` `perform()`: only confirmed, successful calls spend one |
| A layout problem | `styles/layout.css` (frame and panels) or `styles/components.css` |

## Adding a feature

1. `features/<name>/api.ts`: types and endpoints with `call()`.
2. `features/<name>/<Name>Panel.tsx` (and smaller components beside it).
3. `features/<name>/index.ts`: export what the shell needs.
4. Open it from `app/Panels.tsx` with the permission that protects it.
5. Texts in `shared/i18n/fr.ts` and `en.ts`; a journey in `e2e/tests/flows.spec.ts`.
