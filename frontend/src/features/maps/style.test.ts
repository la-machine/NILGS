// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Our map styles must pass MapLibre's own validator: an invalid rule makes the whole
// background fail in the browser (it then falls back to the technical view).
import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec'
import { describe, expect, it } from 'vitest'
import type { Background } from './api'
import { styleFor } from './style'

const base: Background = {
  kind: 'GEOGRAPHIC', available: true, unavailableReason: null, name: 'OSM', tileType: 'vector',
  tilesUrl: '/api/v1/map/geographic/tiles.pmtiles', glyphsUrl: '/api/v1/map/geographic/fonts/{fontstack}/{range}.pbf',
  attribution: '© OpenStreetMap contributors', license: 'ODbL', source: 'test', date: '2026-10-21',
  bounds: [8.3, 1.6, 16.2, 13.1], minzoom: 0, maxzoom: 14, synthetic: false,
}

describe('map styles', () => {
  it('are valid for MapLibre: geographic and satellite', () => {
    for (const background of [base, { ...base, kind: 'SATELLITE' as const, tileType: 'raster' as const }]) {
      const errors = validateStyleMin(styleFor(background) as never)
      expect(errors.map((e) => e.message)).toEqual([])
    }
  })

  it('draw sharper details over the satellite base, within their zooms, from our own archives', () => {
    const style = styleFor({
      ...base, kind: 'SATELLITE', tileType: 'raster', details: [{
        name: 'Douala aerial', tilesUrl: '/api/v1/map/satellite/detail-douala.pmtiles', attribution: '(c) OAM', license: 'CC-BY 4.0',
        source: 'OpenAerialMap', date: '2026-05-10', bounds: [9.72, 3.97, 9.77, 4.01], minzoom: 14, maxzoom: 20,
      }],
    })
    expect(validateStyleMin(style as never).map((e) => e.message)).toEqual([])
    expect(style.layers.map((l) => l.id)).toEqual(['paper', 'imagery', 'detail-0'])
    expect(style.layers[2]).toMatchObject({ minzoom: 14 })
    expect(JSON.stringify(style.sources)).toContain('detail-douala.pmtiles')
  })

  it('draw streets, their names, buildings and places from our own archive only', () => {
    const style = styleFor(base)
    const ids = style.layers.map((l) => l.id)
    for (const id of ['building', 'road-minor', 'road-primary', 'road-name', 'place', 'place-local', 'poi']) {
      expect(ids).toContain(id)
    }
    expect(JSON.stringify(style.sources)).toContain(`pmtiles://${window.location.origin}/api/v1/map/`)
    expect(style.glyphs?.startsWith(window.location.origin)).toBe(true)
  })
})
