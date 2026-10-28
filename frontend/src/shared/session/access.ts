// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// How the interface finds what a user may do (ADR-025, ADR-026). The session lists role
// permissions as `code` (everywhere) or `code@OFFICE` (at that office); relative scopes
// (`code#linked` ...) never count here, except own records through canOwn(). A permission may also come from a special grant: a
// screen opened through a grant loads nothing by itself, and every protected call is a
// button, confirmed first, naming the uses left. The server decides in the end; this only
// shapes the screen, so nobody is shown a control they cannot use.
import type { ActiveGrant, Session } from './types'

export type Access = { kind: 'role' } | { kind: 'grant'; grant: ActiveGrant } | null

/** Held everywhere through a role. */
export function can(session: Session, permission: string): boolean {
  return session.permissions.includes(permission)
}

/** Held everywhere or at some office through a role (the server narrows records to those offices). */
export function canSomewhere(session: Session, permission: string): boolean {
  return session.permissions.some((p) => p === permission || p.startsWith(`${permission}@`))
}

/** Held for the records assigned to the person (`code#assigned`): a reviewer's or decision authority's cases. */
export function canAssigned(session: Session, permission: string): boolean {
  return session.permissions.includes(`${permission}#assigned`)
}

/** Held for the person's own records (`code#own`): an applicant's applications. */
export function canOwn(session: Session, permission: string): boolean {
  return session.permissions.includes(`${permission}#own`)
}

/** Where a permission is held: everywhere, and/or at these offices. */
export function reach(session: Session, permission: string): { everywhere: boolean; offices: string[] } {
  const offices = session.permissions.filter((p) => p.startsWith(`${permission}@`)).map((p) => p.slice(permission.length + 1))
  return { everywhere: session.permissions.includes(permission), offices }
}

/** Held at this office (or everywhere). */
export function canAt(session: Session, permission: string, office: string): boolean {
  const r = reach(session, permission)
  return r.everywhere || r.offices.includes(office)
}

/** Access to a permission checked everywhere (no office): through a role, or a special grant. */
export function accessTo(session: Session, permission: string): Access {
  if (session.permissions.includes(permission)) return { kind: 'role' }
  const grant = session.specialGrants.find((g) => g.permission === permission && g.officeCode === null)
  return grant ? { kind: 'grant', grant } : null
}

/** The offices the person works at, from their role grants, in a stable order. */
export function homeOffices(session: Session): string[] {
  return Array.from(new Set(session.grants.map((g) => g.officeCode).filter((o): o is string => !!o))).sort()
}
