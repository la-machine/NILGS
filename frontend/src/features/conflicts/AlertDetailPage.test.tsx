// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { AlertDetailPage } from './AlertDetailPage'
import { conflictsApi } from './api'
import type { AlertDetail } from './api'
import { resolveAccess } from './rules'

const square = (x: number, w: number) => ({
  type: 'MultiPolygon' as const, coordinates: [[[[x, 0], [x + w, 0], [x + w, 30], [x, 30], [x, 0]]]],
})

const alert: AlertDetail = {
  id: 'a1', type: 'OVERLAP', status: 'OPEN', version: 0,
  a: { reference: 'DEMO-PCL-YDE1-2026-000001-7', officeCode: 'YDE1', versionNo: 1, currentVersionNo: 1, authorName: 'Officer One', areaM2: 900, share: 0.66667 },
  b: { reference: 'DEMO-PCL-YDE1-2026-000002-8', officeCode: 'YDE1', versionNo: 1, currentVersionNo: 2, authorName: 'Officer One', areaM2: 900, share: 0.66667 },
  intersectionM2: 600, normalizedReference: null,
  display: { srid: 32632, a: square(0, 30), b: square(10, 30), intersection: square(10, 20) },
  raisedAt: '2026-10-24T09:00:00Z', raisedByReference: 'DEMO-PCL-YDE1-2026-000002-8',
  resolution: null, resolutionNote: null, resolvedByName: null, resolvedAt: null, supersededAt: null, resolverIsAuthor: false,
}

function context(permissions = ['alert.review@YDE1', 'alert.resolve@YDE1'], specialGrants: Session['specialGrants'] = []): PageContext {
  const session: Session = {
    userId: 'u2', displayName: 'Officer Two', accountType: 'STAFF', roles: ['CADASTRAL_OFFICER'],
    grants: [{ role: 'CADASTRAL_OFFICER', officeCode: 'YDE1' }], permissions, specialGrants,
    authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-24T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

const page = (ctx: PageContext) => render(<DialogProvider><AlertDetailPage ctx={ctx} id="a1" /></DialogProvider>)

describe('reviewing an alert', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('draws both parcels and the intersection, side by side with the version each was compared at', async () => {
    vi.spyOn(conflictsApi, 'read').mockResolvedValue(alert)
    page(context())
    expect(await screen.findByRole('img', { name: /Parcels DEMO-PCL-YDE1-2026-000001-7 and DEMO-PCL-YDE1-2026-000002-8 over each other/ })).toBeTruthy()
    expect(screen.getByText('Intersection of 600.00 m² on the spheroid: 66.7 % of A, 66.7 % of B.')).toBeTruthy()
    expect(screen.getByText('current: v2')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Zoom to the intersection' }).getAttribute('aria-pressed')).toBe('false')
  })

  it('tells the author why they cannot resolve, instead of a form the server would refuse', async () => {
    vi.spyOn(conflictsApi, 'read').mockResolvedValue({ ...alert, resolverIsAuthor: true })
    page(context())
    expect(await screen.findByText('You cannot resolve this alert')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Record the resolution' })).toBeNull()
  })

  it('needs an outcome and a note, confirms, then sends them with the version read', async () => {
    vi.spyOn(conflictsApi, 'read').mockResolvedValue(alert)
    const resolve = vi.spyOn(conflictsApi, 'resolve').mockResolvedValue({
      ...alert, status: 'RESOLVED', version: 1, resolution: 'NO_CONFLICT', resolutionNote: 'Checked on site with both owners',
      resolvedByName: 'Officer Two', resolvedAt: '2026-10-24T10:00:00Z',
    })
    page(context())
    fireEvent.click(await screen.findByRole('button', { name: 'Record the resolution' }))
    expect(screen.getByText('Choose an outcome.')).toBeTruthy()
    expect(screen.getByText('The note needs at least 10 characters.')).toBeTruthy()

    fireEvent.click(screen.getByRole('radio', { name: /No conflict/ }))
    fireEvent.change(screen.getByLabelText(/^Justification note/), { target: { value: '  Checked on site with both owners  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the resolution' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(resolve).toHaveBeenCalledWith('a1',
      { expectedVersion: 0, resolution: 'NO_CONFLICT', note: 'Checked on site with both owners' }))
    expect(await screen.findByText('Alert resolved')).toBeTruthy()
    expect(screen.getByText('Officer Two')).toBeTruthy()
  })

  it('overwrites nothing when the alert changed meanwhile, and offers to reload', async () => {
    vi.spyOn(conflictsApi, 'read').mockResolvedValue(alert)
    vi.spyOn(conflictsApi, 'resolve').mockRejectedValue(new ApiError(409, { code: 'STALE_VERSION' }))
    page(context())
    fireEvent.click(await screen.findByRole('radio', { name: /Referred for adjudication/ }))
    fireEvent.change(screen.getByLabelText(/^Justification note/), { target: { value: 'Both claims are documented' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the resolution' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Resolve' }))
    expect(await screen.findByText('The alert changed meanwhile')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeTruthy()
  })
})

describe('who may resolve', () => {
  it('a role at either office, else a special grant in force there or everywhere, else nobody', () => {
    const pair = { a: { ...alert.a, officeCode: 'YDE1' }, b: { ...alert.b, officeCode: 'DLA1' } }
    expect(resolveAccess(context(['alert.resolve@DLA1']).session, pair)).toEqual({ kind: 'role' })
    const grant = { id: 'g1', permission: 'alert.resolve', officeCode: 'DLA1', validUntil: null, usesLeft: 2 }
    expect(resolveAccess(context([], [grant]).session, pair)).toEqual({ kind: 'grant', grant })
    expect(resolveAccess(context([], [{ ...grant, officeCode: 'BFS1' }]).session, pair)).toBeNull()
    expect(resolveAccess(context(['alert.review@YDE1']).session, pair)).toBeNull()
  })
})
