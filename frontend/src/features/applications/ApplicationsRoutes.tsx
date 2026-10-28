// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's applications (MVP-02, E-12): #/applications (the list), #/applications/new
// (start one) and #/applications/<id> (a draft's form, or a submitted application).
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { ApplicationDetailPage } from './ApplicationDetailPage'
import { ApplicationListPage } from './ApplicationListPage'
import { ApplicationNewPage } from './ApplicationNewPage'

export function ApplicationsRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  const sub = route.segments[1]
  if (sub === 'new') return <ApplicationNewPage ctx={ctx} typeCode={route.params.get('type')} />
  // Keyed by the query too: after a submission the same address returns with ?submitted=, and the
  // page reads the application again.
  if (sub) return <ApplicationDetailPage key={`${sub}?${route.params}`} ctx={ctx} id={sub} params={route.params} />
  return <ApplicationListPage ctx={ctx} params={route.params} />
}
