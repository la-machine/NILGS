// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { parcelsApi } from './api'
import { ParcelCreatePage } from './ParcelCreatePage'

const square = '{"type":"Polygon","coordinates":[[[11.5,3.87],[11.5003,3.87],[11.5003,3.8703],[11.5,3.8703],[11.5,3.87]]]}'

function context(): PageContext {
  const session: Session = {
    userId: 'u1', displayName: 'Cadastre', accountType: 'STAFF', roles: ['CADASTRAL_OFFICER'],
    grants: [{ role: 'CADASTRAL_OFFICER', officeCode: 'YDE1' }], permissions: ['parcel.create@YDE1', 'parcel.read'],
    specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-23T08:00:00Z',
  }
  const run = async (action: () => Promise<void>) => {
    try {
      await action()
    } catch {
      // the shell would show it
    }
  }
  return { texts: t('en'), lang: 'en', session, run, refreshSession: async () => {} }
}

describe('recording a parcel', () => {
  afterEach(cleanup)

  it('previews the boundary, then sends exactly the geometry typed, with the chosen system', async () => {
    const create = vi.spyOn(parcelsApi, 'create').mockResolvedValue({ reference: 'DEMO-PCL-YDE1-2026-000001-U', versionNo: 1, areaM2: 1100, alerts: [] })
    render(<ParcelCreatePage ctx={context()} />)
    expect(screen.getByRole('combobox', { name: /Office/ })).toHaveProperty('value', 'YDE1')
    fireEvent.change(screen.getByLabelText(/^Locality/), { target: { value: 'Mvog-Ada' } })
    fireEvent.change(screen.getByLabelText(/^GeoJSON \(Polygon/), { target: { value: square } })
    // Described in the field and repeated in the review summary.
    expect(screen.getAllByText('Polygon: 1 polygon(s), 4 vertices')).toHaveLength(2)
    expect(screen.getByRole('img', { name: /Preview of the boundary entered, UTM 32N/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Record the parcel' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    const [body, key] = create.mock.calls[0]
    expect(body).toEqual({ officeCode: 'YDE1', locality: 'Mvog-Ada', externalReference: undefined, srid: 4326, geometry: JSON.parse(square) })
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    await waitFor(() => expect(window.location.hash).toBe('#/parcels/DEMO-PCL-YDE1-2026-000001-U?created=1100.00'))
  })

  it('shows a refused geometry next to the boundary, with the server\'s reason, and keeps everything typed', async () => {
    vi.spyOn(parcelsApi, 'create').mockRejectedValue(new ApiError(422, { code: 'GEOMETRY_INVALID', reason: 'Self-intersection[11.50015 3.87015]' }))
    render(<ParcelCreatePage ctx={context()} />)
    fireEvent.change(screen.getByLabelText(/^Locality/), { target: { value: 'Mvog-Ada' } })
    fireEvent.change(screen.getByLabelText(/^GeoJSON \(Polygon/), { target: { value: square } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the parcel' }))
    expect(await screen.findByText('Invalid geometry.')).toBeTruthy()
    expect(screen.getByText(/Server's explanation: Self-intersection/)).toBeTruthy()
    expect(screen.getByLabelText(/^GeoJSON \(Polygon/)).toHaveProperty('value', square)
    expect(screen.getByLabelText(/^Locality/)).toHaveProperty('value', 'Mvog-Ada')
  })

  it('sends nothing while the locality or the boundary is missing', () => {
    const create = vi.spyOn(parcelsApi, 'create')
    render(<ParcelCreatePage ctx={context()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Record the parcel' }))
    expect(screen.getByText('Enter the locality (at least 2 characters).')).toBeTruthy()
    expect(screen.getByText('Paste or load the parcel\'s boundary.')).toBeTruthy()
    expect(create).not.toHaveBeenCalled()
  })

  it('warns, without changing anything, when the numbers do not look like the chosen system', () => {
    render(<ParcelCreatePage ctx={context()} />)
    fireEvent.click(screen.getByRole('radio', { name: /UTM zone 33 North/ }))
    fireEvent.change(screen.getByLabelText(/^GeoJSON \(Polygon/), { target: { value: square } })
    expect(screen.getByText(/do not look like the chosen system/)).toBeTruthy()
    expect(screen.getByLabelText(/^GeoJSON \(Polygon/)).toHaveProperty('value', square)
  })
})
