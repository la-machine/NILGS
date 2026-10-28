// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The review map of an alert: both compared parcel versions drawn over each other in one UTM
// zone (metres, transformed by the server), the intersection hatched on top, a scale bar and a
// legend naming each parcel. A sliver can be a few square metres beside parcels of thousands,
// so the view can zoom to the intersection. Display only: the evidence is the server's.
import { useState } from 'react'
import { extent, formatLength, niceStep, zoneLabel } from '../maps'
import type { Extent, GeoJsonMultiPolygon } from '../maps'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import type { AlertDetail } from './api'

const W = 800
const H = 420

function union(a: Extent, b: Extent): Extent {
  return { minX: Math.min(a.minX, b.minX), minY: Math.min(a.minY, b.minY), maxX: Math.max(a.maxX, b.maxX), maxY: Math.max(a.maxY, b.maxY) }
}

export function AlertMap({ texts, alert }: { texts: Texts; alert: AlertDetail & { display: NonNullable<AlertDetail['display']> } }) {
  const c = texts.conflicts
  const { srid, a, b, intersection } = alert.display
  const [focus, setFocus] = useState<'pair' | 'intersection'>('pair')
  const box = focus === 'intersection' && intersection
    ? extent(intersection.coordinates)
    : union(extent(a.coordinates), extent(b.coordinates))
  const w = Math.max(box.maxX - box.minX, 1)
  const h = Math.max(box.maxY - box.minY, 1)
  const k = 0.82 * Math.min(W / w, H / h)
  const cx = (box.minX + box.maxX) / 2
  const cy = (box.minY + box.maxY) / 2
  const screen = ([x, y]: number[]) => `${((x - cx) * k + W / 2).toFixed(1)},${(H / 2 - (y - cy) * k).toFixed(1)}`
  const path = (g: GeoJsonMultiPolygon) => g.coordinates.flatMap((polygon) => polygon)
    .map((ring) => ring.map((p, i) => `${i === 0 ? 'M' : 'L'}${screen(p)}`).join('') + 'Z').join('')
  const scale = niceStep(W / k, 4)

  return (
    <figure className="alert-map">
      <div className="map-stage">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" className="alert-canvas"
          aria-label={fill(c.mapLabel, { a: alert.a.reference, b: alert.b.reference, zone: zoneLabel(srid) })}>
          <defs>
            <pattern id="overlap-hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width="8" height="8" className="overlap-hatch-bg" />
              <line x1="0" y1="0" x2="0" y2="8" className="overlap-hatch-line" />
            </pattern>
          </defs>
          <rect width={W} height={H} className="map-paper" />
          <path d={path(a)} className="alert-parcel-a" fillRule="evenodd" />
          <path d={path(b)} className="alert-parcel-b" fillRule="evenodd" />
          {intersection && <path d={path(intersection)} className="alert-overlap" fillRule="evenodd" />}
          <g className="scale-bar" transform={`translate(16, ${H - 16})`}>
            <rect x={0} y={-3} width={(scale * k) / 2} height={6} className="scale-dark" />
            <rect x={(scale * k) / 2} y={-3} width={(scale * k) / 2} height={6} className="scale-light" />
            <text x={0} y={-8}>{formatLength(scale)}</text>
          </g>
        </svg>
        {intersection && (
          <div className="map-tools" role="group" aria-label={c.mapTools}>
            <Button size="sm" variant={focus === 'pair' ? 'primary' : 'ghost'} aria-pressed={focus === 'pair'}
              onClick={() => setFocus('pair')}>{c.showPair}</Button>
            <Button size="sm" variant={focus === 'intersection' ? 'primary' : 'ghost'} aria-pressed={focus === 'intersection'}
              onClick={() => setFocus('intersection')}>{c.showIntersection}</Button>
          </div>
        )}
      </div>
      <figcaption>
        <ul className="map-legend">
          <li><span className="legend-swatch alert-a" aria-hidden="true" />{fill(c.legendParcel, { reference: alert.a.reference, version: alert.a.versionNo })}</li>
          <li><span className="legend-swatch alert-b" aria-hidden="true" />{fill(c.legendParcel, { reference: alert.b.reference, version: alert.b.versionNo })}</li>
          {intersection && <li><span className="legend-swatch overlap" aria-hidden="true" />{c.legendIntersection}</li>}
          <li>{fill(c.mapZone, { zone: zoneLabel(srid) })}</li>
        </ul>
      </figcaption>
    </figure>
  )
}
