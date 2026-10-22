// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// How the interface finds what a user may do (ADR-026): a permission held through a role,
// or only through a special grant. Reading the session costs nothing; a grant's use is spent
// by the server only when the protected action succeeds. So a screen opened through a grant
// loads nothing by itself: every protected call is a button, confirmed first, naming the
// uses left. The server decides in the end; this only shapes the screen.
import type { ActiveGrant, Session } from './types'

export type Access = { kind: 'role' } | { kind: 'grant'; grant: ActiveGrant } | null

/** Access to a permission checked everywhere (no office). */
export function accessTo(session: Session, permission: string): Access {
  if (session.permissions.includes(permission)) return { kind: 'role' }
  const grant = session.specialGrants.find((g) => g.permission === permission && g.officeCode === null)
  return grant ? { kind: 'grant', grant } : null
}
