// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import { accessTo, can, canAt, canSomewhere, homeOffices, reach } from './access'
import type { Session } from './types'

function session(permissions: string[], extra: Partial<Session> = {}): Session {
  return {
    userId: 'u1', displayName: 'Test Person', accountType: 'STAFF', roles: [], grants: [], permissions,
    specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-23T08:00:00Z',
    ...extra,
  }
}

describe('what a session may do', () => {
  it('distinguishes everywhere, an office, and somewhere', () => {
    const s = session(['parcel.read', 'parcel.create@YDE1'])
    expect(can(s, 'parcel.read')).toBe(true)
    expect(can(s, 'parcel.create')).toBe(false)
    expect(canSomewhere(s, 'parcel.create')).toBe(true)
    expect(canAt(s, 'parcel.create', 'YDE1')).toBe(true)
    expect(canAt(s, 'parcel.create', 'DLA1')).toBe(false)
    expect(canAt(s, 'parcel.read', 'DLA1')).toBe(true)
    expect(reach(s, 'parcel.create')).toEqual({ everywhere: false, offices: ['YDE1'] })
  })

  it('never counts a relative scope as everywhere or as an office', () => {
    const applicant = session(['parcel.read#linked'], { accountType: 'APPLICANT' })
    expect(can(applicant, 'parcel.read')).toBe(false)
    expect(canSomewhere(applicant, 'parcel.read')).toBe(false)
    expect(canAt(applicant, 'parcel.read', 'YDE1')).toBe(false)
  })

  it('tells a role from a special grant, and ignores office-bound grants for office-free actions', () => {
    const grant = { id: 'g1', permission: 'audit.key.read', officeCode: null, validUntil: null, usesLeft: 1 }
    expect(accessTo(session(['audit.key.read']), 'audit.key.read')).toEqual({ kind: 'role' })
    expect(accessTo(session([], { specialGrants: [grant] }), 'audit.key.read')).toEqual({ kind: 'grant', grant })
    expect(accessTo(session([], { specialGrants: [{ ...grant, officeCode: 'YDE1' }] }), 'audit.key.read')).toBeNull()
  })

  it('lists the offices the person works at, once each', () => {
    const s = session([], { grants: [{ role: 'REVIEWER', officeCode: 'YDE1' }, { role: 'SUPERVISOR', officeCode: 'YDE1' },
      { role: 'APPLICANT', officeCode: null }, { role: 'REVIEWER', officeCode: 'DLA1' }] })
    expect(homeOffices(s)).toEqual(['DLA1', 'YDE1'])
  })
})
