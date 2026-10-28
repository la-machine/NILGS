// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Where an application stands, as its applicant follows it (MVP-06): its state, since when (from
// the server's history, never a date anyone typed), what that state means in plain words, and
// whose turn it is. No person of the office is ever named: the server does not send them.
import { useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Card } from '../../shared/ui/Card'
import { Badge } from '../../shared/ui/Feedback'
import type { Application } from './api'
import { STATE_TONE } from './states'

const DAY_MS = 86_400_000

export function TrackingCard({ texts, lang, application, now }: {
  texts: Texts; lang: Lang; application: Pick<Application, 'state' | 'since' | 'waitingOn'>; now?: Date
}) {
  // The moment the card was first shown, read once.
  const [opened] = useState(() => new Date())
  const tr = texts.tracking
  const since = new Date(application.since)
  const days = Math.max(Math.floor(((now ?? opened).getTime() - since.getTime()) / DAY_MS), 0)
  return (
    <Card title={tr.title} icon="clock">
      <div className="tracking" data-waiting-on={application.waitingOn ?? 'NOBODY'}>
        <p className="tracking-state">
          <Badge tone={STATE_TONE[application.state]}>{texts.applications.states[application.state]}</Badge>
          <span className="muted">
            {fill(tr.since, { date: since.toLocaleDateString(lang, { day: 'numeric', month: 'long', year: 'numeric' }) })}
            {' · '}
            <span className="num">{days === 0 ? tr.today : fill(tr.days, { n: days })}</span>
          </span>
        </p>
        <p>{tr.explain[application.state]}</p>
        <p className={application.waitingOn === 'APPLICANT' ? 'tracking-turn strong' : 'tracking-turn muted'}>
          {application.waitingOn ? tr.waiting[application.waitingOn] : tr.ended}
        </p>
      </div>
    </Card>
  )
}
