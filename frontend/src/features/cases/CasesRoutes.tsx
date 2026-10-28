// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Cases (MVP-03, E-14): #/cases (an office's submitted applications) and #/cases/<id> (one case).
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { CaseDetailPage } from './CaseDetailPage'
import { CaseListPage } from './CaseListPage'

export function CasesRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  const id = route.segments[1]
  if (id) return <CaseDetailPage key={id} ctx={ctx} id={id} />
  return <CaseListPage ctx={ctx} params={route.params} />
}
