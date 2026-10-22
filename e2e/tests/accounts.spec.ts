// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Phase 1: registers this run's accounts through the registration screen. The password is
// random per run and kept in /out/state-<run>.json (mode 0600, git-ignored), never in an
// environment variable. infra/scripts/e2e.sh then makes the staff accounts staff.
import { randomBytes } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { PEOPLE, STATE_FILE, displayName, email, english } from './support'

test('the accounts of this run register through the interface', async ({ page }) => {
  const password = `Pw-${randomBytes(15).toString('base64url')}-7q`
  writeFileSync(STATE_FILE, JSON.stringify({ password }), { mode: 0o600 })
  for (const who of PEOPLE) {
    await page.goto('/')
    await english(page)
    await page.getByRole('button', { name: 'Create an applicant account' }).click()
    await page.getByLabel('E-mail address').fill(email(who))
    await page.getByLabel('Display name').fill(displayName(who))
    await page.getByLabel('Password').fill(password)
    await page.getByRole('button', { name: 'Create account' }).click()
    await expect(page.getByText('If the address was new, the account is created.')).toBeVisible()
  }
})
