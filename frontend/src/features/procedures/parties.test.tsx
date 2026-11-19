// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { PartiesPanel } from './PartiesPanel'
import { partiesApi, problemParts } from './partiesApi'
import type { CaseParties, Identity } from './partiesApi'

const ME = 'me'
const identity = (over: Partial<Identity> = {}): Identity => ({
  id: 'v2', versionNo: 2, source: 'RECORDED', fullName: 'DEMO Heir Two', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-2', phone: null,
  postalAddress: null, validUntil: null, expiredAtTranscription: false, exception: null, sourceFileId: 'f1', sourceVersionId: null, enteredById: 'other', enteredByName: 'Intake One', enteredAt: '2026-11-14T10:00:00Z',
  supersedesId: 'v1', correctionReason: null, verification: 'UNVERIFIED_DEMO', check: null, ...over,
})
const parties = (over: Partial<CaseParties> = {}): CaseParties => ({
  capacity: 'ON_BEHALF_OF_SEVERAL',
  persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false, identities: [identity()] }],
  statements: [{ id: 's1', versionNo: 1, source: 'DECLARED', enteredById: 'app', enteredByName: 'Applicant', enteredAt: '2026-11-14T09:00:00Z',
    correctionReason: null, verification: 'UNVERIFIED_DEMO', check: null,
    shares: [{ personId: 'p2', personRef: 'p2', interest: 'CO_OWNERSHIP', shareNum: 2, shareDen: 3, usufructTerm: null, usufructUntil: null, basisFileId: null, basisVersionId: null }] }],
  mandates: [], files: [{ id: 'f1', purpose: 'IDENTITY_DOCUMENT', sha256: 'a'.repeat(64), sizeBytes: 10, mediaType: 'application/pdf', originalName: 'id.pdf',
    uploadedByName: 'Intake One', uploadedAt: '2026-11-14T10:00:00Z' }],
  problems: ['IDENTITY_NOT_CHECKED:p2', 'OWNERSHIP_NOT_RECORDED'], ...over,
})

