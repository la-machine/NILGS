// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The logo (frontend/BRAND.md) loads through the proxy: header emblem, full logo on the
// sign-in screen, favicon. Creates no account; saves /out/sign-in.png for a visual check.
import { expect, test } from '@playwright/test'
import { english } from './support'

test('the sign-in screen shows the SNIGF logo and the tab its emblem', async ({ page }) => {
  await page.goto('/')
  await english(page)
  for (const image of [page.locator('h1.brand img'), page.locator('img.brand-logo')]) {
    await expect(image).toBeVisible()
    expect(await image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true)
  }
  await expect(page.locator('img.brand-logo')).toHaveAttribute('alt', /National Integrated Land Governance System/)
  const favicon = await page.locator('link[rel="icon"]').first().getAttribute('href')
  // Fetched by the browser, which trusts the development CA (Node's own client does not).
  expect(await page.evaluate(async (href) => (await fetch(href!)).headers.get('content-type'), favicon)).toBe('image/png')
  await page.screenshot({ path: '/out/sign-in.png', fullPage: true })
})
