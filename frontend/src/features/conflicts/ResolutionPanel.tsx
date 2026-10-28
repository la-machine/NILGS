// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Resolving an open alert (MVP-04, E-11): one of the Baseline's three outcomes and a note
// (10 to 2000 characters), confirmed before it is sent, against the version of the alert on
// screen. The author of a compared version is told why they cannot resolve it instead of being
// shown a form the server would refuse (C6b). A resolution changed meanwhile overwrites
// nothing: the reviewer reloads and decides again.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { ApiError } from '../../shared/api/client'
import type { Access } from '../../shared/session/access'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert } from '../../shared/ui/Feedback'
import { useDialog } from '../../shared/ui/dialogContext'
import { errorMessage } from '../../shared/ui/errorMessage'
import { conflictsApi, NOTE_MAX, NOTE_MIN, RESOLUTIONS } from './api'
import type { AlertDetail, Resolution } from './api'

export function ResolutionPanel({ texts, alert, access, onResolved, onReload }: {
  texts: Texts; alert: AlertDetail; access: Access
  onResolved: (resolved: AlertDetail) => void
  onReload: () => void
}) {
  const c = texts.conflicts
  const dialog = useDialog()
  const [resolution, setResolution] = useState<Resolution | null>(null)
  const [note, setNote] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  if (alert.resolverIsAuthor) {
    return (
      <Card title={c.resolveTitle} icon="shield">
        <Alert tone="info" title={c.authorTitle}>{c.authorText}</Alert>
      </Card>
    )
  }
  if (access === null) {
    return (
      <Card title={c.resolveTitle} icon="shield">
        <p className="muted">{c.cannotResolve}</p>
      </Card>
    )
  }

  const length = note.trim().length
  const noteError = touched && length < NOTE_MIN ? fill(c.noteTooShort, { n: NOTE_MIN })
    : length > NOTE_MAX ? fill(c.noteTooLong, { n: NOTE_MAX }) : null
  const choiceError = touched && resolution === null ? c.chooseOutcome : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (resolution === null || length < NOTE_MIN || length > NOTE_MAX) return
    const ok = await dialog.confirm({
      title: c.confirmTitle,
      message: (
        <>
          <p>{fill(c.confirmText, { outcome: c.resolutions[resolution], a: alert.a.reference, b: alert.b.reference })}</p>
          {access.kind === 'grant' && (
            <p>{access.grant.usesLeft === null ? c.grantUse : fill(c.grantUsesLeft, { n: access.grant.usesLeft })}</p>
          )}
        </>
      ),
      confirmLabel: c.confirmResolve,
      cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      onResolved(await conflictsApi.resolve(alert.id, { expectedVersion: alert.version, resolution, note: note.trim() }))
    } catch (error) {
      if (error instanceof ApiError && (error.code === 'STALE_VERSION' || error.code === 'ALERT_NOT_OPEN')) {
        setStale(true)
      } else {
        setFailure(errorMessage(texts, error))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={c.resolveTitle} icon="shield" description={c.resolveIntro}>
      {stale && (
        <Alert tone="warning" title={c.staleTitle}
          action={<Button size="sm" icon="refresh" onClick={onReload}>{c.reload}</Button>}>
          {c.staleText}
        </Alert>
      )}
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form onSubmit={submit} noValidate className="form">
        <fieldset className="resolution-choices" aria-describedby={choiceError ? 'resolution-error' : undefined}>
          <legend className="field-label">{c.outcome}</legend>
          {RESOLUTIONS.map((r) => (
            <label key={r} className="resolution-choice">
              <input type="radio" name="resolution" value={r} checked={resolution === r} onChange={() => setResolution(r)} />
              <strong>{c.resolutions[r]}</strong>
              <span>{c.resolutionHelp[r]}</span>
            </label>
          ))}
          {choiceError && <p id="resolution-error" className="field-error">{choiceError}</p>}
        </fieldset>
        <Field label={c.note} hint={c.noteHint} error={noteError} required requiredLabel={texts.common.required}>
          <textarea rows={5} maxLength={NOTE_MAX + 200} value={note}
            onChange={(e) => setNote(e.target.value)} onBlur={() => setTouched(true)} />
        </Field>
        <p className="note-counter" aria-live="polite">{fill(c.noteCount, { n: length, max: NOTE_MAX })}</p>
        <div className="form-actions">
          <Button type="submit" variant="primary" icon="checkCircle" busy={busy} disabled={busy || stale}>{c.resolve}</Button>
        </div>
      </form>
    </Card>
  )
}
