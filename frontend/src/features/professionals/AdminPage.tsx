// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The administrators' page of professional accounts (#/professionals): the requests waiting for a
// second administrator, then the directory, newest account first (ADR-041 D20), searched and filtered
// on the server. The filters and the page live in the address (#/professionals?q=...&pending=ACTIVATION),
// so a reload, the back button and the dashboard's links keep them. Reading needs
// admin.professional.read; deciding needs admin.professional.approve, and the database refuses the
// requester and the verifier whatever the screen shows.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { directoryFilterFrom, PENDING_STEPS, PROFESSIONS, professionalApi, SEARCH_MAX, SEARCH_MIN, STATUSES } from './api'
import type { ChangeRequest, DirectoryFilter, DirectoryPage } from './api'
import { useDecision } from './decisions'
import { RequestItem } from './RequestItem'
import { STATUS_TONE } from './tones'

export function AdminPage({ ctx, params = new URLSearchParams() }: { ctx: PageContext; params?: URLSearchParams }) {
  const { texts, session, lang, run } = ctx
  const p = texts.professionals
  const [queue, setQueue] = useState<ChangeRequest[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const loadQueue = async () => setQueue(await professionalApi.pending())
  useEffect(() => { void run(loadQueue) }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const decision = useDecision(ctx, async () => { setNotice(p.decided); await loadQueue() })

  return (
    <div className="page">
      <PageHeader title={p.adminTitle} description={p.adminIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: href(['dashboard']) }, { label: p.adminTitle }]} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}
      <Card title={p.queueTitle} icon="userCheck" description={p.queueIntro}>
        {queue === null ? <Skeleton lines={3} /> : queue.length === 0 ? <EmptyState icon="checkCircle" title={p.noQueue} /> : (
          <ul className="request-list" data-professional-queue>
            {queue.map((r) => (
              <RequestItem key={r.id} texts={texts} lang={lang} request={r} viewerId={session.userId} showName
                canDecide={can(session, 'admin.professional.approve')} onApprove={() => void decision.approve(r)} onReject={() => void decision.reject(r)} />
            ))}
          </ul>
        )}
      </Card>
      {/* A new address is a new directory: its search box and its result start from the address. */}
      <Directory key={params.toString()} texts={texts} lang={lang} run={ctx.run} params={params} />
    </div>
  )
}

function Directory({ texts, lang, run, params }: { texts: Texts; lang: Lang; run: PageContext['run']; params: URLSearchParams }) {
  const p = texts.professionals
  const { filter, page } = directoryFilterFrom(params)
  const [result, setResult] = useState<DirectoryPage | null>(null)
  const [text, setText] = useState(filter.q)
  const [office, setOffice] = useState(filter.office)
  useEffect(() => {
    void run(async () => setResult(await professionalApi.directory(filter, page)))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: Partial<DirectoryFilter>, to = 0) => {
    const f = { ...filter, ...next }
    navigate(href(['professionals'], {
      q: f.q.trim() || undefined, status: f.status || undefined, profession: f.profession || undefined,
      office: f.office.trim().toUpperCase() || undefined, pending: f.pending || undefined, page: to > 0 ? String(to) : undefined,
    }))
  }
  const trimmed = text.trim()
  const tooShort = trimmed.length > 0 && trimmed.length < SEARCH_MIN
  const search = (e: FormEvent) => { e.preventDefault(); if (!tooShort) go({ q: trimmed, office }) }
  const filtered = Object.values(filter).some(Boolean)
  const pages = result ? Math.max(Math.ceil(result.total / result.size), 1) : 1

  return (
    <Card title={p.directoryTitle} icon="users" padded={false} description={p.newestFirst}>
      <form className="filter-bar card-pad" role="search" aria-label={p.directoryFilters} onSubmit={search} data-directory-filters>
        <Field label={p.searchLabel} error={tooShort ? fill(p.searchTooShort, { n: SEARCH_MIN }) : null}>
          <input type="search" value={text} maxLength={SEARCH_MAX} onChange={(e) => setText(e.target.value)} />
        </Field>
        <Field label={p.officeFilter}>
          <input value={office} maxLength={32} onChange={(e) => setOffice(e.target.value)} />
        </Field>
        <Field label={p.status}>
          <select value={filter.status} onChange={(e) => go({ status: e.target.value as DirectoryFilter['status'] })}>
            <option value="">{p.all}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{p.statuses[s]}</option>)}
          </select>
        </Field>
        <Field label={p.profession}>
          <select value={filter.profession} onChange={(e) => go({ profession: e.target.value as DirectoryFilter['profession'] })}>
            <option value="">{p.all}</option>
            {PROFESSIONS.map((s) => <option key={s} value={s}>{p.professions[s]}</option>)}
          </select>
        </Field>
        <Field label={p.pendingFilter}>
          <select value={filter.pending} onChange={(e) => go({ pending: e.target.value as DirectoryFilter['pending'] })}>
            <option value="">{p.all}</option>
            {PENDING_STEPS.map((s) => <option key={s} value={s}>{p.pendingSteps[s]}</option>)}
          </select>
        </Field>
        <div className="form-actions">
          <Button type="submit" icon="search" disabled={tooShort}>{p.searchAction}</Button>
          {filtered && (
            <Button variant="ghost" onClick={() => { setText(''); setOffice(''); navigate(href(['professionals'])) }}>{p.clearFilters}</Button>
          )}
        </div>
      </form>
      {result === null && <div className="card-pad"><Skeleton lines={4} /></div>}
      {result !== null && <p className="card-pad small muted" data-directory-total={result.total}>{fill(p.totalAccounts, { n: result.total })}</p>}
      {result !== null && result.items.length === 0 && <EmptyState icon="users" title={filtered ? p.noMatch : p.noProfiles} />}
      {result !== null && result.items.length > 0 && (
        <table className="table table-responsive" data-professionals>
          <thead>
            <tr>
              <th scope="col">{p.name}</th><th scope="col">{p.profession}</th><th scope="col">{p.status}</th>
              <th scope="col">{p.offices}</th><th scope="col">{p.requestedAt}</th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((r) => (
              <tr key={r.userId} data-professional={r.email}>
                <td data-label={p.name}>
                  <a className="strong" href={href(['professionals', r.userId])}>{r.displayName}</a>
                  <div className="small muted">{r.email}</div>
                </td>
                <td data-label={p.profession}>{p.professions[r.profession]}</td>
                <td data-label={p.status}><Badge tone={STATUS_TONE[r.status]}>{p.statuses[r.status]}</Badge></td>
                <td data-label={p.offices}>{r.offices.length > 0 ? r.offices.join(', ') : p.none}</td>
                <td data-label={p.requestedAt} className="num">{new Date(r.requestedAt).toLocaleString(lang)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {result !== null && pages > 1 && (
        <nav className="pagination card-pad" aria-label={p.pages}>
          <Button size="sm" variant="ghost" icon="chevronLeft" disabled={page === 0} onClick={() => go({}, page - 1)}>{p.previous}</Button>
          <span className="muted" data-directory-page>{fill(p.pageOf, { n: page + 1, total: pages })}</span>
          <Button size="sm" variant="ghost" disabled={page + 1 >= pages} onClick={() => go({}, page + 1)}>{p.next}</Button>
        </nav>
      )}
    </Card>
  )
}
