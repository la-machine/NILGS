// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import { documentsApi } from './api'
import type { Documents } from './api'
import { DocumentsPanel } from './DocumentsPanel'
import { precheck, readableSize } from './files'

const listing: Documents = {
  acceptsDocuments: true, openCategories: ['DEMO_IDENTITY', 'DEMO_PROOF_OF_RIGHT'], maxBytes: 10 * 1024 * 1024, allowedTypes: ['application/pdf', 'image/jpeg', 'image/png'],
  missingRequired: ['DEMO_PROOF_OF_RIGHT'],
  categories: [
    { code: 'DEMO_IDENTITY', required: true, labelFr: 'Identité (DEMO)', labelEn: 'Identity document (DEMO)', versions: [
      { id: 'v2', versionNo: 2, status: 'STORED', originalName: 'id-v2.pdf', sizeBytes: 2048, type: 'application/pdf', sha256: 'a'.repeat(64), uploadedAt: '2026-10-24T10:00:00Z' },
      { id: 'v1', versionNo: 1, status: 'STORED', originalName: 'id.pdf', sizeBytes: 1024, type: 'application/pdf', sha256: 'b'.repeat(64), uploadedAt: '2026-10-24T09:00:00Z' },
    ] },
    { code: 'DEMO_PROOF_OF_RIGHT', required: true, labelFr: 'Preuve (DEMO)', labelEn: 'Proof of right (DEMO)', versions: [] },
  ],
}

const row = (code: string) => document.querySelector(`[data-category="${code}"]`) as HTMLElement

describe('the documents of an application', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('show each category with its latest file as a download, earlier versions folded, what is still missing', async () => {
    vi.spyOn(documentsApi, 'list').mockResolvedValue(listing)
    render(<DocumentsPanel texts={t('en')} lang="en" applicationId="a1" editable />)
    expect(await screen.findByText('Required documents still missing: Proof of right (DEMO).')).toBeTruthy()
    const latest = within(row('DEMO_IDENTITY')).getByRole('link', { name: 'id-v2.pdf' })
    expect(latest.getAttribute('href')).toBe('/api/v1/applications/a1/documents/v2/content')
    expect(within(row('DEMO_IDENTITY')).getByText('Earlier versions (1)')).toBeTruthy()
    expect(within(row('DEMO_PROOF_OF_RIGHT')).getByText('No file.')).toBeTruthy()
  })

  it('refuses before sending what the server would refuse, and uploads the rest with a key, then reloads', async () => {
    const list = vi.spyOn(documentsApi, 'list').mockResolvedValue(listing)
    const upload = vi.spyOn(documentsApi, 'upload').mockResolvedValue(
      { id: 'n1', category: 'DEMO_PROOF_OF_RIGHT', versionNo: 1, sizeBytes: 4, type: 'application/pdf', sha256: 'c'.repeat(64) })
    render(<DocumentsPanel texts={t('en')} lang="en" applicationId="a1" editable />)
    const input = await screen.findByLabelText('Choose a file for "Proof of right (DEMO)"')
    fireEvent.change(input, { target: { files: [new File(['MZ'], 'setup.exe')] } })
    expect(screen.getByText(/Only PDF, JPEG and PNG files are accepted/)).toBeTruthy()
    expect(within(row('DEMO_PROOF_OF_RIGHT')).getByRole('button', { name: 'Upload' })).toHaveProperty('disabled', true)

    const pdf = new File(['%PDF'], 'deed.pdf', { type: 'application/pdf' })
    fireEvent.change(input, { target: { files: [pdf] } })
    fireEvent.click(within(row('DEMO_PROOF_OF_RIGHT')).getByRole('button', { name: 'Upload' }))
    await waitFor(() => expect(upload).toHaveBeenCalledWith('a1', 'DEMO_PROOF_OF_RIGHT', pdf, expect.stringMatching(/^[0-9a-f-]{36}$/)))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
  })

  it('says in words why the server refused a file', async () => {
    vi.spyOn(documentsApi, 'list').mockResolvedValue(listing)
    vi.spyOn(documentsApi, 'upload').mockRejectedValue(new ApiError(422, { code: 'PDF_ACTIVE_CONTENT' }))
    render(<DocumentsPanel texts={t('en')} lang="en" applicationId="a1" editable />)
    fireEvent.change(await screen.findByLabelText('Choose a file for "Identity document (DEMO)"'),
      { target: { files: [new File(['%PDF'], 'form.pdf')] } })
    fireEvent.click(within(row('DEMO_IDENTITY')).getByRole('button', { name: 'Upload a new version' }))
    expect(await screen.findByText(/contains active content/)).toBeTruthy()
  })

  it('offers no upload once the application is submitted', async () => {
    vi.spyOn(documentsApi, 'list').mockResolvedValue({ ...listing, acceptsDocuments: false, openCategories: [] })
    render(<DocumentsPanel texts={t('en')} lang="en" applicationId="a1" editable={false} />)
    await screen.findByRole('link', { name: 'id-v2.pdf' })
    expect(screen.queryByRole('button', { name: /Upload/ })).toBeNull()
  })
})

describe('the checks before sending', () => {
  it('stop an empty, oversized or non-PDF/JPEG/PNG file', () => {
    expect(precheck({ name: 'a.pdf', size: 0 }, listing)).toBe('FILE_EMPTY')
    expect(precheck({ name: 'a.pdf', size: listing.maxBytes + 1 }, listing)).toBe('FILE_TOO_LARGE')
    expect(precheck({ name: 'a.docx', size: 10 }, listing)).toBe('TYPE_NOT_ALLOWED')
    expect(precheck({ name: 'photo.JPG', size: 10 }, listing)).toBeNull()
    expect(readableSize(10 * 1024 * 1024, 'fr')).toBe('10 Mo')
  })
})
