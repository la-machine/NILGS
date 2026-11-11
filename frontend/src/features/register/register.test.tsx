// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { references, registerApi, share, signingActFor } from './api'
import type { DepositDocument, DocumentReplacement, Inscription, TitleView } from './api'
import { DepositDocuments } from './DepositDocuments'
import { ProposalItem } from './Proposals'
import { RegisterPage } from './RegisterPage'
import { TitlePage } from './TitlePage'

const ME = 'me'
const inscription = (over: Partial<Inscription> = {}): Inscription => ({
  id: 'i1', titleId: 't1', officeCode: 'DEMO1', number: null, kind: 'TRANSFER', status: 'PROPOSED', expectedVersion: 1, authorityType: 'SYNTHETIC_DEMO',
  authorityReference: 'DEMO-A', reason: 'A synthetic reason', evidenceReferences: ['DEMO-E'], targetInscriptionId: null, areaM2: null, proposedBy: ME,
  proposedByName: 'Me', proposedAt: '2026-10-30T10:00:00Z', confirmedByName: null, registeredByName: null, registeredAt: null, closedByName: null,
  closedReason: null, hash: null, holders: [], charge: null, cancelledByNumber: null, correctedByNumber: null, titleNumber: 'DEMO-TF-DEMO1-000001-A', ...over,
})
const session = {
  userId: ME, displayName: 'Me', permissions: ['register.read@DEMO1', 'register.propose@DEMO1', 'register.inscribe@DEMO1', 'register.confirm@DEMO1', 'register.transaction.decide@DEMO1'],
  specialGrants: [], roles: [],
} as unknown as Session
const ctx = { texts: t('en'), lang: 'en', session } as unknown as PageContext
const view: TitleView = {
  title: {
    id: 't1', number: 'DEMO-TF-DEMO1-000001-A', officeCode: 'DEMO1', origin: 'SYNTHETIC_DEMO', version: 2, status: 'ACTIVE', areaM2: 10000, parcelReference: null,
    holders: [{ partyId: 'p2', partyName: 'DEMO Buyer', interest: 'OWNERSHIP', shareNum: 1, shareDen: 1 }], charges: [], restrictions: [],
  },
  inscriptions: [
    inscription({ id: 'a', kind: 'CREATION', status: 'REGISTERED', number: 1, correctedByNumber: 2, registeredByName: 'Me', registeredAt: '2026-10-30T10:00:00Z',
      holders: [{ partyId: 'p1', partyName: 'DEMO Seller', interest: 'OWNERSHIP', shareNum: 1, shareDen: 1 }] }),
    inscription({ id: 'b', kind: 'CORRECTION', status: 'REGISTERED', number: 2, registeredByName: 'Two', confirmedByName: 'Two', registeredAt: '2026-10-30T11:00:00Z' }),
  ],
  transactions: [], conflicts: [],
}

describe('the rules the screens show', () => {
  it('offers the signing act that applies, and never a self-confirmation', () => {
    expect(signingActFor(inscription(), ME)).toBe('register')
    expect(signingActFor(inscription({ kind: 'CORRECTION' }), ME)).toBe('request-confirmation')
    expect(signingActFor(inscription({ kind: 'CORRECTION' }), 'other')).toBeNull()
    expect(signingActFor(inscription({ kind: 'CORRECTION', status: 'CONFIRMATION_PENDING' }), ME)).toBeNull()
    expect(signingActFor(inscription({ kind: 'CORRECTION', status: 'CONFIRMATION_PENDING' }), 'other')).toBe('confirm')
    expect(signingActFor(inscription({ status: 'REGISTERED' }), ME)).toBeNull()
  })

  it('reads references and shows exact shares', () => {
    expect(references('DEMO-1, DEMO-2\nDEMO-3 ,')).toEqual(['DEMO-1', 'DEMO-2', 'DEMO-3'])
    expect(share({ shareNum: 2, shareDen: 3 })).toBe('2/3')
  })
})

