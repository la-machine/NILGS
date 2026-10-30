// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The application's places and who sees them. A place appears only for a session that can
// use it (a role, or a special grant for the audit actions), so nobody is shown a link to a
// page that would only refuse them; the server checks every call regardless.
import { AUDIT_ACTIONS } from '../features/audit'
import type { Texts } from '../shared/i18n'
import { accessTo, can, canAssigned, canOwn, canSomewhere } from '../shared/session/access'
import type { Session } from '../shared/session/types'
import type { IconName } from '../shared/ui/Icon'

export type PlaceId = 'dashboard' | 'applications' | 'cases' | 'reviews' | 'parcels' | 'alerts' | 'accounts' | 'roles' | 'grants' | 'workload' | 'payments' | 'issued-documents' | 'confirmations' | 'audit' | 'account'

export interface Place {
  id: PlaceId
  icon: IconName
  label: (texts: Texts) => string
  visible: (session: Session) => boolean
}

export interface PlaceGroup {
  label: (texts: Texts) => string | null
  places: Place[]
}

export const GROUPS: PlaceGroup[] = [
  {
    label: () => null,
    places: [
      { id: 'dashboard', icon: 'dashboard', label: (t) => t.nav.dashboard, visible: () => true },
      {
        id: 'applications', icon: 'file', label: (t) => t.nav.applications,
        visible: (s) => canOwn(s, 'application.read'),
      },
    ],
  },
  {
    label: (t) => t.nav.groupLand,
    places: [{
      id: 'cases', icon: 'file', label: (t) => t.nav.cases,
      visible: (s) => canSomewhere(s, 'application.read'),
    }, {
      id: 'reviews', icon: 'shield', label: (t) => t.nav.reviews,
      visible: (s) => canAssigned(s, 'application.read'),
    }, {
      id: 'parcels', icon: 'parcel', label: (t) => t.nav.parcels,
      visible: (s) => canSomewhere(s, 'parcel.read') || canSomewhere(s, 'parcel.create'),
    }, {
      id: 'alerts', icon: 'alert', label: (t) => t.nav.alerts,
      visible: (s) => canSomewhere(s, 'alert.review'),
    }],
  },
  {
    label: (t) => t.nav.groupAdministration,
    places: [
      { id: 'accounts', icon: 'users', label: (t) => t.nav.accounts, visible: (s) => can(s, 'admin.account.list') },
      { id: 'roles', icon: 'shield', label: (t) => t.nav.roles, visible: (s) => can(s, 'admin.role.read') },
      { id: 'grants', icon: 'key', label: (t) => t.nav.grants, visible: (s) => can(s, 'admin.grant.read') },
    ],
  },
  {
    label: (t) => t.nav.groupOversight,
    places: [
      {
        id: 'workload', icon: 'clock', label: (t) => t.nav.workload,
        visible: (s) => canSomewhere(s, 'report.workload'),
      },
      {
        id: 'payments', icon: 'card', label: (t) => t.nav.payments,
        visible: (s) => can(s, 'payment.reconcile'),
      },
      {
        id: 'issued-documents', icon: 'signature', label: (t) => t.nav.issuedDocuments,
        visible: (s) => can(s, 'document.issued.manage') || can(s, 'document.incident.review'),
      },
      {
        id: 'confirmations', icon: 'userCheck', label: (t) => t.nav.confirmations,
        visible: (s) => canSomewhere(s, 'identity.reset.confirm'),
      },
      {
        id: 'audit', icon: 'audit', label: (t) => t.nav.audit,
        visible: (s) => AUDIT_ACTIONS.some((p) => accessTo(s, p) !== null),
      },
    ],
  },
]

export const ACCOUNT_PLACE: Place = { id: 'account', icon: 'user', label: (t) => t.nav.account, visible: () => true }

export function placeOf(id: string): Place | undefined {
  return [...GROUPS.flatMap((g) => g.places), ACCOUNT_PLACE].find((p) => p.id === id)
}
