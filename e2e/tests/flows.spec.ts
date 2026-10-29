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
//     the supervisor verifies the chain and reads the events of their office only (ADR-038);
//   - recovery (ADR-024, ADR-028): both keys lost -> a single-use recovery code, a new key,
//     and the lost keys removed; every key and code lost -> an administrator asks, the
//     supervisor confirms in the application how the identity was checked, old codes dead,
//     enrolment again; the reset is in the audit trail;
//   - parcels and map views (parcels.ts), with the same people.
import { expect, test } from '@playwright/test'
import { applicationJourneys } from './applications'
import { parcelJourneys } from './parcels'
import { designTour } from './tour'
import { OFFICE, Person, RUN, SIGNED_IN, email } from './support'

test.describe.configure({ mode: 'serial' })

let applicant: Person
let admin1: Person
let admin2: Person
let officer: Person
let auditor: Person
let supervisor: Person
let surveyor: Person
let intake: Person
let reviewer: Person
let decider: Person
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
  surveyor = await Person.open(browser, 'surveyor')
  intake = await Person.open(browser, 'intake')
  reviewer = await Person.open(browser, 'reviewer')
  decider = await Person.open(browser, 'decider')
})

test.afterAll(async () => {
  for (const person of [applicant, admin1, admin2, officer, auditor, supervisor, surveyor, intake, reviewer, decider]) {
    await person?.context.close()
  }
})

test('an applicant signs in with a password and sees no back-office panel', async () => {
  await applicant.signIn()
  const nav = applicant.page.getByRole('complementary', { name: 'Main navigation' })
  await expect(nav.getByRole('link')).toHaveText(['Dashboard', 'My applications', 'My account'])
  await applicant.open('My account')
  await expect(applicant.page.getByText('Applicant', { exact: true }).first()).toBeVisible()
})

test('staff enrol two security keys, then sign in again by proving one', async () => {
  for (const person of [admin1, admin2, officer, auditor, supervisor, surveyor, intake, reviewer, decider]) {
    await person.signIn()
  }
  officerId = (await officer.session()).userId
  auditorId = (await auditor.session()).userId
  await officer.signOut()
  await officer.signIn()
  expect((await officer.session()).authLevel).toBe('FULL')
})

test('a role takes effect only after a second administrator approves it', async () => {
  const accounts = await admin1.open('Accounts', 'Account administration')
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

  const toApprove = (await admin2.open('Accounts', 'Account administration')).locator(`li[data-request="${pending.id}"]`)
  await toApprove.getByRole('button', { name: 'Approve' }).click()
  await admin2.respond('Checked with the head of office')
  await expect(toApprove).toHaveCount(0)

  const account = await officer.open('My account')
  await expect(account.locator('.role-list')).toContainText('Cadastral officer')
})

test('a custom role exists only after a second administrator approves it', async () => {
  const code = `E2E_ROLE_${RUN}`
  const roles = await admin1.open('Roles and permissions')
  await roles.getByRole('button', { name: 'Propose a new role' }).click()
  await roles.getByLabel('Code (UPPER_CASE_WITH_UNDERSCORES)').fill(code)
  await roles.getByLabel('Name in French').fill('Lecteur de parcelles (test)')
  await roles.getByLabel('Name in English').fill('Parcel reader (test)')
  await roles.getByLabel('Scope of parcel.read', { exact: true }).selectOption('ANY')
  await roles.getByLabel('Reason for the request (at least 10 characters)').fill('Read-only profile for the end-to-end test')
  await roles.getByRole('button', { name: 'Send the request' }).click()
  await expect(roles.getByText('Request recorded: another administrator must approve it.')).toBeVisible()
  await expect(roles.locator('li', { hasText: code }).getByText('your request: another administrator decides'))
    .toBeVisible()
  expect((await admin1.api('GET', '/admin/roles')).body.map((r: any) => r.code)).not.toContain(code)

  const others = await admin2.open('Roles and permissions')
  await others.locator('li', { hasText: code }).getByRole('button', { name: 'Approve' }).click()
  await admin2.respond('Profile reviewed')
  await expect(others.locator('summary', { hasText: code })).toBeVisible()
})

