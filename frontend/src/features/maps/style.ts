// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Map styles for the tiled views (ADR-029), written for this project (no third-party style):
// the geographic view reads the OpenMapTiles schema produced by Planetiler, the satellite
// view a raster archive. Every URL is on our own origin; the parcel layers are added on top
// from the authoritative geometry.
import type { StyleSpecification } from 'maplibre-gl'
import type { Background } from './api'

const LABEL_FONT = ['Noto Sans Regular']

/** An absolute pmtiles:// URL on our own origin for the PMTiles protocol. */
export function pmtilesUrl(path: string): string {
  return `pmtiles://${window.location.origin}${path}`
}

export function styleFor(background: Background): StyleSpecification {
  return background.kind === 'SATELLITE' ? satellite(background) : geographic(background)
}

function satellite(b: Background): StyleSpecification {
  // The base mosaic, then each sharper detail over it, only within its bounds and from its
  // lowest zoom; beyond each archive's highest zoom, its tiles are enlarged.
  const details = b.details ?? []
  const sources: StyleSpecification['sources'] = {
    imagery: { type: 'raster', url: pmtilesUrl(b.tilesUrl!), tileSize: 256, attribution: b.attribution ?? undefined },
  }
  details.forEach((d, i) => {
    sources[`detail-${i}`] = { type: 'raster', url: pmtilesUrl(d.tilesUrl), tileSize: 256, attribution: d.attribution }
  })
  return {
    version: 8,
    sources,
    layers: [
      { id: 'paper', type: 'background', paint: { 'background-color': '#e8ebe9' } },
      { id: 'imagery', type: 'raster', source: 'imagery' },
      ...details.map((d, i) => ({ id: `detail-${i}`, type: 'raster' as const, source: `detail-${i}`, minzoom: d.minzoom })),
    ],
  }
}

