// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import type { ApplicationType } from '../applications'
import { documentsApi } from '../documents'
import { casesApi } from './api'
import type { Case } from './api'
import { CaseDetailPage } from './CaseDetailPage'

const type: ApplicationType = {
  code: 'DEMO_REGISTRATION', versionNo: 1, nameFr: 'Demande (DEMO)', nameEn: 'Land registration request (DEMO)', effectiveFrom: '2026-10-24T08:00:00Z',
  requirements: {
    fields: [{ key: 'statement', kind: 'LONG_TEXT', required: true, maxLength: 2000, options: null, labelFr: 'Exposé', labelEn: 'Statement', hintFr: null, hintEn: null }],
    documents: [
      { code: 'DEMO_IDENTITY', required: true, labelFr: 'Identité (DEMO)', labelEn: 'Identity document (DEMO)' },
      { code: 'DEMO_PROOF_OF_RIGHT', required: true, labelFr: 'Preuve (DEMO)', labelEn: 'Proof of right (DEMO)' },
    ],
  },
}

const found: Case = {
  id: 'c1', reference: 'DEMO-APP-2026-000001-X', typeCode: 'DEMO_REGISTRATION', state: 'COMPLETENESS_REVIEW',
  parcelReference: 'DEMO-PCL-YDE1-2026-000001-7', officeCode: 'YDE1', applicantName: 'Demo Applicant',
  submittedAt: '2026-10-24T09:00:00Z', updatedAt: '2026-10-24T09:00:00Z', version: 3, type,
  data: { statement: 'Inherited (synthetic)' }, missing: ['MISSING_DOCUMENT:DEMO_PROOF_OF_RIGHT'],
  completeness: [{ kind: 'AUTOMATIC', missing: ['MISSING_DOCUMENT:DEMO_PROOF_OF_RIGHT'], requested: [], message: null, actorName: null, at: '2026-10-24T09:00:00Z' }],
  history: [{ from: 'DRAFT', to: 'SUBMITTED', actorName: 'Demo Applicant', reason: null, at: '2026-10-24T09:00:00Z' },
    { from: 'SUBMITTED', to: 'COMPLETENESS_REVIEW', actorName: null, reason: null, at: '2026-10-24T09:00:00Z' }],
  payment: { standing: 'DUE', reference: null, amount: 25000, currency: 'XAF', blocksAcceptance: true },
}

function context(permissions: string[]): PageContext {
  const session: Session = {
    userId: 'u1', displayName: 'Intake', accountType: 'STAFF', roles: ['INTAKE_OFFICER'], grants: [{ role: 'INTAKE_OFFICER', officeCode: 'YDE1' }],
    permissions, specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-24T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

const intake = ['application.read@YDE1', 'completeness.review@YDE1', 'completeness.request-info@YDE1']

describe('a case in completeness review', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('shows what is missing in words, and refuses to accept until nothing is', async () => {
    vi.spyOn(casesApi, 'read').mockResolvedValue(found)
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: false, openCategories: [], maxBytes: 1, allowedTypes: [], missingRequired: [], categories: [] })
    render(<DialogProvider><CaseDetailPage ctx={context(intake)} id="c1" /></DialogProvider>)
    expect((await screen.findAllByText('Required document missing: "Proof of right (DEMO)".')).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Accept the case as complete' })).toHaveProperty('disabled', true)
    expect(screen.getByText('Not possible while something required is missing: request it.')).toBeTruthy()
    expect(screen.getAllByText(/^The system \(automatic check\)/).length).toBeGreaterThan(0)
  })

  it('requests the missing documents, ticked for the officer, with a message, after confirmation', async () => {
    vi.spyOn(casesApi, 'read').mockResolvedValue(found)
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: false, openCategories: [], maxBytes: 1, allowedTypes: [], missingRequired: [], categories: [] })
    const request = vi.spyOn(casesApi, 'requestInformation').mockResolvedValue({ ...found, state: 'INFO_REQUESTED', version: 4 })
    render(<DialogProvider><CaseDetailPage ctx={context(intake)} id="c1" /></DialogProvider>)
    const proof = await screen.findByRole('checkbox', { name: 'Proof of right (DEMO)' })
    expect(proof).toHaveProperty('checked', true)
    expect(screen.getByRole('checkbox', { name: 'Identity document (DEMO)' })).toHaveProperty('checked', false)
    fireEvent.click(screen.getByRole('button', { name: 'Request this information' }))
    expect(screen.getByText('The message needs 10 to 2000 characters.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^Message to the applicant/), { target: { value: 'The deed is missing from the file' } })
    fireEvent.click(screen.getByRole('button', { name: 'Request this information' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Request this information' }))
    await waitFor(() => expect(request).toHaveBeenCalledWith('c1',
      { expectedVersion: 3, categories: ['DEMO_PROOF_OF_RIGHT'], message: 'The deed is missing from the file' }))
    expect(await screen.findByText('Information requested: the case waits for the applicant\'s response.')).toBeTruthy()
  })

  it('holds the acceptance of a complete case until the DEMO fee is paid', async () => {
    vi.spyOn(casesApi, 'read').mockResolvedValue({ ...found, missing: [] })
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: false, openCategories: [], maxBytes: 1, allowedTypes: [], missingRequired: [], categories: [] })
    const { unmount } = render(<DialogProvider><CaseDetailPage ctx={context(intake)} id="c1" /></DialogProvider>)
    expect(await screen.findByText('Not possible until the DEMO fee is paid (simulated payment).')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Accept the case as complete' })).toHaveProperty('disabled', true)
    expect(screen.getByText('To pay')).toBeTruthy()
    unmount()
    vi.spyOn(casesApi, 'read').mockResolvedValue({ ...found, missing: [],
      payment: { standing: 'PAID', reference: 'DEMO-PAY-2026-00000001-X', amount: 25000, currency: 'XAF', blocksAcceptance: false } })
    render(<DialogProvider><CaseDetailPage ctx={context(intake)} id="c1" /></DialogProvider>)
    expect(await screen.findByText('DEMO-PAY-2026-00000001-X')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Accept the case as complete' })).toHaveProperty('disabled', false)
  })

  it('offers no decision to staff who only read cases', async () => {
    vi.spyOn(casesApi, 'read').mockResolvedValue(found)
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: false, openCategories: [], maxBytes: 1, allowedTypes: [], missingRequired: [], categories: [] })
    render(<DialogProvider><CaseDetailPage ctx={context(['application.read@YDE1'])} id="c1" /></DialogProvider>)
    await screen.findByText('Demo Applicant')
    expect(screen.queryByRole('button', { name: /Accept the case/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Request this information' })).toBeNull()
  })
})
