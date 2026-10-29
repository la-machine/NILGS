// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Which page the address shows. A place this session cannot use shows a plain "no access"
// page instead of a screen that would only fail call after call; the server refuses the
// calls anyway.
import { lazy, Suspense } from 'react'
import { AccountPage } from '../features/auth'
import { href } from '../shared/router/router'
import type { Route } from '../shared/router/router'
import type { PageContext } from '../shared/session/types'
import { EmptyState, Skeleton } from '../shared/ui/Feedback'
import type { PlaceId } from './navigation'

// Each page is loaded when first opened: the sign-in screens download none of them.
const DashboardPage = lazy(() => import('../features/dashboard').then((m) => ({ default: m.DashboardPage })))
const ApplicationsRoutes = lazy(() => import('../features/applications').then((m) => ({ default: m.ApplicationsRoutes })))
const CasesRoutes = lazy(() => import('../features/cases').then((m) => ({ default: m.CasesRoutes })))
const ReviewsRoutes = lazy(() => import('../features/reviews').then((m) => ({ default: m.ReviewsRoutes })))
const ParcelsRoutes = lazy(() => import('../features/parcels').then((m) => ({ default: m.ParcelsRoutes })))
const AlertsRoutes = lazy(() => import('../features/conflicts').then((m) => ({ default: m.AlertsRoutes })))
const AccountsPage = lazy(() => import('../features/accounts').then((m) => ({ default: m.AccountsPage })))
const RolesPage = lazy(() => import('../features/roles').then((m) => ({ default: m.RolesPage })))
const GrantsPage = lazy(() => import('../features/grants').then((m) => ({ default: m.GrantsPage })))
const WorkloadPage = lazy(() => import('../features/reports').then((m) => ({ default: m.WorkloadPage })))
const FinancePage = lazy(() => import('../features/payments').then((m) => ({ default: m.FinancePage })))
const ConfirmationsPage = lazy(() => import('../features/recovery').then((m) => ({ default: m.ConfirmationsPage })))
const AuditPage = lazy(() => import('../features/audit').then((m) => ({ default: m.AuditPage })))
const AuditTimelinePage = lazy(() => import('../features/audit').then((m) => ({ default: m.AuditTimelinePage })))

export function Pages({ route, place, allowed, ctx, onAddKey }: {
  route: Route; place: PlaceId | null; allowed: boolean; ctx: PageContext; onAddKey: (label: string) => Promise<void>
}) {
  const { texts } = ctx
  if (!place) {
    return (
      <div className="page page-narrow">
        <EmptyState icon="map" title={texts.shell.notFoundTitle}
          action={<a className="btn btn-primary" href={href(['dashboard'])}>{texts.shell.backToDashboard}</a>}>
          {texts.shell.notFound}
        </EmptyState>
      </div>
    )
  }
  if (!allowed) {
    return (
      <div className="page page-narrow">
        <EmptyState icon="lock" title={texts.shell.noAccessTitle}
          action={<a className="btn btn-primary" href={href(['dashboard'])}>{texts.shell.backToDashboard}</a>}>
          {texts.shell.noAccess}
        </EmptyState>
      </div>
    )
  }
  return (
    <Suspense fallback={<div className="page" aria-busy="true"><Skeleton lines={6} /></div>}>
      {place === 'dashboard' && <DashboardPage ctx={ctx} />}
      {place === 'applications' && <ApplicationsRoutes ctx={ctx} route={route} />}
      {place === 'cases' && <CasesRoutes ctx={ctx} route={route} />}
      {place === 'reviews' && <ReviewsRoutes ctx={ctx} route={route} />}
      {place === 'parcels' && <ParcelsRoutes ctx={ctx} route={route} />}
      {place === 'alerts' && <AlertsRoutes ctx={ctx} route={route} />}
      {place === 'accounts' && <AccountsPage ctx={ctx} />}
      {place === 'roles' && <RolesPage ctx={ctx} />}
      {place === 'grants' && <GrantsPage ctx={ctx} />}
      {place === 'workload' && <WorkloadPage ctx={ctx} params={route.params} />}
      {place === 'payments' && <FinancePage ctx={ctx} params={route.params} />}
      {place === 'confirmations' && <ConfirmationsPage ctx={ctx} />}
      {place === 'audit' && route.segments[1] !== 'timeline' && <AuditPage ctx={ctx} />}
      {place === 'audit' && route.segments[1] === 'timeline' && <AuditTimelinePage ctx={ctx} params={route.params} />}
      {place === 'account' && <AccountPage ctx={ctx} onAddKey={onAddKey} />}
    </Suspense>
  )
}
