// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Phase 2, the DEMO land register (Slice 2, ADR-040), through the real screens with virtual security
// keys: a title proposed then signed; a counter deposit that holds it; a second deposit recorded as
// a conflict, which blocks the transfer; the conflict resolved by a second conservator; the transfer
// registered and signed; a correction confirmed by a second conservator; the extract labelled as not
// a certificate; the history verified again. Everything is synthetic.
import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { RUN } from './support'
import type { Person } from './support'

export interface RegisterPeople {
  first: Person
  second: Person
}

/** A minimal synthetic PDF, recognised from its bytes. */
const PDF = Buffer.from([
  '%PDF-1.4', '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj', '2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj',
  'trailer << /Root 1 0 R >>', '%%EOF', '',
].join('\n'))

const name = (who: string) => `DEMO ${who} ${RUN}`

/** Picks the option of a labelled select by its visible text. */
async function choose(scope: Locator, label: string, option: string) {
  await scope.getByLabel(label).selectOption({ label: option })
}

export function registerJourneys(people: () => RegisterPeople, parcelReference: () => string) {
  let first: Person
  let second: Person
  let titleHref = ''

  const openTitle = async (who: Person) => {
    await who.page.goto(titleHref)
    await expect(who.page.locator('[data-title-page]')).toBeVisible()
    return who.page.locator('main')
  }

  const sign = async (who: Person, scope: Locator, button: string) => {
    await scope.getByRole('button', { name: button }).click()
    await who.respond(true)
  }

  test('a conservator proposes a DEMO title, and it exists only once signed', async () => {
    ({ first, second } = people())
    const main = await first.open('Land register (DEMO)')
    await expect(main.getByText('Demonstration: no legal effect')).toBeVisible()

    const party = main.locator('[data-party-form]')
    for (const who of ['Seller', 'Buyer', 'Rival', 'Corrected Buyer']) {
      await party.getByLabel('Name').fill(name(who))
      await party.getByLabel('Identity number').fill(`DEMO-ID-${who.replace(' ', '')}-${RUN}`)
      await party.getByRole('button', { name: 'Add the party' }).click()
      await expect(main.locator('[data-new-title-form]').getByLabel('Holder').locator('option', { hasText: name(who) })).toHaveCount(1)
    }

    const form = main.locator('[data-new-title-form]')
    await form.getByLabel('Parcel reference').fill(parcelReference())
    await choose(form, 'Holder', name('Seller'))
    await form.getByLabel('Reason').fill('A synthetic title for the journey')
    await form.getByLabel('Evidence references').fill('DEMO-EVIDENCE-1')
    await form.getByRole('button', { name: 'Propose the title' }).click()

    const proposal = main.locator('[data-kind="CREATION"][data-status="PROPOSED"]').filter({ hasText: name('Seller') })
    await expect(proposal).toBeVisible()
    await expect(main.locator('[data-title]').filter({ hasText: name('Seller') })).toHaveCount(0)

    await sign(first, proposal, 'Register with my security key')
    const row = main.locator('[data-title]').filter({ hasText: name('Seller') })
    await expect(row).toBeVisible()
    await row.getByRole('link').click()
    await expect(first.page.locator('[data-title-page]')).toHaveAttribute('data-version', '1')
    titleHref = first.page.url()
  })

  test('a second deposit is recorded as a conflict and blocks the transfer', async () => {
    const main = await openTitle(first)
    const deposit = main.locator('[data-deposit-form]')
    await choose(deposit, 'Depositor', name('Buyer'))
    await deposit.getByLabel('Evidence references').fill('DEMO-DEED-1')
    await deposit.getByRole('button', { name: 'Record the deposit' }).click()
    await expect(main.locator('[data-transaction][data-status="HOLDING"]')).toBeVisible()

    await choose(deposit, 'Depositor', name('Rival'))
    await deposit.getByLabel('Evidence references').fill('DEMO-DEED-2')
    await deposit.getByRole('button', { name: 'Record the deposit' }).click()
    await expect(main.locator('[data-transaction][data-status="IN_CONFLICT"]')).toBeVisible()

    // The deposit keeps its documents: a disguised file is refused, a real one stored, and it registers nothing.
    const holding = main.locator('[data-transaction][data-status="HOLDING"]')
    const documents = holding.locator('[data-deposit-documents]')
    await documents.getByLabel('File', { exact: true }).setInputFiles({ name: 'deed.pdf', mimeType: 'application/pdf', buffer: Buffer.from('<html><script>alert(1)</script></html>') })
    await documents.getByRole('button', { name: 'Add the document' }).click()
    await expect(documents.getByText(/Only PDF, JPEG and PNG files are accepted/)).toBeVisible()
    await documents.getByLabel('File', { exact: true }).setInputFiles({ name: 'deed.pdf', mimeType: 'application/pdf', buffer: PDF })
    await documents.getByRole('button', { name: 'Add the document' }).click()
    await expect(documents.getByRole('link', { name: 'deed.pdf' })).toBeVisible()
    await expect(first.page.locator('[data-title-page]')).toHaveAttribute('data-version', '1')

    // A correction keeps the original beside it, with a reason.
    await documents.getByLabel('Replace deed.pdf:').setInputFiles({ name: 'deed-corrected.pdf', mimeType: 'application/pdf', buffer: Buffer.concat([PDF, Buffer.from('\n% corrected\n')]) })
    await first.respond('The first scan was the wrong page')
    await expect(documents.getByRole('link', { name: 'deed-corrected.pdf' })).toBeVisible()
    await expect(documents.locator('[data-document="deed.pdf"]')).toContainText('Replaced, kept')

    const propose = main.locator('[data-propose-form]')
    await choose(propose, 'New holder', name('Buyer'))
    await propose.getByLabel('Authority reference').fill('DEMO-DEED-1')
    await propose.getByLabel('Reason').fill('The sale deposited at the counter')
    await propose.getByLabel('Evidence references').fill('DEMO-DEED-1')
    await propose.getByRole('button', { name: 'Propose', exact: true }).click()

    const transfer = main.locator('[data-kind="TRANSFER"][data-status="PROPOSED"]')
    await sign(first, transfer, 'Register with my security key')
    await expect(transfer.getByText('A conflict is open on this title: a transfer waits for the decision.')).toBeVisible()
    await expect(first.page.locator('[data-title-page]')).toHaveAttribute('data-version', '1')
  })

  test('who received a deposit does not decide; a second conservator resolves the conflict', async () => {
    let main = await first.open('Land register (DEMO)')
    let conflict = main.locator('[data-conflict]').filter({ hasText: name('Rival') })
    await conflict.getByLabel('Evidence references').fill('DEMO-DECISION-1')
    await conflict.getByRole('button', { name: 'Record the decision' }).click()
    await first.respond('The first deposit stands as filed')
    await expect(conflict.getByText('Whoever received a deposit does not decide this conflict')).toBeVisible()

    main = await second.open('Land register (DEMO)')
    conflict = main.locator('[data-conflict]').filter({ hasText: name('Rival') })
    await conflict.getByLabel('Evidence references').fill('DEMO-DECISION-1')
    await conflict.getByRole('button', { name: 'Record the decision' }).click()
    await second.respond('The first deposit stands as filed')
    await expect(main.locator('[data-conflict]').filter({ hasText: name('Rival') })).toHaveCount(0)
  })

  test('the transfer is registered through the lock and signed; the previous holder stays in the history', async () => {
    const main = await openTitle(first)
    await sign(first, main.locator('[data-kind="TRANSFER"][data-status="PROPOSED"]'), 'Register with my security key')
    await expect(first.page.locator('[data-title-page]')).toHaveAttribute('data-version', '2')
    await expect(main.locator('[data-holders]')).toContainText(name('Buyer'))
    await expect(main.locator('[data-entry="1"]')).toContainText(name('Seller'))
    const closed = main.locator('[data-transaction][data-status="REGISTERED"]')
    await expect(closed).toBeVisible()
    await expect(closed.getByRole('link', { name: 'deed.pdf', exact: true })).toBeVisible()
    await expect(closed.getByRole('button', { name: 'Add the document' })).toHaveCount(0)
  })

  test('a correction is confirmed by a second conservator, who alone can; the original is kept', async () => {
    let main = await openTitle(first)
    const propose = main.locator('[data-propose-form]')
    await propose.getByLabel('Kind of inscription').selectOption('CORRECTION')
    await propose.getByLabel('Entry concerned').selectOption({ label: 'No. 2 · Transfer' })
    await choose(propose, 'New holder', name('Corrected Buyer'))
    await propose.getByLabel('Authority reference').fill('DEMO-CORRECTION-1')
    await propose.getByLabel('Reason').fill('The name was misspelt in the deposit')
    await propose.getByLabel('Evidence references').fill('DEMO-CORRECTION-1')
    await propose.getByRole('button', { name: 'Propose', exact: true }).click()

    const proposed = main.locator('[data-kind="CORRECTION"][data-status="PROPOSED"]')
    await expect(proposed.getByRole('button', { name: 'Register with my security key' })).toHaveCount(0)
    await sign(first, proposed, 'Sign and ask for confirmation')
    const pending = main.locator('[data-kind="CORRECTION"][data-status="CONFIRMATION_PENDING"]')
    await expect(pending).toBeVisible()
    await expect(pending.getByRole('button', { name: 'Confirm with my security key' })).toHaveCount(0)
    await expect(main.locator('[data-holders]')).toContainText(name('Buyer'))

    main = await openTitle(second)
    await sign(second, main.locator('[data-kind="CORRECTION"][data-status="CONFIRMATION_PENDING"]'), 'Confirm with my security key')
    await expect(second.page.locator('[data-title-page]')).toHaveAttribute('data-version', '3')
    await expect(main.locator('[data-holders]')).toContainText(name('Corrected Buyer'))
    await expect(main.locator('[data-entry="2"]')).toContainText('Corrected by no. 3')
    await expect(main.locator('[data-entry="2"]')).toContainText(name('Buyer'))
  })

  test('the extract says it is not a certificate, and the history verifies again', async () => {
    const main = await openTitle(second)
    await main.getByRole('button', { name: 'Show the extract' }).click()
    const extract = main.locator('[data-extract]')
    await expect(extract).toContainText('NOT A CERTIFICATE — DEMONSTRATION DATA')
    await expect(extract.getByRole('link')).toHaveCount(0)
    await expect(extract.getByRole('button')).toHaveCount(0)

    await main.getByRole('button', { name: 'Verify again' }).click()
    await expect(main.locator('[data-verification="intact"]')).toContainText('3 inscriptions, 4 signatures and 3 derived versions checked')
  })
}
