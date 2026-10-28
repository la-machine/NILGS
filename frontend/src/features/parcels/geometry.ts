// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Reading the geometry an officer supplies. A Feature or a one-feature collection is reduced to
// its geometry; nothing else is checked here: PostGIS validates on the server.
import { inspect } from '../maps'
import type { GeometryFacts } from '../maps'
import type { Srid } from './api'

/** The geometry object to send, or an error code when the text is not JSON. */
export function geometryOf(text: string): { geometry?: unknown; error?: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { error: 'GEOMETRY_MALFORMED' }
  }
  const value = parsed as { type?: string; geometry?: unknown; features?: { geometry?: unknown }[] }
  if (value?.type === 'Feature') return { geometry: value.geometry }
  if (value?.type === 'FeatureCollection') {
    return value.features?.length === 1 ? { geometry: value.features[0].geometry } : { error: 'GEOMETRY_ONE_FEATURE' }
  }
  return { geometry: parsed }
}

export interface GeometryValue { srid: Srid; text: string }

/** What the typed text currently is: nothing yet, unreadable, or a geometry with its facts. */
export type GeometryReading =
  | { state: 'empty' }
  | { state: 'error'; code: string }
  | { state: 'unsupported' }
  | { state: 'ok'; geometry: unknown; facts: GeometryFacts }

export function readGeometry(value: GeometryValue): GeometryReading {
  if (!value.text.trim()) return { state: 'empty' }
  const parsed = geometryOf(value.text)
  if (parsed.error) return { state: 'error', code: parsed.error }
  const facts = inspect(parsed.geometry, value.srid)
  return facts ? { state: 'ok', geometry: parsed.geometry, facts } : { state: 'unsupported' }
}
