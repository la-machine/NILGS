// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { mapsApi } from './api'
import type { Background, ProjectedGeometry } from './api'
import { MapView } from './MapView'

// MapLibre needs WebGL, which jsdom has not: the tiled view is replaced by a marker.
vi.mock('./TileView', () => ({
  default: ({ background }: { background: Background }) => <div data-testid={`tile-${background.kind}`} />,
}))

const texts = t('en')
const geometry = { type: 'MultiPolygon' as const, coordinates: [[[[11.5, 3.87], [11.5003, 3.87], [11.5003, 3.8703], [11.5, 3.87]]]] }
const display: ProjectedGeometry = {
  srid: 32632,
  geometry: { type: 'MultiPolygon', coordinates: [[[[776200, 428100], [776230, 428100], [776230, 428130], [776200, 428100]]]] },
}

const base = { name: null, tileType: null, tilesUrl: null, glyphsUrl: null, attribution: null, license: null, source: null, date: null, bounds: null, minzoom: null, maxzoom: null, synthetic: false }

const catalogue = (): Background[] => [
  { ...base, kind: 'TECHNICAL', available: true, unavailableReason: null },
  { ...base, kind: 'GEOGRAPHIC', available: false, unavailableReason: 'NOT_INSTALLED' },
  { ...base, kind: 'SATELLITE', available: true, unavailableReason: null, tileType: 'raster', tilesUrl: '/api/v1/map/satellite/tiles.pmtiles', synthetic: true, name: 'Synthetic' },
]

describe('map workspace', () => {
  afterEach(cleanup)

  it('offers the three views together, the technical one first and offline', async () => {
    vi.spyOn(mapsApi, 'backgrounds').mockResolvedValue(catalogue())
    render(<MapView texts={texts} geometry={geometry} display={display} areaM2={900} />)
    const radios = await screen.findAllByRole('radio')
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(['TECHNICAL', 'GEOGRAPHIC', 'SATELLITE'])
    expect((radios[0] as HTMLInputElement).checked).toBe(true)
    expect(screen.getByTestId('parcel-boundary')).toBeTruthy()
    expect(screen.getByText(/Works offline/)).toBeTruthy()
  })

  it('explains a view without data and offers the technical view back', async () => {
    vi.spyOn(mapsApi, 'backgrounds').mockResolvedValue(catalogue())
    render(<MapView texts={texts} geometry={geometry} display={display} areaM2={900} />)
    fireEvent.click(await screen.findByRole('radio', { name: /Geographic/ }))
    const panel = await screen.findByTestId('unavailable-geographic')
    expect(panel.textContent).toContain('data not installed on this server')
    expect(panel.textContent).toContain('OpenStreetMap tiles')
    fireEvent.click(screen.getByRole('button', { name: 'Switch to the technical view' }))
    expect(screen.getByTestId('parcel-boundary')).toBeTruthy()
  })

  it('renders an available background, says it is synthetic, and never reads the backgrounds twice', async () => {
    const backgrounds = vi.spyOn(mapsApi, 'backgrounds').mockResolvedValue(catalogue())
    render(<MapView texts={texts} geometry={geometry} display={display} areaM2={900} />)
    const satellite = await screen.findByRole('radio', { name: /Satellite/ })
    fireEvent.click(satellite)
    expect(await screen.findByTestId('tile-SATELLITE')).toBeTruthy()
    expect(screen.getByText(/needs a connection to the server/)).toBeTruthy()
    expect(screen.getAllByText(/synthetic test data/).length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('radio', { name: /Technical/ }))
    expect(screen.getByTestId('parcel-boundary')).toBeTruthy()
    expect(backgrounds).toHaveBeenCalledTimes(1)
  })

  it('lets the page hold the chosen view (the address keeps it)', async () => {
    vi.spyOn(mapsApi, 'backgrounds').mockResolvedValue(catalogue())
    const onViewChange = vi.fn()
    render(<MapView texts={texts} geometry={geometry} display={display} areaM2={900} view="TECHNICAL" onViewChange={onViewChange} />)
    fireEvent.click(await screen.findByRole('radio', { name: /Satellite/ }))
    expect(onViewChange).toHaveBeenCalledWith('SATELLITE')
  })

  it('keeps the technical view when the list of backgrounds cannot be read', async () => {
    vi.spyOn(mapsApi, 'backgrounds').mockRejectedValue(new Error('offline'))
    render(<MapView texts={texts} geometry={geometry} display={display} areaM2={900} />)
    expect(screen.getByTestId('parcel-boundary')).toBeTruthy()
    fireEvent.click(await screen.findByRole('radio', { name: /Satellite/ }))
    expect((await screen.findByTestId('unavailable-satellite')).textContent).toContain('list of backgrounds unavailable')
  })
})
