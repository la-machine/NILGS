# 10. Frontend architecture

*Copyright (c) 2026 FLYT. Author: Pythagore.*

The interface is React 19 + TypeScript built by Vite, served by the proxy (Caddy) on the
same origin as the API, so the session cookie and the CSRF cookie work without CORS.

## Layers

| Layer | Folder | May import | Holds |
| --- | --- | --- | --- |
| Shell | `frontend/src/app/` | features (through `index.ts`), shared | sign-in flow (`App.tsx`), sign-in and signed-in frames, navigation by permission, page routing |
| Features | `frontend/src/features/<name>/` | shared, own files, other features through `index.ts` | one per back-end capability: `api.ts` (endpoints), pages, `index.ts` (public face) |
| Shared | `frontend/src/shared/` | nothing above it | HTTP client with CSRF, router, session types and access, texts FR/EN, the component library |
| Styles | `frontend/src/styles/` | | tokens, base elements, layout, components |

Features today: `dashboard`, `auth`, `accounts`, `roles`, `grants`, `recovery`, `audit`, `parcels`,
`maps`, matching the identity, audit, parcel and maps modules of the back end
([chapter 2](02-backend-architecture.md)).

## Shell, navigation and addresses

Signed out, `AuthLayout` shows the institution (logo, purpose, three commitments) beside the
current step; the server's `nextStep` decides the step. Signed in, `AppShell` frames every
page: the demonstration notice, a sidebar of the places this session can use, a top bar with
the person's offices, the language switch and the account menu. Below 64rem the sidebar
becomes a drawer.