describe('a proposal', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  const item = (i: Inscription, onChanged = vi.fn()) => (
    <DialogProvider>
      <ul><ProposalItem texts={t('en')} lang="en" viewerId={ME} inscription={i} canInscribe canConfirm canPropose onChanged={onChanged} /></ul>
    </DialogProvider>
  )

  it('is signed with the security key only after confirmation', async () => {
    const sign = vi.spyOn(registerApi, 'sign').mockResolvedValue(inscription({ status: 'REGISTERED' }))
    const onChanged = vi.fn()
    render(item(inscription(), onChanged))
    fireEvent.click(screen.getByRole('button', { name: 'Register with my security key' }))
    expect(sign).not.toHaveBeenCalled()
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('.btn-primary') as Element)
    await waitFor(() => expect(sign).toHaveBeenCalledWith('i1', 'register'))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('shows why the database refused', async () => {
    vi.spyOn(registerApi, 'sign').mockRejectedValue(new ApiError(409, { code: 'OPEN_CONFLICT' }))
    render(item(inscription()))
    fireEvent.click(screen.getByRole('button', { name: 'Register with my security key' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('.btn-primary') as Element)
    expect(await screen.findByText(t('en').errors.OPEN_CONFLICT)).toBeTruthy()
  })

  it('offers its proposer no way to confirm their own dual-control entry', () => {
    render(item(inscription({ kind: 'CANCELLATION', status: 'CONFIRMATION_PENDING' })))
    expect(screen.queryByRole('button', { name: t('en').register.signAction.confirm })).toBeNull()
    expect(screen.getByText(t('en').register.dualControl)).toBeTruthy()
  })
})

describe('a title', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  const page = () => render(<DialogProvider><TitlePage ctx={ctx} id="t1" /></DialogProvider>)

  it('shows the derived state and marks corrected entries while keeping them', async () => {
    vi.spyOn(registerApi, 'title').mockResolvedValue(view)
    vi.spyOn(registerApi, 'parties').mockResolvedValue([])
    page()
    expect(await screen.findByText('DEMO Buyer (Ownership, 1/1)')).toBeTruthy()
    expect(screen.getByText('DEMO Seller (Ownership, 1/1)')).toBeTruthy()
    expect(screen.getByText('Corrected by no. 2')).toBeTruthy()
  })

  it('shows how each deposit arrived: the notary\'s account and capacity, or the officer at the counter', async () => {
    const deposit = {
      id: 'x1', titleId: 't1', titleNumber: view.title.number, officeCode: 'DEMO1', kind: 'SALE', depositorName: 'DEMO Client', submittedByName: 'DEMO Notary',
      evidenceReferences: [], status: 'HOLDING', depositedAt: '2026-11-01T10:00:00Z', reviewDueAt: '2026-12-01T10:00:00Z', overdue: false, closedReason: null,
    }
    vi.spyOn(registerApi, 'title').mockResolvedValue({ ...view, transactions: [
      { ...deposit, channel: 'PROFESSIONAL_ACCOUNT', receivedByName: null, submittedByAccount: 'n1', submittedByAccountName: 'DEMO Notary', capacity: 'NOTARY' },
      { ...deposit, id: 'x2', status: 'IN_CONFLICT', channel: 'COUNTER', receivedByName: 'Officer', submittedByAccount: null, submittedByAccountName: null, capacity: null },
    ] })
    vi.spyOn(registerApi, 'parties').mockResolvedValue([])
    vi.spyOn(registerApi, 'documents').mockResolvedValue([])
    page()
    const professional = await waitFor(() => { const e = document.querySelector('[data-transaction="x1"]'); if (!e) throw new Error('x1'); return e as HTMLElement })
    expect(professional.querySelector('[data-channel]')?.textContent).toBe('Professional account')
    expect(professional.querySelector('[data-deposited-by]')?.textContent).toBe('DEMO Notary (notary)')
    expect(professional.textContent).not.toContain('Received by')
    const counter = document.querySelector('[data-transaction="x2"]') as HTMLElement
    expect(counter.querySelector('[data-channel]')?.textContent).toBe('At the counter')
    expect(counter.textContent).toContain('Received by')
    expect(counter.querySelector('[data-deposited-by]')).toBeNull()
  })

  it('labels the extract as not a certificate and offers no download or print', async () => {
    vi.spyOn(registerApi, 'title').mockResolvedValue(view)
    vi.spyOn(registerApi, 'parties').mockResolvedValue([])
    vi.spyOn(registerApi, 'extract').mockResolvedValue({
      label: 'NOT A CERTIFICATE — DEMONSTRATION DATA', titleNumber: view.title.number, officeCode: 'DEMO1', status: 'ACTIVE', version: 2, areaM2: 10000,
      holders: view.title.holders, charges: [], restrictions: [],
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: t('en').register.showExtract }))
    expect(await screen.findByText('NOT A CERTIFICATE — DEMONSTRATION DATA')).toBeTruthy()
    expect(screen.queryByRole('link', { name: /download|pdf/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /print|download/i })).toBeNull()
  })

  it('says when a re-verification finds a problem', async () => {
    vi.spyOn(registerApi, 'title').mockResolvedValue(view)
    vi.spyOn(registerApi, 'parties').mockResolvedValue([])
    vi.spyOn(registerApi, 'verification').mockResolvedValue({
      intact: false, inscriptionsChecked: 2, signaturesChecked: 2, versionsChecked: 2, documentsChecked: 0, problems: ['inscription 1: the row differs from what was signed'],
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: t('en').register.verify }))
    expect(await screen.findByText(t('en').register.verifyBroken)).toBeTruthy()
    expect(screen.getByText('inscription 1: the row differs from what was signed')).toBeTruthy()
  })
})

