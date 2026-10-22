// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Phase 2: the security journeys through the real screens, in order, each person in their
// own browser context with their own virtual security keys.
//   - sign-in: an applicant with a password; staff enrol two keys, then prove one;
//   - four eyes: a role assignment and a custom role exist only after a second administrator
//     approves, and the requester cannot approve, not even by calling the API;
//   - special grants: approved by another administrator, shown to the beneficiary, never
//     spent by viewing or by a cancelled confirmation, spent once by the action, audited;
//   - audit: the auditor verifies the chain, finds the grant use and signs a checkpoint;
//     the supervisor verifies the chain without reading events;
//   - recovery (ADR-024): both keys lost -> a single-use recovery code, then a new key; every
//     key and code lost -> a reset by another administrator after the supervisor's
//     confirmation, old codes dead, enrolment again; the reset is in the audit trail.
import { expect, test } from '@playwright/test'
import { OFFICE, Person, RUN, email } from './support'

test.describe.configure({ mode: 'serial' })

let applicant: Person
let admin1: Person
let admin2: Person
let officer: Person
let auditor: Person
let supervisor: Person
let officerId: string
let auditorId: string

test.beforeAll(async ({ browser }, info) => {
  info.setTimeout(300_000)
  applicant = await Person.open(browser, 'applicant')
  admin1 = await Person.open(browser, 'admin1')
  admin2 = await Person.open(browser, 'admin2')
  officer = await Person.open(browser, 'officer')
  auditor = await Person.open(browser, 'auditor')
  supervisor = await Person.open(browser, 'supervisor')
})

test.afterAll(async () => {
  for (const person of [applicant, admin1, admin2, officer, auditor, supervisor]) {
    await person?.context.close()
  }
})

test('an applicant signs in with a password and sees no back-office panel', async () => {
  await applicant.signIn()
  await expect(applicant.page.locator('dd').first()).toHaveText('APPLICANT')
  await expect(applicant.heading('Account administration')).toHaveCount(0)
  await expect(applicant.heading('Audit trail')).toHaveCount(0)
})

test('staff enrol two security keys, then sign in again by proving one', async () => {
  for (const person of [admin1, admin2, officer, auditor, supervisor]) {
    await person.signIn()
  }
  officerId = (await officer.session()).userId
  auditorId = (await auditor.session()).userId
  await officer.signOut()
  await officer.signIn()
  expect((await officer.session()).authLevel).toBe('FULL')
})

test('a role takes effect only after a second administrator approves it', async () => {
  const accounts = admin1.panel('Account administration')
  await accounts.getByLabel('Search (address or name)').fill(email('officer'))
  await accounts.getByRole('button', { name: 'Search', exact: true }).click()
  const card = accounts.locator('article.account', { hasText: email('officer') })
  const form = card.locator('form', { has: admin1.page.getByRole('button', { name: 'Request the role' }) })
  await form.getByLabel('Role').selectOption('CADASTRAL_OFFICER')
  await form.getByLabel('Office code').fill(OFFICE)
  await form.getByLabel('Reason (required, kept in the audit trail)').fill('Transferred to the cadastral unit')
  await form.getByRole('button', { name: 'Request the role' }).click()
  await expect(accounts.getByText('Request recorded: another administrator must approve the assignment.')).toBeVisible()

  const pending = (await admin1.api('GET', '/admin/assignment-requests?status=PENDING')).body
    .find((r: any) => r.userId === officerId && r.role === 'CADASTRAL_OFFICER')
  expect(pending).toBeTruthy()
  const row = accounts.locator(`li[data-request="${pending.id}"]`)
  await expect(row.getByText('your request: another administrator decides')).toBeVisible()
  await expect(row.getByRole('button', { name: 'Approve' })).toHaveCount(0)
  const own = await admin1.api('POST', `/admin/assignment-requests/${pending.id}/approve`, { reason: 'Trying myself' })
  expect(own.status).toBe(403)
  expect(own.body.code).toBe('SOD_FOUR_EYES')
  expect((await officer.session()).roles).not.toContain('CADASTRAL_OFFICER')

  await admin2.home()
  const toApprove = admin2.panel('Account administration').locator(`li[data-request="${pending.id}"]`)
  admin2.answerNextDialog('Checked with the head of office')
  await toApprove.getByRole('button', { name: 'Approve' }).click()
  await expect(toApprove).toHaveCount(0)

  await officer.home()
  await expect(officer.page.locator('dd').first()).toContainText('CADASTRAL_OFFICER')
})

