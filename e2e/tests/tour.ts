// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A screenshot tour of the main screens at three sizes (desktop, tablet, phone), for design
// review: e2e/out/tour/<size>-<screen>.png. Runs only with E2E_TOUR=1 (e2e.sh passes it
// through); it asserts that each screen renders, nothing more.
import { expect, test } from '@playwright/test'
import type { Person } from './support'

const SIZES = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'tablet', width: 834, height: 1112 },
  { name: 'phone', width: 390, height: 844 },
] as const

export interface TourPeople { cadastral: Person; admin: Person; auditor: Person; reference: () => string }

export function designTour(people: () => TourPeople) {
  test('design tour: the main screens at three sizes', async () => {
    test.skip(process.env.E2E_TOUR !== '1', 'set E2E_TOUR=1 to capture the design tour')
    test.setTimeout(300_000)
    const { cadastral, admin, auditor, reference } = people()
    const shot = async (person: Person, size: string, name: string) => {
      await person.page.evaluate(() => window.scrollTo(0, 0))
      await person.page.waitForTimeout(400)
      await person.page.screenshot({ path: `/out/tour/${size}-${name}.png`, fullPage: true })
    }
    for (const size of SIZES) {
      for (const person of [cadastral, admin, auditor]) await person.page.setViewportSize({ width: size.width, height: size.height })
      const page = cadastral.page
      await page.goto('/#/dashboard')
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      await shot(cadastral, size.name, '01-dashboard')
      await page.goto('/#/parcels')
      await expect(page.getByRole('table').or(page.getByText('No parcel recorded at this office yet'))).toBeVisible()
      await shot(cadastral, size.name, '02-parcels')
      await page.goto('/#/parcels/new')
      await page.getByLabel(/^Locality/).fill('Mvog-Ada')
      await page.getByLabel(/^GeoJSON \(Polygon/).fill(JSON.stringify({ type: 'Polygon', coordinates: [[[11.5, 3.87], [11.5004, 3.87], [11.5005, 3.8704], [11.5001, 3.8706], [11.5, 3.87]]] }))
      await shot(cadastral, size.name, '03-new-parcel')
      await page.goto(`/#/parcels/${reference()}`)
      await expect(page.getByTestId('parcel-boundary')).toBeVisible()
      await shot(cadastral, size.name, '04-detail-technical')
      await page.goto(`/#/parcels/${reference()}?view=satellite`)
      await expect(page.locator('[data-loaded="true"]')).toBeVisible({ timeout: 30_000 })
      await shot(cadastral, size.name, '05-detail-satellite')
      await page.goto(`/#/parcels/${reference()}?view=geographic`)
      await page.waitForTimeout(300)
      await shot(cadastral, size.name, '06-detail-geographic')
      await page.goto(`/#/parcels/${reference()}?mode=correct`)
      await expect(page.getByRole('heading', { name: 'Correction mode' })).toBeVisible()
      await shot(cadastral, size.name, '07-correction')
      await admin.page.goto('/#/accounts')
      await expect(admin.page.getByRole('heading', { level: 1 })).toBeVisible()
      await shot(admin, size.name, '08-accounts')
      await admin.page.goto('/#/roles')
      await expect(admin.page.getByRole('heading', { level: 1 })).toBeVisible()
      await shot(admin, size.name, '09-roles')
      await auditor.page.goto('/#/audit')
      await auditor.page.getByLabel('Action').fill('PARCEL_CREATED')
      await auditor.page.getByRole('button', { name: 'Search', exact: true }).click()
      await expect(auditor.page.locator('table.events')).toBeVisible()
      await shot(auditor, size.name, '10-audit')
    }
    for (const person of [cadastral, admin, auditor]) await person.page.setViewportSize({ width: 1280, height: 720 })
  })
}
