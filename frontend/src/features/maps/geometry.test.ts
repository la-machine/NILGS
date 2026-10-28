// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import {
  displayZone, distance, extent, formatArea, formatLength, gridLines, inspect, niceStep, pathLength, planarArea, polygonArea, previewOf, toLatLon,
} from './geometry'

describe('technical view geometry', () => {
  it('finds the extent of every ring', () => {
    expect(extent([[[[10, 20], [30, 25], [15, 40], [10, 20]]], [[[50, 5], [55, 5], [55, 9], [50, 5]]]]))
      .toEqual({ minX: 10, minY: 5, maxX: 55, maxY: 40 })
  })

  it('chooses round grid steps of 1, 2 or 5 times a power of ten', () => {
    expect(niceStep(30)).toBe(5)
    expect(niceStep(120)).toBe(20)
    expect(niceStep(800)).toBe(100)
    expect(niceStep(0.9)).toBe(0.1)
    expect(gridLines(12, 47, 10)).toEqual([20, 30, 40])
  })

  it('measures distances and planar areas in metres', () => {
    expect(distance([0, 0], [3, 4])).toBe(5)
    expect(pathLength([[0, 0], [3, 4], [3, 10]])).toBe(11)
    expect(polygonArea([[0, 0], [30, 0], [30, 30], [0, 30]])).toBe(900)
    expect(formatLength(12.345)).toBe('12.35 m')
    expect(formatLength(1500)).toBe('1.500 km')
    expect(formatArea(900)).toBe('900.00 m²')
    expect(formatArea(25_000)).toBe('2.5000 ha (25000 m²)')
  })

  it('reads latitude and longitude back from UTM within a centimetre', () => {
    const latLon = toLatLon(32632, [777_645.20, 428_166.83])!
    expect(latLon[0]).toBeCloseTo(3.87, 3)
    expect(latLon[1]).toBeCloseTo(11.5, 3)
    expect(toLatLon(3857, [0, 0])).toBeNull()
  })
})

describe('geometry an officer types', () => {
  const square = { type: 'Polygon', coordinates: [[[11.5, 3.87], [11.5003, 3.87], [11.5003, 3.8703], [11.5, 3.8703], [11.5, 3.87]]] }

  it('is described, never changed', () => {
    const facts = inspect(square, 4326)!
    expect(facts).toMatchObject({ type: 'Polygon', polygons: 1, vertices: 4, openRings: 0, unitMismatch: false })
    expect(square.coordinates[0][0]).toEqual([11.5, 3.87])
    expect(inspect({ ...square, coordinates: [[[11.5, 3.87], [11.6, 3.87], [11.6, 3.9]]] }, 4326)!.openRings).toBe(1)
    expect(inspect(square, 32632)!.unitMismatch).toBe(true)
    expect(inspect({ type: 'Point', coordinates: [1, 2] }, 4326)).toBeNull()
    expect(inspect({ type: 'Polygon', coordinates: [[['a', 1]]] }, 4326)).toBeNull()
  })

  it('is previewed in the zone the server displays it in, with an indicative area', () => {
    const preview = previewOf(4326, inspect(square, 4326)!.coordinates)!
    expect(preview.srid).toBe(32632)
    expect(planarArea(preview.coordinates)).toBeGreaterThan(1050)
    expect(planarArea(preview.coordinates)).toBeLessThan(1150)
    expect(previewOf(32633, [[[[1, 2], [3, 4], [1, 2]]]])!.srid).toBe(32633)
    expect(displayZone(14.2)).toBe(32633)
  })
})
