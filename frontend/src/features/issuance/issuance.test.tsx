// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { AcknowledgmentCard } from './AcknowledgmentCard'
import { issuanceApi } from './api'
import type { Acknowledgment, Incident, IssuedDocument, Verification } from './api'
import { IssuedDocumentsPage } from './IssuedDocumentsPage'
import { VerifyPage } from './VerifyPage'

const NUMBER = 'SNIGF-01JBXQ3Z4V8K2M6N9P0R5S7T1W'
const ack: Acknowledgment = {
  number: NUMBER, type: 'DEMO_SUBMISSION_ACKNOWLEDGMENT', status: 'VALID', issuedAt: '2026-10-29T10:00:00Z',
  verifyUrl: `https://localhost:47000/#/verify/${NUMBER}`, replacement: null, demo: true,
}
const summary = {
  number: NUMBER, type: 'DEMO_SUBMISSION_ACKNOWLEDGMENT', status: 'VALID' as const, issuedAt: '2026-10-29T10:00:00Z',
  issuingOffice: { code: 'DEMOIT', nameFr: 'Bureau de test', nameEn: 'Test office' }, replacement: null,
}
const answer = (outcome: Verification['outcome'], reason: string, scope: Verification['scope'], document = summary): Verification =>
  ({ verification: 'v1', demo: true, channel: scope === 'EXACT_FILE' ? 'FILE' : 'NUMBER', outcome, reason, scope, document })

describe('the DEMO acknowledgment of an application', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('says it has no legal value and offers the exact PDF and the verification page', async () => {
    vi.spyOn(issuanceApi, 'acknowledgment').mockResolvedValue(ack)
    render(<AcknowledgmentCard texts={t('en')} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Demonstration: no legal value')).toBeTruthy()
    expect(screen.getByText(NUMBER)).toBeTruthy()
    expect(screen.getByText('Valid')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Download the PDF' }).getAttribute('href'))
      .toBe('/api/v1/applications/a1/acknowledgment/file')
    expect(screen.getByRole('link', { name: 'Verification page' }).getAttribute('href')).toBe(`#/verify/${NUMBER}`)
  })

  it('lets the applicant ask for it once when it could not be issued, and only tells the office', async () => {
    vi.spyOn(issuanceApi, 'acknowledgment').mockRejectedValue(new ApiError(404, { code: 'NOT_ISSUED' }))
    const issue = vi.spyOn(issuanceApi, 'issue').mockResolvedValue(ack)
    const { unmount } = render(<AcknowledgmentCard texts={t('en')} lang="en" applicationId="a1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Issue the acknowledgment' }))
    await waitFor(() => expect(issue).toHaveBeenCalledWith('a1'))
    expect(await screen.findByText(NUMBER)).toBeTruthy()
    unmount()
    render(<AcknowledgmentCard texts={t('en')} lang="en" applicationId="a1" source="case" />)
    expect(await screen.findByText('No acknowledgment has been issued for this application yet.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Issue the acknowledgment' })).toBeNull()
  })
})

describe('public verification', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('looks up the number the QR code names and says it confirms the record, not the paper', async () => {
    const lookUp = vi.spyOn(issuanceApi, 'verifyNumber').mockResolvedValue(answer('RECORD_FOUND', 'RECORD_FOUND', 'RECORD_ONLY'))
    render(<VerifyPage texts={t('en')} lang="en" number={NUMBER.toLowerCase()} />)
    expect(await screen.findByText('Document on record')).toBeTruthy()
    expect(lookUp).toHaveBeenCalledWith(NUMBER)
    expect(screen.getByText('Only the number was checked: this confirms the record, not the paper in your hands.')).toBeTruthy()
    expect(screen.getByText('Test office')).toBeTruthy()
    expect(screen.getByText(/A printed or scanned copy cannot carry the electronic signature/)).toBeTruthy()
  })

  it('refuses a malformed number before sending it', async () => {
    const lookUp = vi.spyOn(issuanceApi, 'verifyNumber')
    render(<VerifyPage texts={t('en')} lang="en" />)
    fireEvent.change(screen.getByLabelText(/^Document number/), { target: { value: 'DEMO-APP-2026-000001-X' } })
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }))
    expect(await screen.findByText('This is not an SNIGF document number: SNIGF- followed by 26 letters and digits.')).toBeTruthy()
    expect(lookUp).not.toHaveBeenCalled()
  })

  it('confirms an identical file, flags an altered one, and accuses nobody when it cannot confirm', async () => {
    const check = vi.spyOn(issuanceApi, 'verifyFile')
    render(<VerifyPage texts={t('en')} lang="en" />)
    const pdf = new File(['%PDF-1.7'], 'ack.pdf', { type: 'application/pdf' })
    const send = async () => {
      fireEvent.change(screen.getByLabelText(/^PDF file/), { target: { files: [pdf] } })
      fireEvent.click(screen.getByRole('button', { name: 'Verify the file' }))
    }
    check.mockResolvedValueOnce(answer('AUTHENTIC', 'EXACT_MATCH', 'EXACT_FILE'))
    await send()
    expect(await screen.findByText('This file is exactly the document SNIGF issued, and it is valid.')).toBeTruthy()
    check.mockResolvedValueOnce(answer('ALTERED', 'CONTENT_DIFFERS', 'EXACT_FILE'))
    await send()
    expect(await screen.findByText('Content altered')).toBeTruthy()
    check.mockResolvedValueOnce({ ...answer('UNVERIFIED', 'NO_SNIGF_EVIDENCE', 'EXACT_FILE'), document: null })
    await send()
    expect(await screen.findByText(/It may be a genuine document from elsewhere/)).toBeTruthy()
    expect(check).toHaveBeenCalledTimes(3)
  })

  it('shows the replacement of a superseded document', async () => {
    const next = 'SNIGF-01JBXQ3Z4V8K2M6N9P0R5S7T2X'
    vi.spyOn(issuanceApi, 'verifyNumber').mockResolvedValue(answer('SUPERSEDED', 'STATUS_SUPERSEDED', 'RECORD_ONLY',
      { ...summary, status: 'SUPERSEDED' as never, replacement: next as never }))
    render(<VerifyPage texts={t('en')} lang="en" number={NUMBER} />)
    expect(await screen.findByText('Document superseded')).toBeTruthy()
    expect(screen.getByRole('link', { name: next }).getAttribute('href')).toBe(`#/verify/${next}`)
  })
})

