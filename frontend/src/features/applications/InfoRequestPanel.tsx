// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// An information request, as the applicant answers it (MVP-03): what the intake officer asked
// for and why, the files to add (in those categories only; a new file adds a version and
// replaces nothing), then the response, with an optional message, confirmed before it is sent.
// The application then goes back to completeness review.
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
import { DocumentsPanel } from '../documents'
import { applicationsApi } from './api'
import type { Application } from './api'
import { documentLabel } from './form'

const MESSAGE_MAX = 2000

export function InfoRequestPanel({ texts, lang, application, onResponded, onReload }: {
  texts: Texts; lang: Lang; application: Application
  onResponded: (updated: Application) => void
  onReload: () => void
}) {
  const c = texts.completeness
  const dialog = useDialog()
  const request = application.pendingRequest
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  const respond = async () => {
    const ok = await dialog.confirm({
      title: c.confirmResponseTitle, message: c.confirmResponseText, confirmLabel: c.sendResponse, cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      onResponded(await applicationsApi.respond(application.id, application.version, message.trim() || null))
    } catch (error) {
      if (error instanceof ApiError && (error.code === 'STALE_VERSION' || error.code === 'INVALID_TRANSITION')) setStale(true)
      else setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Alert tone="warning" title={c.requestTitle}>
        {request && (
          <>
            <p className="pre-line">« {request.message} »</p>
            <p>{fill(c.requestedList, {
              list: request.categories.map((code) => documentLabel(lang, application.type, code)).join(', '),
            })}</p>
          </>
        )}
      </Alert>
      <DocumentsPanel texts={texts} lang={lang} applicationId={application.id} editable />
      <Card title={c.responseTitle} icon="arrowRight" description={c.responseIntro}>
        {stale && (
          <Alert tone="warning" title={c.staleTitle}
            action={<Button size="sm" icon="refresh" onClick={onReload}>{texts.applications.reload}</Button>}>
            {c.staleText}
          </Alert>
        )}
        {failure && <Alert tone="danger">{failure}</Alert>}
        <div className="form">
          <Field label={c.responseMessage} hint={c.responseHint}>
            <textarea rows={3} maxLength={MESSAGE_MAX} value={message} onChange={(e) => setMessage(e.target.value)} />
          </Field>
          <div className="form-actions">
            <Button variant="primary" icon="arrowRight" onClick={respond} busy={busy} disabled={busy || stale}>
              {c.sendResponse}
            </Button>
          </div>
        </div>
      </Card>
    </>
  )
}
