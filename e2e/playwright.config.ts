// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// End-to-end tests against the running stack, through the proxy, in Chromium (ADR-026,
// docs/guides/end-to-end-testing.md). Three projects, run in order by infra/scripts/e2e.sh,
// which promotes the staff accounts between the last two:
//   branding  the logo loads through the proxy (creates no account);
//   accounts  registers this run's accounts through the interface;
//   flows     sign-in with virtual security keys, four-eyes approvals, special grants, audit,
//             then parcels and their map views (tests/parcels.ts).
// The browser trusts the development CA (imported by run.sh); certificate errors are never
// ignored.
import { defineConfig } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL
if (!baseURL?.startsWith('https://')) {
  throw new Error('E2E_BASE_URL must be an https URL')
}

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  workers: 1,
  retries: 0,
  forbidOnly: true,
  reporter: [['list'], ['html', { outputFolder: '/out/report', open: 'never' }]],
  outputDir: '/out/results',
  use: {
    baseURL,
    locale: 'en-GB',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // The container has no GPU: the geographic and satellite views (WebGL) render with
    // Chromium's software renderer, which it enables only when asked. Test browser only.
    launchOptions: { args: ['--enable-unsafe-swiftshader'] },
  },
  projects: [
    { name: 'branding', testMatch: /branding\.spec\.ts/ },
    { name: 'accounts', testMatch: /accounts\.spec\.ts/ },
    { name: 'flows', testMatch: /flows\.spec\.ts/ },
  ],
})