| Address | Page | Shown to |
| --- | --- | --- |
| `#/dashboard` | what waits for a decision, what to start, latest parcels, map data, own security | everyone |
| `#/applications`, `#/applications/new`, `#/applications/<id>` | the applicant's applications: list, new draft, a draft's form (save, delete, submit) or a submitted application: where it stands and whose turn it is, its history naming parties (ADR-032, ADR-036) | `application.read#own` |
| `#/cases`, `#/cases/<id>` | an office's submitted applications (completeness review by default); one case: applicant, automatic check, answers, documents, completeness and history; the intake officer's decision (ADR-034) | `application.read` somewhere |
| `#/reviews`, `#/reviews/<id>` | the reviewer's or decision authority's cases: mine, and those waiting at my stage (`?stage=` for someone holding both); one case beside its review: claim, recommend with a finding, return, or record the simulated decision with grounds and cited documents (ADR-035) | `application.read#assigned` |
| `#/parcels`, `#/parcels/new`, `#/parcels/<reference>` | list and search; record; detail with map, history, correction (`?view=`, `?version=`, `?mode=correct`) | `parcel.read` or `parcel.create` somewhere |
| `#/alerts`, `#/alerts/<id>` | overlap and duplicate alerts of an office (filters in the address); review of one alert: map, both parcels, resolution (ADR-031) | `alert.review` somewhere |
| `#/accounts` | pending role assignments, directory, role and reset requests | `admin.account.list` |
| `#/roles` | roles and permissions, change proposals | `admin.role.read` |
| `#/grants` | special grants | `admin.grant.read` |
| `#/workload` | an office's workload: stages against their DEMO targets, counts per state, load per officer, stage durations, the queue of cases under way (`?office=`, `?state=`, `?overdue=1`) (ADR-036) | `report.workload` somewhere |
| `#/payments` | DEMO payments, simulated (no real money moves): counts, the exceptions to reconcile and their resolution, resolved exceptions, the payment requests (`?view=open\|resolved\|requests`, `?status=`) (ADR-037) | `payment.reconcile` |
| `#/issued-documents` | documents issued by SNIGF: look one up by number, revoke, cancel or re-issue it with a reason; the verification incidents to review, each resolved with a note (ADR-039) | `document.issued.manage` or `document.incident.review` |
| `#/verify`, `#/verify/<number>` | **public, no sign-in**: verify an SNIGF document by number (the record and its status) or by its PDF (the exact file); the address the QR code names (ADR-039) | everyone, signed in or not |
| `#/confirmations` | identity confirmations | `identity.reset.confirm` somewhere |
| `#/audit` | verification, checkpoints, keys, events (a supervisor's limited to their offices), the way to a timeline | an audit permission, by role or special grant |
| `#/audit/timeline` | a case's or a parcel's timeline from the audit trail alone, oldest first, each event in words with who acted, the office and whether it is intact (`?type=APPLICATION\|PARCEL&reference=`) (ADR-038); linked from the case and parcel pages | `audit.event.search` somewhere |
| `#/account` | profile, roles by office, security keys, special grants in force | everyone |

Addresses use the fragment (`shared/router/router.ts`, no library), so they survive a reload
and the back button and need no server rule. A place the session cannot use shows "not
authorized" instead of a page that would only fail; the server refuses the calls anyway.
The session is read again on every navigation and when the window regains focus, so a role
approved meanwhile changes the menu without a reload. Pages are loaded on first use: the
sign-in screens download none of them, and MapLibre loads only with a tiled view.

## Design system

The look is defined once, in `styles/tokens.css`, and used through `shared/ui`:

| Element | Choice |
| --- | --- |
| Colour | forest green from the logo as the anchor (sidebar, primary actions), warm neutral greys, the flag's gold only as an accent (active navigation mark, test tags); semantic success, information, warning and danger with soft backgrounds |
| Type | the system's interface font (Segoe UI Variable on Windows), no web font to download; tabular figures for every number; monospace for references, codes and coordinates |
| Space and shape | 4-pixel spacing scale, radii 4/6/10 px, three shadow levels |
| Icons | `shared/ui/Icon.tsx`: one 24-pixel grid, one stroke width, drawn for the project; always beside a text or with a label |
| Components | `Button` (primary, secondary, ghost, danger), `Card` and `PageHeader`, `Field` (label, hint, error tied by `aria-describedby`), `Alert`, `Badge`, `Spinner`, `Skeleton`, `EmptyState`, `Dialog` |
| States | every list has loading (skeleton), empty (explained, with the next action), error (with retry) |
| Dialogs | only for decisions, revocations, irreversible actions and spending a special grant; a reason is checked as it is typed and kept in the audit trail |
| Responsiveness | breakpoints 80, 64, 48 and 36 rem: side columns move under the main one, the sidebar becomes a drawer, forms become one column, tables become labelled cards, map tools move above a small plan |
| Accessibility | WCAG 2.2 AA as the target: landmarks and a skip link, visible focus, labelled controls, errors announced and tied to their field, status never told by colour alone, reduced motion respected |

## Applications

An applicant's place is "My applications" (`features/applications`, [ADR-032](../adr/032-applications-drafts-and-submission.md)).
The form is drawn from the type's requirements as the server sends them (labels in both
languages, choices, lengths), so a new version of the catalogue needs no release of the
interface. A draft is saved as it is; submitting saves, confirms and submits once (one
`Idempotency-Key` per form). The server's items (`MISSING:statement`, `PARCEL_NOT_FOUND` ...)
become a summary above the form and a message at each field (`form.ts`). The document
documents (`features/documents`, [ADR-033](../adr/033-documents-upload-and-storage.md)) appear once the draft
is saved: one row per category, the latest file as a download link (an attachment), earlier
versions folded, and an upload per row. The interface checks size and extension before
sending to save a wait; the server reads the bytes and decides, and its refusal is shown on
the row in words. A submitted application is read-only: reference, parcel, office, the answers
and documents as submitted, the history.

