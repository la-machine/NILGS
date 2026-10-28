// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Parcels (MVP-01, E-08): #/parcels (search), #/parcels/new (record), #/parcels/<reference>
// (detail, map, history, correction). The interface offers only what the session's
// permissions allow; the server decides every call.
import type { Route } from '../../shared/router/router'
import { canSomewhere } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { ParcelCreatePage } from './ParcelCreatePage'
import { ParcelDetailPage } from './ParcelDetailPage'
import { ParcelListPage } from './ParcelListPage'

export function ParcelsRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  const sub = route.segments[1]
  if (sub === 'new' && canSomewhere(ctx.session, 'parcel.create')) return <ParcelCreatePage ctx={ctx} />
  if (sub && sub !== 'new') return <ParcelDetailPage key={sub} ctx={ctx} reference={sub} params={route.params} />
  return <ParcelListPage ctx={ctx} params={route.params} />
}
