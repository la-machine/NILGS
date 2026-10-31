// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The land register's addresses: #/register for the conservation, #/register/titles/<id> for a title.
import type { Route } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { RegisterPage } from './RegisterPage'
import { TitlePage } from './TitlePage'

export function RegisterRoutes({ ctx, route }: { ctx: PageContext; route: Route }) {
  if (route.segments[1] === 'titles' && route.segments[2]) {
    return <TitlePage key={route.segments[2]} ctx={ctx} id={route.segments[2]} />
  }
  return <RegisterPage ctx={ctx} params={route.params} />
}
