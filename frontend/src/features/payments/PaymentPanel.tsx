// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's DEMO payment, on their submitted application (MVP-08). NO REAL MONEY MOVES,
// and the card says so before anything else. It shows the DEMO fee and whether the completeness
// review waits for it; while it is due, the applicant picks what the simulated provider will do
// and pays once (Idempotency-Key, kept for a retry of the same attempt). The provider answers
// later, so a pending payment is read again every few seconds for a short while.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { paymentsApi, SCENARIOS } from './api'
import type { PaymentView, Scenario } from './api'
import { formatAmount } from './format'
import { STANDING_TONE, STATUS_TONE } from './tones'

const POLL_MS = 2000
const POLLS = 15

export function PaymentPanel({ texts, lang, applicationId }: { texts: Texts; lang: Lang; applicationId: string }) {
  const p = texts.payments
  const [view, setView] = useState<PaymentView | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [scenario, setScenario] = useState<Scenario>('SUCCEED')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)
  const [polls, setPolls] = useState(0)

  useEffect(() => {
    let current = true
    paymentsApi.view(applicationId)
      .then((loaded) => { if (current) setView(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [applicationId]) // eslint-disable-line react-hooks/exhaustive-deps

  // A pending payment: read it again until the provider's answer arrives, a few times only.
  const waiting = view?.standing === 'PENDING' && polls < POLLS
  useEffect(() => {
    if (!waiting) return undefined
    const timer = setTimeout(() => {
      paymentsApi.view(applicationId).then((loaded) => { setView(loaded); setPolls((n) => n + 1) }).catch(() => setPolls(POLLS))
    }, POLL_MS)
    return () => clearTimeout(timer)
  }, [waiting, polls, applicationId])

  if (failure) return <Card title={p.title} icon="card"><Alert tone="danger">{failure}</Alert></Card>
  if (!view) return <Card title={p.title} icon="card"><Skeleton lines={3} /></Card>
  if (view.standing === 'NOT_REQUIRED' && view.history.length === 0) return null

  const pay = async () => {
    setBusy(true)
    setFailure(null)
    try {
      setView(await paymentsApi.pay(applicationId, scenario, key))
      setPolls(0)
      setKey(crypto.randomUUID())
    } catch (error) {
      setFailure(errorMessage(texts, error))
      setKey(crypto.randomUUID())
    } finally {
      setBusy(false)
    }
  }

  const fee = view.fee
  const current = view.current
  return (
    <Card title={p.title} icon="card" description={p.intro}
      actions={<Badge tone={STANDING_TONE[view.standing]}>{p.standing[view.standing]}</Badge>}>
      <Alert tone="warning" title={p.demoTitle}>{p.demoText}</Alert>
      {fee && (
        <dl className="properties">
          <div><dt>{p.fee}</dt><dd className="num strong">{formatAmount(lang, fee.amount, fee.currency)}</dd></div>
          <div><dt>{p.feeLabel}</dt><dd>{lang === 'fr' ? fee.labelFr : fee.labelEn}</dd></div>
          <div><dt>{p.gate}</dt><dd>{view.requiredBeforeAcceptance ? p.gateOn : p.gateOff}</dd></div>
        </dl>
      )}
      {current && (
        <dl className="properties">
          <div><dt>{p.reference}</dt><dd className="mono">{current.reference}</dd></div>
          <div><dt>{p.status}</dt><dd><Badge tone={STATUS_TONE[current.status]}>{p.statuses[current.status]}</Badge></dd></div>
          {current.status === 'PENDING' && (
            <div><dt>{p.expiresAt}</dt><dd className="num">{new Date(current.expiresAt).toLocaleString(lang)}</dd></div>
          )}
          {current.settledAt && <div><dt>{p.settledAt}</dt><dd className="num">{new Date(current.settledAt).toLocaleString(lang)}</dd></div>}
        </dl>
      )}
      {view.standing === 'PAID' && <Alert tone="success">{p.paidText}</Alert>}
      {view.standing === 'PENDING' && (
        <Alert tone="info" title={p.pendingTitle}
          action={!waiting ? <Button size="sm" icon="refresh" onClick={() => setPolls(0)}>{p.refresh}</Button> : undefined}>
          {p.pendingText}
        </Alert>
      )}
      {view.standing === 'DUE' && fee && (
        <div className="form">
          {current && current.status !== 'PENDING' && (
            <Alert tone={current.status === 'FAILED' ? 'danger' : 'warning'}>{p.again[current.status as 'FAILED' | 'EXPIRED']}</Alert>
          )}
          <Field label={p.scenario} hint={p.scenarioHint}>
            <select value={scenario} onChange={(e) => setScenario(e.target.value as Scenario)}>
              {SCENARIOS.map((s) => <option key={s} value={s}>{p.scenarios[s]}</option>)}
            </select>
          </Field>
          <div className="form-actions start">
            <Button variant="primary" icon="card" onClick={pay} busy={busy} disabled={busy}>
              {fill(p.pay, { amount: formatAmount(lang, fee.amount, fee.currency) })}
            </Button>
          </div>
        </div>
      )}
      {view.history.length > 1 && (
        <details className="payment-history">
          <summary>{fill(p.history, { n: view.history.length })}</summary>
          <ul className="plain-list">
            {view.history.map((r) => (
              <li key={r.id}>
                <span className="mono">{r.reference}</span> · <Badge tone={STATUS_TONE[r.status]}>{p.statuses[r.status]}</Badge>
                {' '}· <span className="num">{new Date(r.createdAt).toLocaleString(lang)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </Card>
  )
}