Staff see submitted applications as cases (`features/cases`, [ADR-034](../adr/034-completeness-review.md)): the
office's queue, waiting longest first, and a case with what the automatic check finds missing,
in words. The intake officer accepts (disabled, with the reason, while anything is missing)
or requests information: the missing categories are ticked, a message of 10 to 2000
characters, confirmed. The applicant then sees the request, adds files in those categories
only, and sends a response. Answers, completeness steps and history are drawn by the same
components for both (`applications/CaseRecord.tsx`). The dashboard counts the cases waiting.

The applicant follows a submitted application in `TrackingCard`: its state, since when, what
it means and whose turn it is. The history and completeness cards name a person for staff and
a party ("You", "The office", "The system") for the applicant, as the server sends it
([ADR-036](../adr/036-tracking-and-workload-report.md)). Supervisors read the workload in
`features/reports` ([tracking and workload guide](../guides/tracking-and-workload.md)): a tile
per stage with a red edge once a case is overdue, counts, load per officer, stage durations
(`formatDuration`), and the queue; a reference opens the case only for someone who also reads
the office's cases. The dashboard counts overdue cases.

The DEMO payment lives in `features/payments` ([ADR-037](../adr/037-simulated-payment-and-reconciliation.md),
[payments guide](../guides/payments.md)). Every view says first that no real money moves.
`PaymentPanel`, on a submitted application, shows the DEMO fee and its label, whether the
completeness review waits for it, and, while it is due, the scenario of the simulated provider
and one Pay button (an `Idempotency-Key` per attempt, kept for a retry of the same attempt);
a pending payment is read again every two seconds, fifteen times at most. The case page shows
the fee's standing, and the intake officer's Accept button says why it waits while it is
unpaid. `FinancePage` (`#/payments`) shows the counts and the exceptions to reconcile, each
explained, resolved with a note after confirmation. The dashboard counts open exceptions.

Documents issued by SNIGF live in `features/issuance` ([ADR-039](../adr/039-issued-documents-and-verification.md),
[issued documents guide](../guides/issued-documents.md)). Every view says first that the document is a
DEMO with no legal value. `AcknowledgmentCard`, on a submitted application and on its case, shows
the number, status and date, the exact PDF to download and the verification page; if the
acknowledgment could not be issued at submission, the applicant asks for it again there (issued
once). `VerifyPage` is the one page outside the session: `App` renders it in `PublicLayout` for
anyone signed out, and inside the shell for anyone signed in. It looks a number up as soon as the
address names one, says a number confirms the record and never the paper, compares an uploaded
PDF byte for byte, never calls an unverified document a forgery, and shows a replacement as a
link. `IssuedDocumentsPage` (`#/issued-documents`) shows the officer's lookup and actions (each
confirmed with a reason) and the incident queue, each part only for its permission.

Reviewers and decision authorities work in `features/reviews` ([ADR-035](../adr/035-review-and-simulated-decision.md)).
The queue shows their cases and those of their office waiting to be claimed at their stage.
A case page shows the case on the left (answers, documents read-only through
`/assigned-cases`, completeness, history) and, on the right, the action panel and the review
record. Every action is confirmed, and a case changed meanwhile overwrites nothing:

- the reviewer chooses a recommendation and writes a finding;
- the decision authority returns the case with a reason, or chooses an outcome, writes the
  grounds and ticks the stored document versions cited;
- the decision button stays disabled while an alert on the parcel is open;
- whoever recommended is shown why they cannot decide, instead of the button.

`DecisionCard` (in `applications/CaseRecord.tsx`) shows a decision to staff and to the applicant
alike. It always opens with the notice "Simulated decision — no legal effect". Its print button
uses the `@media print` rules in `styles/`: the controls and the navigation are hidden, and the
notice and grounds are kept in the print tokens (`--print-ink`, `--print-paper`,
`--print-rule`). The office's readers see the review on the case page (`/cases/<id>/review`).

## Parcels and the map workspace

