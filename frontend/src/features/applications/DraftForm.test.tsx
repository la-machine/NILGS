// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { documentsApi } from '../documents'
import { applicationsApi } from './api'
import type { Application, ApplicationType } from './api'
import { DraftForm } from './DraftForm'
import { messages } from './form'

const type: ApplicationType = {
  code: 'DEMO_REGISTRATION', versionNo: 1, nameFr: 'Demande (DEMO)', nameEn: 'Land registration request (DEMO)',
  effectiveFrom: '2026-10-24T08:00:00Z',
  requirements: {
    fields: [
      { key: 'landUse', kind: 'CHOICE', required: true, maxLength: 0, labelFr: 'Usage', labelEn: 'Land use', hintFr: null, hintEn: null,
        options: [{ code: 'RESIDENTIAL', labelFr: 'Habitation', labelEn: 'Residential' }, { code: 'AGRICULTURAL', labelFr: 'Agricole', labelEn: 'Agricultural' }] },
      { key: 'statement', kind: 'LONG_TEXT', required: true, maxLength: 2000, options: null, labelFr: 'Exposé', labelEn: 'Statement',
        hintFr: null, hintEn: null },
    ],
    documents: [{ code: 'DEMO_IDENTITY', required: true, labelFr: 'Identité (DEMO)', labelEn: 'Identity document (DEMO)' }],
  },
}

const draft: Application = {
  id: 'd1', reference: null, type, state: 'DRAFT', parcelReference: null, officeCode: null, data: { landUse: 'RESIDENTIAL' },
  createdAt: '2026-10-24T08:00:00Z', updatedAt: '2026-10-24T08:00:00Z', submittedAt: null, version: 3, history: [], completeness: [], decision: null,
  since: '2026-10-24T08:00:00Z', waitingOn: 'APPLICANT', pendingRequest: null,
}

function context(): PageContext {
  const session: Session = {
    userId: 'u1', displayName: 'Applicant', accountType: 'APPLICANT', roles: ['APPLICANT'], grants: [],
    permissions: ['application.create#own', 'application.read#own', 'application.submit#own'], specialGrants: [],
    authLevel: 'FULL', nextStep: 'DONE', identityVerified: false, authenticatedAt: '2026-10-24T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

const form = (current: Application | null) =>
  render(<DialogProvider><DraftForm ctx={context()} type={type} draft={current} /></DialogProvider>)

describe('an application draft', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('shows the fields of its type, an upload per document category, and saves with the version read', async () => {
    const update = vi.spyOn(applicationsApi, 'update').mockResolvedValue({ ...draft, version: 4 })
    vi.spyOn(documentsApi, 'list').mockResolvedValue({
      acceptsDocuments: true, openCategories: ['DEMO_IDENTITY'], maxBytes: 10485760, allowedTypes: [], missingRequired: ['DEMO_IDENTITY'],
      categories: [{ code: 'DEMO_IDENTITY', required: true, labelFr: 'Identité (DEMO)', labelEn: 'Identity document (DEMO)', versions: [] }],
    })
    form(draft)
    expect(screen.getByRole('combobox', { name: /Land use/ })).toHaveProperty('value', 'RESIDENTIAL')
    expect(await screen.findByLabelText('Choose a file for "Identity document (DEMO)"')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^Parcel reference/), { target: { value: ' demo-pcl-yde1-2026-000001-7 ' } })
    fireEvent.change(screen.getByLabelText(/^Statement/), { target: { value: '  Inherited (synthetic)  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the draft' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('d1', {
      expectedVersion: 3, parcelReference: 'DEMO-PCL-YDE1-2026-000001-7', data: { landUse: 'RESIDENTIAL', statement: 'Inherited (synthetic)' },
    }))
    expect(await screen.findByText('Draft saved.')).toBeTruthy()
  })

  it('submits after confirmation, and an incomplete draft shows every item, next to its field too', async () => {
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ acceptsDocuments: true, openCategories: [], maxBytes: 1, allowedTypes: [], missingRequired: [], categories: [] })
    vi.spyOn(applicationsApi, 'update').mockResolvedValue({ ...draft, version: 4 })
    const submit = vi.spyOn(applicationsApi, 'submit').mockRejectedValue(new ApiError(422, {
      code: 'APPLICATION_INCOMPLETE', items: ['MISSING:statement', 'MISSING:parcelReference'],
    }))
    form(draft)
    fireEvent.click(screen.getByRole('button', { name: 'Submit the application' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit the application' }))
    await waitFor(() => expect(submit).toHaveBeenCalledWith('d1', 4, expect.stringMatching(/^[0-9a-f-]{36}$/)))
    expect(await screen.findByText('2 item(s) to fix before going on')).toBeTruthy()
    expect(screen.getAllByText('"Statement" is required.')).toHaveLength(2)
    expect(screen.getAllByText('"Parcel reference" is required.')).toHaveLength(2)
  })

  it('a new draft is created once, with one key for retries', async () => {
    const create = vi.spyOn(applicationsApi, 'create').mockResolvedValue({ id: 'n1', reference: null, state: 'DRAFT', version: 0 })
    form(null)
    expect(screen.queryByRole('button', { name: 'Delete the draft' })).toBeNull()
    expect(screen.getByText('Save the draft first: then you can upload each document here.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Save the draft' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toEqual({ typeCode: 'DEMO_REGISTRATION', parcelReference: null, data: {} })
    await waitFor(() => expect(window.location.hash).toBe('#/applications/n1?saved=1'))
  })
})

describe('the server\'s items', () => {
  it('become messages tied to their field, in the reader\'s language', () => {
    const texts = t('fr')
    expect(messages(texts, 'fr', type, ['TOO_LONG:statement', 'PARCEL_NOT_FOUND', 'UNKNOWN_FIELD:salary'])).toEqual([
      { field: 'statement', text: '« Exposé » est trop long.' },
      { field: 'parcelReference', text: 'Aucune parcelle active ne porte cette référence.' },
      { field: null, text: 'Le champ « salary » n\'existe plus dans ce type de demande.' },
    ])
  })
})
