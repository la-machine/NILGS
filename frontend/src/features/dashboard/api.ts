// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the dashboard reads, all through endpoints that already exist and only those the
// session may call: pending decisions, the latest parcels of the person's offices, the map
// data status and the person's own security keys. A count is the length of a list (open alerts:
// the first page, up to 50, of each office; cases in completeness review: the first page of each
// office), except overdue cases, which the supervisor's workload report counts (MVP-06), and DEMO
// payment exceptions, which the finance summary counts (MVP-08).
import { accountsApi } from '../accounts'
import { authApi } from '../auth'
import { casesApi } from '../cases'
import { conflictsApi } from '../conflicts'
import { grantsApi } from '../grants'
import { mapsApi } from '../maps'
import type { Background } from '../maps'
import { parcelsApi } from '../parcels'
import { paymentsApi } from '../payments'
import type { ParcelSummary } from '../parcels'
import { recoveryApi } from '../recovery'
import { reportsApi } from '../reports'
import { rolesApi } from '../roles'

export interface PendingCounts {
  assignments?: number
  roleChanges?: number
  grants?: number
  resetsWaiting?: number
  confirmations?: number
  /** Open overlap and duplicate alerts at the reviewer's offices (each counted once). */
  alerts?: number
  /** Cases waiting for the completeness review at the intake officer's offices. */
  cases?: number
  /** Cases past the DEMO target of their stage at the supervisor's offices (MVP-06). */
  overdue?: number
  /** DEMO payment exceptions waiting for the finance officer (MVP-08). */
  paymentExceptions?: number
}

export const dashboardApi = {
  /** Only the lists the session may read are asked for; the others stay undefined. */
  pending: async (may: { assignments: boolean; roleChanges: boolean; grants: boolean; resets: boolean; confirmations: boolean;
    reconciles?: boolean },
    alertOffices: string[] = [], intakeOffices: string[] = [], workloadOffices: string[] = []) => {
    const [assignments, roleChanges, grants, resetsWaiting, confirmations, alerts, cases, overdue, paymentExceptions] = await Promise.all([
      may.assignments ? accountsApi.assignmentRequests('PENDING').then((l) => l.length) : undefined,
      may.roleChanges ? rolesApi.requests('PENDING').then((l) => l.length) : undefined,
      may.grants ? grantsApi.list('PENDING').then((l) => l.length) : undefined,
      may.resets ? accountsApi.resetRequests('PENDING').then((l) => l.length) : undefined,
      may.confirmations ? recoveryApi.toConfirm().then((l) => l.length) : undefined,
      alertOffices.length > 0
        ? Promise.all(alertOffices.map((o) => conflictsApi.list(o, 'OPEN', '', 0)))
          .then((lists) => new Set(lists.flat().map((a) => a.id)).size)
        : undefined,
      intakeOffices.length > 0
        ? Promise.all(intakeOffices.map((o) => casesApi.queue(o, 'COMPLETENESS_REVIEW', 0))).then((lists) => lists.flat().length)
        : undefined,
      workloadOffices.length > 0
        ? Promise.all(workloadOffices.map((o) => reportsApi.workload(o)))
          .then((reports) => reports.reduce((n, r) => n + r.open.reduce((m, s) => m + s.overdue, 0), 0))
        : undefined,
      may.reconciles ? paymentsApi.summary().then((s) => s.openExceptions) : undefined,
    ])
    return { assignments, roleChanges, grants, resetsWaiting, confirmations, alerts, cases, overdue, paymentExceptions } satisfies PendingCounts
  },
  latestParcels: (office: string): Promise<ParcelSummary[]> => parcelsApi.search(office, '', 0),
  backgrounds: (): Promise<Background[]> => mapsApi.backgrounds(),
  keys: () => authApi.keys(),
}
