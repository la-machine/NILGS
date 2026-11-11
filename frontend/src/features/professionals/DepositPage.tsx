// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One of the professional's own deposits (#/professional/deposits/<id>). Any other id answers "not
// found", a conflicting deposit's included. It shows the deposit's real status and, if a conflict
// names it, whether conservators are still reviewing it (ADR-041 D17); never the other deposit. While
// it is open they add documents, ask for one to be replaced (a conservator of the office approves: ADR-041
// D19), nothing ever deleted, and may withdraw it with a reason.
// Conservators decide it; nothing here registers anything.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { canOwn } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { depositsApi, DOCUMENT_CATEGORIES, isOpen, REASON_MAX, REASON_MIN } from './api'
import type { Deposit, OwnDocument, OwnReplacement } from './api'
import { CONFLICT_TONE, DEPOSIT_TONE, REPLACEMENT_TONE, refusal } from './tones'

export function DepositPage({ ctx, id }: { ctx: PageContext; id: string }) {
  const { texts, session, lang } = ctx
  const p = texts.professionals
  const dialog = useDialog()
  const [deposit, setDeposit] = useState<Deposit | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [reloads, setReloads] = useState(0)
  const reload = () => setReloads((n) => n + 1)

  useEffect(() => {
    let current = true
    depositsApi.one(id)
      .then((loaded) => { if (current) setDeposit(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  if (failure && !deposit) return <div className="page"><Alert tone="danger">{failure}</Alert></div>
  if (!deposit) return <div className="page" aria-busy="true"><Skeleton lines={6} /></div>
  const open = isOpen(deposit)

  const withdraw = async () => {
    const reason = await dialog.reason({
      title: p.withdrawTitle, message: p.withdrawText, label: p.reason, hint: p.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX,
      tooShort: p.reasonTooShort, confirmLabel: p.withdraw, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (reason === null) return
    try {
      setDeposit(await depositsApi.withdraw(id, reason.trim()))
      setFailure(null)
    } catch (error) {
      setFailure(refusal(texts, error))
    }
  }

  return (
    <div className="page" data-own-deposit={deposit.id} data-status={deposit.status} data-conflict={deposit.conflict}>
      <PageHeader title={deposit.titleNumber} description={p.depositsIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: p.ownTitle, href: href(['professional']) }, { label: deposit.titleNumber }]} />
      <Alert tone="warning" title={texts.register.demoTitle}>{texts.register.demoText}</Alert>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {deposit.conflict === 'UNDER_REVIEW' && <Alert tone="warning" title={p.conflictTitle}>{p.conflictText}</Alert>}
      <Card title={p.depositsTitle} icon="lock" description={open ? p.openText : undefined}>
        <dl className="properties">
          <div><dt>{p.status}</dt><dd><Badge tone={DEPOSIT_TONE[deposit.status]}>{p.depositStatuses[deposit.status]}</Badge></dd></div>
          {deposit.conflict !== 'NONE' && (
            <div><dt>{p.conflict}</dt><dd><Badge tone={CONFLICT_TONE[deposit.conflict]}>{p.conflictStates[deposit.conflict]}</Badge></dd></div>
          )}
          {deposit.reviewDueAt && <div><dt>{p.reviewDueAt}</dt><dd className="num">{new Date(deposit.reviewDueAt).toLocaleDateString(lang)}</dd></div>}
          {open && <div><dt>{p.actionRequired}</dt><dd>{p.actionsRequired[deposit.actionRequired]}</dd></div>}
          <div><dt>{p.title}</dt><dd className="mono">{deposit.titleNumber}</dd></div>
          <div><dt>{p.office}</dt><dd>{deposit.officeCode}</dd></div>
          <div><dt>{p.kind}</dt><dd>{(p.depositKinds as Record<string, string>)[deposit.kind] ?? deposit.kind}</dd></div>
          <div><dt>{p.depositor}</dt><dd>{deposit.depositorName}</dd></div>
          <div><dt>{p.depositedAt}</dt><dd className="num">{new Date(deposit.depositedAt).toLocaleString(lang)}</dd></div>
          {deposit.closedReason && <div><dt>{p.closedReason}</dt><dd>{deposit.closedReason}</dd></div>}
          {deposit.status === 'REFUSED' && !deposit.closedReason && <div><dt>{p.closedReason}</dt><dd>{p.refusedInConflict}</dd></div>}
        </dl>
        {open && canOwn(session, 'register.deposit.withdraw') && (
          <div className="form-actions start"><Button variant="danger" onClick={withdraw}>{p.withdraw}</Button></div>
        )}
      </Card>
      <Card title={p.documents} icon="file" description={texts.register.documentsIntro}>
        <OwnDocuments texts={texts} lang={lang} depositId={deposit.id} open={open} canAdd={canOwn(session, 'register.deposit.document')} onChanged={reload} />
      </Card>
    </div>
  )
}

function OwnDocuments({ texts, lang, depositId, open, canAdd, onChanged }: {
  texts: Texts; lang: Lang; depositId: string; open: boolean; canAdd: boolean; onChanged: () => void
}) {
  const p = texts.professionals
  const k = texts.register
  const dialog = useDialog()
  const [rows, setRows] = useState<OwnDocument[]>([])
  const [requests, setRequests] = useState<OwnReplacement[]>([])
  const [category, setCategory] = useState<string>(DOCUMENT_CATEGORIES[0])
  const [file, setFile] = useState<File | null>(null)
  const [chooser, setChooser] = useState(0)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([depositsApi.documents(depositId), depositsApi.replacements(depositId)])
      .then(([loaded, asked]) => { if (current) { setRows(loaded); setRequests(asked) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [depositId, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  const attempt = async (action: () => Promise<unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      await action()
      setReloads((n) => n + 1)
      onChanged()
      return true
    } catch (error) {
      setFailure(refusal(texts, error))
      return false
    } finally {
      setBusy(false)
    }
  }

  const add = async () => {
    if (!file) return
    if (await attempt(() => depositsApi.addDocument(depositId, category, file))) {
      setFile(null)
      setChooser((n) => n + 1)
    }
  }

  const replace = async (original: OwnDocument, replacement: File) => {
    const reason = await dialog.reason({
      title: fill(k.replaceDocumentTitle, { name: original.originalName }), message: k.replaceDocumentText, label: p.reason, hint: p.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: p.reasonTooShort, confirmLabel: k.replaceDocument, cancelLabel: texts.common.cancel,
    })
    if (reason === null) return
    await attempt(() => depositsApi.replaceDocument(depositId, original.id, original.category, replacement, reason.trim()))
  }

  const withdraw = async (r: OwnReplacement) => {
    const yes = await dialog.confirm({
      title: k.withdrawReplacementTitle, message: k.withdrawReplacementText, confirmLabel: k.withdrawReplacement, cancelLabel: texts.common.cancel,
    })
    if (yes) await attempt(() => depositsApi.withdrawReplacement(depositId, r.id))
  }

  return (
    <div data-own-documents={depositId}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {rows.length === 0 ? <p className="small muted">{k.noDocuments}</p> : (
        <ul className="plain-list">
          {rows.map((d) => {
            const asked = requests.filter((r) => r.documentId === d.id)
            const pending = asked.some((r) => r.status === 'PENDING')
            return (
            <li key={d.id} data-document={d.originalName} data-replaced={d.replacedById !== null}>
              <a href={depositsApi.documentUrl(depositId, d.id)}>{d.originalName}</a>
              {' '}<span className="small">{(p.categories as Record<string, string>)[d.category] ?? d.category}</span>
              {d.replacedById !== null && <> <Badge tone="warning">{k.replaced}</Badge></>}
              <div className="small muted">
                {!d.uploadedBySelf && <>{p.byConservation} · </>}
                <span className="num">{new Date(d.uploadedAt).toLocaleString(lang)}</span> · <span className="mono">{d.sha256.slice(0, 16)}…</span>
              </div>
              {d.replaceReason && <div className="small">{fill(k.replacesBecause, { reason: d.replaceReason })}</div>}
              {asked.length > 0 && (
                <ul className="plain-list replacement-list">
                  {asked.map((r) => (
                    <li key={r.id} data-replacement={r.id} data-status={r.status}>
                      <Badge tone={REPLACEMENT_TONE[r.status]}>{(k.replacementStatuses as Record<string, string>)[r.status]}</Badge>{' '}
                      <a href={depositsApi.replacementUrl(depositId, r.id)}>{r.originalName}</a>
                      <div className="small muted">
                        {!r.requestedBySelf && <>{p.byConservation} · </>}<span className="num">{new Date(r.requestedAt).toLocaleString(lang)}</span>
                      </div>
                      <div className="small">{fill(k.replacementReason, { reason: r.reason })}</div>
                      {r.decisionReason && <div className="small">{fill(k.replacementDecisionReason, { reason: r.decisionReason })}</div>}
                      {r.status === 'PENDING' && (
                        <div className="form-actions start">
                          <span className="small muted">{k.replacementAwaitsConservator}</span>
                          {r.requestedBySelf && canAdd && (
                            <Button variant="ghost" disabled={busy} onClick={() => void withdraw(r)}>{k.withdrawReplacement}</Button>
                          )}
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {open && canAdd && d.replacedById === null && !pending && (
                <label className="small">
                  {fill(k.replaceFile, { name: d.originalName })}{' '}
                  <input type="file" accept=".pdf,.jpg,.jpeg,.png" disabled={busy}
                    onChange={(e) => { const chosen = e.target.files?.[0]; e.target.value = ''; if (chosen) void replace(d, chosen) }} />
                </label>
              )}
            </li>
            )
          })}
        </ul>
      )}
      {open && canAdd && (
        <div className="form form-inline">
          <Field label={k.documentCategory}>
            <select value={category} onChange={(e) => setCategory(e.target.value)}>
              {DOCUMENT_CATEGORIES.map((c) => <option key={c} value={c}>{p.categories[c]}</option>)}
            </select>
          </Field>
          <Field label={k.documentFile} hint={k.documentFileHint}>
            <input key={chooser} type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </Field>
          <div className="form-actions start"><Button icon="plus" onClick={add} busy={busy} disabled={busy || !file}>{k.addDocument}</Button></div>
        </div>
      )}
    </div>
  )
}
