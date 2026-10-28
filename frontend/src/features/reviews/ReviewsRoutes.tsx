// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Reviews (MVP-05, E-15): #/reviews (the reviewer's or decision authority's work) and #/reviews/<id> (one case).
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { ReviewCasePage } from './ReviewCasePage'
import { ReviewQueuePage } from './ReviewQueuePage'

export function ReviewsRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  const id = route.segments[1]
  if (id) return <ReviewCasePage key={id} ctx={ctx} id={id} />
  return <ReviewQueuePage ctx={ctx} params={route.params} />
}
