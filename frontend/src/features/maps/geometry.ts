// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Pure helpers of the technical view, in projected metres (UTM): extents, grid steps,
// measurements and their display. Display only: the authoritative area is the server's,
// computed by PostGIS on the WGS84 spheroid.
import proj4 from 'proj4'
import type { MultiPolygon } from './api'

export interface Extent { minX: number; minY: number; maxX: number; maxY: number }
export type Point = [number, number]

export function extent(geometry: MultiPolygon): Extent {
  const e = { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity }
  for (const polygon of geometry) {
    for (const ring of polygon) {
      for (const [x, y] of ring) {
        e.minX = Math.min(e.minX, x)
        e.minY = Math.min(e.minY, y)
        e.maxX = Math.max(e.maxX, x)
        e.maxY = Math.max(e.maxY, y)
      }
    }
  }
  return e
}

/** A round step (1, 2 or 5 × 10^n) giving about `target` intervals over `span`. */
export function niceStep(span: number, target = 6): number {
  if (!(span > 0)) return 1
  const raw = span / target
  const power = 10 ** Math.floor(Math.log10(raw))
  const unit = raw / power
  const nice = unit < 1.5 ? 1 : unit < 3.5 ? 2 : unit < 7.5 ? 5 : 10
  return nice * power
}

/** The multiples of `step` inside [from, to]. */
export function gridLines(from: number, to: number, step: number): number[] {
  const lines: number[] = []
  for (let v = Math.ceil(from / step) * step; v <= to; v += step) {
    lines.push(Number(v.toFixed(6)))
  }
  return lines
}

export function distance(a: Point, b: Point): number {
  return Math.hypot(b[0] - a[0], b[1] - a[1])
}

export function pathLength(points: Point[]): number {
  let total = 0
  for (let i = 1; i < points.length; i++) total += distance(points[i - 1], points[i])
  return total
}

/** Planar area of a closed polygon (shoelace), square metres. */
export function polygonArea(points: Point[]): number {
  let sum = 0
  for (let i = 0; i < points.length; i++) {
    const [x1, y1] = points[i]
    const [x2, y2] = points[(i + 1) % points.length]
    sum += x1 * y2 - x2 * y1
  }
  return Math.abs(sum) / 2
}

export function formatLength(metres: number): string {
  return metres >= 1000 ? `${(metres / 1000).toFixed(3)} km` : `${metres.toFixed(2)} m`
}

export function formatArea(squareMetres: number): string {
  return squareMetres >= 10_000
    ? `${(squareMetres / 10_000).toFixed(4)} ha (${squareMetres.toFixed(0)} m²)`
    : `${squareMetres.toFixed(2)} m²`
}

/** The UTM zones of the accepted input CRS (ADR-029); only these are drawn. */
const UTM: Record<number, string> = {
  32632: '+proj=utm +zone=32 +datum=WGS84 +units=m +no_defs',
  32633: '+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs',
}

export function zoneLabel(srid: number): string {
  return srid === 32632 ? 'UTM 32N (EPSG:32632)' : srid === 32633 ? 'UTM 33N (EPSG:32633)' : `EPSG:${srid}`
}

/** Latitude and longitude of a point under the cursor, for the readout only. */
export function toLatLon(srid: number, point: Point): Point | null {
  const definition = UTM[srid]
  if (!definition) return null
  const [lon, lat] = proj4(definition, 'EPSG:4326', point)
  return [lat, lon]
}

/** The UTM zone the server displays a parcel in: 32N west of 12°E, 33N east of it. */
export function displayZone(longitude: number): 32632 | 32633 {
  return longitude < 12 ? 32632 : 32633
}

/**
 * A preview of geometry an officer typed, in metres, for drawing only. Latitude/longitude
 * is projected into the zone the server would use; UTM input is drawn as given. What is sent
 * to the server is the text as typed, never this.
 */
export function previewOf(srid: number, coordinates: MultiPolygon): { srid: number; coordinates: MultiPolygon } | null {
  if (srid === 32632 || srid === 32633) return { srid, coordinates }
  if (srid !== 4326) return null
  const box = extent(coordinates)
  if (!Number.isFinite(box.minX)) return null
  const zone = displayZone((box.minX + box.maxX) / 2)
  const projected = coordinates.map((polygon) => polygon.map((ring) =>
    ring.map(([lon, lat]) => proj4('EPSG:4326', UTM[zone], [lon, lat]) as [number, number])))
  return { srid: zone, coordinates: projected }
}

/** What an officer's geometry looks like before the server judges it: counts and hints only. */
export interface GeometryFacts {
  type: 'Polygon' | 'MultiPolygon'
  coordinates: MultiPolygon
  polygons: number
  vertices: number
  /** Rings whose last position differs from the first. */
  openRings: number
  /** Coordinates that do not look like the chosen system (metres given as degrees, or the reverse). */
  unitMismatch: boolean
}

function isPosition(value: unknown): value is number[] {
  return Array.isArray(value) && value.length >= 2 && value.every((n) => typeof n === 'number' && Number.isFinite(n))
}

/** Facts about a parsed GeoJSON geometry, or null when it is not a (Multi)Polygon of numbers. */
export function inspect(geometry: unknown, srid: number): GeometryFacts | null {
  const g = geometry as { type?: unknown; coordinates?: unknown }
  if (!g || (g.type !== 'Polygon' && g.type !== 'MultiPolygon') || !Array.isArray(g.coordinates)) return null
  const polygons = (g.type === 'Polygon' ? [g.coordinates] : g.coordinates) as unknown[]
  const coordinates: MultiPolygon = []
  let vertices = 0
  let openRings = 0
  for (const polygon of polygons) {
    if (!Array.isArray(polygon) || polygon.length === 0) return null
    const rings: number[][][] = []
    for (const ring of polygon) {
      if (!Array.isArray(ring) || !ring.every(isPosition) || ring.length < 2) return null
      const positions = ring.map((p) => [p[0], p[1]])
      const [first, last] = [positions[0], positions[positions.length - 1]]
      if (first[0] !== last[0] || first[1] !== last[1]) openRings++
      vertices += Math.max(positions.length - 1, 0)
      rings.push(positions)
    }
    coordinates.push(rings)
  }
  const box = extent(coordinates)
  const degreesLike = Math.abs(box.minX) <= 180 && Math.abs(box.maxX) <= 180 && Math.abs(box.minY) <= 90 && Math.abs(box.maxY) <= 90
  const unitMismatch = srid === 4326 ? !degreesLike : degreesLike
  return { type: g.type, coordinates, polygons: coordinates.length, vertices, openRings, unitMismatch }
}

/** Planar area of a multipolygon in metres: outer rings minus holes. Indicative only. */
export function planarArea(coordinates: MultiPolygon): number {
  const open = (ring: number[][]) => {
    const [first, last] = [ring[0], ring[ring.length - 1]]
    return (first[0] === last[0] && first[1] === last[1] ? ring.slice(0, -1) : ring) as Point[]
  }
  return coordinates.reduce((total, polygon) => total + polygon.reduce((sum, ring, i) =>
    sum + (i === 0 ? 1 : -1) * polygonArea(open(ring)), 0), 0)
}
