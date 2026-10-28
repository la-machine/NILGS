// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the maps feature: the three-view parcel map (ADR-029), the background
// catalogue (for the dashboard's status), and the display-only geometry helpers the parcel
// forms use for their preview and the alert review map draws with.
export { KINDS, mapsApi } from './api'
export type { Background, BackgroundKind, GeoJsonMultiPolygon, ProjectedGeometry } from './api'
export { extent, formatArea, formatLength, inspect, niceStep, planarArea, previewOf, zoneLabel } from './geometry'
export type { Extent, GeometryFacts } from './geometry'
export { MapView } from './MapView'
export { GeometryPreview } from './GeometryPreview'
