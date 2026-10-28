// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import type { ProjectedGeometry } from './api'
import { TechnicalView } from './TechnicalView'

const texts = t('en')
/** A 30 m × 30 m square in UTM 32N. */
const square: ProjectedGeometry = {
  srid: 32632,
  geometry: { type: 'MultiPolygon', coordinates: [[[[776200, 428100], [776230, 428100], [776230, 428130], [776200, 428130], [776200, 428100]]]] },
}

describe('technical view', () => {
  beforeEach(() => {
    // jsdom has no layout: the drawing is 800 × 440 on screen as in its viewBox.
    Element.prototype.getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 440, right: 800, bottom: 440, x: 0, y: 0, toJSON: () => ({}) })
  })
  afterEach(cleanup)

  it('draws the boundary, its vertices, a metric grid and a scale, with the authoritative area', () => {
    const { container } = render(<TechnicalView texts={texts} display={square} areaM2={900.4} />)
    expect(screen.getByTestId('parcel-boundary').getAttribute('d')).toMatch(/^M.*Z$/)
    expect(container.querySelectorAll('.parcel-vertex')).toHaveLength(4)
    expect(container.querySelectorAll('.grid line').length).toBeGreaterThan(4)
    expect(container.querySelector('.scale-bar text')?.textContent).toMatch(/ m$/)
    expect(screen.getByText(/UTM 32N \(EPSG:32632\)/)).toBeTruthy()
    expect(screen.getByText(/900\.40 m²/)).toBeTruthy()
  })

  it('shows the coordinates under the cursor in metres and degrees', () => {
    const { container } = render(<TechnicalView texts={texts} display={square} areaM2={900} />)
    fireEvent.pointerMove(container.querySelector('svg')!, { clientX: 400, clientY: 220 })
    expect(screen.getByText(/E 776215\.00 m · N 428115\.00 m/)).toBeTruthy()
    expect(screen.getByText(/3\.86956\d+°, 11\.48712\d+°/)).toBeTruthy()
  })

  it('zooms and fits back', () => {
    const { container } = render(<TechnicalView texts={texts} display={square} areaM2={900} />)
    const scale = () => container.querySelector('.scale-bar text')?.textContent
    const before = scale()
    fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
    fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
    expect(scale()).not.toBe(before)
    fireEvent.click(screen.getByRole('button', { name: 'Fit the parcel' }))
    expect(scale()).toBe(before)
  })

  it('measures a length and an area between clicked points', () => {
    const { container } = render(<TechnicalView texts={texts} display={square} areaM2={900} />)
    const svg = container.querySelector('svg')!
    fireEvent.click(screen.getByRole('button', { name: 'Measure' }))
    const click = (x: number, y: number) => {
      fireEvent.pointerDown(svg, { clientX: x, clientY: y })
      fireEvent.pointerUp(svg, { clientX: x, clientY: y })
    }
    // The fit is 0.75 × 440 / 30 = 11 px per metre around the centre (400, 220).
    click(400, 220)
    click(510, 220)
    expect(screen.getByTestId('measurement').textContent).toContain('10.00 m')
    click(510, 110)
    expect(screen.getByTestId('measurement').textContent).toContain('50.00 m²')
  })
})
