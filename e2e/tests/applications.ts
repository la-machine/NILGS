// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's applications (MVP-02, E-12), through the real screens. Registered by
// flows.spec.ts after the parcel journeys, whose parcel the application names:
//   - an applicant starts the DEMO type, saves an incomplete draft (no reference yet);
//   - a disguised file is refused in words; a real PDF and a PNG are uploaded (E-13);
//   - submitting it lists every missing item, and it stays a draft;
//   - completed with the parcel, it is submitted once: the reference is issued, the answers
//     are frozen, the history shows the submission;
//   - nobody else finds it: another account gets 404 and does not list it;
//   - after submission the files are listed, downloadable as attachments, and no longer added to;
//   - the intake officer asks for a clearer identity document; the applicant adds a version in
//     that category only and responds (E-14);
//   - complete but unpaid, the case cannot be accepted; the applicant pays the DEMO fee through
//     the simulated provider (no real money moves: the card says so), its signed callback comes
//     back through the proxy's provider listener, and the officer accepts the case (E-17);
//   - the reviewer claims it and recommends; the decision authority claims it and records a
//     simulated decision citing a document, under the notice, once the parcel's open overlap
//     alerts are resolved by a cadastral officer (they block it); the applicant reads the
//     decision, never the internal finding (E-15);
//   - the applicant follows where it stands (state, since when, whose turn) and reads who acted
//     only as "you", "the office" or "the system"; the API sends no staff name nor internal
//     reason; the supervisor of the office reads the workload: the decided case counted, the
//     stages it went through timed from the history of states (E-16);
//   - the supervisor of the office reconstructs the case from the audit trail alone, from the
//     submission to the decision, every event intact; the draft's events are the auditor's (E-18);
//   - the auditor finds the creation, the refused and the accepted submission, the uploads, the
//     payment and its callback, the claims, the recommendation and the decision.
import { expect, test } from '@playwright/test'
import { RUN, SIGNED_IN } from './support'
import type { Person } from './support'

/** A minimal PDF and a 1 x 1 PNG: synthetic files, recognised from their bytes. */
const PDF = Buffer.from([
  '%PDF-1.4', '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj', '2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj',
  'trailer << /Root 1 0 R >>', '%%EOF', '',
].join('\n'), 'latin1')
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')

export interface ApplicationPeople {
  applicant: Person; intake: Person; reviewer: Person; decider: Person; surveyor: Person; admin: Person; auditor: Person
  supervisor: Person
}

async function ready(person: Person) {
  await person.home()
  const signIn = person.page.getByRole('button', { name: 'Sign in' })
  await expect(signIn.or(person.heading(SIGNED_IN))).toBeVisible()
  if (await signIn.isVisible()) await person.signIn()
}