Recording a parcel is one page in three steps: location; boundary (the coordinate system
chosen in plain words, GeoJSON pasted or loaded, described as it is typed: shape, vertices,
closed rings, numbers that do not look like the chosen system); review with an indicative
area and a preview. The server issues the reference and the official area; a refusal appears
next to the boundary with PostGIS's reason, and everything typed is kept. The detail page
shows identity and status, the map, the properties and the version history (newest first,
with each change of area and its reason). Correction is a separate mode: it says which
version it corrects, previews the proposed boundary, requires a reason, and on a version
saved meanwhile (`STALE_VERSION`) overwrites nothing and offers to reload.

After recording or correcting, a warning says how many overlap or duplicate alerts the new
version raised (the server returns them with the version, [ADR-030](../adr/030-overlap-and-duplicate-detection.md)).
For holders of `alert.review` at the parcel's office (cadastral officers), the detail page
lists the parcel's alerts (`features/conflicts/ParcelAlerts.tsx`): the type, the other parcel
(a link), both version numbers, the intersection area and both shares, or the shared
reference; superseded and resolved ones are folded under a history. Others see no card, and
the server refuses them the list. Each alert opens its review (`#/alerts/<id>`, `features/conflicts`,
[ADR-031](../adr/031-alert-review-and-resolution.md)): `AlertMap` draws both compared versions in one
UTM zone, parcel A solid and B dashed so they differ without colour, the intersection hatched,
with a zoom to the intersection for slivers; the two parcels stand side by side (version compared
and current, author, area, share). An entitled reviewer who authored neither version picks one of
three explained outcomes and writes a note (10 to 2000 characters, counted), confirmed before
sending; the author is told why they cannot resolve; an alert changed meanwhile overwrites nothing.
The dashboard counts open alerts at the reviewer's offices.

`maps` draws the three views of a parcel ([ADR-029](../adr/029-map-views-and-sovereign-map-data.md)).
The technical view (`TechnicalView.tsx`) is SVG in the UTM metres returned by the server: grid,
zoom and pan, scale, coordinate readout (proj4 converts the cursor position for display only),
length and area measurement. It needs neither WebGL nor map data. The geographic and satellite
views (`TileView.tsx`) are loaded only when chosen: MapLibre over our PMTiles archives through
the same-origin API, with its worker served from our origin, so the page keeps
`default-src 'self'`. The three views sit together in a segmented control; each says whether
it works offline or needs the server. A view the server reports unavailable can still be
chosen: it explains why, what would enable it, and offers the technical view; offline, the
tiled views say so. Every view draws the same authoritative geometry, fetched once; the chosen
view is kept in the address.

The full map, the rules and a debugging table are in
[`frontend/src/README.md`](../../frontend/src/README.md). The rules are checked by
`frontend/scripts/check-architecture.mjs`, run by `npm run lint`, as `DddLayeringTest`
does for the back end. The logo files and how they were derived:
[`frontend/BRAND.md`](../../frontend/BRAND.md).

## Principles

- **The server decides.** Sign-in steps follow the server's `nextStep`; places and pages follow the
  session's `permissions` and `specialGrants`; every call is checked again by the server and
  the database. Hiding a button is convenience, never security.
- **One HTTP client.** `shared/api/client.ts` sends cookies, echoes the CSRF token on every
  state-changing call and turns RFC 9457 problems into `ApiError` with the stable code.
- **No hard-coded sentence.** Texts live in `shared/i18n/fr.ts` (reference) and `en.ts`; the
  compiler refuses an English set with a missing key.
- **Special grants are never spent by viewing.** `shared/session/access.ts` says whether a
  permission comes from a role or a grant; grant-only actions ask for confirmation (ADR-026).
- **Verified in a browser.** The end-to-end suite drives these screens in Chromium
  ([guide](../guides/end-to-end-testing.md)); unit and component tests (`*.test.ts(x)` beside
  the code, Vitest in jsdom, `npm test`) cover logic that needs no server.
- **The browser never changes geometry.** It sends what the officer supplied; validation,
  repair refusal, transformation and areas are the server's.
