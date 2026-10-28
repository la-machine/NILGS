// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The finance officer's reconciliation (MVP-08, ADR-037), for holders of payment.reconcile.
// NO REAL MONEY MOVES. The counts of DEMO payments, then one of three lists, chosen in the
// address (#/payments?view=open|resolved|requests&status=PAID): the open exceptions (money a
// provider reported that matches no pending request of the same amount), the resolved ones, or
// the payment requests with the report that settled them. Resolving an exception records a
// simulated refund or that nothing is to be done, with a note; it never pays a request.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { useDialog } from '../../shared/ui/dialogContext'
import { errorMessage } from '../../shared/ui/errorMessage'
import { NOTE_MAX, NOTE_MIN, paymentsApi, RESOLUTIONS } from './api'
import type { FinanceException, FinanceRequest, FinanceSummary, PaymentStatus, Resolution } from './api'
import { formatAmount } from './format'
import { STATUS_TONE } from './tones'

const VIEWS = ['open', 'resolved', 'requests'] as const
type View = (typeof VIEWS)[number]
const STATUSES: PaymentStatus[] = ['PENDING', 'PAID', 'FAILED', 'EXPIRED']
/** Rows per page, as the server sends them (SNIGF_PAYMENT_PAGE_SIZE). */
const PAGE_SIZE = 50