describe('the documents of a deposit', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  const row: DepositDocument = {
    id: 'd1', transactionId: 'x1', category: 'DEED', sha256: 'a'.repeat(64), sizeBytes: 10, mediaType: 'application/pdf', originalName: 'deed.pdf',
    uploadedBy: ME, uploadedByName: 'Me', uploadedAt: '2026-10-31T10:00:00Z', replacesId: null, replaceReason: null, replacedById: 'd2',
  }
  const asked = (over: Partial<DocumentReplacement> = {}): DocumentReplacement => ({
    id: 'r1', documentId: 'd1', transactionId: 'x1', sha256: 'b'.repeat(64), sizeBytes: 12, mediaType: 'application/pdf', originalName: 'deed-v2.pdf',
    reason: 'The first scan was the wrong page', requestedBy: 'someone-else', requestedByName: 'DEMO Notary', requestedAs: 'PROFESSIONAL',
    requestedAt: '2026-11-05T10:00:00Z', status: 'PENDING', decidedBy: null, decidedByName: null, decidedAt: null, decisionReason: null,
    resultDocumentId: null, ...over,
  })
  const panel = (open: boolean, canConfirm = false) => render(<DialogProvider><DepositDocuments texts={t('en')} lang="en" transactionId="x1" open={open}
    canAdd canConfirm={canConfirm} viewerId={ME} onChanged={vi.fn()} /></DialogProvider>)

  it('says storing registers nothing, and keeps a replaced original readable', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([row])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([])
    panel(true)
    expect((await screen.findByRole('link', { name: 'deed.pdf' })).getAttribute('href')).toBe('/api/v1/register/transactions/x1/documents/d1/content')
    expect(screen.getByText('Replaced, kept')).toBeTruthy()
    expect(screen.getByText(/registers nothing and authorises nothing/)).toBeTruthy()
    expect(screen.queryByLabelText(/^Ask to replace deed.pdf/)).toBeNull()
  })

  it('offers no change once the deposit is closed, and no deletion ever', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([{ ...row, replacedById: null }])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([asked()])
    panel(false, true)
    expect(await screen.findByRole('link', { name: 'deed.pdf' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Add the document' })).toBeNull()
    expect(screen.queryByLabelText(/^Ask to replace deed.pdf/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Approve the replacement' })).toBeNull()
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull()
  })

  it('says why a file was refused', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([])
    vi.spyOn(registerApi, 'addDocument').mockRejectedValue(new ApiError(422, { code: 'PDF_ACTIVE_CONTENT' }))
    panel(true)
    fireEvent.change(await screen.findByLabelText('File'), { target: { files: [new File(['x'], 'a.pdf', { type: 'application/pdf' })] } })
    fireEvent.click(screen.getByRole('button', { name: 'Add the document' }))
    expect(await screen.findByText(t('en').documents.codes.PDF_ACTIVE_CONTENT)).toBeTruthy()
  })

  it('asks for a replacement only with a reason; the original stays current', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([{ ...row, replacedById: null }])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([])
    const replace = vi.spyOn(registerApi, 'replaceDocument').mockResolvedValue({ id: 'r1' })
    panel(true)
    const input = await screen.findByLabelText(/^Ask to replace deed.pdf/)
    const file = new File(['x'], 'new.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [file] } })
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toMatch(/other than you, approves or rejects it/)
    expect(replace).not.toHaveBeenCalled()
    fireEvent.change(dialog.querySelector('textarea, input') as Element, { target: { value: 'The first scan was the wrong page' } })
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(replace).toHaveBeenCalledWith('x1', 'd1', 'DEED', file, 'The first scan was the wrong page'))
  })

  it('lets another conservator approve or reject a pending request, with a reason to reject', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([{ ...row, replacedById: null }])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([asked()])
    const decide = vi.spyOn(registerApi, 'decideReplacement').mockResolvedValue(asked({ status: 'REJECTED' }))
    panel(true, true)
    expect(await screen.findByText('Replacement waiting for approval')).toBeTruthy()
    expect(screen.getByText(/Asked by DEMO Notary \(the depositing professional\)/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'deed-v2.pdf' }).getAttribute('href')).toBe('/api/v1/register/transactions/x1/replacements/r1/content')
    expect(screen.queryByLabelText(/^Ask to replace deed.pdf/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(dialog.querySelector('textarea, input') as Element, { target: { value: 'Still the wrong page, please check' } })
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(decide).toHaveBeenCalledWith('x1', 'r1', 'REJECTED', 'Still the wrong page, please check'))
  })

  it('never offers the requester a decision on their own request, only its withdrawal', async () => {
    vi.spyOn(registerApi, 'documents').mockResolvedValue([{ ...row, replacedById: null }])
    vi.spyOn(registerApi, 'replacements').mockResolvedValue([asked({ requestedBy: ME, requestedByName: 'Me', requestedAs: 'STAFF' })])
    const withdraw = vi.spyOn(registerApi, 'withdrawReplacement').mockResolvedValue(asked({ status: 'WITHDRAWN' }))
    panel(true, true)
    expect(await screen.findByText('Waiting for another conservator of the office.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Approve the replacement' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Withdraw my request' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('x1', 'r1'))
  })
})

describe('the conservation', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('lists titles, conflicts and overdue locks, and says it is a demonstration', async () => {
    vi.spyOn(registerApi, 'titles').mockResolvedValue([{ id: 't1', number: 'DEMO-TF-DEMO1-000001-A', officeCode: 'DEMO1', version: 1, status: 'ACTIVE', holderNames: 'DEMO Owner', locked: true, inConflict: true }])
    vi.spyOn(registerApi, 'proposals').mockResolvedValue([])
    vi.spyOn(registerApi, 'parties').mockResolvedValue([])
    vi.spyOn(registerApi, 'conflicts').mockResolvedValue([{
      id: 'c1', titleId: 't1', titleNumber: 'DEMO-TF-DEMO1-000001-A', officeCode: 'DEMO1', holdingDepositor: 'DEMO First', holdingStatus: 'HOLDING', arrivingDepositor: 'DEMO Second',
      arrivingStatus: 'IN_CONFLICT', status: 'OPEN', openedAt: '2026-10-30T10:00:00Z', outcome: null, resolvedByName: null, resolutionReason: null,
    }])
    vi.spyOn(registerApi, 'overdue').mockResolvedValue([])
    render(<DialogProvider><RegisterPage ctx={ctx} params={new URLSearchParams()} /></DialogProvider>)
    expect(await screen.findByText('DEMO Owner')).toBeTruthy()
    expect(screen.getByText(t('en').register.demoTitle)).toBeTruthy()
    expect(screen.getByText(/DEMO First.*DEMO Second/)).toBeTruthy()
  })
})
