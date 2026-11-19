// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the two conservators examine before signing a CREATION from a case (ADR-043, owner's review).
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import { registerApi } from './api'
import type { CaseEvidence as Evidence } from './api'
import { CaseEvidence } from './CaseEvidence'

const check = { outcome: 'MATCHES' as const, by: 'Checker Two', at: '2026-11-14T10:00:00Z' }
const evidence: Evidence = {
  reference: 'CASE-1', capacity: 'SELF',
  statement: { id: 's1', version: 2, enteredBy: 'Intake One', enteredAt: '2026-11-14T09:00:00Z', verification: 'UNVERIFIED_DEMO', check },
  owners: [
    { ref: 'p1', kind: 'NATURAL_PERSON', fullName: 'DEMO Usufructuary', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-1', validUntil: '2020-01-01',
      expiredAtTranscription: true, exception: { policy: 'DEMO_EXPIRED_IDENTITY_DOCUMENT', reason: 'Renewal pending, receipt presented.', by: 'Supervisor One', at: '2026-11-14T09:30:00Z' },
      interest: 'USUFRUCT', shareNum: 1, shareDen: 1, usufructTerm: 'LIFE', identityVersionId: 'v1', identityVersion: 1, verification: 'UNVERIFIED_DEMO',
      transcribedBy: 'Intake One', transcribedAt: '2026-11-14T09:00:00Z', sourceFile: 'f1', sourceVersion: undefined, check },
    { ref: 'p2', kind: 'NATURAL_PERSON', fullName: 'DEMO Bare Owner', identityKind: 'PASSPORT', identityNumber: 'DEMO-P-2', expiredAtTranscription: false,
      interest: 'BARE_OWNERSHIP', shareNum: 1, shareDen: 1, identityVersionId: 'v2', identityVersion: 1, verification: 'UNVERIFIED_DEMO',
      transcribedBy: 'Intake One', transcribedAt: '2026-11-14T09:00:00Z', basisFile: 'f2', check },
  ],
  mandates: [],
  files: [
    { id: 'f1', purpose: 'IDENTITY_DOCUMENT', name: 'id.pdf', sha256: 'a'.repeat(64), mediaType: 'application/pdf', sizeBytes: 10 },
    { id: 'f2', purpose: 'OWNERSHIP_EVIDENCE', name: 'deed.pdf', sha256: 'b'.repeat(64), mediaType: 'application/pdf', sizeBytes: 10 },
  ],
}

describe('the evidence a conservator examines (ADR-043)', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('reads only on request, and shows rights, terms, validity, exception and the cited documents', async () => {
    const read = vi.spyOn(registerApi, 'caseEvidence').mockResolvedValue(evidence)
    render(<CaseEvidence texts={t('en')} lang="en" applicationId="a1" />)
    expect(read).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Examine the approved owners and their evidence' }))
    expect(await screen.findByText('DEMO Usufructuary')).toBeTruthy()
    expect(read).toHaveBeenCalledWith('a1')
    expect(screen.getByText('Usufruct 1/1')).toBeTruthy()
    expect(screen.getByText('For life')).toBeTruthy()
    expect(screen.getByText('Bare ownership 1/1')).toBeTruthy()
    expect(screen.getByText(/Already expired when transcribed/)).toBeTruthy()
    expect(screen.getByText('Authorised by Supervisor One: Renewal pending, receipt presented.')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'id.pdf' }).getAttribute('href')).toBe('/api/v1/register/cases/a1/evidence/files/f1')
    expect(screen.getByRole('link', { name: 'deed.pdf' })).toBeTruthy()
    expect(screen.getAllByText('DEMO, not independently verified')).toHaveLength(2)
    expect(screen.queryByText(/phone/i)).toBeNull()
  })

  it('says why the server refuses', async () => {
    vi.spyOn(registerApi, 'caseEvidence').mockRejectedValue(new ApiError(403, { code: 'FORBIDDEN' }))
    render(<CaseEvidence texts={t('en')} lang="en" applicationId="a1" />)
    fireEvent.click(screen.getByRole('button', { name: 'Examine the approved owners and their evidence' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByText('DEMO Usufructuary')).toBeNull()
  })

  it('names a stale proposal in both languages', () => {
    expect(t('en').errors.STALE_PROPOSAL).toMatch(/withdraw or reject it/)
    expect(t('fr').errors.STALE_PROPOSAL).toMatch(/retirez-la ou rejetez-la/)
  })
})
