// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The alerts of an office (MVP-04, E-11), newest first: open ones by default, filterable by
// status and type. The filters live in the address (#/alerts?office=YDE1&status=OPEN), so the
// back button and a reload keep them. Each line names both parcels and what they share, and
// opens the review.
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
import { ALERT_STATUSES, ALERT_TYPES, conflictsApi } from './api'
import type { AlertStatus, AlertSummary, AlertType } from './api'
import { percent, STATUS_TONE } from './rules'

const PAGE_SIZE = 50

export function AlertListPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const c = texts.conflicts
  const review = reach(session, 'alert.review')
  const offices = review.everywhere ? homeOffices(session) : review.offices
  const office = (params.get('office') ?? offices[0] ?? '').toUpperCase()
  const statusParam = params.get('status')
  const status = (statusParam === null ? 'OPEN' : ALERT_STATUSES.find((s) => s === statusParam) ?? '') as AlertStatus | ''
  const type = (ALERT_TYPES.find((t) => t === params.get('type')) ?? '') as AlertType | ''
  const page = Math.max(Number(params.get('page') ?? 0) || 0, 0)

  const [state, setState] = useState<{ status: 'loading' | 'error' | 'done'; items: AlertSummary[]; error?: string }>(
    { status: 'loading', items: [] })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!office) return
    let current = true
    // oxlint-disable-next-line react/set-state-in-effect
    setState((s) => ({ ...s, status: 'loading' }))
    conflictsApi.list(office, status, type, page)
      .then((items) => { if (current) setState({ status: 'done', items }) })
      .catch((e) => { if (current) setState({ status: 'error', items: [], error: errorMessage(texts, e) }) })
    return () => { current = false }
  }, [office, status, type, page, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: { office?: string; status?: AlertStatus | ''; type?: AlertType | ''; page?: number }) =>
    navigate(href(['alerts'], {
      office: next.office ?? office,
      // OPEN is the default; "all" is written explicitly as an empty status.
      status: (next.status ?? status) === 'OPEN' ? undefined : (next.status ?? status) || 'ALL',
      type: (next.type ?? type) || undefined,
      page: next.page ? String(next.page) : undefined,
    }))

  const hasMore = state.status === 'done' && state.items.length === PAGE_SIZE

  return (
    <div className="page">
      <PageHeader title={c.listTitle} description={c.listIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.alerts }]} />

      <Card padded>
        <div className="filter-bar" role="search" aria-label={c.filters}>
          <Field label={texts.office}>
            {review.everywhere
              ? <input maxLength={16} defaultValue={office} className="uppercase"
                  onBlur={(e) => e.target.value.trim() && go({ office: e.target.value.trim().toUpperCase(), page: 0 })} />
              : (
                <select value={office} onChange={(e) => go({ office: e.target.value, page: 0 })}>
                  {offices.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              )}
          </Field>
          <Field label={c.statusFilter}>
            <select value={status} onChange={(e) => go({ status: e.target.value as AlertStatus | '', page: 0 })}>
              {ALERT_STATUSES.map((s) => <option key={s} value={s}>{c.statusNames[s]}</option>)}
              <option value="">{c.allStatuses}</option>
            </select>
          </Field>
          <Field label={c.typeFilter}>
            <select value={type} onChange={(e) => go({ type: e.target.value as AlertType | '', page: 0 })}>
              <option value="">{c.allTypes}</option>
              {ALERT_TYPES.map((t) => <option key={t} value={t}>{c.types[t]}</option>)}
            </select>
          </Field>
        </div>
      </Card>

      <Card padded={false} title={office ? fill(c.listAt, { office }) : c.listTitle}
        description={state.status === 'done' && state.items.length > 0 ? c.newestFirst : undefined}>
        {!office && <EmptyState icon="office" title={c.chooseOffice} />}
        {office && state.status === 'loading' && <div className="card-pad"><Skeleton lines={5} /></div>}
        {state.status === 'error' && (
          <EmptyState icon="alert" title={c.loadFailed}
            action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>
            {state.error}
          </EmptyState>
        )}
        {office && state.status === 'done' && state.items.length === 0 && (
          <EmptyState icon="checkCircle" title={page > 0 ? c.noMore : status === 'OPEN' ? c.noneOpen : c.noneMatch}>
            {status === 'OPEN' && page === 0 ? c.noneOpenText : undefined}
          </EmptyState>
        )}
        {state.status === 'done' && state.items.length > 0 && (
          <table className="table table-responsive">
            <thead>
              <tr>
                <th scope="col">{c.type}</th>
                <th scope="col">{c.parcels}</th>
                <th scope="col">{c.evidence}</th>
                <th scope="col">{c.raised}</th>
                <th scope="col">{c.status}</th>
                <th scope="col"><span className="visually-hidden">{c.review}</span></th>
              </tr>
            </thead>
            <tbody>
              {state.items.map((alert) => (
                <tr key={alert.id} data-alert={alert.id}>
                  <td data-label={c.type}>
                    <Badge tone={alert.type === 'OVERLAP' ? 'warning' : 'danger'} icon="alert">{c.types[alert.type]}</Badge>
                  </td>
                  <td data-label={c.parcels}>
                    <span className="mono">{alert.a.reference}</span> <span className="muted">v{alert.a.versionNo}</span>
                    <br />
                    <span className="mono">{alert.b.reference}</span> <span className="muted">v{alert.b.versionNo}</span>
                  </td>
                  <td data-label={c.evidence} className="num">
                    {alert.intersectionM2 !== null
                      ? fill(c.evidenceShort, { area: alert.intersectionM2.toFixed(2), a: percent(alert.a.share), b: percent(alert.b.share) })
                      : alert.normalizedReference}
                  </td>
                  <td data-label={c.raised} className="num">{new Date(alert.raisedAt).toLocaleString(lang)}</td>
                  <td data-label={c.status}><Badge tone={STATUS_TONE[alert.status]}>{c.statusNames[alert.status]}</Badge></td>
                  <td>
                    <a className="btn btn-sm btn-secondary" href={href(['alerts', alert.id])}>
                      {alert.status === 'OPEN' ? c.review : c.open}
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {state.status === 'done' && (page > 0 || hasMore) && (
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
