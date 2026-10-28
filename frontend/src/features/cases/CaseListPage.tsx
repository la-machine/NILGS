// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The submitted applications of an office, waiting longest first: by default those in
// completeness review, the intake officer's queue. Filters live in the address
// (#/cases?office=YDE1&state=INFO_REQUESTED), so a reload and the back button keep them.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { STATE_TONE } from '../applications'
import type { ApplicationState } from '../applications'
import { CASE_STATES, casesApi } from './api'
import type { CaseSummary } from './api'

const PAGE_SIZE = 20

export function CaseListPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const c = texts.cases
  const a = texts.applications
  const read = reach(session, 'application.read')
  const offices = read.everywhere ? homeOffices(session) : read.offices
  const office = (params.get('office') ?? offices[0] ?? '').toUpperCase()
  const stateParam = params.get('state')
  const state = (stateParam === null ? 'COMPLETENESS_REVIEW' : CASE_STATES.find((s) => s === stateParam) ?? '') as ApplicationState | ''
  const page = Math.max(Number(params.get('page') ?? 0) || 0, 0)
  const [items, setItems] = useState<CaseSummary[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!office) return
    let current = true
    casesApi.queue(office, state, page)
      .then((list) => { if (current) { setItems(list); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [office, state, page, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: { office?: string; state?: ApplicationState | ''; page?: number }) => navigate(href(['cases'], {
    office: next.office ?? office,
    state: (next.state ?? state) === 'COMPLETENESS_REVIEW' ? undefined : (next.state ?? state) || 'ALL',
    page: next.page ? String(next.page) : undefined,
  }))
  const hasMore = items !== null && items.length === PAGE_SIZE

  return (
    <div className="page">
      <PageHeader title={c.listTitle} description={c.listIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.cases }]} />
      <Card padded>
        <div className="filter-bar" role="search" aria-label={c.filters}>
          <Field label={texts.office}>
            <select value={office} onChange={(e) => go({ office: e.target.value, page: 0 })}>
              {offices.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </Field>
          <Field label={a.state}>
            <select value={state} onChange={(e) => go({ state: e.target.value as ApplicationState | '', page: 0 })}>
              {CASE_STATES.map((s) => <option key={s} value={s}>{a.states[s]}</option>)}
              <option value="">{c.allStates}</option>
            </select>
          </Field>
        </div>
      </Card>
      <Card padded={false} title={office ? fill(c.listAt, { office }) : c.listTitle} description={c.oldestFirst}>
        {failure && (
          <EmptyState icon="alert" title={c.loadFailed}
            action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
        )}
        {!failure && office && items === null && <div className="card-pad"><Skeleton lines={4} /></div>}
        {items && items.length === 0 && <EmptyState icon="checkCircle" title={c.none}>{c.noneText}</EmptyState>}
        {items && items.length > 0 && (
          <table className="table table-responsive">
            <thead>
              <tr>
                <th scope="col">{a.reference}</th>
                <th scope="col">{c.applicant}</th>
                <th scope="col">{a.parcelReference}</th>
                <th scope="col">{a.state}</th>
                <th scope="col">{c.waitingSince}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} data-case={item.id}>
                  <td data-label={a.reference}><a href={href(['cases', item.id])} className="mono strong">{item.reference}</a></td>
                  <td data-label={c.applicant}>{item.applicantName}</td>
                  <td data-label={a.parcelReference} className="mono">{item.parcelReference}</td>
                  <td data-label={a.state}><Badge tone={STATE_TONE[item.state]}>{a.states[item.state]}</Badge></td>
                  <td data-label={c.waitingSince} className="num">{new Date(item.updatedAt).toLocaleString(lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {items && (page > 0 || hasMore) && (
          <nav className="pagination" aria-label={c.pages}>
            <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => go({ page: page - 1 })}>{c.previous}</Button>
            <span className="muted">{fill(c.pageN, { n: page + 1 })}</span>
            <Button size="sm" disabled={!hasMore} onClick={() => go({ page: page + 1 })}>{texts.more}</Button>
          </nav>
        )}
      </Card>
    </div>
  )
}
