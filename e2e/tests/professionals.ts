// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Phase 2, professional accounts (Slice 3, ADR-041), through the real screens with virtual security
// keys: a notary requests an account on the public form and enrols two keys; one administrator
// verifies the credential and proposes the office; the verifier cannot approve, a second
// administrator does; the notary looks up the journey's title, learns nothing more than its number
// and office, and deposits a sale with a deed; a counter deposit conflicts with it, and the notary
// sees the conflict under review without anything of the other deposit (D17) until a second
// conservator decides it; a conservator sees the deposit's channel and the notary, and registers the
// transfer; the notary sees their deposit registered. Everything is synthetic.
import { expect, test } from '@playwright/test'
import type { Browser } from '@playwright/test'
import { PDF, choose, name as partyName } from './register'
import type { JourneyTitle } from './register'
import { OFFICE, Person, RUN, SIGNED_IN, email, english, password } from './support'

export interface ProfessionalPeople {
  verifier: Person
  approver: Person
  conservator: Person
  /** A second conservator of the office, who decides a conflict on a deposit the first received. */
  secondConservator: Person
}

const notaryName = `DEMO Notary ${RUN}`
const client = `DEMO Notary Client ${RUN}`

export function professionalJourneys(browser: () => Browser, people: () => ProfessionalPeople, title: () => JourneyTitle) {
  let notary: Person
  let verifier: Person
  let approver: Person
  let conservator: Person
  let secondConservator: Person

  test.afterAll(async () => {
    await notary?.context.close()
  })

  test('a notary requests a professional account, enrols two keys and sees only their status', async () => {
    ({ verifier, approver, conservator, secondConservator } = people())
    notary = await Person.open(browser(), 'notary')
    const page = notary.page
    await page.goto('/')
    await english(page)
    await page.getByRole('button', { name: 'Request a professional account' }).click()
    const form = page.locator('[data-professional-request]')
    await form.getByLabel('Profession').selectOption('NOTARY')
    await form.getByLabel('E-mail address').fill(email('notary'))
    await form.getByLabel('Display name').fill(notaryName)
    await form.getByLabel('Password').fill(password())
    await form.getByLabel('Chamber').fill('DEMO Chamber of notaries')
    await form.getByLabel('Registration number').fill(`DEMO-N-${RUN}`)
    await form.getByLabel('Jurisdiction').fill('DEMO Centre')
    await form.getByRole('button', { name: 'Send the request' }).click()
    await expect(page.getByText('If the address was new, the request is recorded.')).toBeVisible()

    expect(await notary.enterPassword()).toBe('enrol')
    await notary.enrolTwoKeys()
    await expect(notary.heading(SIGNED_IN)).toBeVisible()
    const nav = page.getByRole('complementary', { name: 'Main navigation' })
    await expect(nav.getByRole('link')).toHaveText(['Dashboard', 'My professional account', 'My account'])
    const main = await notary.open('My professional account')
    await expect(main.locator('[data-status="REQUESTED"]')).toBeVisible()
    await expect(main.getByText('Deposit on a title')).toHaveCount(0)
    expect((await notary.session()).permissions).toEqual([])
  })

  test('one administrator verifies and proposes the office; only another approves', async () => {
    // The directory lists the newest account first (D20): the notary's request heads it, then a search finds it.
    const directory = await verifier.open('Professionals')
    await expect(directory.locator('[data-professional]').first()).toHaveAttribute('data-professional', email('notary'))
    const filters = directory.locator('[data-directory-filters]')
    await filters.getByLabel('Search: address, name, chamber or registration number').fill(`DEMO-N-${RUN}`)
    await filters.getByRole('button', { name: 'Search', exact: true }).click()
    await expect(directory.locator('[data-directory-total]')).toHaveAttribute('data-directory-total', '1')
    await directory.locator(`[data-professional="${email('notary')}"]`).getByRole('link').click()
    await expect(verifier.page.locator(`[data-professional-detail="${email('notary')}"]`)).toBeVisible()
    const form = verifier.page.locator('[data-verify-form]')
    await form.getByLabel('Professional body').fill('DEMO Chamber of notaries')
    await form.getByLabel('Reference of the check').fill(`DEMO-CHECK-${RUN}`)
    await form.getByLabel('Note').fill('Register of the chamber consulted by telephone')
    await form.getByLabel('Offices').fill(OFFICE)
    await form.getByRole('button', { name: 'Record the verification' }).click()
    await expect(verifier.page.getByText('Verification recorded: a second administrator must approve.')).toBeVisible()
    const activation = verifier.page.locator('[data-kind="ACTIVATION"][data-status="PENDING"]')
    await expect(activation.getByText('your request: another administrator decides')).toBeVisible()
    await expect(activation.getByRole('button', { name: 'Approve' })).toHaveCount(0)
    const id = await activation.getAttribute('data-request')
    const own = await verifier.api('POST', `/admin/professional-requests/${id}/approval`)
    expect(own.status, 'the verifier cannot approve').toBe(403)
    expect((await notary.session()).permissions).toEqual([])

    // The dashboard counts what waits for each (D21): the verifier's own verification waits for another
    // administrator; the approver opens the activations from their tile, the directory filtered on that step.
    await verifier.home()
    await expect(verifier.page.locator('[data-waiting-for-others]')).toBeVisible()
    await approver.home()
    await approver.page.getByRole('link', { name: /verified professional account\(s\) awaiting a decision/ }).click()
    await expect(approver.page.locator(`[data-professional="${email('notary')}"]`)).toBeVisible()

    const main = await approver.open('Professionals')
    await main.locator(`[data-request="${id}"]`).getByRole('button', { name: 'Approve' }).click()
    await approver.respond(true)
    await expect(main.getByText('Decision recorded.')).toBeVisible()
    await expect(main.locator(`[data-request="${id}"]`)).toHaveCount(0)

    expect((await notary.session()).permissions).toContain(`register.deposit.submit@${OFFICE}`)
    const status = await notary.open('My professional account')
    await expect(status.locator('[data-status="ACTIVE"]')).toBeVisible()
  })

  test('the notary looks up the title, learns nothing more, and deposits a sale with a deed', async () => {
    const main = await notary.open('My professional account')
    const lookup = main.locator('[data-title-lookup]')
    await lookup.getByLabel('Title number').fill(`DEMO-TF-NONE-${RUN}`)
    await lookup.getByRole('button', { name: 'Find the title' }).click()
    await expect(main.getByText('No title with this number at an office you serve.')).toBeVisible()

    await lookup.getByLabel('Title number').fill(title().number)
    await lookup.getByRole('button', { name: 'Find the title' }).click()
    const result = main.locator(`[data-lookup-result="${title().number}"]`)
    await expect(result).toContainText('Accepts a deposit')
    // Nothing of the title's holders, charges or history (D16), and no register page.
    await expect(main.getByText(`DEMO Corrected Buyer ${RUN}`)).toHaveCount(0)
    expect((await notary.api('GET', `/register/titles?office=${OFFICE}`)).status).toBe(403)

    const form = main.locator('[data-professional-deposit-form]')
    await form.getByLabel('Client name').fill(client)
    await form.getByLabel('Identity number').fill(`DEMO-ID-NC-${RUN}`)
    await form.getByLabel('Add files').setInputFiles({ name: 'deed.pdf', mimeType: 'application/pdf', buffer: PDF })
    await expect(form.getByLabel('Kind of document for deed.pdf')).toHaveValue('DEED')
    await form.getByRole('button', { name: 'Deposit', exact: true }).click()
    await notary.respond(true)
    const deposit = notary.page.locator('[data-own-deposit]')
    await expect(deposit).toHaveAttribute('data-status', 'HOLDING')
    await expect(deposit.getByRole('link', { name: 'deed.pdf' })).toBeVisible()
  })

  test('a counter deposit conflicts: the notary sees it under review, nothing of the other, until a second conservator decides', async () => {
    await conservator.home()
    await conservator.page.goto(title().href)
    const main = conservator.page.locator('main')
    const form = main.locator('[data-deposit-form]')
    await choose(form, 'Depositor', partyName('Rival'))
    await form.getByLabel('Evidence references').fill(`DEMO-DEED-R-${RUN}`)
    await form.getByRole('button', { name: 'Record the deposit' }).click()
    await expect(main.locator('[data-transaction][data-status="IN_CONFLICT"]')).toBeVisible()

    const status = await notary.open('My professional account')
    const row = status.locator('[data-deposit]').filter({ hasText: title().number })
    await expect(row).toHaveAttribute('data-status', 'HOLDING')
    await expect(row).toContainText('Under review by the conservation')
    await row.getByRole('link').click()
    const own = notary.page.locator('[data-own-deposit]')
    await expect(own).toHaveAttribute('data-conflict', 'UNDER_REVIEW')
    await expect(notary.page.getByText('A conflict is under review')).toBeVisible()
    await expect(notary.page.getByText(partyName('Rival'))).toHaveCount(0)
    await expect(notary.page.getByText(`DEMO-DEED-R-${RUN}`)).toHaveCount(0)

    // The first conservator received the counter deposit; the second decides that the notary's stands.
    const register = await secondConservator.open('Land register (DEMO)')
    const conflict = register.locator('[data-conflict]').filter({ hasText: partyName('Rival') })
    await conflict.getByLabel('Evidence references').fill(`DEMO-DECISION-N-${RUN}`)
    await conflict.getByRole('button', { name: 'Record the decision' }).click()
    await secondConservator.respond('The notary deposited first, with a deed')
    await expect(register.locator('[data-conflict]').filter({ hasText: partyName('Rival') })).toHaveCount(0)

    await notary.page.reload()
    await expect(own).toHaveAttribute('data-conflict', 'RESOLVED')
    await expect(own).toHaveAttribute('data-status', 'HOLDING')
    await expect(notary.page.getByText('The notary deposited first, with a deed')).toHaveCount(0)
  })

  test('a conservator sees who deposited and registers the transfer; the notary sees it registered', async () => {
    // From the dashboard: the conservator's window may still show this title from before the deposit.
    await conservator.home()
    await conservator.page.goto(title().href)
    await expect(conservator.page.locator('[data-title-page]')).toHaveAttribute('data-version', String(title().version))
    const main = conservator.page.locator('main')
    const holding = main.locator('[data-transaction][data-status="HOLDING"]')
    await expect(holding.locator('[data-channel]')).toHaveText('Professional account')
    await expect(holding.locator('[data-deposited-by]')).toHaveText(`${notaryName} (notary)`)
    await expect(holding.getByRole('link', { name: 'deed.pdf' })).toBeVisible()

    const propose = main.locator('[data-propose-form]')
    await propose.getByLabel('New holder').selectOption({ label: client })
    await propose.getByLabel('Authority reference').fill(`DEMO-DEED-N-${RUN}`)
    await propose.getByLabel('Reason').fill('The sale deposited by the notary')
    await propose.getByLabel('Evidence references').fill(`DEMO-DEED-N-${RUN}`)
    await propose.getByRole('button', { name: 'Propose', exact: true }).click()
    await main.locator('[data-kind="TRANSFER"][data-status="PROPOSED"]').getByRole('button', { name: 'Register with my security key' }).click()
    await conservator.respond(true)
    await expect(conservator.page.locator('[data-title-page]')).toHaveAttribute('data-version', String(title().version + 1))
    await expect(main.locator('[data-holders]')).toContainText(client)

    const status = await notary.open('My professional account')
    const row = status.locator('[data-deposit]').filter({ hasText: title().number })
    await expect(row).toHaveAttribute('data-status', 'REGISTERED')
    await row.getByRole('link').click()
    await expect(notary.page.locator('[data-own-deposit]')).toHaveAttribute('data-status', 'REGISTERED')
    await expect(notary.page.getByRole('button', { name: 'Withdraw the deposit' })).toHaveCount(0)
  })
}
