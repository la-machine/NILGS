// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The addresses of professional accounts: #/professional and #/professional/deposits/<id> for the
// professional; #/professionals and #/professionals/<userId> for administrators.
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { AdminDetailPage } from './AdminDetailPage'
import { AdminPage } from './AdminPage'
import { DepositPage } from './DepositPage'
import { OwnPage } from './OwnPage'

export function ProfessionalRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  if (route.segments[1] === 'deposits' && route.segments[2]) {
    return <DepositPage key={route.segments[2]} ctx={ctx} id={route.segments[2]} />
  }
  return <OwnPage ctx={ctx} />
}

export function ProfessionalsAdminRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  if (route.segments[1]) return <AdminDetailPage key={route.segments[1]} ctx={ctx} userId={route.segments[1]} />
  return <AdminPage ctx={ctx} params={route.params} />
}