export function applicationJourneys(people: () => ApplicationPeople, parcel: () => string) {
  let applicant: Person
  let intake: Person
  let reviewer: Person
  let decider: Person
  let surveyor: Person
  let admin: Person
  let auditor: Person
  let supervisor: Person
  let applicationId = ''

  test('an applicant drafts an application: no reference before submission, every missing item listed', async () => {
    ({ applicant, intake, reviewer, decider, surveyor, admin, auditor, supervisor } = people())
    await ready(applicant)
    const list = await applicant.open('My applications')
    await list.getByRole('link', { name: 'New application' }).first().click()
    const page = applicant.page
    await expect(page.getByRole('heading', { level: 1, name: 'Land registration request (DEMO)' })).toBeVisible()
    await expect(page.getByText('Proof of right (DEMO)')).toBeVisible()
    await page.getByRole('combobox', { name: /^Land use/ }).selectOption('RESIDENTIAL')
    await page.getByRole('button', { name: 'Save the draft' }).click()
    await expect(page).toHaveURL(/#\/applications\/[0-9a-f-]{36}\?saved=1$/)
    applicationId = page.url().split('/applications/')[1].split('?')[0]
    await expect(page.getByRole('heading', { level: 1, name: 'Draft' })).toBeVisible()

    // Documents: a web page named .pdf is refused from its bytes; real files are kept.
    const identity = page.locator('[data-category="DEMO_IDENTITY"]')
    await identity.getByLabel(/^Choose a file for/).setInputFiles(
      { name: 'identity.pdf', mimeType: 'application/pdf', buffer: Buffer.from('<html><script>alert(1)</script></html>') })
    await identity.getByRole('button', { name: 'Upload' }).click()
    await expect(identity.getByText(/Only PDF, JPEG and PNG files are accepted/)).toBeVisible()
    await identity.getByLabel(/^Choose a file for/).setInputFiles({ name: 'identity.pdf', mimeType: 'application/pdf', buffer: PDF })
    await identity.getByRole('button', { name: 'Upload' }).click()
    await expect(identity.getByRole('link', { name: 'identity.pdf' })).toBeVisible()
    const proof = page.locator('[data-category="DEMO_PROOF_OF_RIGHT"]')
    await proof.getByLabel(/^Choose a file for/).setInputFiles({ name: 'deed.png', mimeType: 'image/png', buffer: PNG })
    await proof.getByRole('button', { name: 'Upload' }).click()
    await expect(proof.getByRole('link', { name: 'deed.png' })).toBeVisible()
    await expect(page.getByText(/^Required documents still missing/)).toHaveCount(0)

    await page.getByRole('button', { name: 'Submit the application' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Submit the application' }).click()
    await expect(page.getByText(/item\(s\) to fix before going on/)).toBeVisible()
    await expect(page.getByText('"Statement" is required.').first()).toBeVisible()
    await expect(page.getByText('"Parcel reference" is required.').first()).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'Draft' })).toBeVisible()
  })

  test('completed with an active parcel, it is submitted once and gets its reference', async () => {
    const page = applicant.page
    await page.getByLabel(/^Parcel reference/).fill(parcel())
    await page.getByRole('combobox', { name: /^Applicant's capacity/ }).selectOption('HEIR')
    await page.getByLabel(/^Statement/).fill(`Inherited from my mother, e2e ${RUN} (synthetic data)`)
    await page.getByRole('button', { name: 'Submit the application' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Submit the application' }).click()
    await expect(page.getByText(/^Application submitted: DEMO-APP-\d{4}-\d{6}-[0-9A-Z]$/)).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: /^DEMO-APP-/ })).toBeVisible()
    await expect(page.getByText('As submitted; they can no longer be changed.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Save the draft' })).toHaveCount(0)
    await expect(page.locator('.timeline-item').filter({ hasText: 'Submitted' })).toBeVisible()

    // The files as submitted: downloadable as attachments, no longer added to.
    const kept = page.locator('[data-category="DEMO_IDENTITY"]').getByRole('link', { name: 'identity.pdf' })
    await expect(kept).toBeVisible()
    await expect(page.getByRole('button', { name: /^Upload/ })).toHaveCount(0)
    const [file] = await Promise.all([page.waitForEvent('download'), kept.click()])
    expect(file.suggestedFilename()).toBe('identity.pdf')

    // Someone else's application does not exist for anyone else: this administrator registered as
    // an applicant too, so the endpoint opens for their own records, and answers "not found".
    await ready(admin)
    expect((await admin.api('GET', `/applications/${applicationId}`)).status).toBe(404)
    const theirs = (await admin.api('GET', '/applications')).body as { id: string }[]
    expect(theirs.map((x) => x.id)).not.toContain(applicationId)
  })

  test('the intake officer asks for a clearer document, the applicant responds, the case is accepted', async () => {
    await ready(intake)
    const list = await intake.open('Cases', 'Submitted cases')
    await list.locator(`tr[data-case="${applicationId}"]`).getByRole('link').click()
    const officer = intake.page
    await expect(officer.getByText('Nothing required is missing.')).toBeVisible()
    await officer.getByRole('checkbox', { name: 'Identity document (DEMO)' }).check()
    await officer.getByLabel(/^Message to the applicant/).fill('The identity document is unreadable, please send a clearer copy')
    await officer.getByRole('button', { name: 'Request this information' }).click()
    await officer.getByRole('dialog').getByRole('button', { name: 'Request this information' }).click()
    await expect(officer.getByText(/^Information requested: the case waits/)).toBeVisible()

    const page = applicant.page
    await page.goto(`/#/applications/${applicationId}`)
    await expect(page.getByText('The office asks you for information')).toBeVisible()
    await expect(page.getByText('« The identity document is unreadable, please send a clearer copy »').first()).toBeVisible()
    const identity = page.locator('[data-category="DEMO_IDENTITY"]')
    await identity.getByLabel(/^Choose a file for/).setInputFiles({ name: 'identity-clear.pdf', mimeType: 'application/pdf', buffer: PDF })
    await identity.getByRole('button', { name: 'Upload a new version' }).click()
    await expect(identity.getByRole('link', { name: 'identity-clear.pdf' })).toBeVisible()
    await expect(identity.getByText('Earlier versions (1)')).toBeVisible()
    await expect(page.locator('[data-category="DEMO_SITE_PLAN"]').getByRole('button', { name: /^Upload/ })).toHaveCount(0)
    await page.getByLabel(/^Message \(optional\)/).fill('A clearer scan is attached')
    await page.getByRole('button', { name: 'Send my response' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Send my response' }).click()
    await expect(page.getByText('Response sent')).toBeVisible()

    // Complete, but the DEMO fee is unpaid: the acceptance waits (E-17).
    await officer.reload()
    await expect(officer.getByText('Not possible until the DEMO fee is paid (simulated payment).')).toBeVisible()
    await expect(officer.getByRole('button', { name: 'Accept the case as complete' })).toBeDisabled()

    // The applicant pays through the DEMO simulator: nothing real is charged, and the card says so.
    await expect(page.getByText('Demonstration: no real money moves')).toBeVisible()
    await expect(page.getByText('DEMO platform service charge — not an official administrative fee')).toBeVisible()
    await page.getByRole('button', { name: /^Pay .+ \(simulated\)$/ }).click()
    await expect(page.getByText('Paid. The office can now accept the application as complete.')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(/^DEMO-PAY-\d{4}-\d{8}-[0-9A-Z]$/)).toBeVisible()

    // Same address as before: reload to read the case again.
    await officer.reload()
    await expect(officer.locator('[data-payment="PAID"]')).toBeVisible()
    await officer.getByRole('button', { name: 'Accept the case as complete' }).click()
    await officer.getByRole('dialog').getByRole('button', { name: 'Accept the case as complete' }).click()
    await expect(officer.getByText(/accepted: it goes to review\.$/)).toBeVisible()
    await expect(officer.locator('[data-step="COMPLETE"]')).toBeVisible()
  })

  test('the reviewer recommends, another authority records a simulated decision, the applicant reads it', async () => {
    const finding = `Deed and identity checked against the register, e2e ${RUN}`
    await ready(reviewer)
    await (await reviewer.open('Review', 'Review and decision')).locator(`tr[data-review="${applicationId}"]`).getByRole('link').click()
    const review = reviewer.page
    await review.getByRole('button', { name: 'Claim this case' }).click()
    await review.getByRole('dialog').getByRole('button', { name: 'Claim this case' }).click()
    await review.getByRole('radio', { name: 'Recommends approval' }).check()
    await review.getByLabel(/^Finding/).fill(finding)
    await review.getByRole('button', { name: 'Record the recommendation' }).click()
    await review.getByRole('dialog').getByRole('button', { name: 'Record the recommendation' }).click()
    await expect(review.getByText(finding)).toBeVisible()
    await expect(review.getByRole('button', { name: 'Record the recommendation' })).toHaveCount(0)

    await ready(decider)
    await (await decider.open('Review', 'Review and decision')).locator(`tr[data-review="${applicationId}"]`).getByRole('link').click()
    const decide = decider.page
    await decide.getByRole('button', { name: 'Claim this case' }).click()
    await decide.getByRole('dialog').getByRole('button', { name: 'Claim this case' }).click()
    await expect(decide.getByRole('note').filter({ hasText: 'Simulated decision — no legal effect' })).toBeVisible()

    // The parcel still carries the overlap alerts its journey (and earlier runs at the same place)
    // raised: no decision until a cadastral officer resolves them.
    const decideButton = decide.getByRole('button', { name: 'Record the simulated decision' })
    await expect(decide.getByText(/overlap or duplicate alert\(s\) open on the parcel/).first()).toBeVisible()
    await expect(decideButton).toBeDisabled()
    await ready(surveyor)
    const alerts = (await surveyor.api('GET', `/parcels/${parcel()}/alerts`)).body as { id: string; status: string }[]
    for (const alert of alerts.filter((a) => a.status === 'OPEN')) {
      const { version } = (await surveyor.api('GET', `/alerts/${alert.id}`)).body as { version: number }
      const resolved = await surveyor.api('POST', `/alerts/${alert.id}/resolution`,
        { expectedVersion: version, resolution: 'NO_CONFLICT', note: `Checked on site before the decision, e2e ${RUN}` })
      expect(resolved.status, alert.id).toBe(200)
    }
    await decide.reload()
    await expect(decide.getByText(/overlap or duplicate alert\(s\) open on the parcel/)).toHaveCount(0)
    await decide.getByRole('radio', { name: 'Approved (simulated)' }).check()
    await decide.getByLabel(/^Grounds/).fill('The right is proven by the deed and the identity document (synthetic)')
    await decide.getByRole('checkbox', { name: /identity-clear\.pdf/ }).check()
    await decideButton.click()
    await decide.getByRole('dialog').getByRole('button', { name: 'Record the simulated decision' }).click()
    await expect(decide.locator('.decision-card')).toContainText('Approved (simulated)')
    await expect(decide.locator('.decision-card')).toContainText('identity-clear.pdf')

    const page = applicant.page
    await page.goto(`/#/applications/${applicationId}`)
    await page.reload()
    const card = page.locator('.decision-card')
    await expect(card.getByRole('note')).toHaveText('Simulated decision — no legal effect')
    await expect(card).toContainText('Approved (simulated)')
    await expect(card).toContainText('The right is proven by the deed')
    // The review is internal: the applicant reads the decision, never the reviewer's finding.
    await expect(page.getByText(finding)).toHaveCount(0)
  })

  test('the applicant follows where it stands without names; the supervisor reads the workload', async () => {
    const page = applicant.page
    await page.goto(`/#/applications/${applicationId}`)
    await page.reload()
    const tracking = page.locator('.tracking')
    await expect(tracking).toContainText('This application is closed.')
    await expect(page.locator('.timeline-meta').filter({ hasText: /^The office ·/ }).first()).toBeVisible()
    const mine = await applicant.api('GET', `/applications/${applicationId}`)
    expect(mine.status).toBe(200)
    expect(mine.body.waitingOn).toBeNull()
    expect(JSON.stringify(mine.body)).not.toMatch(/actorName|"reason"|RECOMMEND_/)

    const office = mine.body.officeCode as string
    await ready(supervisor)
    const report = await supervisor.open('Workload')
    await expect(report.locator('tr[data-state="APPROVED"]').first()).toBeVisible()
    await expect(report.locator('tr[data-state="COMPLETENESS_REVIEW"]').first()).toBeVisible()
    const summary = (await supervisor.api('GET', `/reports/workload?office=${office}`)).body
    expect(summary.counts.find((c: { state: string }) => c.state === 'APPROVED').cases).toBeGreaterThan(0)
    expect(summary.durations.find((d: { state: string }) => d.state === 'PENDING_DECISION').stays).toBeGreaterThan(0)
    expect((await applicant.api('GET', `/reports/workload?office=${office}`)).status).toBe(403)
  })

  test('the supervisor reconstructs the case from the audit trail alone; the draft is the auditor\'s', async () => {
    const reference = (await applicant.api('GET', `/applications/${applicationId}`)).body.reference as string
    await ready(supervisor)
    const page = supervisor.page
    await page.goto(`/#/cases/${applicationId}`)
    await page.getByRole('link', { name: 'Audit timeline' }).click()
    await expect(page.getByText(`Timeline of ${reference}`)).toBeVisible()
    await expect(page.getByText('Every event intact')).toBeVisible()
    for (const action of ['APPLICATION_SUBMITTED', 'PAYMENT_REQUESTED', 'PAYMENT_CALLBACK_ACCEPTED', 'COMPLETENESS_INFO_REQUESTED',
      'COMPLETENESS_RESPONSE_SENT', 'COMPLETENESS_ACCEPTED', 'CASE_CLAIMED', 'REVIEW_RECOMMENDED', 'DECISION_RECORDED']) {
      await expect(page.locator(`li[data-action="${action}"]`).first(), action).toBeVisible()
    }
    await expect(page.locator('li[data-action="APPLICATION_DRAFT_CREATED"]')).toHaveCount(0)
    const order = await page.locator('li[data-action]').evaluateAll((items) => items.map((i) => Number(i.getAttribute('data-pos'))))
    expect(order).toEqual([...order].sort((a, b) => a - b))

    const theirs = (await supervisor.api('GET', `/audit/timeline?type=APPLICATION&reference=${reference}`)).body
    const all = (await auditor.api('GET', `/audit/timeline?type=APPLICATION&reference=${reference}`)).body
    expect(all.events.map((e: { action: string }) => e.action)).toContain('APPLICATION_DRAFT_CREATED')
    expect(all.events.length).toBeGreaterThan(theirs.events.length)
    expect((await applicant.api('GET', `/audit/timeline?type=APPLICATION&reference=${reference}`)).status).toBe(403)
  })

  test('the auditor finds the draft, the refused and the accepted submission, the documents', async () => {
    await ready(auditor)
    for (const action of ['APPLICATION_DRAFT_CREATED', 'APPLICATION_SUBMIT_REJECTED', 'APPLICATION_SUBMITTED',
      'COMPLETENESS_INFO_REQUESTED', 'COMPLETENESS_RESPONSE_SENT', 'COMPLETENESS_ACCEPTED', 'CASE_CLAIMED', 'REVIEW_RECOMMENDED',
      'DECISION_RECORDED']) {
      const events = (await auditor.api('GET', `/audit/events?action=${action}&entityId=${applicationId}`)).body.events
      expect(events.length, action).toBeGreaterThan(0)
    }
    // The DEMO payment and the simulator's callback, recorded on the payment request.
    const payment = (await applicant.api('GET', `/applications/${applicationId}/payment`)).body as { current: { id: string } }
    for (const action of ['PAYMENT_REQUESTED', 'PAYMENT_CALLBACK_ACCEPTED']) {
      const events = (await auditor.api('GET', `/audit/events?action=${action}&entityId=${payment.current.id}`)).body.events
      expect(events.length, action).toBeGreaterThan(0)
    }
    const rejected = (await auditor.api('GET', `/audit/events?action=DOCUMENT_REJECTED&entityId=${applicationId}`)).body.events
    expect(rejected.length).toBeGreaterThan(0)
    const applicantId = (await applicant.session()).userId
    const uploaded = (await auditor.api('GET', `/audit/events?action=DOCUMENT_UPLOADED&actorId=${applicantId}`)).body.events
    expect(uploaded.length).toBeGreaterThanOrEqual(2)
  })
}
