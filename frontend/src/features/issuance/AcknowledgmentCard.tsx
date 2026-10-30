// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The DEMO acknowledgment of a submitted application (Slice 1, ADR-039), on the applicant's
// application page and on the office's case page. It says first that it has no legal value,
// then shows its number, status and date, the exact PDF to download and where anyone can
// verify it. Issued after the submission; if that failed (key custody unavailable), the
// applicant asks for it again here, which issues it once.
import { useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { issuanceApi } from './api'
import type { Acknowledgment, AcknowledgmentSource } from './api'
import { STATUS_TONE } from './tones'

export function AcknowledgmentCard({ texts, lang, applicationId, source = 'own' }: {
  texts: Texts; lang: Lang; applicationId: string; source?: AcknowledgmentSource
}) {
  const k = texts.issuance
  const [ack, setAck] = useState<Acknowledgment | null>(null)
  const [missing, setMissing] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    issuanceApi.acknowledgment(applicationId, source)
      .then((loaded) => { if (current) setAck(loaded) })
      .catch((e) => {
        if (!current) return
        if (e instanceof ApiError && e.code === 'NOT_ISSUED') setMissing(true)
        else setFailure(errorMessage(texts, e))
      })
    return () => { current = false }
  }, [applicationId, source]) // eslint-disable-line react-hooks/exhaustive-deps

  const issue = async () => {
    setBusy(true)
    setFailure(null)
    try {
      setAck(await issuanceApi.issue(applicationId))
      setMissing(false)
    } catch (e) {
      setFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  if (!ack && !missing && !failure) return <Card title={k.ackTitle} icon="signature"><Skeleton lines={3} /></Card>
  return (
    <Card title={k.ackTitle} icon="signature" description={k.ackIntro}
      actions={ack ? <Badge tone={STATUS_TONE[ack.status]}>{k.statuses[ack.status]}</Badge> : undefined}>
      <Alert tone="warning" title={k.demoTitle}>{k.demoText}</Alert>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {missing && (
        <div data-acknowledgment="missing">
          <p>{source === 'own' ? k.ackMissing : k.ackMissingCase}</p>
          {source === 'own' && (
            <div className="form-actions start">
              <Button icon="signature" onClick={issue} busy={busy} disabled={busy}>{k.ackIssue}</Button>
            </div>
          )}
        </div>
      )}
      {ack && (
        <div data-acknowledgment={ack.number}>
          <dl className="properties">
            <div><dt>{k.number}</dt><dd className="mono">{ack.number}</dd></div>
            <div><dt>{k.issuedAt}</dt><dd className="num">{new Date(ack.issuedAt).toLocaleString(lang)}</dd></div>
            {ack.replacement && <div><dt>{k.replacedBy}</dt><dd className="mono">{ack.replacement}</dd></div>}
          </dl>
          {ack.status !== 'VALID' && <Alert tone="info">{k.ackNotValid[ack.status]}</Alert>}
          <div className="form-actions start">
            <a className="btn btn-primary" href={issuanceApi.fileUrl(applicationId, source)}
              download={`snigf-demo-acknowledgment-${ack.number}.pdf`}>{k.download}</a>
            <a className="btn btn-secondary" href={href(['verify', ack.number])}>{k.verifyLink}</a>
          </div>
          <p className="small muted">{fill(k.ackKeep, { number: ack.number })}</p>
        </div>
      )}
    </Card>
  )
}
