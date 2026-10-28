// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The geographic and satellite views (ADR-029): MapLibre over our own PMTiles archive, the
// parcel drawn on top from the authoritative geometry (EPSG:4326, as stored). Loaded only when
// such a view is chosen, so the technical view never needs this code or WebGL. The worker is
// a file of our own origin, so the page keeps its strict Content Security Policy.
import { useEffect, useRef, useState } from 'react'
import { addProtocol, Map as MapLibreMap, NavigationControl, ScaleControl, setWorkerUrl } from 'maplibre-gl'
import type { ErrorEvent } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import { Protocol } from 'pmtiles'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Icon } from '../../shared/ui/Icon'
import type { Background, GeoJsonMultiPolygon } from './api'
import { extent } from './geometry'
import { styleFor } from './style'

let protocolInstalled = false

function install() {
  if (protocolInstalled) return
  setWorkerUrl(workerUrl)
  addProtocol('pmtiles', new Protocol().tile)
  protocolInstalled = true
}

export default function TileView({ texts, background, geometry, onTechnical }: {
  texts: Texts; background: Background; geometry: GeoJsonMultiPolygon; onTechnical: () => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    if (!container.current) return
    let map: MapLibreMap | null = null
    try {
      install()
      const box = extent(geometry.coordinates)
      map = new MapLibreMap({
        container: container.current,
        style: styleFor(background),
        bounds: [[box.minX, box.minY], [box.maxX, box.maxY]],
        // The first view fits the parcel; beyond the archive's highest zoom the tiles are enlarged.
        fitBoundsOptions: { padding: 80, maxZoom: 19 },
        attributionControl: { compact: false },
        maxZoom: 21,
      })
      map.addControl(new NavigationControl({ showCompass: false }), 'top-right')
      map.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-left')
      map.on('load', () => {
        map!.addSource('parcel', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry } })
        // The same colour as the technical view, with a light casing so the boundary reads on dark imagery.
        map!.addLayer({ id: 'parcel-fill', type: 'fill', source: 'parcel', paint: { 'fill-color': '#c2410c', 'fill-opacity': 0.14 } })
        map!.addLayer({ id: 'parcel-casing', type: 'line', source: 'parcel', paint: { 'line-color': '#ffffff', 'line-width': 5, 'line-opacity': 0.85 } })
        map!.addLayer({ id: 'parcel-line', type: 'line', source: 'parcel', paint: { 'line-color': '#c2410c', 'line-width': 2.5 } })
        container.current?.setAttribute('data-loaded', 'true')
      })
      map.on('error', (e: ErrorEvent) => setFailure(e.error?.message ?? 'error'))
    } catch (e) {
      // MapLibre refused to start (no WebGL, for instance): an external failure, reported once.
      // oxlint-disable-next-line react/set-state-in-effect
      setFailure(e instanceof Error ? e.message : 'error')
    }
    return () => map?.remove()
  }, [background, geometry])

  return (
    <div className="tile-view">
      <div className="map-canvas-wrap">
        <div ref={container} className="tile-canvas" data-testid={`tile-view-${background.kind.toLowerCase()}`} />
        {background.synthetic && (
          <p className="map-banner warning" role="note"><Icon name="alert" size={16} />{texts.syntheticData}</p>
        )}
        {failure && (
          <div className="map-placeholder overlay" role="alert">
            <span className="map-placeholder-icon"><Icon name="alert" size={28} /></span>
            <p className="map-placeholder-title">{texts.mapFailed}</p>
            <Button variant="primary" icon="technical" onClick={onTechnical}>{texts.map.useTechnical}</Button>
          </div>
        )}
      </div>
      <div className="map-footer">
        <p className="hint">
          <strong>{background.name}</strong> · {background.license} · {background.date}
        </p>
        {(background.details ?? []).map((d) => (
          <p key={d.tilesUrl} className="hint">
            {fill(texts.map.detailLine, { name: d.name, license: d.license, date: d.date })}
          </p>
        ))}
        <p className="hint">{texts.map.sameGeometry}</p>
      </div>
    </div>
  )
}
