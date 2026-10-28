// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A still drawing of geometry being entered, before it is sent: outline, vertices and a scale
// bar, in metres. Display only: the browser never sends this projection, and the server's
// validation and area are the authoritative ones.
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import type { MultiPolygon } from './api'
import { extent, formatLength, niceStep, zoneLabel } from './geometry'

const W = 360
const H = 240

export function GeometryPreview({ texts, srid, coordinates }: { texts: Texts; srid: number; coordinates: MultiPolygon }) {
  const box = extent(coordinates)
  const w = Math.max(box.maxX - box.minX, 1)
  const h = Math.max(box.maxY - box.minY, 1)
  const k = 0.8 * Math.min(W / w, H / h)
  const cx = (box.minX + box.maxX) / 2
  const cy = (box.minY + box.maxY) / 2
  const screen = ([x, y]: number[]) => [(x - cx) * k + W / 2, H / 2 - (y - cy) * k]
  const path = coordinates.flatMap((polygon) => polygon)
    .map((ring) => ring.map((p, i) => `${i === 0 ? 'M' : 'L'}${screen(p).join(',')}`).join('') + 'Z').join('')
  const scale = niceStep(W / k, 4)
  return (
    <figure className="geometry-preview">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={fill(texts.parcel.previewLabel, { zone: zoneLabel(srid) })}>
        <rect width={W} height={H} className="map-paper" />
        <path d={path} className="parcel-boundary" fillRule="evenodd" />
        {coordinates.flatMap((polygon) => polygon.flatMap((ring) => ring.slice(0, -1))).map((p, i) => {
          const [x, y] = screen(p)
          return <circle key={i} cx={x} cy={y} r={3} className="parcel-vertex" />
        })}
        <g className="scale-bar" transform={`translate(16, ${H - 16})`}>
          <rect x={0} y={-3} width={(scale * k) / 2} height={6} className="scale-dark" />
          <rect x={(scale * k) / 2} y={-3} width={(scale * k) / 2} height={6} className="scale-light" />
          <text x={0} y={-8}>{formatLength(scale)}</text>
        </g>
      </svg>
      <figcaption>{fill(texts.parcel.previewCaption, { zone: zoneLabel(srid) })}</figcaption>
    </figure>
  )
}