function geographic(b: Background): StyleSpecification {
  const zoomed = (stops: number[]) => ['interpolate', ['linear'], ['zoom'], ...stops] as unknown as number
  const roadWidth = (w: number, extra = 0) =>
    ['interpolate', ['exponential', 1.6], ['zoom'], 6, w * 0.25 + extra / 4, 18, w * 8 + extra] as unknown as number
  const classes = (list: string[]) => ['in', ['get', 'class'], ['literal', list]] as unknown as boolean
  // A road is drawn twice: its outline below, its fill above, so it reads over buildings.
  const road = (id: string, list: string[], fill: string, casing: string, w: number, minzoom = 5) => [
    {
      id: `${id}-casing`, type: 'line' as const, source: 'base', 'source-layer': 'transportation', minzoom,
      filter: classes(list), layout: { 'line-cap': 'round' as const, 'line-join': 'round' as const },
      paint: { 'line-color': casing, 'line-width': roadWidth(w, 2) },
    },
    {
      id, type: 'line' as const, source: 'base', 'source-layer': 'transportation', minzoom,
      filter: classes(list), layout: { 'line-cap': 'round' as const, 'line-join': 'round' as const },
      paint: { 'line-color': fill, 'line-width': roadWidth(w) },
    },
  ]
  // Names in French where OpenStreetMap has them, otherwise the local name.
  const name = ['coalesce', ['get', 'name:fr'], ['get', 'name']] as unknown as string
  const halo = { 'text-halo-color': '#ffffff', 'text-halo-width': 1.4 }
  return {
    version: 8,
    glyphs: `${window.location.origin}${b.glyphsUrl}`,
    sources: { base: { type: 'vector', url: pmtilesUrl(b.tilesUrl!), attribution: b.attribution ?? undefined } },
    layers: [
      { id: 'land', type: 'background', paint: { 'background-color': '#f4f1ea' } },
      { id: 'landcover', type: 'fill', source: 'base', 'source-layer': 'landcover', paint: { 'fill-color': '#dfe8cf', 'fill-opacity': 0.7 } },
      { id: 'landuse', type: 'fill', source: 'base', 'source-layer': 'landuse', paint: { 'fill-color': '#ece4d8', 'fill-opacity': 0.6 } },
      { id: 'park', type: 'fill', source: 'base', 'source-layer': 'park', paint: { 'fill-color': '#cfe3c0', 'fill-opacity': 0.7 } },
      { id: 'water', type: 'fill', source: 'base', 'source-layer': 'water', paint: { 'fill-color': '#a9cbe5' } },
      { id: 'waterway', type: 'line', source: 'base', 'source-layer': 'waterway', paint: { 'line-color': '#a9cbe5', 'line-width': zoomed([8, 0.6, 16, 2.5]) } },
      {
        id: 'building', type: 'fill', source: 'base', 'source-layer': 'building', minzoom: 13,
        paint: { 'fill-color': '#ddd5cb', 'fill-outline-color': '#b3a595', 'fill-opacity': zoomed([13, 0.4, 15, 1]) },
      },
      ...road('road-path', ['path', 'track'], '#fbfaf7', '#d6cfc4', 0.5, 14),
      ...road('road-minor', ['minor', 'service'], '#ffffff', '#cfc6b8', 0.9, 12),
      ...road('road-secondary', ['secondary', 'tertiary'], '#fff6d5', '#d9c58f', 1.3, 8),
      ...road('road-primary', ['primary', 'trunk'], '#ffe4a0', '#d4a95a', 1.7, 6),
      ...road('road-motorway', ['motorway'], '#f7c66b', '#c48a2c', 2.0, 5),
      {
        id: 'boundary', type: 'line', source: 'base', 'source-layer': 'boundary',
        filter: ['<=', ['get', 'admin_level'], 4] as unknown as boolean,
        paint: { 'line-color': '#8e7f9a', 'line-dasharray': [3, 2], 'line-width': 1 },
      },
      {
        id: 'housenumber', type: 'symbol', source: 'base', 'source-layer': 'housenumber', minzoom: 17,
        layout: { 'text-field': ['get', 'housenumber'] as unknown as string, 'text-font': LABEL_FONT, 'text-size': 10 },
        paint: { 'text-color': '#8a7f73' },
      },
      {
        id: 'water-name', type: 'symbol', source: 'base', 'source-layer': 'water_name', minzoom: 10,
        layout: { 'text-field': name, 'text-font': LABEL_FONT, 'text-size': 11 },
        paint: { 'text-color': '#4f7ea6', ...halo },
      },
      {
        // Street names: what an officer recognises first on the ground.
        id: 'road-name', type: 'symbol', source: 'base', 'source-layer': 'transportation_name', minzoom: 13,
        layout: {
          'text-field': name, 'text-font': LABEL_FONT, 'symbol-placement': 'line', 'text-size': zoomed([13, 10, 18, 13]),
          'text-max-angle': 30,
        },
        paint: { 'text-color': '#4a4540', ...halo },
      },
      {
        // Landmarks by name (no icons): schools, health, worship, public offices, markets, shops.
        id: 'poi', type: 'symbol', source: 'base', 'source-layer': 'poi', minzoom: 15,
        filter: classes(['school', 'college', 'hospital', 'doctors', 'pharmacy', 'place_of_worship', 'town_hall',
          'police', 'post', 'shop', 'grocery', 'bank', 'fuel', 'stadium']),
        layout: { 'text-field': name, 'text-font': LABEL_FONT, 'text-size': 10.5, 'text-max-width': 8, 'text-optional': true },
        paint: { 'text-color': '#6b5f52', ...halo },
      },
      {
        id: 'place-local', type: 'symbol', source: 'base', 'source-layer': 'place', minzoom: 12,
        filter: classes(['suburb', 'quarter', 'neighbourhood', 'hamlet', 'isolated_dwelling']),
        layout: {
          'text-field': name, 'text-font': LABEL_FONT, 'text-size': zoomed([12, 11, 16, 13]),
          'text-transform': 'uppercase', 'text-letter-spacing': 0.08,
        },
        paint: { 'text-color': '#6f665c', ...halo },
      },
      {
        id: 'place', type: 'symbol', source: 'base', 'source-layer': 'place',
        filter: classes(['city', 'town', 'village']),
        layout: {
          'text-field': name, 'text-font': LABEL_FONT,
          // Zoom must be the outermost expression; the class chooses the size at each stop.
          'text-size': ['interpolate', ['linear'], ['zoom'],
            5, ['match', ['get', 'class'], 'city', 12, 'town', 10, 9],
            13, ['match', ['get', 'class'], 'city', 18, 'town', 15, 13]] as unknown as number,
        },
        paint: { 'text-color': '#2f2d29', 'text-halo-color': '#ffffff', 'text-halo-width': 1.6 },
      },
    ],
  }
}
