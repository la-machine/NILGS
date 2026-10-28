// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import { geometryOf } from './geometry'

describe('geometry input', () => {
  const polygon = { type: 'Polygon', coordinates: [[[11.5, 3.87], [11.6, 3.87], [11.6, 3.97], [11.5, 3.87]]] }

  it('passes a geometry through unchanged; the server validates it', () => {
    expect(geometryOf(JSON.stringify(polygon))).toEqual({ geometry: polygon })
    expect(geometryOf('{"type":"Point","coordinates":[1,2]}')).toEqual({ geometry: { type: 'Point', coordinates: [1, 2] } })
  })

  it('takes the geometry of a Feature or of a one-feature collection', () => {
    expect(geometryOf(JSON.stringify({ type: 'Feature', properties: {}, geometry: polygon }))).toEqual({ geometry: polygon })
    expect(geometryOf(JSON.stringify({ type: 'FeatureCollection', features: [{ type: 'Feature', geometry: polygon }] })))
      .toEqual({ geometry: polygon })
    expect(geometryOf(JSON.stringify({ type: 'FeatureCollection', features: [] }))).toEqual({ error: 'GEOMETRY_ONE_FEATURE' })
  })

  it('refuses text that is not JSON', () => {
    expect(geometryOf('POLYGON((1 2, 3 4))')).toEqual({ error: 'GEOMETRY_MALFORMED' })
  })
})