test('a custom role exists only after a second administrator approves it', async () => {
  const code = `E2E_ROLE_${RUN}`
  const roles = admin1.panel('Roles and permissions')
  await roles.getByRole('button', { name: 'Propose a new role' }).click()
  await roles.getByLabel('Code (UPPER_CASE_WITH_UNDERSCORES)').fill(code)
  await roles.getByLabel('Name in French').fill('Lecteur de parcelles (test)')
  await roles.getByLabel('Name in English').fill('Parcel reader (test)')
  await roles.locator('fieldset label', { hasText: 'parcel.read' }).locator('select').selectOption('ANY')
  await roles.getByLabel('Reason for the request (at least 10 characters)').fill('Read-only profile for the end-to-end test')
  await roles.getByRole('button', { name: 'Send the request' }).click()
  await expect(roles.getByText('Request recorded: another administrator must approve it.')).toBeVisible()
  await expect(roles.locator('li', { hasText: code }).getByText('your request: another administrator decides'))
    .toBeVisible()
  expect((await admin1.api('GET', '/admin/roles')).body.map((r: any) => r.code)).not.toContain(code)

  await admin2.home()
  const toApprove = admin2.panel('Roles and permissions').locator('li', { hasText: code })
  admin2.answerNextDialog('Profile reviewed')
  await toApprove.getByRole('button', { name: 'Approve' }).click()
  await expect(admin2.panel('Roles and permissions').locator('summary', { hasText: code })).toBeVisible()
})

test('a special grant is spent only by the action, after confirmation, never by viewing', async () => {
  const grants = admin1.panel('Special grants')
  await grants.getByText('Request a special grant').click()
  await grants.getByLabel('Beneficiary id (see the account directory)').fill(officerId)
  await grants.getByLabel('Permission').selectOption('audit.key.read')
  await grants.getByLabel('Maximum number of uses (optional)').fill('1')
  await grants.getByLabel('Reason for the request (at least 10 characters)').fill('Check the published checkpoint keys once')
  await grants.getByRole('button', { name: 'Send the request' }).click()
  await expect(grants.getByText('Request recorded: another administrator must approve it.')).toBeVisible()

  await admin2.home()
  const toApprove = admin2.panel('Special grants').locator('li', { hasText: officerId })
  admin2.answerNextDialog('One check only')
  await toApprove.getByRole('button', { name: 'Approve' }).click()
  await expect(toApprove).toHaveCount(0)

  await officer.home()
  await expect(officer.page.getByText('Special grants in force')).toBeVisible()
  await expect(officer.page.getByText('1 use(s) left').first()).toBeVisible()
  const keys = officer.panel('Audit trail').getByRole('button', { name: /^Public keys/ })
  await expect(keys).toContainText('special grant')

  await officer.home()
  expect((await officer.session()).specialGrants[0].usesLeft).toBe(1)
  officer.answerNextDialog(false)
  await keys.click()
  expect((await officer.session()).specialGrants[0].usesLeft).toBe(1)

  officer.answerNextDialog(true)
  await keys.click()
  await expect(officer.page.getByText('BEGIN PUBLIC KEY').first()).toBeVisible()
  await expect(officer.page.getByText('Special grants in force')).toHaveCount(0)
  await expect(keys).toHaveCount(0)
  expect((await officer.session()).specialGrants).toHaveLength(0)
  expect((await officer.api('GET', '/audit/checkpoint-keys')).status).toBe(403)
})

