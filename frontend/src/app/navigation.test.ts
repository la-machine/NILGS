// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import type { Session } from '../shared/session/types'
import { GROUPS } from './navigation'

function session(permissions: string[], extra: Partial<Session> = {}): Session {
  return {
    userId: 'u1', displayName: 'Test', accountType: 'STAFF', roles: [], grants: [], permissions, specialGrants: [],
    authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-23T08:00:00Z', ...extra,
  }
}

const visible = (s: Session) => GROUPS.flatMap((g) => g.places).filter((p) => p.visible(s)).map((p) => p.id)

describe('navigation follows the session', () => {
  it('shows an applicant nothing but the dashboard', () => {
    expect(visible(session(['parcel.read#linked'], { accountType: 'APPLICANT' }))).toEqual(['dashboard'])
  })

  it('shows a cadastral officer the parcels only', () => {
    expect(visible(session(['parcel.create@YDE1', 'parcel.correct@YDE1', 'parcel.read']))).toEqual(['dashboard', 'parcels'])
  })

  it('shows an administrator administration and no parcels (decision D14)', () => {
    expect(visible(session(['admin.account.list', 'admin.role.read', 'admin.grant.read']))).toEqual(['dashboard', 'accounts', 'roles', 'grants'])
  })

  it('shows a supervisor the workload, confirmations and the audit verification', () => {
    expect(visible(session(['parcel.read', 'report.workload@YDE1', 'identity.reset.confirm@YDE1', 'audit.chain.verify'])))
      .toEqual(['dashboard', 'parcels', 'workload', 'confirmations', 'audit'])
  })

  it('opens the audit trail for a special grant on an audit action', () => {
    const grant = { id: 'g', permission: 'audit.key.read', officeCode: null, validUntil: null, usesLeft: 1 }
    expect(visible(session([], { specialGrants: [grant] }))).toEqual(['dashboard', 'audit'])
  })
})
