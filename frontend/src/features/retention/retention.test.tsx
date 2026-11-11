// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { retentionApi } from './api'
import type { Hold, RetentionClass } from './api'
import { HoldsPanel } from './HoldsPanel'
import { RetentionPage } from './RetentionPage'

const texts = t('en')
const session = (permissions: string[]) => ({
  userId: 'me', displayName: 'Me', accountType: 'STAFF', permissions, specialGrants: [], roles: [], grants: [],
}) as unknown as Session
const ctx = (s: Session) => ({ texts, lang: 'en', session: s, run: async (a: () => Promise<void>) => { await a() }, refreshSession: vi.fn() }) as unknown as PageContext
const ADMIN = ['records.retention.read', 'records.hold.place', 'records.hold.release']
const AUDITOR = ['records.retention.read']
const classes: RetentionClass[] = ['DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'PROFESSIONAL_EVIDENCE'].map((code) => ({
  code, descriptionEn: `About ${code}`, descriptionFr: `À propos de ${code}`, scheduleReference: null, scheduleApprovedOn: null, standing: 'NO_APPROVED_SCHEDULE_KEPT',
}))
const hold = (over: Partial<Hold> = {}): Hold => ({
  id: 'h1', subjectKind: 'PROFESSIONAL', subjectId: 'p1', reason: 'Litigation notice DEMO-LIT-1 received', placedBy: 'someone', placedByName: 'Admin A',
  placedAt: '2026-11-06T10:00:00Z', inForce: true, releases: [], ...over,
})
const release = (over: Partial<Hold['releases'][number]> = {}): Hold['releases'][number] => ({
  id: 'r1', holdId: 'h1', reason: 'The litigation was settled', requestedBy: 'someone', requestedByName: 'Admin A', requestedAt: '2026-11-07T10:00:00Z',
  status: 'PENDING', decidedBy: null, decidedByName: null, decidedAt: null, decisionReason: null, ...over,
})
const wrap = (node: React.ReactNode) => render(<DialogProvider>{node}</DialogProvider>)

describe('retention and legal holds', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('says no schedule is approved, nothing is disposed of, and an auditor only reads', async () => {
    vi.spyOn(retentionApi, 'classes').mockResolvedValue(classes)
    vi.spyOn(retentionApi, 'holds').mockResolvedValue([hold({ releases: [release()] })])
    wrap(<RetentionPage ctx={ctx(session(AUDITOR))} />)
    expect(await screen.findAllByText('No approved schedule: kept')).toHaveLength(3)
    expect(screen.getByText(/nothing is ever deleted or disposed of/)).toBeTruthy()
    expect(await screen.findByText('Reason: Litigation notice DEMO-LIT-1 received')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Place a legal hold' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Approve the release' })).toBeNull()
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull()
  })

  it('places a hold on a subject named by its kind and identifier, with a reason', async () => {
    vi.spyOn(retentionApi, 'classes').mockResolvedValue(classes)
    vi.spyOn(retentionApi, 'holds').mockResolvedValue([])
    const place = vi.spyOn(retentionApi, 'place').mockResolvedValue(hold())
    wrap(<RetentionPage ctx={ctx(session(ADMIN))} />)
    const form = await waitFor(() => document.querySelector('[data-place-form]') as HTMLFormElement)
    fireEvent.change(screen.getByLabelText(/^What the hold covers/), { target: { value: 'PROFESSIONAL' } })
    fireEvent.change(screen.getByLabelText(/^Identifier/), { target: { value: 'not-an-id' } })
    fireEvent.submit(form)
    expect(await screen.findByText(/Give an identifier such as/)).toBeTruthy()
    expect(place).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText(/^Identifier/), { target: { value: '0192F3A4-5B6C-7D8E-9F01-23456789ABCD' } })
    fireEvent.change(form.querySelector('textarea') as Element, { target: { value: 'Litigation notice DEMO-LIT-1 received' } })
    fireEvent.submit(form)
    await waitFor(() => expect(place).toHaveBeenCalledWith('PROFESSIONAL', '0192f3a4-5b6c-7d8e-9f01-23456789abcd', 'Litigation notice DEMO-LIT-1 received'))
    expect(await screen.findByText('Hold placed.')).toBeTruthy()
  })

  it('never offers the requester the decision on their own release, and lets another approve it', async () => {
    vi.spyOn(retentionApi, 'holds').mockResolvedValue([hold({ releases: [release({ requestedBy: 'me', requestedByName: 'Me' })] })])
    const { unmount } = wrap(<HoldsPanel texts={texts} lang="en" session={session(ADMIN)} subject={{ kind: 'PROFESSIONAL', id: 'p1' }} />)
    expect(await screen.findByText('Waiting for another system administrator.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Approve the release' })).toBeNull()
    unmount()

    vi.spyOn(retentionApi, 'holds').mockResolvedValue([hold({ releases: [release()] })])
    const decide = vi.spyOn(retentionApi, 'decideRelease').mockResolvedValue(hold({ inForce: false }))
    wrap(<HoldsPanel texts={texts} lang="en" session={session(ADMIN)} subject={{ kind: 'PROFESSIONAL', id: 'p1' }} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Approve the release' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(decide).toHaveBeenCalledWith('h1', 'r1', 'APPROVED'))
  })

  it('asks for a release with a reason, and keeps a released hold listed', async () => {
    vi.spyOn(retentionApi, 'holds').mockResolvedValue([hold(), hold({ id: 'h0', inForce: false, releases: [release({ id: 'r0', holdId: 'h0', status: 'APPROVED', decidedByName: 'Admin B' })] })])
    const ask = vi.spyOn(retentionApi, 'requestRelease').mockResolvedValue(hold())
    wrap(<HoldsPanel texts={texts} lang="en" session={session(ADMIN)} subject={{ kind: 'PROFESSIONAL', id: 'p1' }} />)
    expect(await screen.findByText('Released')).toBeTruthy()
    expect(screen.getByText('Decided by Admin B')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Ask for the release' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(dialog.querySelector('textarea, input') as Element, { target: { value: 'The litigation was settled' } })
    fireEvent.click(dialog.querySelector('button[type="submit"]') as Element)
    await waitFor(() => expect(ask).toHaveBeenCalledWith('h1', 'The litigation was settled'))
  })
})