test('a special grant is spent only by the action, after confirmation, never by viewing', async () => {
  const grants = await admin1.open('Special grants')
  await grants.getByRole('button', { name: 'Request a special grant' }).click()
  await grants.getByLabel('Beneficiary id (see the account directory)').fill(officerId)
  await grants.getByLabel('Permission', { exact: true }).selectOption('audit.key.read')
  await grants.getByLabel('Maximum number of uses (optional)').fill('1')
  await grants.getByLabel('Reason for the request (at least 10 characters)').fill('Check the published checkpoint keys once')
  await grants.getByRole('button', { name: 'Send the request' }).click()
  await expect(grants.getByText('Request recorded: another administrator must approve it.')).toBeVisible()

  const toApprove = (await admin2.open('Special grants')).locator(`li[data-beneficiary="${officerId}"]`, { hasText: 'audit.key.read' })
  await toApprove.getByRole('button', { name: 'Approve' }).click()
  await admin2.respond('One check only')
  await expect(toApprove.getByRole('button', { name: 'Approve' })).toHaveCount(0)

  const account = await officer.open('My account')
  await expect(account.getByText('Special grants in force')).toBeVisible()
  await expect(account.getByText('1 use(s) left').first()).toBeVisible()
  const audit = await officer.open('Audit trail')
  const keysCard = audit.locator('section.card', { has: officer.heading('Public keys') })
  await expect(keysCard).toContainText('special grant')
  const keys = keysCard.getByRole('button', { name: 'Show the keys' })

  // Viewing the page spent nothing; a cancelled confirmation spends nothing.
  expect((await officer.session()).specialGrants[0].usesLeft).toBe(1)
  await keys.click()
  await officer.respond(false)
  expect((await officer.session()).specialGrants[0].usesLeft).toBe(1)

  await keys.click()
  await officer.respond(true)
  await expect(officer.page.getByText('BEGIN PUBLIC KEY').first()).toBeVisible()
  await expect(keys).toHaveCount(0)
  expect((await officer.session()).specialGrants).toHaveLength(0)
  expect((await officer.api('GET', '/audit/checkpoint-keys')).status).toBe(403)
})

