// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The map workspace of a parcel (ADR-029): three views of the same authoritative geometry.
// Technical (SVG in UTM metres, offline), geographic and satellite (MapLibre over our own
// archives, loaded only when chosen). Every view can be chosen; one without data says why and
// offers the technical view, which never depends on map data or a connection. Switching only
// changes what is drawn behind the boundary: parcel data is fetched once by the page and
// never changed here. The chosen view can be held by the page (in the address) or here.
import { lazy, Suspense, useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Badge, Spinner } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import type { IconName } from '../../shared/ui/Icon'
import { KINDS, mapsApi } from './api'
import type { Background, BackgroundKind, GeoJsonMultiPolygon, ProjectedGeometry } from './api'
import { TechnicalView } from './TechnicalView'
import { useOnline } from './useOnline'

const TileView = lazy(() => import('./TileView'))

const ICONS: Record<BackgroundKind, IconName> = { TECHNICAL: 'technical', GEOGRAPHIC: 'map', SATELLITE: 'satellite' }

function unreachable(kind: BackgroundKind): Background {
  return {
    kind, available: kind === 'TECHNICAL', unavailableReason: kind === 'TECHNICAL' ? null : 'UNREACHABLE', name: null,
    tileType: null, tilesUrl: null, glyphsUrl: null, attribution: null, license: null, source: null, date: null,
    bounds: null, minzoom: null, maxzoom: null, synthetic: false,
  }
}

export function MapView({ texts, geometry, display, areaM2, view, onViewChange }: {
  texts: Texts; geometry: GeoJsonMultiPolygon; display: ProjectedGeometry; areaM2: number
  view?: BackgroundKind; onViewChange?: (view: BackgroundKind) => void
}) {
  const [backgrounds, setBackgrounds] = useState<Background[] | null>(null)
  const [ownView, setOwnView] = useState<BackgroundKind>('TECHNICAL')
  const online = useOnline()
  const current = view ?? ownView
  const choose = (kind: BackgroundKind) => (onViewChange ? onViewChange(kind) : setOwnView(kind))

  useEffect(() => {
    // The list only enables views; failing to read it leaves the technical view working.
    mapsApi.backgrounds().then(setBackgrounds).catch(() => setBackgrounds(KINDS.map((k) => unreachable(k))))
  }, [])

  const t = texts.map
  const label: Record<BackgroundKind, string> = {
    TECHNICAL: texts.viewTechnical, GEOGRAPHIC: texts.viewGeographic, SATELLITE: texts.viewSatellite,
  }
  const list = backgrounds ?? KINDS.map((k) => ({ ...unreachable(k), unavailableReason: k === 'TECHNICAL' ? null : 'LOADING' }))
  const chosen = list.find((b) => b.kind === current) ?? list[0]
  const reasons: Record<string, string> = texts.mapUnavailable

  const status = (b: Background): { icon: IconName; text: string } => {
    if (b.kind === 'TECHNICAL') return { icon: 'offline', text: t.technicalStatus }
    if (b.unavailableReason === 'LOADING') return { icon: 'clock', text: t.checking }
    if (!b.available) return { icon: 'alert', text: fill(t.unavailableStatus, { reason: reasons[b.unavailableReason ?? 'UNREACHABLE'] ?? reasons.UNREACHABLE }) }
    return { icon: 'server', text: b.kind === 'GEOGRAPHIC' ? t.geographicStatus : t.satelliteStatus }
  }
  const chosenStatus = status(chosen)

  return (
    <div className="map-workspace">
      <div className="map-header">
        <fieldset className="segmented">
          <legend className="visually-hidden">{texts.mapViews}</legend>
          {list.map((b) => (
            <label key={b.kind} className={['segment', current === b.kind ? 'selected' : '', b.available ? '' : 'unavailable'].join(' ')}>
              <input type="radio" name="map-view" value={b.kind} checked={current === b.kind} onChange={() => choose(b.kind)}
                className="segment-input" />
              <Icon name={ICONS[b.kind]} size={18} />
              <span>{label[b.kind]}</span>
              {b.synthetic && <span className="segment-tag">{t.testTag}</span>}
              {!b.available && b.unavailableReason !== 'LOADING' && (
                <span className="segment-tag muted">
                  <span className="visually-hidden">— </span>{t.unavailableTag}
                  <span className="visually-hidden">: {reasons[b.unavailableReason ?? 'UNREACHABLE'] ?? reasons.UNREACHABLE}</span>
                </span>
              )}
            </label>
          ))}
        </fieldset>
        <p className="map-status" aria-live="polite">
          <Icon name={chosenStatus.icon} size={16} />
          <span>{chosenStatus.text}</span>
          {chosen.synthetic && <Badge tone="warning" icon="alert">{texts.synthetic}</Badge>}
        </p>
      </div>

      <div className="map-stage">
        {current === 'TECHNICAL' && <TechnicalView texts={texts} display={display} areaM2={areaM2} />}
        {current !== 'TECHNICAL' && chosen.unavailableReason === 'LOADING' && (
          <div className="map-placeholder"><Spinner label={t.checking} /></div>
        )}
        {current !== 'TECHNICAL' && !chosen.available && chosen.unavailableReason !== 'LOADING' && (
          <Unavailable texts={texts} kind={current} reason={chosen.unavailableReason} onTechnical={() => choose('TECHNICAL')} />
        )}
        {current !== 'TECHNICAL' && chosen.available && !online && (
          <div className="map-placeholder" role="status">
            <span className="map-placeholder-icon"><Icon name="offline" size={28} /></span>
            <p className="map-placeholder-title">{t.offlineTitle}</p>
            <p>{t.offlineText}</p>
            <Button variant="primary" icon="technical" onClick={() => choose('TECHNICAL')}>{t.useTechnical}</Button>
          </div>
        )}
        {current !== 'TECHNICAL' && chosen.available && online && (
          <Suspense fallback={<div className="map-placeholder"><Spinner label={texts.loadingMap} /></div>}>
            <TileView key={chosen.kind} texts={texts} background={chosen} geometry={geometry}
              onTechnical={() => choose('TECHNICAL')} />
          </Suspense>
        )}
      </div>
    </div>
  )
}

/** A view the server cannot offer: what it is, why not, what would enable it, and the way on. */
function Unavailable({ texts, kind, reason, onTechnical }: {
  texts: Texts; kind: BackgroundKind; reason: string | null; onTechnical: () => void
}) {
  const t = texts.map
  const reasons: Record<string, string> = texts.mapUnavailable
  return (
    <div className="map-placeholder" role="status" data-testid={`unavailable-${kind.toLowerCase()}`}>
      <span className="map-placeholder-icon"><Icon name={ICONS[kind]} size={28} /></span>
      <p className="map-placeholder-title">{kind === 'GEOGRAPHIC' ? t.geographicUnavailableTitle : t.satelliteUnavailableTitle}</p>
      <p>{reasons[reason ?? 'UNREACHABLE'] ?? reasons.UNREACHABLE}.</p>
      <p className="muted">{kind === 'GEOGRAPHIC' ? t.geographicEnable : t.satelliteEnable}</p>
      <Button variant="primary" icon="technical" onClick={onTechnical}>{t.useTechnical}</Button>
    </div>
  )
}
