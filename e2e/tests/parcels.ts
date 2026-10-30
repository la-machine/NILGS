// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Parcels and their map views (ADR-029), through the real screens. Registered by
// flows.spec.ts after its journeys, with the same people: a virtual security key lives in
// its browser context, so staff enrolled there can sign in again only there.
//   - a cadastral officer records a parcel; the server issues the reference and the area;
//   - an invalid geometry is refused with its reason, never repaired, and nothing is created;
//   - the technical view draws the boundary with a grid, scale, readout and measurement;
//   - the three views are offered together; one without data explains why and offers the
//     technical view back; available ones render from our own archive; switching never reads
//     or changes the parcel, and the browser contacts no origin but ours, no CSP violation;
//   - a correction makes version 2 with its reason, and the history keeps version 1;
//   - a second parcel over the first raises an overlap alert (MVP-04), shown at once and
//     listed on both parcels for their reviewers;
//   - the alert is reviewed on its own page (both parcels on one map, side by side); its
//     author cannot resolve it, another cadastral officer does, with an outcome and a note;
//   - a supervisor reads parcels but cannot record or correct them; an administrator and an
//     applicant see no parcel at all (decision D14);
//   - the auditor finds the creation and the correction in the audit trail.
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { OFFICE, RUN, SIGNED_IN } from './support'
import type { Person } from './support'

/** About 33 m × 33 m in Yaoundé, inside the synthetic imagery installed by e2e.sh. */
const square = {
  type: 'Polygon',
  coordinates: [[[11.5, 3.87], [11.5003, 3.87], [11.5003, 3.8703], [11.5, 3.8703], [11.5, 3.87]]],
}
/** The same square, its east side moved 10 m east. */
const corrected = {
  type: 'Polygon',
  coordinates: [[[11.5, 3.87], [11.50039, 3.87], [11.50039, 3.8703], [11.5, 3.8703], [11.5, 3.87]]],
}
/** Self-intersecting: a bow tie. */
const bowTie = {
  type: 'Polygon',
  coordinates: [[[11.5, 3.87], [11.5003, 3.8703], [11.5003, 3.87], [11.5, 3.8703], [11.5, 3.87]]],
}

export interface ParcelPeople { cadastral: Person; colleague: Person; supervisor: Person; admin: Person; applicant: Person; auditor: Person }

/** Signed in already (by the earlier journeys), or signs in now. */
async function ready(person: Person) {
  await person.home()
  const signIn = person.page.getByRole('button', { name: 'Sign in' })
  await expect(signIn.or(person.heading(SIGNED_IN))).toBeVisible()
  if (await signIn.isVisible()) await person.signIn()
}

function watch(page: Page, foreign: string[], csp: string[]) {
  const origin = new URL(process.env.E2E_BASE_URL!).origin
  page.on('request', (request) => {
    const url = request.url()
    if (!url.startsWith(origin) && !url.startsWith('data:') && !url.startsWith('blob:')) foreign.push(url)
  })
  page.on('console', (message) => {
    if (/Content Security Policy/i.test(message.text())) csp.push(message.text())
  })
}