const session = (permissions: string[]): Session => ({ permissions } as unknown as Session)
const ctx = (permissions: string[]): PageContext =>
  ({ texts: t('en'), lang: 'en', session: session(permissions), run: async () => {}, refreshSession: async () => {} } as unknown as PageContext)
const officerDoc: IssuedDocument = {
  id: 'd1', number: NUMBER, type: 'DEMO_SUBMISSION_ACKNOWLEDGMENT', templateVersion: 'ack-demo-1', subjectType: 'APPLICATION',
  subjectRef: 'DEMO-APP-2026-000001-X', officeCode: 'DEMOIT', issuedAt: '2026-10-29T10:00:00Z', printed: {}, keyVersion: 1,
  status: 'VALID', statusReason: null, statusChangedAt: null, replacement: null, verifyUrl: ack.verifyUrl, demo: true,
}
const incident: Incident = {
  id: 'i1', kind: 'ALTERED', status: 'OPEN', openedAt: '2026-10-29T11:00:00Z', documentNumber: NUMBER, channel: 'FILE',
  claimedNumber: NUMBER, presentedSha256: 'ab'.repeat(32), reason: 'CONTENT_DIFFERS', resolution: null, note: null,
  resolvedByName: null, resolvedAt: null, version: 0,
}

describe('the document officer', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('revokes a document with a reason, after confirmation', async () => {
    vi.spyOn(issuanceApi, 'document').mockResolvedValue(officerDoc)
    const revoke = vi.spyOn(issuanceApi, 'revoke').mockResolvedValue({ ...officerDoc, status: 'REVOKED', statusReason: 'Wrongly issued here' })
    render(<DialogProvider><IssuedDocumentsPage ctx={ctx(['document.issued.manage'])} /></DialogProvider>)
    expect(screen.queryByText('Verification incidents')).toBeNull()
    fireEvent.change(screen.getByLabelText(/^Document number/), { target: { value: NUMBER } })
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }))
    expect(await screen.findByText('DEMO-APP-2026-000001-X')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/no longer be valid, for good/)).toBeTruthy()
    fireEvent.change(within(dialog).getByLabelText(/^Reason/), { target: { value: 'Wrongly issued here' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(revoke).toHaveBeenCalledWith(NUMBER, 'Wrongly issued here'))
    expect(await screen.findByText('The document\'s status was changed.')).toBeTruthy()
  })

  it('resolves an incident once with a note and the version read', async () => {
    vi.spyOn(issuanceApi, 'incidents').mockResolvedValue([incident])
    const resolve = vi.spyOn(issuanceApi, 'resolve').mockResolvedValue({ ...incident, status: 'RESOLVED', version: 1 })
    render(<DialogProvider><IssuedDocumentsPage ctx={ctx(['document.incident.review'])} /></DialogProvider>)
    expect(await screen.findByText('Altered file')).toBeTruthy()
    expect(screen.queryByText('Look up a document')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(await screen.findByText('From 10 to 2000 characters.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^Note/), { target: { value: 'Holder name edited after issuance' } })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Alteration confirmed\. The incident is closed for good\./)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(resolve).toHaveBeenCalledWith('i1',
      { expectedVersion: 0, resolution: 'CONFIRMED_ALTERATION', note: 'Holder name edited after issuance' }))
  })
})
