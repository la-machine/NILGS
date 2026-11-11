// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The documents of a deposit (ADR-040 §4.7). Storing one inscribes nothing and authorises nothing.
// Nothing is ever deleted. Replacing a file is a request with a reason (ADR-041 D19): the original
// stays the current document until another conservator of the office approves, and every request,
// rejected or withdrawn, stays listed with its file. A closed deposit takes no more, and keeps what it has.
import { useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { DOCUMENT_CATEGORIES, REASON_MAX, REASON_MIN, registerApi } from './api'
import type { DepositDocument, DocumentReplacement } from './api'
import { REPLACEMENT_TONE } from './tones'

/** A refused file says why, in the words an application's documents use (ADR-033). */
function refusal(texts: Texts, error: unknown): string {
  const codes: Record<string, string> = texts.documents.codes
  return error instanceof ApiError && error.code in codes ? codes[error.code] : errorMessage(texts, error)
}

export function DepositDocuments({ texts, lang, transactionId, open, canAdd, canConfirm = false, viewerId = '', onChanged }: {
  texts: Texts; lang: Lang; transactionId: string; open: boolean; canAdd: boolean; canConfirm?: boolean; viewerId?: string; onChanged: () => void
}) {
  const k = texts.register
  const dialog = useDialog()
  const [rows, setRows] = useState<DepositDocument[]>([])
  const [requests, setRequests] = useState<DocumentReplacement[]>([])
  const [category, setCategory] = useState<string>(DOCUMENT_CATEGORIES[0])
  const [file, setFile] = useState<File | null>(null)
  const [chooser, setChooser] = useState(0)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([registerApi.documents(transactionId), registerApi.replacements(transactionId)])
      .then(([loaded, asked]) => { if (current) { setRows(loaded); setRequests(asked) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [transactionId, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  const done = () => { setReloads((n) => n + 1); onChanged() }

  const run = async (step: () => Promise<unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      await step()
      done()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const add = async () => {
    if (!file) return
    await run(async () => {
      await registerApi.addDocument(transactionId, category, file)
      setFile(null)
      setChooser((n) => n + 1)
    })
  }

  const ask = async (original: DepositDocument, replacement: File) => {
    const reason = await dialog.reason({
      title: fill(k.replaceDocumentTitle, { name: original.originalName }), message: k.replaceDocumentText, label: k.reason, hint: k.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort, confirmLabel: k.replaceDocument, cancelLabel: texts.common.cancel,
    })
    if (reason === null) return
    await run(() => registerApi.replaceDocument(transactionId, original.id, original.category, replacement, reason.trim()))
  }

  const approve = async (r: DocumentReplacement, original: DepositDocument) => {
    const yes = await dialog.confirm({
      title: fill(k.approveReplacementTitle, { name: original.originalName }), message: k.approveReplacementText,
      confirmLabel: k.approveReplacement, cancelLabel: texts.common.cancel,
    })
    if (yes) await run(() => registerApi.decideReplacement(transactionId, r.id, 'APPROVED'))
  }

  const reject = async (r: DocumentReplacement, original: DepositDocument) => {
    const reason = await dialog.reason({
      title: fill(k.rejectReplacementTitle, { name: original.originalName }), message: k.rejectReplacementText, label: k.reason, hint: k.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort, confirmLabel: k.rejectReplacement, cancelLabel: texts.common.cancel,
      tone: 'danger',
    })
    if (reason !== null) await run(() => registerApi.decideReplacement(transactionId, r.id, 'REJECTED', reason.trim()))
  }

  const withdraw = async (r: DocumentReplacement) => {
    const yes = await dialog.confirm({
      title: k.withdrawReplacementTitle, message: k.withdrawReplacementText, confirmLabel: k.withdrawReplacement, cancelLabel: texts.common.cancel,
    })
    if (yes) await run(() => registerApi.withdrawReplacement(transactionId, r.id))
  }

  return (
    <div data-deposit-documents={transactionId}>
      <h4>{k.documentsTitle}</h4>
      <p className="small muted">{k.documentsIntro}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {rows.length === 0 ? <p className="small muted">{k.noDocuments}</p> : (
        <ul className="plain-list">
          {rows.map((d) => {
            const asked = requests.filter((r) => r.documentId === d.id)
            const pending = asked.some((r) => r.status === 'PENDING')
            return (
              <li key={d.id} data-document={d.originalName} data-replaced={d.replacedById !== null}>
                <a href={registerApi.documentUrl(transactionId, d.id)}>{d.originalName}</a>
                {' '}<span className="small">{(k.documentCategories as Record<string, string>)[d.category] ?? d.category}</span>
                {d.replacedById !== null && <> <Badge tone="warning">{k.replaced}</Badge></>}
                <div className="small muted">
                  {d.uploadedByName} · <span className="num">{new Date(d.uploadedAt).toLocaleString(lang)}</span> · <span className="mono">{d.sha256.slice(0, 16)}…</span>
                </div>
                {d.replaceReason && <div className="small">{fill(k.replacesBecause, { reason: d.replaceReason })}</div>}
                {asked.length > 0 && (
                  <ul className="plain-list replacement-list">
                    {asked.map((r) => (
                      <li key={r.id} data-replacement={r.id} data-status={r.status}>
                        <Badge tone={REPLACEMENT_TONE[r.status]}>{(k.replacementStatuses as Record<string, string>)[r.status]}</Badge>{' '}
                        <a href={registerApi.replacementUrl(transactionId, r.id)}>{r.originalName}</a>
                        <div className="small muted">
                          {fill(k.replacementAskedBy, { name: r.requestedByName })}
                          {r.requestedAs === 'PROFESSIONAL' && ` (${k.replacementByProfessional})`} · <span className="num">{new Date(r.requestedAt).toLocaleString(lang)}</span>
                        </div>
                        <div className="small">{fill(k.replacementReason, { reason: r.reason })}</div>
                        {r.decidedByName && r.status !== 'PENDING' && r.status !== 'WITHDRAWN' && (
                          <div className="small muted">{fill(k.replacementDecidedBy, { name: r.decidedByName })}</div>
                        )}
                        {r.decisionReason && <div className="small">{fill(k.replacementDecisionReason, { reason: r.decisionReason })}</div>}
                        {r.status === 'PENDING' && (
                          <div className="form-actions start">
                            {r.requestedBy === viewerId
                              ? <><span className="small muted">{k.replacementAwaitsAnother}</span>
                                  <Button variant="ghost" disabled={busy} onClick={() => void withdraw(r)}>{k.withdrawReplacement}</Button></>
                              : canConfirm && open && <>
                                  <Button disabled={busy} onClick={() => void approve(r, d)}>{k.approveReplacement}</Button>
                                  <Button variant="ghost" disabled={busy} onClick={() => void reject(r, d)}>{k.rejectReplacement}</Button>
                                </>}
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
                      onChange={(e) => { const chosen = e.target.files?.[0]; e.target.value = ''; if (chosen) void ask(d, chosen) }} />
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
              {DOCUMENT_CATEGORIES.map((c) => <option key={c} value={c}>{(k.documentCategories as Record<string, string>)[c]}</option>)}
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
