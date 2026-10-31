// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The documents of a counter deposit (ADR-040 §4.7). Storing one inscribes nothing and authorises
// nothing. Nothing is ever deleted: a correction replaces a file with a reason, and the original
// stays listed and readable. A closed deposit takes no more, and keeps what it has.
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
import type { DepositDocument } from './api'

/** A refused file says why, in the words an application's documents use (ADR-033). */
function refusal(texts: Texts, error: unknown): string {
  const codes: Record<string, string> = texts.documents.codes
  return error instanceof ApiError && error.code in codes ? codes[error.code] : errorMessage(texts, error)
}

export function DepositDocuments({ texts, lang, transactionId, open, canAdd, onChanged }: {
  texts: Texts; lang: Lang; transactionId: string; open: boolean; canAdd: boolean; onChanged: () => void
}) {
  const k = texts.register
  const dialog = useDialog()
  const [rows, setRows] = useState<DepositDocument[]>([])
  const [category, setCategory] = useState<string>(DOCUMENT_CATEGORIES[0])
  const [file, setFile] = useState<File | null>(null)
  const [chooser, setChooser] = useState(0)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    let current = true
    registerApi.documents(transactionId)
      .then((loaded) => { if (current) setRows(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [transactionId, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  const done = () => { setReloads((n) => n + 1); onChanged() }

  const add = async () => {
    if (!file) return
    setBusy(true)
    setFailure(null)
    try {
      await registerApi.addDocument(transactionId, category, file)
      setFile(null)
      setChooser((n) => n + 1)
      done()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const replace = async (original: DepositDocument, replacement: File) => {
    const reason = await dialog.reason({
      title: fill(k.replaceDocumentTitle, { name: original.originalName }), message: k.replaceDocumentText, label: k.reason, hint: k.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort, confirmLabel: k.replaceDocument, cancelLabel: texts.common.cancel,
    })
    if (reason === null) return
    setBusy(true)
    setFailure(null)
    try {
      await registerApi.replaceDocument(transactionId, original.id, original.category, replacement, reason.trim())
      done()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div data-deposit-documents={transactionId}>
      <h4>{k.documentsTitle}</h4>
      <p className="small muted">{k.documentsIntro}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {rows.length === 0 ? <p className="small muted">{k.noDocuments}</p> : (
        <ul className="plain-list">
          {rows.map((d) => (
            <li key={d.id} data-document={d.originalName} data-replaced={d.replacedById !== null}>
              <a href={registerApi.documentUrl(transactionId, d.id)}>{d.originalName}</a>
              {' '}<span className="small">{(k.documentCategories as Record<string, string>)[d.category] ?? d.category}</span>
              {d.replacedById !== null && <> <Badge tone="warning">{k.replaced}</Badge></>}
              <div className="small muted">
                {d.uploadedByName} · <span className="num">{new Date(d.uploadedAt).toLocaleString(lang)}</span> · <span className="mono">{d.sha256.slice(0, 16)}…</span>
              </div>
              {d.replaceReason && <div className="small">{fill(k.replacesBecause, { reason: d.replaceReason })}</div>}
              {open && canAdd && d.replacedById === null && (
                <label className="small">
                  {fill(k.replaceFile, { name: d.originalName })}{' '}
                  <input type="file" accept=".pdf,.jpg,.jpeg,.png" disabled={busy}
                    onChange={(e) => { const chosen = e.target.files?.[0]; e.target.value = ''; if (chosen) void replace(d, chosen) }} />
                </label>
              )}
            </li>
          ))}
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
