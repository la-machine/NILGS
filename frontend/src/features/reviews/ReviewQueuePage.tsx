// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The reviewer's or decision authority's work (MVP-05): the cases assigned to them, and those of
// their office waiting at their stage to be claimed, oldest first. Someone holding both roles
// chooses the stage; the stage lives in the address (#/reviews?stage=DECISION).
import { useEffect, useState } from 'react'
import { href, navigate } from '../../shared/router/router'
import { canAssigned } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { STATE_TONE } from '../applications'
import { reviewsApi } from './api'
import type { QueueEntry, Stage } from './api'

export function ReviewQueuePage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const r = texts.reviews
  const stages: Stage[] = [
    ...(canAssigned(session, 'review.recommend') ? ['REVIEW' as const] : []),
    ...(canAssigned(session, 'decision.record') ? ['DECISION' as const] : []),
  ]
  const stage = (stages.find((s) => s === params.get('stage')) ?? stages[0] ?? 'REVIEW') as Stage
  const [mine, setMine] = useState<QueueEntry[] | null>(null)
  const [waiting, setWaiting] = useState<QueueEntry[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([reviewsApi.queue(stage, true), reviewsApi.queue(stage, false)])
      .then(([assigned, open]) => { if (current) { setMine(assigned); setWaiting(open); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [stage, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const table = (items: QueueEntry[] | null, empty: string) => (
    <>
      {!failure && items === null && <div className="card-pad"><Skeleton lines={3} /></div>}
      {items && items.length === 0 && <p className="card-pad muted">{empty}</p>}
      {items && items.length > 0 && (
        <table className="table table-responsive">
          <thead>
            <tr>
              <th scope="col">{texts.applications.reference}</th>
              <th scope="col">{texts.cases.applicant}</th>
              <th scope="col">{texts.office}</th>
              <th scope="col">{texts.applications.state}</th>
              <th scope="col">{texts.cases.waitingSince}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((q) => (
              <tr key={q.id} data-review={q.id}>
                <td data-label={texts.applications.reference}><a href={href(['reviews', q.id])} className="mono strong">{q.reference}</a></td>
                <td data-label={texts.cases.applicant}>{q.applicantName}</td>
                <td data-label={texts.office}>{q.officeCode}</td>
                <td data-label={texts.applications.state}><Badge tone={STATE_TONE[q.state]}>{texts.applications.states[q.state]}</Badge></td>
                <td data-label={texts.cases.waitingSince} className="num">{new Date(q.updatedAt).toLocaleString(lang)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )

  return (
    <div className="page">
      <PageHeader title={r.queueTitle} description={r.stageIntro[stage]} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.reviews }]} />
      {stages.length > 1 && (
        <Card padded>
          <Field label={r.stage}>
            <select value={stage} onChange={(e) => navigate(href(['reviews'], { stage: e.target.value }))}>
              {stages.map((s) => <option key={s} value={s}>{r.stages[s]}</option>)}
            </select>
          </Field>
        </Card>
      )}
      {failure && (
        <EmptyState icon="alert" title={r.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      )}
      <Card padded={false} title={r.mine}>{table(mine, r.noneMine)}</Card>
      <Card padded={false} title={r.waiting} description={r.waitingIntro}>{table(waiting, r.noneWaiting)}</Card>
    </div>
  )
}
