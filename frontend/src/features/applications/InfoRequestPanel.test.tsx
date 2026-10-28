// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { DialogProvider } from '../../shared/ui/Dialog'
import { documentsApi } from '../documents'
import { applicationsApi } from './api'
import type { Application } from './api'
import { InfoRequestPanel } from './InfoRequestPanel'

const application: Application = {
  id: 'a1', reference: 'DEMO-APP-2026-000001-X', state: 'INFO_REQUESTED', parcelReference: 'DEMO-PCL-YDE1-2026-000001-7',
  officeCode: 'YDE1', data: {}, createdAt: '2026-10-24T08:00:00Z', updatedAt: '2026-10-24T10:00:00Z', submittedAt: '2026-10-24T09:00:00Z',
  version: 4, since: '2026-10-24T10:00:00Z', waitingOn: 'APPLICANT', history: [], decision: null,
  pendingRequest: { categories: ['DEMO_PROOF_OF_RIGHT'], message: 'The deed is missing from the file', since: '2026-10-24T10:00:00Z' },
  type: {
    code: 'DEMO_REGISTRATION', versionNo: 1, nameFr: 'Demande (DEMO)', nameEn: 'Request (DEMO)', effectiveFrom: '2026-10-24T08:00:00Z',
    requirements: { fields: [], documents: [{ code: 'DEMO_PROOF_OF_RIGHT', required: true, labelFr: 'Preuve (DEMO)', labelEn: 'Proof of right (DEMO)' }] },
  },
  completeness: [{ kind: 'INFO_REQUESTED', missing: [], requested: ['DEMO_PROOF_OF_RIGHT'], message: 'The deed is missing from the file',
    by: 'OFFICE', at: '2026-10-24T10:00:00Z' }],
}

describe('an information request, from the applicant\'s side', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('shows what was asked and why, then sends the response with its message after confirmation', async () => {
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: true, openCategories: ['DEMO_PROOF_OF_RIGHT'], maxBytes: 10, allowedTypes: [],
      missingRequired: [], categories: [] })
    const respond = vi.spyOn(applicationsApi, 'respond').mockResolvedValue({ ...application, state: 'COMPLETENESS_REVIEW', version: 5 })
    const responded = vi.fn()
    render(<DialogProvider><InfoRequestPanel texts={t('en')} lang="en" application={application} onResponded={responded} onReload={() => {}} /></DialogProvider>)
    expect(screen.getByText('« The deed is missing from the file »')).toBeTruthy()
    expect(screen.getByText('Documents requested: Proof of right (DEMO).')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^Message \(optional\)/), { target: { value: ' The deed is attached ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send my response' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Send my response' }))
    await waitFor(() => expect(respond).toHaveBeenCalledWith('a1', 4, 'The deed is attached'))
    expect(responded).toHaveBeenCalledTimes(1)
  })
})
