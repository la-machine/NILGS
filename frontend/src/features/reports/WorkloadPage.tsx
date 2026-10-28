// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The workload of an office, for its supervisor (MVP-06, E-16): the cases under way per stage
// against the DEMO target of the stage, the applications per state, who holds what, how long
// the stages took, and the queue of cases under way, longest waiting first. Filters live in the
// address (#/workload?office=YDE1&state=UNDER_REVIEW&overdue=1). Every time comes from the
// server, computed from the history of states; nothing here is typed by anyone.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { canAt, homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { STATE_TONE } from '../applications'
import { reportsApi, WORKING_STATES } from './api'
import type { OpenCase, WorkingState, WorkloadSummary } from './api'
import { formatDuration } from './format'

/** Cases per page, as the server sends them (SNIGF_REPORTING_PAGE_SIZE). */
const PAGE_SIZE = 50

export function WorkloadPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const w = texts.workload
  const a = texts.applications
  const r = reach(session, 'report.workload')
  const offices = r.everywhere ? homeOffices(session) : r.offices
  const office = (params.get('office') ?? offices[0] ?? '').toUpperCase()
  const state = (WORKING_STATES.find((s) => s === params.get('state')) ?? '') as WorkingState | ''
  const overdue = params.get('overdue') === '1'
  const page = Math.max(Number(params.get('page') ?? 0) || 0, 0)
  const [summary, setSummary] = useState<WorkloadSummary | null>(null)
  const [cases, setCases] = useState<OpenCase[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!office) return
    let current = true
    reportsApi.workload(office)
      .then((loaded) => { if (current) { setSummary(loaded); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [office, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!office) return
    let current = true
    reportsApi.cases(office, state, overdue, page)
      .then((loaded) => { if (current) setCases(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [office, state, overdue, page, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: { office?: string; state?: WorkingState | ''; overdue?: boolean; page?: number }) => navigate(href(['workload'], {
    office: next.office ?? office,
    state: (next.state ?? state) || undefined,
    overdue: (next.overdue ?? overdue) ? '1' : undefined,
    page: next.page ? String(next.page) : undefined,
  }))
  const opensCases = canAt(session, 'application.read', office)
  const hasMore = cases !== null && cases.length === PAGE_SIZE

  return (
    <div className="page">
      <PageHeader title={w.title} description={w.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.workload }]}
        meta={summary && <span className="meta-item num">{fill(w.asOf, { time: new Date(summary.asOf).toLocaleString(lang) })}</span>} />
      <Card padded>
        <div className="filter-bar" role="search" aria-label={w.filters}>
          <Field label={texts.office}>
            <select value={office} onChange={(e) => go({ office: e.target.value, page: 0 })}>
              {offices.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </Field>
        </div>
      </Card>

      {failure && (
        <EmptyState icon="alert" title={w.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      )}
      {!failure && office && summary === null && <Card padded><Skeleton lines={4} /></Card>}
      {summary && <Summary ctx={ctx} summary={summary} />}

      <Card padded={false} title={w.queueTitle} description={w.queueIntro}>
        <div className="filter-bar card-pad" role="search" aria-label={w.queueTitle}>
          <Field label={w.state}>
            <select value={state} onChange={(e) => go({ state: e.target.value as WorkingState | '', page: 0 })}>
              <option value="">{w.allStates}</option>
              {WORKING_STATES.map((s) => <option key={s} value={s}>{a.states[s]}</option>)}
            </select>
          </Field>
          <label className="checkbox">
            <input type="checkbox" checked={overdue} onChange={(e) => go({ overdue: e.target.checked, page: 0 })} />
            {w.overdueOnly}
          </label>
        </div>
        {!failure && office && cases === null && <div className="card-pad"><Skeleton lines={4} /></div>}
        {cases && cases.length === 0 && <EmptyState icon="checkCircle" title={w.none}>{w.noneText}</EmptyState>}
        {cases && cases.length > 0 && (
          <table className="table table-responsive">
            <thead>
              <tr>
                <th scope="col">{w.reference}</th>
                <th scope="col">{w.parcel}</th>
                <th scope="col">{w.state}</th>
                <th scope="col">{w.sinceCol}</th>
                <th scope="col" className="num-col">{w.waited}</th>
                <th scope="col">{w.heldBy}</th>
              </tr>
            </thead>
            <tbody>
              {cases.map((c) => (
                <tr key={c.id} data-case={c.id} data-overdue={c.overdue}>
                  <td data-label={w.reference}>
                    {opensCases ? <a href={href(['cases', c.id])} className="mono strong">{c.reference}</a> : <span className="mono strong">{c.reference}</span>}
                  </td>
                  <td data-label={w.parcel} className="mono">{c.parcelReference}</td>
                  <td data-label={w.state}><Badge tone={STATE_TONE[c.state]}>{a.states[c.state]}</Badge></td>
                  <td data-label={w.sinceCol} className="num">{new Date(c.since).toLocaleString(lang)}</td>
                  <td data-label={w.waited} className="num-col num">
                    {formatDuration(texts, c.waitedSeconds)}
                    {c.overdue && <> <Badge tone="danger" icon="clock">{w.late}</Badge></>}
                  </td>
                  <td data-label={w.heldBy}>{c.assigneeName ?? <span className="muted">{w.unclaimed}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {cases && (page > 0 || hasMore) && (
          <nav className="pagination" aria-label={w.pages}>
            <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => go({ page: page - 1 })}>{texts.cases.previous}</Button>
            <span className="muted">{fill(texts.cases.pageN, { n: page + 1 })}</span>
            <Button size="sm" disabled={!hasMore} onClick={() => go({ page: page + 1 })}>{texts.more}</Button>
          </nav>
        )}
      </Card>
    </div>
  )
}

function Summary({ ctx, summary }: { ctx: PageContext; summary: WorkloadSummary }) {
  const { texts, lang } = ctx
  const w = texts.workload
  const a = texts.applications
  const target = (s: WorkingState) => summary.targets.find((t) => t.state === s)?.seconds ?? null
  const open = (s: WorkingState) => summary.open.find((o) => o.state === s) ?? { state: s, open: 0, overdue: 0 }
  // A stage is shown when it has a target or a case in it (submitted is left at once by the automatic check).
  const stages = WORKING_STATES.filter((s) => target(s) !== null || open(s).open > 0)
  return (
    <>
      <Card title={w.openTitle} icon="clock" description={w.openIntro}>
        <ul className="stage-tiles">
          {stages.map((s) => {
            const o = open(s)
            const t = target(s)
            return (
              <li key={s} className={o.overdue > 0 ? 'stage-tile late' : 'stage-tile'} data-stage={s}>
                <span className="stage-name">{a.states[s]}</span>
                <span className="stage-open num" aria-label={fill(w.open, { n: o.open })}>{o.open}</span>
                {o.overdue > 0
                  ? <Badge tone="danger" icon="alert">{fill(w.overdue, { n: o.overdue })}</Badge>
                  : <span className="muted small">{w.noneOverdue}</span>}
                <span className="muted small">
                  {t === null ? w.noTarget : fill(w.target, { d: formatDuration(texts, t) })}
                  {s === 'INFO_REQUESTED' && <> · {w.applicantTurn}</>}
                </span>
              </li>
            )
          })}
        </ul>
      </Card>

      <div className="grid-2">
        <Card title={w.countsTitle} icon="file" description={w.countsIntro} padded={false}>
          {summary.counts.length === 0
            ? <EmptyState icon="file" title={w.noCases} />
            : (
              <table className="table">
                <thead><tr><th scope="col">{w.state}</th><th scope="col" className="num-col">{w.cases}</th></tr></thead>
                <tbody>
                  {summary.counts.map((c) => (
                    <tr key={c.state} data-state={c.state}>
                      <td><Badge tone={STATE_TONE[c.state]}>{a.states[c.state]}</Badge></td>
                      <td className="num-col num">{c.cases}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </Card>
        <Card title={w.peopleTitle} icon="users" description={w.peopleIntro} padded={false}>
          {summary.assignees.length === 0
            ? <EmptyState icon="users" title={w.nobodyHolds} />
            : (
              <table className="table">
                <thead>
                  <tr><th scope="col">{w.person}</th><th scope="col">{w.state}</th><th scope="col" className="num-col">{w.cases}</th></tr>
                </thead>
                <tbody>
                  {summary.assignees.map((p, n) => (
                    <tr key={n}>
                      <td>{p.name}</td>
                      <td>{a.states[p.state]}</td>
                      <td className="num-col num">
                        {p.cases}
                        {p.overdue > 0 && <> <Badge tone="danger">{fill(w.overdue, { n: p.overdue })}</Badge></>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </Card>
      </div>

      <Card title={w.durationsTitle} icon="history" padded={false}
        description={fill(w.durationsIntro, { date: new Date(summary.from).toLocaleDateString(lang) })}>
        {summary.durations.length === 0
          ? <EmptyState icon="history" title={w.noDurations} />
          : (
            <table className="table table-responsive">
              <thead>
                <tr>
                  <th scope="col">{w.state}</th>
                  <th scope="col" className="num-col">{w.stays}</th>
                  <th scope="col" className="num-col">{w.average}</th>
                  <th scope="col" className="num-col">{w.median}</th>
                  <th scope="col" className="num-col">{w.longest}</th>
                  <th scope="col" className="num-col">{w.overTarget}</th>
                </tr>
              </thead>
              <tbody>
                {summary.durations.map((d) => (
                  <tr key={d.state} data-state={d.state}>
                    <td data-label={w.state}>{a.states[d.state]}</td>
                    <td data-label={w.stays} className="num-col num">{d.stays}</td>
                    <td data-label={w.average} className="num-col num">{formatDuration(texts, d.averageSeconds)}</td>
                    <td data-label={w.median} className="num-col num">{formatDuration(texts, d.medianSeconds)}</td>
                    <td data-label={w.longest} className="num-col num">{formatDuration(texts, d.longestSeconds)}</td>
                    <td data-label={w.overTarget} className="num-col num">{d.overTarget}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </Card>
    </>
  )
}
