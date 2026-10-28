// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { conflictsApi } from './api'
import type { ParcelAlert } from './api'
import { ParcelAlerts } from './ParcelAlerts'

const base: ParcelAlert = {
  id: 'a1', type: 'OVERLAP', status: 'OPEN', otherReference: 'DEMO-PCL-YDE1-2026-000002-8', otherOfficeCode: 'YDE1',
  thisVersionNo: 1, otherVersionNo: 2, intersectionM2: 600.12, shareOfThisParcel: 0.66667, shareOfOtherParcel: 0.5,
  intersection: { type: 'MultiPolygon', coordinates: [] }, normalizedReference: null, raisedAt: '2026-10-24T09:00:00Z',
  resolution: null, resolutionNote: null, resolvedAt: null, supersededAt: null,
}

describe('a parcel\'s alerts', () => {
  afterEach(cleanup)

  it('show open alerts with the other parcel, the intersection and both shares; closed ones as history', async () => {
    vi.spyOn(conflictsApi, 'ofParcel').mockResolvedValue([
      base,
      { ...base, id: 'a2', type: 'DUPLICATE_REFERENCE', intersectionM2: null, shareOfThisParcel: null, shareOfOtherParcel: null,
        intersection: null, normalizedReference: 'TF123MFOUNDI', otherReference: 'DEMO-PCL-YDE1-2026-000003-9' },
      { ...base, id: 'a3', intersectionM2: 10.5, status: 'SUPERSEDED', supersededAt: '2026-10-24T10:00:00Z', otherReference: 'DEMO-PCL-YDE1-2026-000004-A' },
    ])
    render(<ParcelAlerts texts={t('en')} lang="en" reference="DEMO-PCL-YDE1-2026-000001-7" versionNo={1} />)
    expect(await screen.findByText('2 open')).toBeTruthy()
    expect(screen.getByText('Intersection 600.12 m²: 66.7 % of this parcel, 50.0 % of the other')).toBeTruthy()
    expect(screen.getByText('Same external reference: TF123MFOUNDI')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'DEMO-PCL-YDE1-2026-000002-8' }).getAttribute('href')).toBe('#/parcels/DEMO-PCL-YDE1-2026-000002-8')
    expect(screen.getByText('History (1)')).toBeTruthy()
    expect(screen.getByText('superseded by a correction')).toBeTruthy()
  })

  it('say plainly when there is none', async () => {
    vi.spyOn(conflictsApi, 'ofParcel').mockResolvedValue([])
    render(<ParcelAlerts texts={t('en')} lang="en" reference="DEMO-PCL-YDE1-2026-000001-7" versionNo={1} />)
    expect(await screen.findByText('No alert')).toBeTruthy()
  })
})
