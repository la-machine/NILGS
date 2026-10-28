// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Which map views have data (/api/v1/map/backgrounds, ADR-029). Parcel geometry never comes
// from here: it comes from the parcel API.
import { call } from '../../shared/api/client'

export type BackgroundKind = 'TECHNICAL' | 'GEOGRAPHIC' | 'SATELLITE'

/** The three views, in the order they are offered. */
export const KINDS: BackgroundKind[] = ['TECHNICAL', 'GEOGRAPHIC', 'SATELLITE']

export interface Background {
  kind: BackgroundKind
  available: boolean
  /** NOT_INSTALLED, MANIFEST_INVALID, FILE_MISSING, or UNREACHABLE (the list could not be read). */
  unavailableReason: string | null
  name: string | null
  tileType: 'vector' | 'raster' | null
  tilesUrl: string | null
  glyphsUrl: string | null
  attribution: string | null
  license: string | null
  source: string | null
  date: string | null
  bounds: [number, number, number, number] | null
  minzoom: number | null
  maxzoom: number | null
  /** Generated test data: shown as such, never as real imagery. */
  synthetic: boolean
  /** Sharper archives drawn over the base within their bounds and zooms (aerial images over a mosaic). */
  details?: BackgroundDetail[]
}

export interface BackgroundDetail {
  name: string
  tilesUrl: string
  attribution: string
  license: string
  source: string
  date: string
  bounds: [number, number, number, number]
  minzoom: number
  maxzoom: number
}

/** A GeoJSON MultiPolygon's coordinates: polygons, rings, positions [x, y]. */
export type MultiPolygon = number[][][][]

export interface GeoJsonMultiPolygon {
  type: 'MultiPolygon'
  coordinates: MultiPolygon
}

/** A geometry for display in projected metres (the parcel's UTM zone, transformed by PostGIS). */
export interface ProjectedGeometry {
  srid: number
  geometry: GeoJsonMultiPolygon
}

export const mapsApi = {
  backgrounds: () => call<Background[]>('GET', '/map/backgrounds'),
}
