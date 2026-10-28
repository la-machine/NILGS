// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import { href, parse } from './router'

describe('addresses', () => {
  it('read segments and parameters, decoded', () => {
    const route = parse('#/parcels/DEMO-PCL-YDE1-2026-000001-U?view=satellite&version=2')
    expect(route.segments).toEqual(['parcels', 'DEMO-PCL-YDE1-2026-000001-U'])
    expect(route.params.get('view')).toBe('satellite')
    expect(route.params.get('version')).toBe('2')
    expect(parse('').segments).toEqual([])
    expect(parse('#/').segments).toEqual([])
  })

  it('are written with encoded segments and only the defined parameters', () => {
    expect(href(['parcels', 'a b/c'], { q: 'Mvog Ada', page: undefined })).toBe('#/parcels/a%20b%2Fc?q=Mvog+Ada')
    expect(href(['dashboard'])).toBe('#/dashboard')
    expect(parse(href(['parcels', 'a b/c'])).segments).toEqual(['parcels', 'a b/c'])
  })
})