test('the auditor verifies the chain and finds the use of the special grant', async () => {
  await auditor.home()
  const audit = auditor.panel('Audit trail')
  await audit.getByRole('button', { name: 'Verify the whole chain' }).click()
  await expect(audit.getByText(/^Chain intact/)).toBeVisible()
  const search = audit.locator('form', { has: auditor.page.getByLabel('Action') })
  await search.getByLabel('Action').fill('PERMISSION_GRANT_USED')
  await search.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(audit.locator('table.events tr', { hasText: officerId }).first()).toContainText('SUCCESS')

  // Decision D5: the auditor signs a checkpoint on demand; it is recorded and listed.
  await audit.getByRole('button', { name: 'Sign a checkpoint' }).click()
  await audit.getByRole('button', { name: 'Show checkpoints' }).click()
  await expect(audit.locator('li', { hasText: /^#\d+/ }).first()).toBeVisible()
  const created = (await auditor.api('GET', '/audit/events?action=AUDIT_CHECKPOINT_CREATED')).body.events
  expect(created.some((e: any) => e.actorId === auditorId && e.outcome === 'SUCCESS')).toBe(true)
})

test('a supervisor verifies the chain without reading audit events', async () => {
  await supervisor.home()
  const audit = supervisor.panel('Audit trail')
  await expect(audit.getByLabel('Action')).toHaveCount(0)
  await audit.getByRole('button', { name: 'Verify the whole chain' }).click()
  await expect(audit.getByText(/^Chain intact/)).toBeVisible()
  expect((await supervisor.api('GET', '/audit/events')).status).toBe(403)
})

test('a staff member who lost both keys signs in with a recovery code, once, then registers a new key', async () => {
  const [first, second] = officer.recoveryCodes
  expect(officer.recoveryCodes).toHaveLength(10)
  await officer.signOut()
  await officer.loseKeys()

  expect(await officer.enterPassword()).toBe('verify')
  await officer.page.getByRole('button', { name: 'I lost my keys: use a recovery code' }).click()
  await officer.page.getByLabel('Recovery code').fill(first)
  await officer.page.getByRole('button', { name: 'Confirm' }).click()
  await expect(officer.page.getByText('Recovery codes left: 9')).toBeVisible()

  await officer.signOut()
  expect(await officer.enterPassword()).toBe('verify')
  await officer.page.getByRole('button', { name: 'I lost my keys: use a recovery code' }).click()
  await officer.page.getByLabel('Recovery code').fill(first)
  await officer.page.getByRole('button', { name: 'Confirm' }).click()
  await expect(officer.page.getByText('Verification failed. Try again with a registered key.')).toBeVisible()
  await officer.page.getByLabel('Recovery code').fill(second)
  await officer.page.getByRole('button', { name: 'Confirm' }).click()
  await expect(officer.page.getByText('Recovery codes left: 8')).toBeVisible()

  // The browser's new key replaces the lost ones; the next sign-in proves it.
  await officer.page.getByRole('button', { name: 'Add an authenticator' }).click()
  await expect(officer.heading(/^Session — /)).toBeVisible()
  await officer.signOut()
  expect(await officer.enterPassword()).toBe('verify')
  await officer.page.getByRole('button', { name: 'Use my security key' }).click()
  await expect(officer.heading(/^Session — /)).toBeVisible()
})

test('when every key and code is lost, another administrator resets after the supervisor confirms', async () => {
  const oldCode = officer.recoveryCodes[2]
  await officer.signOut()
  await officer.loseKeys()

  const self = await admin1.api('POST', `/admin/users/${(await admin1.session()).userId}/mfa-reset`,
    { reason: 'Resetting my own second factor', confirmedBy: 'myself' })
  expect(self.status).toBe(403)
  expect(self.body.code).toBe('SOD_SELF_ACTION')

  await admin1.home()
  const accounts = admin1.panel('Account administration')
  await accounts.getByLabel('Search (address or name)').fill(email('officer'))
  await accounts.getByRole('button', { name: 'Search', exact: true }).click()
  const card = accounts.locator('article.account', { hasText: email('officer') })
  await card.getByText('Reset the second factor').click()
  const reset = card.locator('form', { has: admin1.page.getByRole('button', { name: 'Reset', exact: true }) })
  await reset.getByLabel('Reason (required, kept in the audit trail)').fill('Lost both keys and the code sheet')
  await reset.getByLabel('Identity confirmed by (name, means)').fill('Test supervisor, in person, ID card checked')
  await reset.getByRole('button', { name: 'Reset', exact: true }).click()
  await expect(accounts.getByText('Done.')).toBeVisible()

  expect(await officer.enterPassword()).toBe('enrol')
  const dead = await officer.api('POST', '/auth/mfa/recovery', { code: oldCode })
  expect(dead.status, 'an old recovery code is dead after the reset').not.toBe(200)
  await officer.enrolTwoKeys()
  expect(officer.recoveryCodes).toHaveLength(10)
  expect(officer.recoveryCodes).not.toContain(oldCode)

  await auditor.home()
  const audit = auditor.panel('Audit trail')
  const search = audit.locator('form', { has: auditor.page.getByLabel('Action') })
  await search.getByLabel('Action').fill('MFA_RESET')
  await search.getByRole('button', { name: 'Search', exact: true }).click()
  const row = audit.locator('table.events tr', { hasText: 'Test supervisor, in person, ID card checked' })
  await expect(row).toContainText((await admin1.session()).userId)
  // The two lost keys were still registered next to the replacement: the reset removes all three.
  await expect(row).toContainText('"authenticatorsRemoved":3')
})
