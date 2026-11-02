// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { AdminDetailPage } from './AdminDetailPage'
import { AdminPage } from './AdminPage'
import { depositProblems, depositsApi, officeCodes, professionalApi } from './api'
import type { ChangeRequest, Deposit, Detail, Own, OwnDocument, Profile } from './api'
import { DepositPage } from './DepositPage'
import { OwnPage } from './OwnPage'
import { RequestScreen } from './RequestScreen'

const texts = t('en')
const NOTARY = ['register.deposit.submit@YDE1', 'register.deposit.read#own', 'register.deposit.document#own', 'register.deposit.withdraw#own']
const session = (permissions: string[], over: Partial<Session> = {}) => ({
  userId: 'me', displayName: 'Me', accountType: 'PROFESSIONAL', permissions, specialGrants: [], roles: [], grants: [], ...over,
}) as unknown as Session
const ctx = (s: Session) => ({ texts, lang: 'en', session: s, run: async (a: () => Promise<void>) => { await a() }, refreshSession: vi.fn() }) as unknown as PageContext
const wrap = (node: React.ReactNode) => render(<DialogProvider>{node}</DialogProvider>)

const profile = (over: Partial<Profile> = {}): Profile => ({
  userId: 'p1', email: 'notary@demo.invalid', displayName: 'DEMO Notary', profession: 'NOTARY', chamber: 'DEMO Chamber', registrationNumber: 'DEMO-N-1',
  jurisdiction: 'DEMO Centre', status: 'ACTIVE', requestedAt: '2026-11-01T08:00:00Z', statusChangedAt: '2026-11-01T09:00:00Z', offices: ['YDE1'], ...over,
})
const deposit = (over: Partial<Deposit> = {}): Deposit => ({
  id: 'd1', titleId: 't1', titleNumber: 'DEMO-TF-YDE1-000001-A', officeCode: 'YDE1', kind: 'SALE', status: 'OPEN', depositorName: 'DEMO Client',
  capacity: 'NOTARY', depositedAt: '2026-11-01T10:00:00Z', closedAt: null, closedReason: null, documents: 1, ...over,
})
const request = (over: Partial<ChangeRequest> = {}): ChangeRequest => ({
  id: 'r1', userId: 'p1', professionalName: 'DEMO Notary', kind: 'ACTIVATION', officesAdd: ['YDE1'], officesRemove: [], reason: 'Checked with the DEMO chamber',
  requestedBy: 'admin-a', requestedByName: 'Admin A', requestedAt: '2026-11-01T09:00:00Z', status: 'PENDING', decidedByName: null, decidedAt: null,
  decisionReason: null, ...over,
})
const pdf = (name: string) => new File(['%PDF-1.4'], name, { type: 'application/pdf' })
/** Waits until the element is there. */
const found = (selector: string) => waitFor(() => {
  const element = document.querySelector(selector)
  if (!element) throw new Error(`no ${selector}`)
  return element as HTMLElement
})
const confirmDialog = async () => {
  const dialog = await screen.findByRole('dialog')
  fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('the rules the deposit form applies before sending', () => {
  it('asks for a deed, at most twenty files, and a DEMO client', () => {
    const client = { kind: 'NATURAL_PERSON' as const, fullName: 'DEMO Client', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-1' }
    expect(depositProblems({ depositor: client, files: [{ category: 'DEED', file: pdf('a.pdf') }] })).toEqual([])
    expect(depositProblems({ depositor: client, files: [{ category: 'OTHER', file: pdf('a.pdf') }] })).toEqual(['DEED_REQUIRED'])
    const many = Array.from({ length: 21 }, (_, i) => ({ category: i === 0 ? 'DEED' : 'OTHER', file: pdf(`${i}.pdf`) }))
    expect(depositProblems({ depositor: client, files: many })).toEqual(['TOO_MANY_FILES'])
    expect(depositProblems({ depositor: { ...client, fullName: 'Real Name' }, files: many.slice(0, 1) })).toEqual(['DEPOSITOR_INVALID'])
  })

  it('reads office codes as a list', () => {
    expect(officeCodes(' yde1, DLA2\nyde1 ,')).toEqual(['YDE1', 'DLA2'])
  })
})

describe('the public request', () => {
  it('sends the credential with the account', () => {
    const onSubmit = vi.fn()
    render(<RequestScreen texts={texts} busy={false} lang="en" onSubmit={onSubmit} onBack={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(/^Profession/), { target: { value: 'SWORN_SURVEYOR' } })
    fireEvent.change(screen.getByLabelText(/^E-mail address/), { target: { value: 'n@demo.invalid' } })
    fireEvent.change(screen.getByLabelText(/^Display name/), { target: { value: 'DEMO Surveyor' } })
    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: 'a-long-password-1' } })
    fireEvent.change(screen.getByLabelText(/^Chamber/), { target: { value: 'DEMO Order' } })
    fireEvent.change(screen.getByLabelText(/^Registration number/), { target: { value: 'DEMO-S-1' } })
    fireEvent.change(screen.getByLabelText(/^Jurisdiction/), { target: { value: 'DEMO Centre' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Send the request' }).closest('form') as HTMLFormElement)
    expect(onSubmit).toHaveBeenCalledWith({
      email: 'n@demo.invalid', displayName: 'DEMO Surveyor', password: 'a-long-password-1', preferredLanguage: 'en', profession: 'SWORN_SURVEYOR',
      chamber: 'DEMO Order', registrationNumber: 'DEMO-S-1', jurisdiction: 'DEMO Centre',
    })
  })
})

describe('the professional\'s own page', () => {
  const own = (p: Profile): Own => ({ profile: p, history: [{ event: 'REQUESTED', actorName: 'DEMO Notary', reason: null, at: '2026-11-01T08:00:00Z' }] })

  it('shows a requested account its status and nothing to do', async () => {
    vi.spyOn(professionalApi, 'me').mockResolvedValue(own(profile({ status: 'REQUESTED', offices: [] })))
    const list = vi.spyOn(depositsApi, 'list')
    wrap(<OwnPage ctx={ctx(session([]))} />)
    expect(await screen.findByText('Your request waits for an administrator to verify your credential with your professional body.')).toBeTruthy()
    expect(screen.getByText('Account requested')).toBeTruthy()
    expect(screen.queryByText('Deposit on a title')).toBeNull()
    expect(screen.queryByText('My deposits')).toBeNull()
    expect(list).not.toHaveBeenCalled()
  })

  it('reads the status again when an approval changes the session', async () => {
    const me = vi.spyOn(professionalApi, 'me').mockResolvedValueOnce(own(profile({ status: 'VERIFIED', offices: [] }))).mockResolvedValue(own(profile()))
    vi.spyOn(depositsApi, 'list').mockResolvedValue([])
    const view = wrap(<OwnPage ctx={ctx(session([]))} />)
    expect(await screen.findByText('Verified, waiting for approval')).toBeTruthy()
    view.rerender(<DialogProvider><OwnPage ctx={ctx(session(NOTARY))} /></DialogProvider>)
    expect(await screen.findByText('Active')).toBeTruthy()
    expect(me).toHaveBeenCalledTimes(2)
  })

  it('tells a notary the same thing for an unknown title and a title elsewhere', async () => {
    vi.spyOn(professionalApi, 'me').mockResolvedValue(own(profile()))
    vi.spyOn(depositsApi, 'list').mockResolvedValue([])
    const lookup = vi.spyOn(depositsApi, 'lookup').mockRejectedValue(new ApiError(404, { code: 'TITLE_NOT_FOUND' }))
    wrap(<OwnPage ctx={ctx(session(NOTARY))} />)
    fireEvent.change(await screen.findByLabelText(/^Title number/), { target: { value: ' DEMO-TF-X ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find the title' }))
    expect(await screen.findByText('No title with this number at an office you serve.')).toBeTruthy()
    expect(lookup).toHaveBeenCalledWith('DEMO-TF-X')
    expect(document.querySelector('[data-professional-deposit-form]')).toBeNull()
  })

  it('deposits the client and every file in one request, after confirmation, with one key per deposit', async () => {
    vi.spyOn(professionalApi, 'me').mockResolvedValue(own(profile()))
    vi.spyOn(depositsApi, 'list').mockResolvedValue([])
    vi.spyOn(depositsApi, 'lookup').mockResolvedValue({ id: 't1', number: 'DEMO-TF-YDE1-000001-A', officeCode: 'YDE1', acceptsDeposit: true })
    const send = vi.spyOn(depositsApi, 'deposit').mockRejectedValueOnce(new ApiError(503, { code: 'BUSY' })).mockResolvedValue({ id: 'd9' })
    wrap(<OwnPage ctx={ctx(session(NOTARY))} />)
    fireEvent.change(await screen.findByLabelText(/^Title number/), { target: { value: 'DEMO-TF-YDE1-000001-A' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find the title' }))
    const form = await found('[data-professional-deposit-form]')
    expect(within(document.querySelector('[data-lookup-result]') as HTMLElement).getByText('Accepts a deposit')).toBeTruthy()
    const submit = within(form).getByRole('button', { name: 'Deposit' })
    expect((submit as HTMLButtonElement).disabled).toBe(true)

    fireEvent.change(within(form).getByLabelText(/^Client name/), { target: { value: 'DEMO Client' } })
    fireEvent.change(within(form).getByLabelText(/^Identity number/), { target: { value: 'DEMO-ID-1' } })
    fireEvent.change(within(form).getByLabelText('Add files'), { target: { files: [pdf('deed.pdf'), pdf('id.pdf')] } })
    expect((within(form).getByLabelText('Kind of document for deed.pdf') as HTMLSelectElement).value).toBe('DEED')
    fireEvent.change(within(form).getByLabelText('Kind of document for id.pdf'), { target: { value: 'IDENTITY' } })
    expect((submit as HTMLButtonElement).disabled).toBe(false)

    fireEvent.click(submit)
    expect(send).not.toHaveBeenCalled()
    await confirmDialog()
    expect(await screen.findByText('The service is busy. Try again in a moment.')).toBeTruthy()
    fireEvent.click(submit)
    await confirmDialog()
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    const [sent, key] = send.mock.calls[0]
    expect(sent.titleId).toBe('t1')
    expect(sent.depositor).toEqual({ kind: 'NATURAL_PERSON', fullName: 'DEMO Client', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-1' })
    expect(sent.files.map((f) => [f.file.name, f.category])).toEqual([['deed.pdf', 'DEED'], ['id.pdf', 'IDENTITY']])
    expect(send.mock.calls[1][1]).toBe(key)
    await waitFor(() => expect(window.location.hash).toBe('#/professional/deposits/d9'))
  })

  it('offers no deposit on a closed title', async () => {
    vi.spyOn(professionalApi, 'me').mockResolvedValue(own(profile()))
    vi.spyOn(depositsApi, 'list').mockResolvedValue([])
    vi.spyOn(depositsApi, 'lookup').mockResolvedValue({ id: 't1', number: 'DEMO-TF-YDE1-000001-A', officeCode: 'YDE1', acceptsDeposit: false })
    wrap(<OwnPage ctx={ctx(session(NOTARY))} />)
    fireEvent.change(await screen.findByLabelText(/^Title number/), { target: { value: 'DEMO-TF-YDE1-000001-A' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find the title' }))
    expect(await screen.findByText('Closed: it accepts no deposit')).toBeTruthy()
    expect(document.querySelector('[data-professional-deposit-form]')).toBeNull()
  })

  it('lists the notary\'s own deposits, an open one reading open', async () => {
    vi.spyOn(professionalApi, 'me').mockResolvedValue(own(profile()))
    vi.spyOn(depositsApi, 'list').mockResolvedValue([deposit(), deposit({ id: 'd2', status: 'REGISTERED' })])
    wrap(<OwnPage ctx={ctx(session(NOTARY))} />)
    const rows = await waitFor(() => { const r = document.querySelectorAll('[data-deposit]'); expect(r).toHaveLength(2); return r })
    expect(rows[0].textContent).toContain('Open')
    expect(rows[1].textContent).toContain('Registered (DEMO)')
    expect(screen.queryByText('Holding the lock')).toBeNull()
  })
})

describe('one of the notary\'s deposits', () => {
  const doc = (over: Partial<OwnDocument> = {}): OwnDocument => ({
    id: 'x1', transactionId: 'd1', category: 'DEED', sha256: 'ab'.repeat(32), sizeBytes: 10, mediaType: 'application/pdf', originalName: 'deed.pdf',
    uploadedBySelf: true, uploadedAt: '2026-11-01T10:00:00Z', replacesId: null, replaceReason: null, replacedById: null, ...over,
  })

  it('while open, takes documents and a withdrawal with a reason; a conservator\'s file is not named', async () => {
    vi.spyOn(depositsApi, 'one').mockResolvedValue(deposit())
    vi.spyOn(depositsApi, 'documents').mockResolvedValue([doc(), doc({ id: 'x2', originalName: 'stamp.pdf', category: 'OTHER', uploadedBySelf: false })])
    const withdraw = vi.spyOn(depositsApi, 'withdraw').mockResolvedValue(deposit({ status: 'WITHDRAWN', closedReason: 'The sale was cancelled' }))
    wrap(<DepositPage ctx={ctx(session(NOTARY))} id="d1" />)
    expect(await screen.findByRole('link', { name: 'stamp.pdf' })).toBeTruthy()
    expect((document.querySelector('[data-document="stamp.pdf"]') as HTMLElement).textContent).toContain('added by the conservation')
    expect((document.querySelector('[data-document="deed.pdf"]') as HTMLElement).textContent).not.toContain('added by the conservation')
    expect(screen.getByRole('button', { name: 'Add the document' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Withdraw the deposit' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'The sale was cancelled' } })
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('d1', 'The sale was cancelled'))
    expect(await screen.findByText('Withdrawn')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Withdraw the deposit' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add the document' })).toBeNull()
  })

  it('answers another deposit\'s id as not found', async () => {
    vi.spyOn(depositsApi, 'one').mockRejectedValue(new ApiError(404, { code: 'TRANSACTION_NOT_FOUND' }))
    wrap(<DepositPage ctx={ctx(session(NOTARY))} id="other" />)
    expect(await screen.findByText('Deposit not found.')).toBeTruthy()
  })
})

describe('the administrators\' pages', () => {
  const ADMIN = ['admin.professional.read', 'admin.professional.verify', 'admin.professional.approve', 'admin.professional.suspend']
  const admin = (id: string) => session(ADMIN, { userId: id, accountType: 'STAFF' })

  it('never offers the requester a decision on their own request', async () => {
    vi.spyOn(professionalApi, 'pending').mockResolvedValue([request()])
    vi.spyOn(professionalApi, 'profiles').mockResolvedValue([profile({ status: 'VERIFIED' })])
    wrap(<AdminPage ctx={ctx(admin('admin-a'))} />)
    const item = await found('[data-request="r1"]')
    expect(within(item).getByText('your request: another administrator decides')).toBeTruthy()
    expect(within(item).queryByRole('button', { name: 'Approve' })).toBeNull()
  })

  it('lets a second administrator approve, after confirmation', async () => {
    vi.spyOn(professionalApi, 'pending').mockResolvedValueOnce([request()]).mockResolvedValue([])
    vi.spyOn(professionalApi, 'profiles').mockResolvedValue([])
    const approve = vi.spyOn(professionalApi, 'approve').mockResolvedValue(request({ status: 'APPROVED' }))
    wrap(<AdminPage ctx={ctx(admin('admin-b'))} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(approve).not.toHaveBeenCalled()
    await confirmDialog()
    await waitFor(() => expect(approve).toHaveBeenCalledWith('r1'))
    expect(await screen.findByText('Decision recorded.')).toBeTruthy()
  })

  it('verifies a requested account with the offices proposed', async () => {
    const detail: Detail = { profile: profile({ status: 'REQUESTED', offices: [] }), requests: [], verification: null, history: [] }
    vi.spyOn(professionalApi, 'detail').mockResolvedValue(detail)
    const verify = vi.spyOn(professionalApi, 'verify').mockResolvedValue({ id: 'r1' })
    wrap(<AdminDetailPage ctx={ctx(admin('admin-a'))} userId="p1" />)
    const form = await found('[data-verify-form]')
    fireEvent.change(within(form).getByLabelText(/^Professional body/), { target: { value: 'DEMO Chamber of notaries' } })
    fireEvent.change(within(form).getByLabelText(/^Reference of the check/), { target: { value: 'DEMO-CHK-1' } })
    fireEvent.change(within(form).getByLabelText(/^Checked on/), { target: { value: '2026-11-01' } })
    fireEvent.change(within(form).getByLabelText(/^Note/), { target: { value: 'Register of the chamber consulted' } })
    fireEvent.change(within(form).getByLabelText(/^Offices/), { target: { value: 'yde1' } })
    fireEvent.submit(form)
    await waitFor(() => expect(verify).toHaveBeenCalledWith('p1', {
      body: 'DEMO Chamber of notaries', reference: 'DEMO-CHK-1', checkedOn: '2026-11-01', note: 'Register of the chamber consulted', offices: ['YDE1'],
    }))
    expect(await screen.findByText('Verification recorded: a second administrator must approve.')).toBeTruthy()
  })

  it('suspends an active account with a reason, and offers only the changes its status allows', async () => {
    const active: Detail = { profile: profile(), requests: [], verification: null, history: [] }
    vi.spyOn(professionalApi, 'detail').mockResolvedValueOnce(active).mockResolvedValue({ ...active, profile: profile({ status: 'SUSPENDED' }) })
    const suspend = vi.spyOn(professionalApi, 'suspend').mockResolvedValue()
    wrap(<AdminDetailPage ctx={ctx(admin('admin-a'))} userId="p1" />)
    const kinds = () => Array.from((screen.getByLabelText('Change') as HTMLSelectElement).options).map((o) => o.value)
    await screen.findByRole('button', { name: 'Suspend' })
    expect(kinds()).toEqual(['OFFICE_CHANGE', 'REVOCATION'])
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Credential under review by the chamber' } })
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(suspend).toHaveBeenCalledWith('p1', 'Credential under review by the chamber'))
    expect(await screen.findByText('Account suspended.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Suspend' })).toBeNull()
    expect(kinds()).toEqual(['REINSTATEMENT', 'REVOCATION', 'OFFICE_CHANGE'])
  })

  it('shows a reader neither the verification form nor the actions', async () => {
    vi.spyOn(professionalApi, 'detail').mockResolvedValue({ profile: profile({ status: 'REQUESTED', offices: [] }), requests: [], verification: null, history: [] })
    wrap(<AdminDetailPage ctx={ctx(session(['admin.professional.read'], { accountType: 'STAFF' }))} userId="p1" />)
    expect(await screen.findByText('Credential')).toBeTruthy()
    expect(document.querySelector('[data-verify-form]')).toBeNull()
    expect(document.querySelector('[data-change-form]')).toBeNull()
  })
})
