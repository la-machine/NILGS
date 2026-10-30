// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Shared helpers: this run's accounts, a person with their own browser context and
// virtual security key (Chrome DevTools WebAuthn domain), sign-in through the real screens,
// and API calls made from the page exactly as the interface makes them (cookies, CSRF).
import { readFileSync } from 'node:fs'
import { expect } from '@playwright/test'
import type { Browser, BrowserContext, CDPSession, Locator, Page } from '@playwright/test'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is not set (run infra/scripts/e2e.sh)`)
  return value
}

export const RUN = required('E2E_RUN_ID')
export const OFFICE = required('E2E_OFFICE')
export const STATE_FILE = `/out/state-${RUN}.json`

/** The accounts of a run; e2e.sh promotes the staff ones between the two phases. */
export const PEOPLE = ['applicant', 'admin1', 'admin2', 'officer', 'auditor', 'supervisor', 'surveyor', 'intake', 'reviewer', 'decider'] as const
export type Who = (typeof PEOPLE)[number]

export const email = (who: Who) => `e2e-${RUN}-${who}@demo.invalid`
export const displayName = (who: Who) => `Test ${who}`

export function password(): string {
  return JSON.parse(readFileSync(STATE_FILE, 'utf8')).password
}

/** The interface starts in French and remembers the choice; the tests read the English texts. */
export async function english(page: Page) {
  const toEnglish = page.getByRole('button', { name: 'English', exact: true })
  const toFrench = page.getByRole('button', { name: 'Français', exact: true })
  await expect(toEnglish.or(toFrench).first()).toBeVisible()
  if (await toEnglish.isVisible()) await toEnglish.click()
  await expect(toFrench.first()).toBeVisible()
}

/** The signed-in shell's mark: the dashboard greets the person. */
export const SIGNED_IN = /^Welcome, /

export class Person {
  private authenticator = ''
  /** The recovery codes shown at the last enrolment, as a person would write them down. */
  recoveryCodes: string[] = []

  private constructor(readonly who: Who, readonly context: BrowserContext, readonly page: Page,
                      private readonly cdp: CDPSession) {
  }

  static async open(browser: Browser, who: Who): Promise<Person> {
    const context = await browser.newContext()
    const page = await context.newPage()
    const cdp = await context.newCDPSession(page)
    await cdp.send('WebAuthn.enable', { enableUI: false })
    const person = new Person(who, context, page, cdp)
    person.authenticator = await person.addAuthenticator()
    return person
  }

  /** A virtual roaming key that verifies the user, as the server requires. */
  private async addAuthenticator(): Promise<string> {
    const { authenticatorId } = await this.cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2', transport: 'usb', hasResidentKey: true, hasUserVerification: true,
        isUserVerified: true, automaticPresenceSimulation: true,
      },
    })
    return authenticatorId
  }

  /** The second key of the enrolment: a different authenticator, as a real officer would use. */
  private async replaceAuthenticator() {
    await this.cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId: this.authenticator })
    this.authenticator = await this.addAuthenticator()
  }

  /** Every registered key is lost: the browser now holds only a new, unregistered key. */
  async loseKeys() {
    await this.replaceAuthenticator()
  }

  async home() {
    await this.page.goto('/')
    await english(this.page)
  }

  heading(name: string | RegExp): Locator {
    return this.page.getByRole('heading', { name, exact: typeof name === 'string' })
  }

  /** Opens a place from the sidebar and waits for its page; returns the page's content. */
  async open(place: string, title: string = place): Promise<Locator> {
    const link = this.page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: place, exact: true })
    if (!(await link.isVisible())) await this.home()
    await link.click()
    await expect(this.page.getByRole('heading', { level: 1, name: title, exact: true })).toBeVisible()
    return this.page.locator('main')
  }

  /** The first factor only; returns the screen the server sends next. */
  async enterPassword(): Promise<'enrol' | 'verify' | 'session'> {
    const page = this.page
    await this.home()
    await page.getByLabel('E-mail address').fill(email(this.who))
    await page.getByLabel('Password').fill(password())
    await page.getByRole('button', { name: 'Sign in' }).click()
    const enrol = this.heading('Register your security keys')
    const verify = this.heading('Second factor')
    const session = this.heading(SIGNED_IN)
    await expect(enrol.or(verify).or(session)).toBeVisible()
    if (await enrol.isVisible()) return 'enrol'
    return (await verify.isVisible()) ? 'verify' : 'session'
  }

  /** Enrolment: two different keys, then the ten recovery codes, kept by the person. */
  async enrolTwoKeys() {
    const page = this.page
    await page.getByLabel('Name of this authenticator').fill('First key')
    await page.getByRole('button', { name: 'Register this authenticator' }).click()
    await expect(page.getByText('1 of 2 registered.')).toBeVisible()
    await this.replaceAuthenticator()
    await page.getByLabel('Name of this authenticator').fill('Second key')
    await page.getByRole('button', { name: 'Register this authenticator' }).click()
    await expect(this.heading('Your recovery codes')).toBeVisible()
    await expect(page.locator('ol.codes li')).toHaveCount(10)
    this.recoveryCodes = (await page.locator('ol.codes li').allInnerTexts()).map((code) => code.trim())
    await page.getByLabel('I have stored these codes safely').check()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(this.heading(SIGNED_IN)).toBeVisible()
  }

  /** Signs in through the screens: password, then enrolment of two keys or proof of one. */
  async signIn() {
    const next = await this.enterPassword()
    if (next === 'enrol') {
      await this.enrolTwoKeys()
    } else if (next === 'verify') {
      await this.page.getByRole('button', { name: 'Use my security key' }).click()
    }
    await expect(this.heading(SIGNED_IN)).toBeVisible()
  }

  async signOut() {
    const menu = this.page.locator('.user-menu-button')
    if (await menu.isVisible()) await menu.click()
    await this.page.getByRole('button', { name: 'Sign out' }).first().click()
    await expect(this.page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  }

  /** A call from the page, with the session cookie and the CSRF header, as the interface sends it. */
  async api(method: string, path: string, body?: unknown, extra: Record<string, string> = {}): Promise<{ status: number; body: any }> {
    return this.page.evaluate(async ({ method, path, body, extra }) => {
      const csrf = await (await fetch('/api/v1/auth/csrf')).json()
      const cookie = document.cookie.split(';').map((c) => c.trim().split('='))
        .find(([name]) => name === csrf.cookieName)
      const headers: Record<string, string> = { 'Content-Type': 'application/json', ...extra }
      if (cookie) headers[csrf.headerName] = decodeURIComponent(cookie[1])
      const response = await fetch(`/api/v1${path}`, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      })
      const text = await response.text()
      return { status: response.status, body: text ? JSON.parse(text) : null }
    }, { method, path, body, extra })
  }

  async session(): Promise<any> {
    const result = await this.api('GET', '/auth/session')
    expect(result.status).toBe(200)
    return result.body
  }

  /**
   * Answers the dialog an action opened: a reason (typed, then confirmed), true to confirm,
   * false to cancel. Call it after the click that opens the dialog.
   */
  async respond(answer: string | boolean) {
    const dialog = this.page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    if (typeof answer === 'string') await dialog.getByRole('textbox').fill(answer)
    if (answer === false) await dialog.getByRole('button', { name: 'Cancel' }).click()
    else await dialog.locator('button[type="submit"]').click()
    await expect(dialog).toHaveCount(0)
  }
}