export function FinancePage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, lang } = ctx
  const f = texts.finance
  const view = (VIEWS.find((v) => v === params.get('view')) ?? 'open') as View
  const status = (STATUSES.find((s) => s === params.get('status')) ?? '') as PaymentStatus | ''
  const page = Math.max(Number(params.get('page') ?? 0) || 0, 0)
  const [summary, setSummary] = useState<FinanceSummary | null>(null)
  const [rows, setRows] = useState<FinanceException[] | FinanceRequest[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    paymentsApi.summary()
      .then((loaded) => { if (current) setSummary(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let current = true
    const load = view === 'requests' ? paymentsApi.requests(status, page) : paymentsApi.exceptions(view === 'resolved', page)
    load.then((loaded) => { if (current) { setRows(loaded); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [view, status, page, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const go = (next: { view?: View; status?: PaymentStatus | ''; page?: number }) => {
    setRows(null)
    navigate(href(['payments'], {
      view: next.view ?? view,
      status: (next.view ?? view) === 'requests' ? ((next.status ?? status) || undefined) : undefined,
      page: next.page ? String(next.page) : undefined,
    }))
  }
  const hasMore = rows !== null && rows.length === PAGE_SIZE

  return (
    <div className="page">
      <PageHeader title={f.title} description={f.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.payments }]} />
      <Alert tone="warning" title={texts.payments.demoTitle}>{f.demoText}</Alert>
      {failure && (
        <Alert tone="danger" action={<Button size="sm" icon="refresh" onClick={() => setAttempt((n) => n + 1)}>{texts.common.retry}</Button>}>
          {failure}
        </Alert>
      )}
      <Card title={f.summaryTitle} icon="card">
        {!summary ? <Skeleton lines={2} /> : (
          <ul className="stage-tiles">
            {([['pending', summary.pending], ['paid', summary.paid], ['failed', summary.failed], ['expired', summary.expired],
              ['openExceptions', summary.openExceptions]] as const).map(([k, n]) => (
              <li key={k} className={k === 'openExceptions' && n > 0 ? 'stage-tile late' : 'stage-tile'} data-count={k}>
                <span className="stage-open num">{n}</span>
                <span className="stage-name">{f.counts[k]}</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card padded={false}>
        <div className="filter-bar card-pad" role="search" aria-label={f.filters}>
          <Field label={f.show}>
            <select value={view} onChange={(e) => go({ view: e.target.value as View, page: 0 })}>
              {VIEWS.map((v) => <option key={v} value={v}>{f.views[v]}</option>)}
            </select>
          </Field>
          {view === 'requests' && (
            <Field label={f.status}>
              <select value={status} onChange={(e) => go({ status: e.target.value as PaymentStatus | '', page: 0 })}>
                <option value="">{f.allStatuses}</option>
                {STATUSES.map((s) => <option key={s} value={s}>{texts.payments.statuses[s]}</option>)}
              </select>
            </Field>
          )}
        </div>
        {rows === null && !failure && <div className="card-pad"><Skeleton lines={4} /></div>}
        {rows && rows.length === 0 && <EmptyState icon="checkCircle" title={f.none[view]} />}
        {rows && rows.length > 0 && view === 'requests' && <Requests texts={texts} lang={lang} rows={rows as FinanceRequest[]} />}
        {rows && rows.length > 0 && view !== 'requests' && (
          <ul className="exception-list">
            {(rows as FinanceException[]).map((e) => (
              <ExceptionItem key={e.id} texts={texts} lang={lang} exception={e} onResolved={() => setAttempt((n) => n + 1)} />
            ))}
          </ul>
        )}
        {rows && (page > 0 || hasMore) && (
          <nav className="pagination card-pad" aria-label={f.pages}>
            <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => go({ page: page - 1 })}>{texts.cases.previous}</Button>
            <span className="muted">{fill(texts.cases.pageN, { n: page + 1 })}</span>
            <Button size="sm" disabled={!hasMore} onClick={() => go({ page: page + 1 })}>{texts.more}</Button>
          </nav>
        )}
      </Card>
    </div>
  )
}

function Requests({ texts, lang, rows }: { texts: Texts; lang: Lang; rows: FinanceRequest[] }) {
  const f = texts.finance
  const p = texts.payments
  return (
    <table className="table table-responsive">
      <thead>
        <tr>
          <th scope="col">{p.reference}</th>
          <th scope="col">{f.application}</th>
          <th scope="col" className="num-col">{f.amount}</th>
          <th scope="col">{f.status}</th>
          <th scope="col">{f.created}</th>
          <th scope="col">{f.transaction}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id} data-payment={r.reference}>
            <td data-label={p.reference} className="mono strong">{r.reference}</td>
            <td data-label={f.application} className="mono">{r.applicationReference}</td>
            <td data-label={f.amount} className="num-col num">{formatAmount(lang, r.amount, r.currency)}</td>
            <td data-label={f.status}><Badge tone={STATUS_TONE[r.status]}>{p.statuses[r.status]}</Badge></td>
            <td data-label={f.created} className="num">{new Date(r.createdAt).toLocaleString(lang)}</td>
            <td data-label={f.transaction} className="mono small">{r.transactionId ?? <span className="muted">—</span>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function ExceptionItem({ texts, lang, exception: e, onResolved }: {
  texts: Texts; lang: Lang; exception: FinanceException; onResolved: () => void
}) {
  const f = texts.finance
  const dialog = useDialog()
  const [resolution, setResolution] = useState<Resolution>('REFUND_SIMULATED')
  const [note, setNote] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const length = note.trim().length
  const noteError = touched && (length < NOTE_MIN || length > NOTE_MAX) ? fill(f.noteBounds, { min: NOTE_MIN, max: NOTE_MAX }) : null

  const resolve = async () => {
    setTouched(true)
    if (length < NOTE_MIN || length > NOTE_MAX) return
    const ok = await dialog.confirm({
      title: f.confirmTitle, message: fill(f.confirmText, { transaction: e.transactionId, resolution: f.resolutions[resolution] }),
      confirmLabel: f.resolve, cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      await paymentsApi.resolve(e.id, { resolution, note: note.trim() }, key)
      onResolved()
    } catch (error) {
      setFailure(errorMessage(texts, error))
      setKey(crypto.randomUUID())
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="exception-item" data-exception={e.transactionId} data-outcome={e.outcome}>
      <div className="exception-head">
        <Badge tone={e.resolution ? 'neutral' : 'danger'} icon="alert">{f.outcomes[e.outcome]}</Badge>
        <span className="mono small">{e.transactionId}</span>
        <span className="muted num small">{new Date(e.receivedAt).toLocaleString(lang)}</span>
      </div>
      <p className="small">{f.explain[e.outcome]}</p>
      <dl className="properties">
        <div><dt>{f.reported}</dt><dd><span className="mono">{e.reportedReference}</span> · <span className="num">{formatAmount(lang, e.reportedAmount, e.reportedCurrency)}</span></dd></div>
        <div>
          <dt>{f.expected}</dt>
          <dd>{e.requestReference
            ? <><span className="mono">{e.requestReference}</span> · <span className="num">{formatAmount(lang, e.expectedAmount ?? 0, 'XAF')}</span>
              {e.applicationReference && <> · <span className="mono">{e.applicationReference}</span></>}</>
            : <span className="muted">{f.noRequest}</span>}</dd>
        </div>
        {e.resolution && (
          <div>
            <dt>{f.resolution}</dt>
            <dd>{f.resolutions[e.resolution]} · {e.resolvedByName} · <span className="num">{e.resolvedAt && new Date(e.resolvedAt).toLocaleString(lang)}</span>
              <br /><span className="muted">{e.resolutionNote}</span></dd>
          </div>
        )}
      </dl>
      {!e.resolution && (
        <div className="form">
          {failure && <Alert tone="danger">{failure}</Alert>}
          <Field label={f.resolution}>
            <select value={resolution} onChange={(ev) => setResolution(ev.target.value as Resolution)}>
              {RESOLUTIONS.map((r) => <option key={r} value={r}>{f.resolutions[r]}</option>)}
            </select>
          </Field>
          <Field label={f.note} hint={f.noteHint} error={noteError} required requiredLabel={texts.common.required}>
            <textarea rows={2} maxLength={NOTE_MAX + 50} value={note} onChange={(ev) => setNote(ev.target.value)}
              onBlur={() => setTouched(true)} />
          </Field>
          <div className="form-actions start">
            <Button icon="checkCircle" onClick={resolve} busy={busy} disabled={busy}>{f.resolve}</Button>
          </div>
        </div>
      )}
    </li>
  )
}