test('the auditor verifies the chain and finds the use of the special grant', async () => {
  const audit = await auditor.open('Audit trail')
  await audit.getByRole('button', { name: 'Verify the whole chain' }).click()
  await expect(audit.getByText(/^Chain intact/)).toBeVisible()
  const search = audit.locator('form', { has: auditor.page.getByLabel('Action') })
  await search.getByLabel('Action').fill('PERMISSION_GRANT_USED')
  await search.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(audit.locator('table.events tr', { hasText: officerId }).first()).toContainText('SUCCESS')

  // Decision D5: the auditor signs a checkpoint on demand; it is recorded and listed.
  // Signing lists the checkpoints at once for an auditor (who may list them).
  await audit.getByRole('button', { name: 'Sign a checkpoint' }).click()
  await expect(audit.getByText(/^Checkpoint signed at position \d+/)).toBeVisible()
  await expect(audit.locator('tr', { hasText: /#\d+/ }).first()).toBeVisible()
  const created = (await auditor.api('GET', '/audit/events?action=AUDIT_CHECKPOINT_CREATED')).body.events
  expect(created.some((e: any) => e.actorId === auditorId && e.outcome === 'SUCCESS')).toBe(true)
})

test('a supervisor verifies the chain and reads the events of their office only (ADR-038)', async () => {
  const audit = await supervisor.open('Audit trail')
  await audit.getByRole('button', { name: 'Verify the whole chain' }).click()
  await expect(audit.getByText(/^Chain intact/)).toBeVisible()
  await expect(audit.getByText(/You read the events of your offices only/)).toBeVisible()
  const read = await supervisor.api('GET', '/audit/events?size=100')
  expect(read.status).toBe(200)
  for (const e of read.body.events as { subjects: { officeCode: string | null }[] }[]) {
    expect(e.subjects.map((s) => s.officeCode)).toContain(OFFICE)
  }
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
  await expect(officer.page.getByText(/Recovery codes left: 9/)).toBeVisible()

  await officer.signOut()
  expect(await officer.enterPassword()).toBe('verify')
  await officer.page.getByRole('button', { name: 'I lost my keys: use a recovery code' }).click()
  await officer.page.getByLabel('Recovery code').fill(first)
  await officer.page.getByRole('button', { name: 'Confirm' }).click()
  await expect(officer.page.getByText('Verification failed. Try again with a registered key.')).toBeVisible()
  await officer.page.getByLabel('Recovery code').fill(second)
  await officer.page.getByRole('button', { name: 'Confirm' }).click()
  await expect(officer.page.getByText(/Recovery codes left: 8/)).toBeVisible()

  // The browser's new key replaces the lost ones; the lost ones are removed (ADR-028), so
  // whoever finds them cannot sign in; the next sign-in proves the new key.
  await officer.page.getByRole('button', { name: 'Add an authenticator' }).click()
  await expect(officer.page.locator('li[data-key="Add an authenticator"]')).toBeVisible()
  for (const lost of ['First key', 'Second key']) {
    await officer.page.locator(`li[data-key="${lost}"]`).getByRole('button', { name: 'Remove (lost)' }).click()
    await officer.respond(true)
    await expect(officer.page.locator(`li[data-key="${lost}"]`)).toHaveCount(0)
  }
  await expect(officer.page.getByText('Fewer than 2 keys registered: add one as soon as possible.')).toBeVisible()
  await expect(officer.page.locator('li[data-key] button')).toHaveCount(0)
  const removed = (await auditor.api('GET', '/audit/events?action=MFA_KEY_REMOVED')).body.events
  expect(removed.filter((e: any) => e.actorId === officerId)).toHaveLength(2)
  await officer.signOut()
  expect(await officer.enterPassword()).toBe('verify')
  await officer.page.getByRole('button', { name: 'Use my security key' }).click()
  await expect(officer.heading(SIGNED_IN)).toBeVisible()
})

test('when every key and code is lost, an administrator asks and the supervisor confirms in the application', async () => {
  const oldCode = officer.recoveryCodes[2]
  await officer.signOut()
  await officer.loseKeys()

  const self = await admin1.api('POST', `/admin/users/${(await admin1.session()).userId}/mfa-reset`,
    { reason: 'Resetting my own second factor' })
  expect(self.status).toBe(403)
  expect(self.body.code).toBe('SOD_SELF_ACTION')

  const accounts = await admin1.open('Accounts', 'Account administration')
  await accounts.getByLabel('Search (address or name)').fill(email('officer'))
  await accounts.getByRole('button', { name: 'Search', exact: true }).click()
  const card = accounts.locator('article.account', { hasText: email('officer') })
  await card.getByText('Reset the second factor').click()
  const reset = card.locator('form', { has: admin1.page.getByRole('button', { name: 'Request a reset' }) })
  await reset.getByLabel('Reason (required, kept in the audit trail)').fill('Lost both keys and the code sheet')
  await reset.getByRole('button', { name: 'Request a reset' }).click()
  await expect(accounts.getByText('Request recorded: the person\'s supervisor must confirm their identity')).toBeVisible()
  await expect(accounts.getByText('waiting for the supervisor\'s confirmation')).toBeVisible()

  // Nothing happened yet: the officer's remaining key still works for the database.
  const pending = (await admin1.api('GET', '/admin/mfa-reset-requests?status=PENDING')).body
    .find((r: any) => r.userId === officerId)
  expect(pending).toBeTruthy()
  const byAdmin = await admin1.api('POST', `/identity/reset-confirmations/${pending.id}/confirm`, { identityCheck: 'myself' })
  expect(byAdmin.status, 'an administrator cannot confirm').toBe(403)

  const confirmations = await supervisor.open('Identity confirmations')
  const request = confirmations.locator(`li[data-reset="${pending.id}"]`)
  await expect(request).toContainText(email('officer'))
  await request.getByLabel('How the identity was checked').fill('In person, national ID card checked')
  await request.getByRole('button', { name: 'Confirm and reset' }).click()
  await supervisor.respond(true)
  await expect(confirmations.getByText('Reset done: the person registers new keys at their next sign-in.')).toBeVisible()

  expect(await officer.enterPassword()).toBe('enrol')
  const dead = await officer.api('POST', '/auth/mfa/recovery', { code: oldCode })
  expect(dead.status, 'an old recovery code is dead after the reset').not.toBe(200)
  await officer.enrolTwoKeys()
  expect(officer.recoveryCodes).toHaveLength(10)
  expect(officer.recoveryCodes).not.toContain(oldCode)

  const audit = await auditor.open('Audit trail')
  const search = audit.locator('form', { has: auditor.page.getByLabel('Action') })
  await search.getByLabel('Action').fill('MFA_RESET')
  await search.getByRole('button', { name: 'Search', exact: true }).click()
  // Earlier runs left the same reason in the trail: this run's row is the one naming its supervisor.
  const supervisorId = (await supervisor.session()).userId
  const row = audit.locator('table.events tr', { hasText: 'In person, national ID card checked' })
    .filter({ hasText: supervisorId })
  await expect(row).toContainText(supervisorId)
  await expect(row).toContainText((await admin1.session()).userId)
  // Only the replacement key was left: the lost ones had been removed by the officer.
  await expect(row).toContainText(/"authenticatorsRemoved": ?1/)
})

// The officer is a cadastral officer since the four-eyes approval above.
const parcelReference = parcelJourneys(() => ({ cadastral: officer, colleague: surveyor, supervisor, admin: admin1, applicant, auditor }))
// The applicant applies for the parcel recorded above (MVP-02).
applicationJourneys(() => ({ applicant, intake, reviewer, decider, surveyor, admin: admin1, auditor, supervisor }), parcelReference)

designTour(() => ({ cadastral: officer, admin: admin1, auditor, reference: parcelReference }))
