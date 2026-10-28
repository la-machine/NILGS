// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The technical view (ADR-029): the parcel drawn in SVG in its UTM zone, in metres, with a
// metric grid that adapts to the zoom, a scale bar, zoom and pan, the coordinates under the
// cursor, and distance and area measurement. Needs no map data, no network and no WebGL.
// The coordinates are the server's (PostGIS transformation); nothing here changes them.
import { useMemo, useRef, useState } from 'react'
import type { PointerEvent, WheelEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Icon } from '../../shared/ui/Icon'
import type { ProjectedGeometry } from './api'
import {
  extent, formatArea, formatLength, gridLines, niceStep, pathLength, polygonArea, toLatLon, zoneLabel,
} from './geometry'
import type { Point } from './geometry'

const WIDTH = 800
const HEIGHT = 440

interface Camera { cx: number; cy: number; k: number }

export function TechnicalView({ texts, display, areaM2 }: {
  texts: Texts; display: ProjectedGeometry; areaM2: number
}) {
  const geometry = display.geometry.coordinates
  const box = useMemo(() => extent(geometry), [geometry])
  const fit = useMemo<Camera>(() => {
    const w = Math.max(box.maxX - box.minX, 1)
    const h = Math.max(box.maxY - box.minY, 1)
    return { cx: (box.minX + box.maxX) / 2, cy: (box.minY + box.maxY) / 2, k: 0.75 * Math.min(WIDTH / w, HEIGHT / h) }
  }, [box])
  const [camera, setCamera] = useState<Camera>(fit)
  const [cursor, setCursor] = useState<Point | null>(null)
  const [measuring, setMeasuring] = useState(false)
  const [points, setPoints] = useState<Point[]>([])
  const drag = useRef<{ x: number; y: number; camera: Camera; moved: boolean } | null>(null)

  const toScreen = ([x, y]: Point): Point => [(x - camera.cx) * camera.k + WIDTH / 2, HEIGHT / 2 - (y - camera.cy) * camera.k]
  const toWorld = (sx: number, sy: number): Point => [camera.cx + (sx - WIDTH / 2) / camera.k, camera.cy - (sy - HEIGHT / 2) / camera.k]

  const local = (e: { clientX: number; clientY: number; currentTarget: Element }): Point => {
    const r = e.currentTarget.getBoundingClientRect()
    const sx = r.width ? ((e.clientX - r.left) / r.width) * WIDTH : WIDTH / 2
    const sy = r.height ? ((e.clientY - r.top) / r.height) * HEIGHT : HEIGHT / 2
    return [sx, sy]
  }

  const zoom = (factor: number, around?: Point) => {
    setCamera((c) => {
      const [ax, ay] = around ?? [WIDTH / 2, HEIGHT / 2]
      const wx = c.cx + (ax - WIDTH / 2) / c.k
      const wy = c.cy - (ay - HEIGHT / 2) / c.k
      const k = Math.min(Math.max(c.k * factor, fit.k / 64), fit.k * 256)
      return { k, cx: wx - (ax - WIDTH / 2) / k, cy: wy + (ay - HEIGHT / 2) / k }
    })
  }

  const onWheel = (e: WheelEvent<SVGSVGElement>) => {
    e.preventDefault()
    zoom(e.deltaY < 0 ? 1.25 : 0.8, local(e))
  }
  const onPointerDown = (e: PointerEvent<SVGSVGElement>) => {
    const [x, y] = local(e)
    drag.current = { x, y, camera, moved: false }
  }
  const onPointerMove = (e: PointerEvent<SVGSVGElement>) => {
    const [sx, sy] = local(e)
    setCursor(toWorld(sx, sy))
    const d = drag.current
    if (d && (Math.abs(sx - d.x) > 3 || Math.abs(sy - d.y) > 3)) {
      d.moved = true
      setCamera({ ...d.camera, cx: d.camera.cx - (sx - d.x) / d.camera.k, cy: d.camera.cy + (sy - d.y) / d.camera.k })
    }
  }
  const onPointerUp = (e: PointerEvent<SVGSVGElement>) => {
    const d = drag.current
    drag.current = null
    if (measuring && d && !d.moved) {
      const [sx, sy] = local(e)
      setPoints((p) => [...p, toWorld(sx, sy)])
    }
  }

  // Grid and scale follow the visible width.
  const left = camera.cx - WIDTH / 2 / camera.k
  const right = camera.cx + WIDTH / 2 / camera.k
  const bottom = camera.cy - HEIGHT / 2 / camera.k
  const top = camera.cy + HEIGHT / 2 / camera.k
  const step = niceStep(right - left)
  const scale = niceStep(right - left, 5)

  const rings = geometry.flatMap((polygon) => polygon)
  const path = rings.map((ring) => ring.map((p, i) => `${i === 0 ? 'M' : 'L'}${toScreen(p as Point).join(',')}`).join('') + 'Z').join('')
  const vertices = geometry.flatMap((polygon) => polygon.flatMap((ring) => ring.slice(0, -1))) as Point[]
  const latLon = cursor ? toLatLon(display.srid, cursor) : null

  return (
    <div className="technical-view">
      <div className="map-canvas-wrap">
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className={measuring ? 'technical-canvas measuring' : 'technical-canvas'} role="img"
          aria-label={fill(texts.technicalMapLabel, { zone: zoneLabel(display.srid) })}
          onWheel={onWheel} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp}
          onPointerLeave={() => { setCursor(null); drag.current = null }}>
          <rect x={0} y={0} width={WIDTH} height={HEIGHT} className="map-paper" />
          <g className="grid">
            {gridLines(left, right, step).map((x) => {
              const [sx] = toScreen([x, 0])
              return <g key={`x${x}`}><line x1={sx} y1={0} x2={sx} y2={HEIGHT} /><text x={sx + 3} y={HEIGHT - 4}>{x.toFixed(0)}</text></g>
            })}
            {gridLines(bottom, top, step).map((y) => {
              const [, sy] = toScreen([0, y])
              return <g key={`y${y}`}><line x1={0} y1={sy} x2={WIDTH} y2={sy} /><text x={3} y={sy - 3}>{y.toFixed(0)}</text></g>
            })}
          </g>
          <path d={path} className="parcel-boundary" fillRule="evenodd" data-testid="parcel-boundary" />
          {vertices.map((v, i) => {
            const [sx, sy] = toScreen(v)
            return <circle key={i} cx={sx} cy={sy} r={3.5} className="parcel-vertex" />
          })}
          {points.length > 0 && (
            <g className="measure">
              <polyline points={points.map((p) => toScreen(p).join(',')).join(' ')} />
              {points.map((p, i) => <circle key={i} cx={toScreen(p)[0]} cy={toScreen(p)[1]} r={3.5} />)}
            </g>
          )}
          <g className="north-arrow" transform={`translate(${WIDTH - 28}, 30)`} aria-hidden="true">
            <path d="M0 -14 L6 6 L0 2 L-6 6 Z" />
            <text x={0} y={20}>N</text>
          </g>
          <g className="scale-bar" transform={`translate(${WIDTH - scale * camera.k - 24}, ${HEIGHT - 24})`}>
            <rect x={0} y={-3} width={scale * camera.k / 2} height={6} className="scale-dark" />
            <rect x={scale * camera.k / 2} y={-3} width={scale * camera.k / 2} height={6} className="scale-light" />
            <text x={0} y={-8}>{formatLength(scale)}</text>
          </g>
        </svg>
        <div className="map-tools" role="toolbar" aria-label={texts.mapTools}>
          <Button size="sm" iconOnly icon="plus" onClick={() => zoom(1.5)} title={texts.zoomIn}>{texts.zoomIn}</Button>
          <Button size="sm" iconOnly icon="minus" onClick={() => zoom(1 / 1.5)} title={texts.zoomOut}>{texts.zoomOut}</Button>
          <Button size="sm" iconOnly icon="fit" onClick={() => setCamera(fit)} title={texts.fitParcel}>{texts.fitParcel}</Button>
          <span className="map-tools-separator" aria-hidden="true" />
          <Button size="sm" icon="ruler" variant={measuring ? 'primary' : 'secondary'} aria-pressed={measuring}
            onClick={() => { setMeasuring(!measuring); setPoints([]) }}>
            {measuring ? texts.stopMeasuring : texts.measure}
          </Button>
          {points.length > 0 && (
            <Button size="sm" variant="ghost" onClick={() => setPoints([])}>{texts.clearMeasure}</Button>
          )}
        </div>
        {measuring && points.length === 0 && <p className="map-hint-overlay">{texts.map.measureHint}</p>}
      </div>
      <div className="map-readout" aria-live="polite">
        <span className="readout-zone"><Icon name="crosshair" size={14} />{zoneLabel(display.srid)}</span>
        <span>{fill(texts.gridStep, { step: formatLength(step) })}</span>
        {cursor
          ? <span className="num">E {cursor[0].toFixed(2)} m · N {cursor[1].toFixed(2)} m</span>
          : <span className="muted">{texts.map.pointHint}</span>}
        {latLon && <span className="num">{latLon[0].toFixed(7)}°, {latLon[1].toFixed(7)}°</span>}
      </div>
      {points.length > 1 && (
        <p className="map-measurement" data-testid="measurement">
          <Icon name="ruler" size={16} />
          {fill(texts.measuredLength, { length: formatLength(pathLength(points)) })}
          {points.length > 2 && <> · {fill(texts.measuredArea, { area: formatArea(polygonArea(points)) })}</>}
        </p>
      )}
      <div className="map-footer">
        <ul className="map-legend" aria-label={texts.map.legend}>
          <li><span className="legend-swatch boundary" />{texts.map.legendBoundary}</li>
          <li><span className="legend-swatch vertex" />{texts.map.legendVertex}</li>
          <li><span className="legend-swatch grid" />{texts.map.legendGrid}</li>
        </ul>
        <p className="hint">{fill(texts.authoritativeArea, { area: formatArea(areaM2) })}</p>
      </div>
    </div>
  )
}
