// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A draft's form (MVP-02): the parcel (optional while drafting, required to submit), the fields
// of its type, and its documents (one upload per category, once the draft is saved). "Save the
// draft" keeps it as it is; "Submit" saves, confirms, then submits once (Idempotency-Key): an
// incomplete draft stays a draft and every missing item is shown, in a summary and next to its
// field too. A draft changed in another tab overwrites nothing.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { useDialog } from '../../shared/ui/dialogContext'
import { DocumentsPanel } from '../documents'
import { errorMessage } from '../../shared/ui/errorMessage'
import { applicationsApi, PARCEL_REFERENCE } from './api'
import type { Application, ApplicationType } from './api'
import { cleaned, hint, label, messages, PARCEL_FIELD, typeName } from './form'
import type { ItemMessage } from './form'

export function DraftForm({ ctx, type, draft }: { ctx: PageContext; type: ApplicationType; draft: Application | null }) {
  const { texts, lang } = ctx
  const a = texts.applications
  const dialog = useDialog()
  const [values, setValues] = useState<Record<string, string>>(draft?.data ?? {})
  const [parcel, setParcel] = useState(draft?.parcelReference ?? '')
  const [saved, setSaved] = useState<{ id: string; version: number } | null>(draft ? { id: draft.id, version: draft.version } : null)
  const [items, setItems] = useState<ItemMessage[]>([])
  const [failure, setFailure] = useState<string | null>(null)
  const [stale, setStale] = useState(false)
  const [busy, setBusy] = useState<'save' | 'submit' | 'delete' | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // One key per form: a retry of the same creation or submission is answered once.
  const [createKey] = useState(() => crypto.randomUUID())
  const [submitKey] = useState(() => crypto.randomUUID())

  const parcelValue = parcel.trim().toUpperCase()
  const parcelShape = parcelValue !== '' && !PARCEL_REFERENCE.test(parcelValue) ? a.items.PARCEL_REFERENCE_INVALID : null
  const errorFor = (key: string) => items.filter((i) => i.field === key).map((i) => i.text).join(' ') || null

  const refused = (error: unknown) => {
    if (error instanceof ApiError && Array.isArray(error.body.items)) {
      setItems(messages(texts, lang, type, error.body.items as string[]))
    } else if (error instanceof ApiError && error.code === 'STALE_VERSION') {
      setStale(true)
    } else {
      setFailure(errorMessage(texts, error))
    }
  }

  /** Saves the form; returns where the draft now stands. */
  const save = async (): Promise<{ id: string; version: number }> => {
    const body = { parcelReference: parcelValue || null, data: cleaned(values) }
    if (!saved) {
      const written = await applicationsApi.create({ typeCode: type.code, ...body }, createKey)
      const next = { id: written.id, version: written.version }
      setSaved(next)
      return next
    }
    const updated = await applicationsApi.update(saved.id, { expectedVersion: saved.version, ...body })
    const next = { id: updated.id, version: updated.version }
    setSaved(next)
    return next
  }

  const start = () => {
    setItems([])
    setFailure(null)
    setNotice(null)
  }

  const onSave = async (e: FormEvent) => {
    e.preventDefault()
    start()
    setBusy('save')
    try {
      const at = await save()
      setNotice(a.saved)
      if (!draft) navigate(href(['applications', at.id], { saved: '1' }), { replace: true })
    } catch (error) {
      refused(error)
    } finally {
      setBusy(null)
    }
  }

  const onSubmit = async () => {
    start()
    const ok = await dialog.confirm({
      title: a.confirmSubmitTitle, message: a.confirmSubmitText, confirmLabel: a.submit, cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy('submit')
    try {
      const at = await save()
      const written = await applicationsApi.submit(at.id, at.version, submitKey)
      navigate(href(['applications', written.id], { submitted: written.reference ?? '' }), { replace: true })
    } catch (error) {
      refused(error)
    } finally {
      setBusy(null)
    }
  }

  const onDelete = async () => {
    if (!saved) return
    const ok = await dialog.confirm({
      title: a.confirmDeleteTitle, message: a.confirmDeleteText, confirmLabel: a.delete, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (!ok) return
    setBusy('delete')
    try {
      await applicationsApi.remove(saved.id, saved.version)
      navigate(href(['applications'], { deleted: '1' }), { replace: true })
    } catch (error) {
      refused(error)
      setBusy(null)
    }
  }

  return (
    <form onSubmit={onSave} noValidate className="form" aria-label={typeName(lang, type)}>
      {notice && <Alert tone="success">{notice}</Alert>}
      {stale && (
        <Alert tone="warning" title={a.staleTitle}
          action={<Button size="sm" icon="refresh" onClick={() => window.location.reload()}>{a.reload}</Button>}>
          {a.staleText}
        </Alert>
      )}
      {failure && <Alert tone="danger">{failure}</Alert>}
      {items.length > 0 && (
        <Alert tone="danger" title={fill(a.itemsTitle, { n: items.length })}>
          <ul className="item-summary">{items.map((i, n) => <li key={n}>{i.text}</li>)}</ul>
        </Alert>
      )}

      <Card title={a.parcelTitle} icon="parcel" description={a.parcelIntro}>
        <Field label={a.parcelReference} hint={a.parcelHint} error={errorFor(PARCEL_FIELD) ?? parcelShape}>
          <input value={parcel} onChange={(e) => setParcel(e.target.value)} maxLength={40} className="mono uppercase"
            autoComplete="off" spellCheck={false} placeholder="DEMO-PCL-YDE1-2026-000001-7" />
        </Field>
      </Card>

      <Card title={a.formTitle} icon="file" description={typeName(lang, type)}>
        <div className="form">
          {type.requirements.fields.map((f) => {
            const value = values[f.key] ?? ''
            const set = (v: string) => setValues((current) => ({ ...current, [f.key]: v }))
            return (
              <Field key={f.key} label={label(lang, f)} hint={hint(lang, f) ?? undefined} error={errorFor(f.key)}
                required={f.required} requiredLabel={texts.common.required}>
                {f.kind === 'CHOICE'
                  ? (
                    <select value={value} onChange={(e) => set(e.target.value)}>
                      <option value="">{a.choose}</option>
                      {(f.options ?? []).map((o) => <option key={o.code} value={o.code}>{label(lang, o)}</option>)}
                    </select>
                  )
                  : f.kind === 'LONG_TEXT'
                    ? <textarea rows={5} value={value} maxLength={f.maxLength} onChange={(e) => set(e.target.value)} />
                    : <input value={value} maxLength={f.maxLength} onChange={(e) => set(e.target.value)} />}
              </Field>
            )
          })}
        </div>
      </Card>

      {type.requirements.documents.length > 0 && saved && (
        <DocumentsPanel texts={texts} lang={lang} applicationId={saved.id} editable />
      )}
      {type.requirements.documents.length > 0 && !saved && (
        <Card title={a.documentsTitle} icon="upload" description={a.documentsIntro}>
          <ul className="document-list">
            {type.requirements.documents.map((d) => (
              <li key={d.code}>
                <span>{label(lang, d)}</span>
                <Badge tone={d.required ? 'warning' : 'neutral'}>{d.required ? a.required : a.optional}</Badge>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <div className="form-actions">
        {saved && (
          <Button variant="ghost" icon="trash" onClick={onDelete} busy={busy === 'delete'} disabled={busy !== null}>
            {a.delete}
          </Button>
        )}
        <Button type="submit" icon="check" busy={busy === 'save'} disabled={busy !== null || stale}>{a.saveDraft}</Button>
        <Button variant="primary" icon="arrowRight" onClick={onSubmit} busy={busy === 'submit'} disabled={busy !== null || stale}>
          {a.submit}
        </Button>
      </div>
    </form>
  )
}