export function parcelJourneys(people: () => ParcelPeople): () => string {
  let cadastral: Person
  let colleague: Person
  let supervisor: Person
  let admin: Person
  let applicant: Person
  let auditor: Person
  let reference = ''
  let alertPath = ''
  const foreignRequests: string[] = []
  const cspViolations: string[] = []

  test('a cadastral officer records a parcel; the server issues the reference and the area', async () => {
    ({ cadastral, colleague, supervisor, admin, applicant, auditor } = people())
    watch(cadastral.page, foreignRequests, cspViolations)
    await ready(cadastral)
    const parcels = await cadastral.open('Parcels')
    // On an empty register the empty state offers the same link as the page header.
    await parcels.getByRole('link', { name: 'New parcel' }).first().click()
    const form = cadastral.page.locator('main')
    await expect(form.getByRole('combobox', { name: /^Office/ })).toHaveValue(OFFICE)
    await form.getByLabel(/^Locality/).fill(`Mvog-Ada, e2e ${RUN}`)
    await form.getByLabel(/^GeoJSON \(Polygon/).fill(JSON.stringify(bowTie))
    await expect(form.getByText('Polygon: 1 polygon(s), 4 vertices').first()).toBeVisible()
    await form.getByRole('button', { name: 'Record the parcel' }).click()
    // Refused next to the boundary, with the server's reason; nothing created, nothing lost.
    await expect(form.getByText('Invalid geometry.')).toBeVisible()
    await expect(form.getByText(/Server's explanation:/)).toBeVisible()
    await expect(form.getByLabel(/^GeoJSON \(Polygon/)).toHaveValue(JSON.stringify(bowTie))
    expect((await cadastral.api('GET', `/parcels?office=${OFFICE}&q=${RUN}`)).body).toHaveLength(0)

    await form.getByLabel(/^GeoJSON \(Polygon/).fill(JSON.stringify(square))
    await form.getByRole('button', { name: 'Record the parcel' }).click()
    const notice = cadastral.page.getByRole('status').filter({ hasText: /Parcel DEMO-PCL-/ })
    await expect(notice).toContainText(new RegExp(`Parcel DEMO-PCL-${OFFICE}-\\d{4}-\\d{6}-[0-9A-Z*] recorded`))
    reference = (await notice.innerText()).match(/DEMO-PCL-[0-9A-Z*-]+/)![0]
    const detail = cadastral.page.locator(`[data-reference="${reference}"]`)
    await expect(detail.getByRole('heading', { level: 1, name: reference })).toBeVisible()
    await expect(detail).toContainText('EPSG:4326')
    await expect(detail).toContainText('1 of 1')
  })

  test('the technical view draws the parcel in UTM metres, with grid, scale, readout and measurement', async () => {
    const detail = cadastral.page.locator(`[data-reference="${reference}"]`)
    const plan = detail.getByRole('img', { name: /UTM 32N \(EPSG:32632\)/ })
    await expect(plan).toBeVisible()
    await expect(detail.getByTestId('parcel-boundary')).toHaveAttribute('d', /^M.*Z$/)
    await expect(plan.locator('.grid line').first()).toBeAttached()
    await expect(plan.locator('.scale-bar text')).toHaveText(/ m$/)

    // Positions relative to the plan: Playwright scrolls it into view before each action.
    const box = (await plan.boundingBox())!
    const at = (fx: number) => ({ position: { x: box.width * fx, y: box.height / 2 } })
    await plan.hover(at(0.5))
    await expect(detail.getByText(/E \d+\.\d{2} m · N \d+\.\d{2} m/)).toBeVisible()

    await detail.getByRole('button', { name: 'Measure' }).click()
    await plan.click(at(0.3))
    await plan.click(at(0.7))
    await expect(detail.getByTestId('measurement')).toContainText(/Measured length: \d+\.\d{2} m/)
  })

  test('every background is offered: available ones render from our archive, missing ones say why', async () => {
    const page = cadastral.page
    const detail = page.locator(`[data-reference="${reference}"]`)
    const backgrounds: any[] = (await cadastral.api('GET', '/map/backgrounds')).body
    expect(backgrounds.map((b) => b.kind)).toEqual(['TECHNICAL', 'GEOGRAPHIC', 'SATELLITE'])
    // e2e.sh installs synthetic imagery when none is: the satellite view must be available.
    expect(backgrounds.find((b) => b.kind === 'SATELLITE').available).toBe(true)

    const parcelCalls: string[] = []
    const rangeStatuses: number[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/parcels')) parcelCalls.push(`${request.method()} ${request.url()}`)
    })
    page.on('response', (response) => {
      if (response.url().includes('/tiles.pmtiles')) rangeStatuses.push(response.status())
    })

    const label = { GEOGRAPHIC: 'Geographic', SATELLITE: 'Satellite' } as const
    await expect(detail.getByRole('radio')).toHaveCount(3)
    for (const background of backgrounds.filter((b) => b.kind !== 'TECHNICAL')) {
      const radio = detail.getByRole('radio', { name: new RegExp(label[background.kind as keyof typeof label]) })
      await radio.check()
      if (!background.available) {
        const panel = detail.getByTestId(`unavailable-${background.kind.toLowerCase()}`)
        await expect(panel).toContainText(/not installed|invalid|missing/)
        await panel.getByRole('button', { name: 'Switch to the technical view' }).click()
        await expect(detail.getByTestId('parcel-boundary')).toBeVisible()
        continue
      }
      const canvas = detail.getByTestId(`tile-view-${background.kind.toLowerCase()}`)
      await expect(canvas).toHaveAttribute('data-loaded', 'true', { timeout: 30_000 })
      await expect(detail.getByText(background.attribution, { exact: false }).first()).toBeVisible()
      if (background.synthetic) await expect(detail.getByText(/SYNTHETIC TEST imagery/)).toBeVisible()
    }
    await detail.getByRole('radio', { name: /Technical/ }).check()
    await expect(detail.getByTestId('parcel-boundary')).toBeVisible()

    expect(parcelCalls).toEqual([])
    expect(rangeStatuses.length).toBeGreaterThan(0)
    expect(rangeStatuses.every((status) => status === 206)).toBe(true)
    expect((await cadastral.api('GET', `/parcels/${reference}`)).body.currentVersion).toBe(1)
  })

  test('tiles are served only to a signed-in user who may read parcels', async ({ browser }) => {
    const anonymous = await browser.newContext()
    const page = await anonymous.newPage()
    await page.goto('/')
    const status = await page.evaluate(async () =>
      (await fetch('/api/v1/map/satellite/tiles.pmtiles', { headers: { Range: 'bytes=0-15' } })).status)
    await anonymous.close()
    expect(status).toBe(401)
  })

  test('a correction makes version 2 with its reason; the history keeps version 1', async () => {
    const detail = cadastral.page.locator(`[data-reference="${reference}"]`)
    await detail.getByRole('button', { name: 'Correct the geometry' }).click()
    await expect(detail.getByRole('heading', { name: 'Correction mode' })).toBeVisible()
    await detail.getByLabel(/^GeoJSON \(Polygon/).fill(JSON.stringify(corrected))
    await detail.getByLabel(/^Reason for the correction/).fill('East boundary re-surveyed on site')
    await detail.getByRole('button', { name: 'Save the new version' }).click()
    await expect(detail).toContainText('2 of 2')
    await expect(detail.locator('ol.versions li')).toHaveCount(2)
    await expect(detail.locator('ol.versions')).toContainText('East boundary re-surveyed on site')

    await detail.locator('ol.versions li', { hasText: 'Version 1 —' }).getByRole('button').click()
    await expect(detail.getByText('Showing version 1: history, read-only.')).toBeVisible()
    await expect(detail.getByRole('button', { name: 'Correct the geometry' })).toHaveCount(0)
  })

  test('a parcel over another raises an overlap alert, shown at once and listed for reviewers', async () => {
    const page = cadastral.page
    await page.goto('/#/parcels/new')
    await page.getByLabel(/^Locality/).fill(`Mvog-Ada east, e2e ${RUN}`)
    // Half of it lies over the parcel recorded above (now its version 2).
    await page.getByLabel(/^GeoJSON \(Polygon/).fill(JSON.stringify({
      type: 'Polygon', coordinates: [[[11.5002, 3.87], [11.5005, 3.87], [11.5005, 3.8703], [11.5002, 3.8703], [11.5002, 3.87]]],
    }))
    await page.getByRole('button', { name: 'Record the parcel' }).click()
    await expect(page.getByText(/^This version raised \d+ overlap or duplicate alert/)).toBeVisible()
    const second = (await page.getByRole('heading', { level: 1 }).innerText()).trim()
    expect(second).toMatch(/^DEMO-PCL-/)

    const alerts = page.locator('section.card', { has: page.getByRole('heading', { name: 'Overlap alerts' }) })
    const withFirst = alerts.locator('li', { has: page.getByRole('link', { name: reference }) })
    await expect(withFirst).toContainText(/Overlap|Duplicate geometry/)
    await expect(withFirst).toContainText(/Intersection \d+\.\d{2} m²/)

    // The same alert, seen from the first parcel.
    await withFirst.getByRole('link', { name: reference }).click()
    await expect(page.getByRole('heading', { level: 1, name: reference })).toBeVisible()
    const line = alerts.locator('li', { has: page.getByRole('link', { name: second }) })
    await expect(line).toBeVisible()
    alertPath = (await line.getByRole('link', { name: 'Review' }).getAttribute('href')) ?? ''
    expect(alertPath).toMatch(/^#\/alerts\/[0-9a-f-]{36}$/)
  })

  test('the alert is reviewed on its own page; its author cannot resolve it, a colleague does', async () => {
    // The author: the office's open alerts, then the review, without a form.
    const author = cadastral.page
    const list = await cadastral.open('Alerts', 'Overlap and duplicate alerts')
    await expect(list.locator(`tr[data-alert="${alertPath.split('/').pop()}"]`)).toBeVisible()
    await author.goto(`/${alertPath}`)
    await expect(author.getByRole('img', { name: /over each other, intersection hatched/ })).toBeVisible()
    await expect(author.getByText('You cannot resolve this alert')).toBeVisible()
    await expect(author.getByRole('button', { name: 'Record the resolution' })).toHaveCount(0)
    // Through the API too: refused (and audited) by the server, whatever the screen shows.
    const id = alertPath.split('/').pop()
    const own = await cadastral.api('POST', `/alerts/${id}/resolution`, {
      expectedVersion: 0, resolution: 'NO_CONFLICT', note: 'Trying to close it myself',
    })
    expect(own.status).toBe(403)
    expect(own.body.code).toBe('RESOLVER_IS_AUTHOR')

    // A colleague at the same office, who authored neither version, resolves it.
    await ready(colleague)
    const page = colleague.page
    await page.goto(`/${alertPath}`)
    await page.getByRole('button', { name: 'Zoom to the intersection' }).click()
    await expect(page.getByRole('button', { name: 'Zoom to the intersection' })).toHaveAttribute('aria-pressed', 'true')
    await page.getByRole('radio', { name: /Referred for adjudication/ }).check()
    await page.getByLabel(/^Justification note/).fill(`Both claims documented on site, e2e ${RUN}`)
    await page.getByRole('button', { name: 'Record the resolution' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Resolve' }).click()
    await expect(page.getByText('Alert resolved')).toBeVisible()
    await expect(page.getByText(`Both claims documented on site, e2e ${RUN}`)).toBeVisible()

  })

  test('a supervisor reads the parcel but cannot change it; an administrator and an applicant see none', async () => {
    await ready(supervisor)
    const parcels = await supervisor.open('Parcels')
    await expect(parcels.getByRole('link', { name: 'New parcel' })).toHaveCount(0)
    // Readable everywhere: the office field suggests the person's offices (a combobox).
    await parcels.getByRole('combobox', { name: /^Office/ }).fill(OFFICE)
    await parcels.getByLabel('Reference or locality').fill(RUN)
    await parcels.getByRole('button', { name: 'Search', exact: true }).click()
    await parcels.getByRole('link', { name: reference }).click()
    await expect(supervisor.page.locator(`[data-reference="${reference}"]`)).toContainText('2 of 2')
    await expect(supervisor.page.getByRole('button', { name: 'Correct the geometry' })).toHaveCount(0)
    // Reading parcels is not reviewing alerts (alert.review is the cadastral officers').
    await expect(supervisor.page.getByRole('heading', { name: 'Overlap alerts' })).toHaveCount(0)
    expect((await supervisor.api('GET', `/parcels/${reference}/alerts`)).status).toBe(403)
    expect((await supervisor.api('POST', `/parcels/${reference}/versions`, {
      expectedVersion: 2, srid: 4326, geometry: square, locality: 'Mvog-Ada', reason: 'not allowed to do this',
    })).status).toBe(403)

    for (const person of [admin, applicant]) {
      await ready(person)
      await expect(person.page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Parcels' })).toHaveCount(0)
      expect((await person.api('GET', `/parcels/${reference}`)).status).toBe(403)
      expect((await person.api('GET', '/map/backgrounds')).status).toBe(403)
    }
  })

  test('the auditor finds the creation, the refusal and the correction', async () => {
    await ready(auditor)
    const actor = (await cadastral.session()).userId
    for (const action of ['PARCEL_CREATED', 'PARCEL_CREATE_REJECTED', 'PARCEL_CORRECTED', 'CONFLICT_ALERT_RAISED',
      'CONFLICT_ALERT_RESOLVE_REJECTED']) {
      const events = (await auditor.api('GET', `/audit/events?action=${action}&actorId=${actor}`)).body.events
      expect(events.length, action).toBeGreaterThan(0)
    }
    const colleagueId = (await colleague.session()).userId
    const resolved = (await auditor.api('GET', `/audit/events?action=CONFLICT_ALERT_RESOLVED&actorId=${colleagueId}`)).body.events
    expect(resolved.length).toBe(1)
  })

  test('the browser contacted no origin but ours, without any CSP violation', () => {
    expect(foreignRequests).toEqual([])
    expect(cspViolations).toEqual([])
  })

  return () => reference
}
