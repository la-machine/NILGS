// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The parcels of an office, newest first, with a search by reference or locality. The search
// lives in the address (#/parcels?office=YDE1&q=...), so the back button and a reload keep
// it. Pages follow the server's page size; there is no total count in the API, so none is
// shown.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { formatArea } from '../maps'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { canSomewhere, homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { parcelsApi } from './api'
import type { ParcelSummary } from './api'

export function ParcelListPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const p = texts.parcel
  const read = reach(session, 'parcel.read')
  const offices = read.everywhere ? homeOffices(session) : read.offices
  const office = (params.get('office') ?? offices[0] ?? '').toUpperCase()
  const q = params.get('q') ?? ''
  const page = Math.max(Number(params.get('page') ?? 0) || 0, 0)

  const [officeInput, setOfficeInput] = useState(office)
  const [qInput, setQInput] = useState(q)
  const [state, setState] = useState<{ status: 'idle' | 'loading' | 'error' | 'done'; items: ParcelSummary[]; error?: string }>(
    { status: 'idle', items: [] })
  const [firstPageSize, setFirstPageSize] = useState<number | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!office) return
    let current = true
    // oxlint-disable-next-line react/set-state-in-effect
    setState((s) => ({ ...s, status: 'loading' }))
    parcelsApi.search(office, q, page)
      .then((items) => {
        if (!current) return
        setState({ status: 'done', items })
        if (page === 0) setFirstPageSize(items.length)
      })
      .catch((e) => { if (current) setState({ status: 'error', items: [], error: errorMessage(texts, e) }) })
    return () => { current = false }
  }, [office, q, page, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: { office?: string; q?: string; page?: number }) =>
    navigate(href(['parcels'], {
      office: next.office ?? office, q: (next.q ?? q) || undefined, page: next.page ? String(next.page) : undefined,
    }))

  const submit = (e: FormEvent) => {
    e.preventDefault()
    go({ office: officeInput.trim().toUpperCase(), q: qInput.trim(), page: 0 })
  }

  const canCreate = canSomewhere(session, 'parcel.create')
  const hasMore = state.status === 'done' && firstPageSize !== null && firstPageSize >= 5 && state.items.length === firstPageSize

  return (
    <div className="page">
      <PageHeader title={texts.parcelsTitle} description={p.listIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.parcels }]}
        actions={canCreate && <a className="btn btn-primary" href={href(['parcels', 'new'])}>{texts.newParcel}</a>} />

      <Card padded>
        <form onSubmit={submit} className="filter-bar" role="search" aria-label={p.searchLabel}>
          <Field label={texts.office} hint={read.everywhere ? p.officeAny : undefined}>
            {read.everywhere
              ? <input required maxLength={16} value={officeInput} onChange={(e) => setOfficeInput(e.target.value)}
                  list="parcel-offices" className="uppercase" />
              : (
                <select value={officeInput} onChange={(e) => setOfficeInput(e.target.value)}>
                  {offices.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              )}
          </Field>
          {read.everywhere && (
            <datalist id="parcel-offices">{offices.map((o) => <option key={o} value={o} />)}</datalist>
          )}
          <Field label={texts.parcelSearch} className="grow">
            <input type="search" maxLength={100} value={qInput} onChange={(e) => setQInput(e.target.value)} />
          </Field>
          <Button type="submit" variant="primary" icon="search">{texts.find}</Button>
        </form>
      </Card>

      <Card padded={false} title={office ? fill(q ? p.resultsFor : p.latestAt, { office, q }) : p.results}
        description={state.status === 'done' && state.items.length > 0 ? p.newestFirst : undefined}>
        {!office && <EmptyState icon="office" title={p.chooseOffice}>{p.chooseOfficeText}</EmptyState>}
        {office && state.status === 'loading' && <div className="card-pad"><Skeleton lines={5} /></div>}
        {state.status === 'error' && (
          <EmptyState icon="alert" title={p.loadFailed} action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>
            {state.error}
          </EmptyState>
        )}
        {state.status === 'done' && state.items.length === 0 && (
          <EmptyState icon="parcel" title={page > 0 ? p.noMore : q ? p.noMatch : p.noneYet}
            action={canCreate && page === 0 && !q ? <a className="btn btn-primary" href={href(['parcels', 'new'])}>{texts.newParcel}</a> : undefined}>
            {q ? p.noMatchText : undefined}
          </EmptyState>
        )}
        {state.status === 'done' && state.items.length > 0 && (
          <table className="table table-responsive">
            <thead>
              <tr>
                <th scope="col">{p.reference}</th>
                <th scope="col">{texts.locality}</th>
                <th scope="col">{p.officeShort}</th>
                <th scope="col" className="num-col">{p.areaShort}</th>
                <th scope="col">{texts.version}</th>
                <th scope="col">{p.lastChange}</th>
              </tr>
            </thead>
            <tbody>
              {state.items.map((parcel) => (
                <tr key={parcel.reference}>
                  <td data-label={p.reference}>
                    <a href={href(['parcels', parcel.reference])} className="mono strong">{parcel.reference}</a>
                  </td>
                  <td data-label={texts.locality}>{parcel.locality}</td>
                  <td data-label={p.officeShort}>{parcel.officeCode}</td>
                  <td data-label={p.areaShort} className="num-col num">{formatArea(parcel.areaM2)}</td>
                  <td data-label={texts.version}>
                    <Badge tone={parcel.currentVersion > 1 ? 'info' : 'neutral'}>v{parcel.currentVersion}</Badge>
                  </td>
                  <td data-label={p.lastChange} className="num">{new Date(parcel.updatedAt).toLocaleString(lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {state.status === 'done' && (page > 0 || hasMore) && (
          <nav className="pagination" aria-label={p.pages}>
            <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => go({ page: page - 1 })}>{p.previous}</Button>
            <span className="muted">{fill(p.pageN, { n: page + 1 })}</span>
            <Button size="sm" disabled={!hasMore} onClick={() => go({ page: page + 1 })}>{texts.more}</Button>
          </nav>
        )}
      </Card>
    </div>
  )
}
