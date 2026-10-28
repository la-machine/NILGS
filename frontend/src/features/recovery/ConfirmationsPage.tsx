// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Identity confirmations (ADR-028): a second-factor reset an administrator requested for a
// person at the supervisor's offices. The supervisor checks the identity out of band, says
// how, and confirms; the database performs the reset. Confirming is irreversible, so it is
// confirmed once more in a dialog.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { recoveryApi } from './api'
import type { ResetToConfirm } from './api'

export function ConfirmationsPage({ ctx }: { ctx: PageContext }) {
  const { texts, run } = ctx
  const c = texts.confirmations
  const dialog = useDialog()
  const [requests, setRequests] = useState<ResetToConfirm[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const load = async () => setRequests(await recoveryApi.toConfirm())
  useEffect(() => { void run(load) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const reject = async (request: ResetToConfirm) => {
    const why = await dialog.reason({
      title: c.rejectTitle, message: fill(c.rejectMessage, { name: request.displayName }),
      label: texts.common.decisionReason, hint: texts.common.auditedHint, minLength: 3, tooShort: texts.common.tooShort,
      confirmLabel: texts.reject, cancelLabel: texts.common.cancel,
    })
    if (why === null) return
    await run(async () => {
      await recoveryApi.reject(request.id, why)
      setNotice(texts.done)
      await load()
    })
  }

  const confirm = async (request: ResetToConfirm, check: string) => {
    const ok = await dialog.confirm({
      title: c.confirmTitle, message: fill(c.confirmMessage, { name: request.displayName, check }),
      confirmLabel: texts.confirmReset, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (!ok) return
    await run(async () => {
      await recoveryApi.confirm(request.id, check)
      setNotice(texts.resetConfirmed)
      await load()
    })
  }

  return (
    <div className="page">
      <PageHeader title={texts.confirmationsTitle} description={texts.confirmationsIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.confirmations }]} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}
      <Card title={c.waiting} icon="userCheck">
        {requests === null ? <Skeleton lines={3} /> : requests.length === 0 ? <EmptyState icon="checkCircle" title={c.none}>{c.noneText}</EmptyState> : (
          <ul className="request-list">
            {requests.map((r) => (
              <ResetConfirmation key={r.id} ctx={ctx} request={r} onConfirm={(check) => void confirm(r, check)} onReject={() => void reject(r)} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

function ResetConfirmation({ ctx, request, onConfirm, onReject }: {
  ctx: PageContext; request: ResetToConfirm; onConfirm: (identityCheck: string) => void; onReject: () => void
}) {
  const { texts, lang } = ctx
  const [check, setCheck] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onConfirm(check)
  }
  return (
    <li className="request stacked" data-reset={request.id}>
      <div className="request-main">
        <p className="request-title"><strong>{request.displayName}</strong> <span className="muted">{request.email}</span></p>
        <p className="request-meta">
          <Icon name="office" size={14} /> {request.offices.join(', ')}
          {' · '}<span className="num">{new Date(request.requestedAt).toLocaleString(lang)}</span>
        </p>
        <p className="request-reason">{request.reason}</p>
      </div>
      <form onSubmit={submit} className="form-row">
        <Field label={texts.identityCheck} hint={texts.confirmations.checkHint} className="grow">
          <input required minLength={3} maxLength={200} value={check} onChange={(e) => setCheck(e.target.value)} />
        </Field>
        <Button type="submit" variant="danger">{texts.confirmReset}</Button>
        <Button variant="ghost" onClick={onReject}>{texts.reject}</Button>
      </form>
    </li>
  )
}
