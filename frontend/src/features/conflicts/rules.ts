// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the review screens show, decided from the session and the alert. The server checks
// everything again: an alert is resolved by someone holding alert.resolve at the office of
// either parcel (a role, or a special grant in force), who authored neither compared version.
import { canAt } from '../../shared/session/access'
import type { Access } from '../../shared/session/access'
import type { Session } from '../../shared/session/types'
import type { Tone } from '../../shared/ui/Feedback'
import type { AlertDetail, AlertStatus } from './api'

/** How this session may resolve the alert: by a role, by a special grant (a use is spent), or not at all. */
export function resolveAccess(session: Session, alert: Pick<AlertDetail, 'a' | 'b'>): Access {
  const offices = [alert.a.officeCode, alert.b.officeCode]
  if (offices.some((office) => canAt(session, 'alert.resolve', office))) return { kind: 'role' }
  const grant = session.specialGrants.find((g) => g.permission === 'alert.resolve'
    && (g.officeCode === null || offices.includes(g.officeCode)))
  return grant ? { kind: 'grant', grant } : null
}

/** A share of a parcel as a percentage with one decimal; a dash when there is none. */
export function percent(share: number | null): string {
  return share === null ? '—' : `${(share * 100).toFixed(1)} %`
}

export const STATUS_TONE: Record<AlertStatus, Tone> = { OPEN: 'warning', RESOLVED: 'success', SUPERSEDED: 'neutral' }
