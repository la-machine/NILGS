// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The logo (frontend/BRAND.md) loads through the proxy: the full logo on the sign-in screen
// and the favicon (the emblem in the signed-in sidebar is covered by the design tour).
// Creates no account; saves /out/sign-in.png for a visual check.
import { expect, test } from '@playwright/test'
import { english } from './support'

test('the sign-in screen shows the SNIGF logo and the tab its emblem', async ({ page }) => {
  await page.goto('/')
  await english(page)
  const logo = page.locator('img.auth-logo')
  await expect(logo).toBeVisible()
  expect(await logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true)
  await expect(logo).toHaveAttribute('alt', /National Integrated Land Governance System/)
  const favicon = await page.locator('link[rel="icon"]').first().getAttribute('href')
  // Fetched by the browser, which trusts the development CA (Node's own client does not).
  expect(await page.evaluate(async (href) => (await fetch(href!)).headers.get('content-type'), favicon)).toBe('image/png')
  await page.screenshot({ path: '/out/sign-in.png', fullPage: true })
})
