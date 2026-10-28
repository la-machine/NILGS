// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Alert review (MVP-04, E-11): #/alerts (the alerts of an office) and #/alerts/<id> (one
// alert: map, both parcels, resolution). The server decides every call.
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { AlertDetailPage } from './AlertDetailPage'
import { AlertListPage } from './AlertListPage'

export function AlertsRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  const id = route.segments[1]
  if (id) return <AlertDetailPage key={id} ctx={ctx} id={id} />
  return <AlertListPage ctx={ctx} params={route.params} />
}
