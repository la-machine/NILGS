// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The intake officer's decision on a case in completeness review (MVP-03): accept, only when
// the automatic check finds nothing missing and the DEMO fee is paid while the payment gate is
// on (MVP-08; otherwise the button says why); or request
// information: the document categories wanted (those missing are ticked) and a message of 10 to
// 2000 characters, confirmed before it is sent. A case changed meanwhile overwrites nothing.
import { useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert } from '../../shared/ui/Feedback'
import { useDialog } from '../../shared/ui/dialogContext'
import { errorMessage } from '../../shared/ui/errorMessage'
import { documentLabel } from '../applications'
import { casesApi, MESSAGE_MAX, MESSAGE_MIN } from './api'
import type { Case } from './api'

export function CompletenessDecision({ texts, lang, found, canAccept, canRequest, onDecided, onReload }: {
  texts: Texts; lang: Lang; found: Case; canAccept: boolean; canRequest: boolean
  onDecided: (updated: Case) => void
  onReload: () => void
}) {
  const c = texts.cases
  const dialog = useDialog()
  const unpaid = found.payment?.blocksAcceptance === true
  const missingCategories = found.missing.filter((m) => m.startsWith('MISSING_DOCUMENT:')).map((m) => m.split(':')[1])
  const [categories, setCategories] = useState<string[]>(missingCategories)
  const [message, setMessage] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState<'accept' | 'request' | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  const length = message.trim().length
  const messageError = touched && (length < MESSAGE_MIN || length > MESSAGE_MAX) ? fill(c.messageBounds, { min: MESSAGE_MIN, max: MESSAGE_MAX }) : null
  const categoriesError = touched && categories.length === 0 ? c.chooseCategories : null

  const run = async (kind: 'accept' | 'request', action: () => Promise<Case>) => {
    setBusy(kind)
    setFailure(null)
    try {
      onDecided(await action())
    } catch (error) {
      if (error instanceof ApiError && (error.code === 'STALE_VERSION' || error.code === 'INVALID_TRANSITION')) setStale(true)
      else setFailure(errorMessage(texts, error))
    } finally {
      setBusy(null)
    }
  }

  const accept = async () => {
    const ok = await dialog.confirm({
      title: c.confirmAcceptTitle, message: fill(c.confirmAcceptText, { reference: found.reference }),
      confirmLabel: c.accept, cancelLabel: texts.common.cancel,
    })
    if (ok) await run('accept', () => casesApi.accept(found.id, found.version))
  }

  const request = async () => {
    setTouched(true)
    if (categories.length === 0 || length < MESSAGE_MIN || length > MESSAGE_MAX) return
    const ok = await dialog.confirm({
      title: c.confirmRequestTitle,
      message: fill(c.confirmRequestText, { list: categories.map((code) => documentLabel(lang, found.type, code)).join(', ') }),
      confirmLabel: c.request, cancelLabel: texts.common.cancel,
    })
    if (ok) {
      await run('request', () => casesApi.requestInformation(found.id,
        { expectedVersion: found.version, categories, message: message.trim() }))
    }
  }

  const toggle = (code: string) =>
    setCategories((current) => (current.includes(code) ? current.filter((x) => x !== code) : [...current, code]))

  return (
    <Card title={c.decisionTitle} icon="shield" description={c.decisionIntro}>
      {stale && (
        <Alert tone="warning" title={c.staleTitle}
          action={<Button size="sm" icon="refresh" onClick={onReload}>{texts.applications.reload}</Button>}>{c.staleText}</Alert>
      )}
      {failure && <Alert tone="danger">{failure}</Alert>}
      {canAccept && (
        <div className="decision-row">
          <Button variant="primary" icon="checkCircle" onClick={accept} busy={busy === 'accept'}
            disabled={busy !== null || stale || found.missing.length > 0 || unpaid}>{c.accept}</Button>
          <span className="muted">{found.missing.length > 0 ? c.acceptBlocked : unpaid ? c.acceptUnpaid : c.acceptReady}</span>
        </div>
      )}
      {canRequest && (
        <div className="form">
          <fieldset className="checkbox-list" aria-describedby={categoriesError ? 'request-categories-error' : undefined}>
            <legend className="field-label">{c.categories}</legend>
            {found.type.requirements.documents.map((d) => (
              <label key={d.code} className="checkbox">
                <input type="checkbox" checked={categories.includes(d.code)} onChange={() => toggle(d.code)} />
                <span>{lang === 'fr' ? d.labelFr : d.labelEn}</span>
              </label>
            ))}
            {categoriesError && <p id="request-categories-error" className="field-error">{categoriesError}</p>}
          </fieldset>
          <Field label={c.message} hint={c.messageHint} error={messageError} required requiredLabel={texts.common.required}>
            <textarea rows={4} maxLength={MESSAGE_MAX + 100} value={message} onChange={(e) => setMessage(e.target.value)}
              onBlur={() => setTouched(true)} />
          </Field>
          <div className="form-actions start">
            <Button icon="arrowRight" onClick={request} busy={busy === 'request'} disabled={busy !== null || stale}>{c.request}</Button>
          </div>
        </div>
      )}
    </Card>
  )
}