describe('the persons of a case at intake (ADR-043)', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('says every identity is DEMO and never verified, and what the case still needs', async () => {
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties())
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord mayCheck />)
    expect(await screen.findByText('DEMO: not independently verified')).toBeTruthy()
    expect(screen.getByText('DEMO, not independently verified')).toBeTruthy()
    expect(screen.getByText('Transcription to check by a second officer (DEMO Heir Two)')).toBeTruthy()
    expect(screen.getByText('Ownership statement to record from the supporting documents')).toBeTruthy()
    expect(screen.queryByText(/^verified$/i)).toBeNull()
  })

  it('lets a second officer check a transcription, and says what MATCHES means', async () => {
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties())
    const check = vi.spyOn(partiesApi, 'check').mockResolvedValue(parties({
      persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false,
        identities: [identity({ check: { id: 'c1', outcome: 'MATCHES', note: null, checkedById: ME, checkedByName: 'Me', checkedAt: '2026-11-14T11:00:00Z' } })] }],
      problems: ['OWNERSHIP_NOT_RECORDED'],
    }))
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord={false} mayCheck />)
    fireEvent.click(await screen.findByRole('button', { name: 'Corresponds to the document' }))
    await waitFor(() => expect(check).toHaveBeenCalledWith('a1', 'IDENTITY', 'v2', 'MATCHES', null))
    expect(await screen.findByText('Corresponds to the document presented')).toBeTruthy()
    expect(screen.getByText('(checked by Me; not an authentication)')).toBeTruthy()
  })

  it('never offers the recorder the check of their own transcription, and a mismatch needs a note', async () => {
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties({
      persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false, identities: [identity({ enteredById: ME })] }],
    }))
    const { unmount } = render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord mayCheck />)
    await screen.findByText('DEMO Heir Two')
    expect(screen.queryByRole('button', { name: 'Corresponds to the document' })).toBeNull()
    unmount()
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties())
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord={false} mayCheck />)
    fireEvent.click(await screen.findByRole('button', { name: 'Does not match…' }))
    expect((screen.getByRole('button', { name: 'Record the mismatch' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows nothing to a reader the server refuses', async () => {
    vi.spyOn(partiesApi, 'read').mockRejectedValue(new Error('403'))
    const { container } = render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord={false} mayCheck={false} />)
    await waitFor(() => expect(container.textContent).toBe(''))
  })

  it('flags an expired document and lets another supervisor authorise an exception with a reason', async () => {
    const expired = identity({ validUntil: '2020-01-01', expiredAtTranscription: true })
    const withExpired = parties({
      persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false, identities: [expired] }],
      problems: ['IDENTITY_DOCUMENT_EXPIRED:p2'],
    })
    vi.spyOn(partiesApi, 'read').mockResolvedValue(withExpired)
    const authorise = vi.spyOn(partiesApi, 'authoriseException').mockResolvedValue(parties({
      persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false, identities: [identity({
        validUntil: '2020-01-01', expiredAtTranscription: true, exception: { id: 'x1', policy: 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
          reason: 'Renewal receipt presented at the counter.', authorisedById: ME, authorisedByName: 'Supervisor Me', authorisedAt: '2026-11-14T12:00:00Z' } })] }],
      problems: [],
    }))
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord={false} mayCheck={false} mayAuthorise />)
    expect(await screen.findByText('Already expired when transcribed')).toBeTruthy()
    expect(screen.getByText('Identity document expired: replace it, or a supervisor authorises an exception (DEMO Heir Two)')).toBeTruthy()
    const button = screen.getByRole('button', { name: 'Authorise the exception' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText(/Why the expired document is accepted/), { target: { value: 'Renewal receipt presented at the counter.' } })
    fireEvent.click(button)
    await waitFor(() => expect(authorise).toHaveBeenCalledWith('a1', 'v2', 'Renewal receipt presented at the counter.'))
    expect(await screen.findByText('Authorised by Supervisor Me: Renewal receipt presented at the counter.')).toBeTruthy()
  })

  it('never offers the exception to whoever transcribed the document, nor without the permission', async () => {
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties({
      persons: [{ id: 'p2', ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], applicantAccount: false,
        identities: [identity({ validUntil: '2020-01-01', expiredAtTranscription: true, enteredById: ME })] }],
    }))
    const { unmount } = render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord mayCheck mayAuthorise />)
    await screen.findByText('Already expired when transcribed')
    expect(screen.queryByRole('button', { name: 'Authorise the exception' })).toBeNull()
    unmount()
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId="someone" mayRecord mayCheck />)
    await screen.findByText('Already expired when transcribed')
    expect(screen.queryByRole('button', { name: 'Authorise the exception' })).toBeNull()
  })

  it('shows the term of a usufruct', async () => {
    vi.spyOn(partiesApi, 'read').mockResolvedValue(parties({
      statements: [{ id: 's1', versionNo: 1, source: 'RECORDED', enteredById: 'other', enteredByName: 'Intake One', enteredAt: '2026-11-14T09:00:00Z',
        correctionReason: null, verification: 'UNVERIFIED_DEMO', check: null,
        shares: [{ personId: 'p2', personRef: 'p2', interest: 'USUFRUCT', shareNum: 1, shareDen: 1, usufructTerm: 'UNTIL', usufructUntil: '2040-01-01',
          basisFileId: 'f1', basisVersionId: null }] }],
    }))
    render(<PartiesPanel texts={t('en')} lang="en" applicationId="a1" viewerId={ME} mayRecord={false} mayCheck={false} />)
    expect(await screen.findByText(/Usufruct 1\/1 · Until 2040-01-01/)).toBeTruthy()
  })

  it('reads a problem code and the person it names', () => {
    expect(problemParts('MANDATE_MISSING:p3')).toEqual({ kind: 'MANDATE_MISSING', ref: 'p3' })
    expect(problemParts('OWNERSHIP_NOT_CHECKED')).toEqual({ kind: 'OWNERSHIP_NOT_CHECKED', ref: null })
  })
})
