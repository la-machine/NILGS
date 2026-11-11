// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The legal holds of one subject, or of every subject (ADR-041 D18). With records.hold.place, place one
// with a reason; with records.hold.release, ask for a release, or approve or reject another person's
// request (never one's own). Nothing here deletes anything: a released hold stays listed with its history.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { can } from '../../shared/session/access'
import type { Session } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { useDialog } from '../../shared/ui/dialogContext'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { REASON_MAX, REASON_MIN, retentionApi } from './api'
import type { Hold, Release, SubjectKind } from './api'

export function HoldsPanel({ texts, lang, session, subject, reloadKey = 0 }: {
  texts: Texts; lang: Lang; session: Session; subject?: { kind: SubjectKind; id: string }; reloadKey?: number
}) {
  const k = texts.retention
  const dialog = useDialog()
  const [holds, setHolds] = useState<Hold[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reloads, setReloads] = useState(0)
  const canPlace = can(session, 'records.hold.place')
  const canRelease = can(session, 'records.hold.release')

  useEffect(() => {
    let current = true
    retentionApi.holds(subject)
      .then((loaded) => { if (current) setHolds(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [subject?.kind, subject?.id, reloads, reloadKey]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (step: () => Promise<unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      await step()
      setReloads((n) => n + 1)
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const ask = (title: string, message: string, confirmLabel: string, tone?: 'danger') => dialog.reason({
    title, message, label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort,
    confirmLabel, cancelLabel: texts.common.cancel, tone,
  })

  const place = async () => {
    if (!subject) return
    const reason = await ask(k.placeTitle, k.placeText, k.place)
    if (reason !== null) await run(() => retentionApi.place(subject.kind, subject.id, reason.trim()))
  }
  const requestRelease = async (h: Hold) => {
    const reason = await ask(k.requestReleaseTitle, k.requestReleaseText, k.requestRelease)
    if (reason !== null) await run(() => retentionApi.requestRelease(h.id, reason.trim()))
  }
  const approve = async (h: Hold, r: Release) => {
    const yes = await dialog.confirm({ title: k.approveReleaseTitle, message: k.approveReleaseText, confirmLabel: k.approveRelease, cancelLabel: texts.common.cancel })
    if (yes) await run(() => retentionApi.decideRelease(h.id, r.id, 'APPROVED'))
  }
  const reject = async (h: Hold, r: Release) => {
    const reason = await ask(k.rejectReleaseTitle, k.rejectReleaseText, k.rejectRelease, 'danger')
    if (reason !== null) await run(() => retentionApi.decideRelease(h.id, r.id, 'REJECTED', reason.trim()))
  }

  return (
    <div data-holds={subject ? `${subject.kind}:${subject.id}` : 'all'}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {holds !== null && holds.length === 0 && <p className="small muted">{k.noHolds}</p>}
      {holds !== null && holds.length > 0 && (
        <ul className="plain-list exception-list">
          {holds.map((h) => {
            const pending = h.releases.find((r) => r.status === 'PENDING')
            return (
              <li key={h.id} className="exception-item" data-hold={h.id} data-in-force={h.inForce}>
                <div className="exception-head">
                  <Badge tone={h.inForce ? 'warning' : 'neutral'} icon="lock">{h.inForce ? k.inForce : k.released}</Badge>
                  <span>{(k.subjectKinds as Record<string, string>)[h.subjectKind] ?? h.subjectKind}</span>
                  {!subject && <span className="mono small">{h.subjectId}</span>}
                </div>
                <div className="small">{fill(k.placedBy, { name: h.placedByName })} · <span className="num">{new Date(h.placedAt).toLocaleString(lang)}</span></div>
                <div className="small">{fill(k.reasonIs, { reason: h.reason })}</div>
                {h.releases.length > 0 && (
                  <ul className="plain-list replacement-list">
                    {h.releases.map((r) => (
                      <li key={r.id} data-release={r.id} data-status={r.status}>
                        <Badge tone={r.status === 'APPROVED' ? 'success' : r.status === 'REJECTED' ? 'danger' : 'warning'}>
                          {(k.releaseStatuses as Record<string, string>)[r.status]}
                        </Badge>
                        <div className="small">{fill(k.releaseAskedBy, { name: r.requestedByName })} · <span className="num">{new Date(r.requestedAt).toLocaleString(lang)}</span></div>
                        <div className="small">{fill(k.reasonIs, { reason: r.reason })}</div>
                        {r.decidedByName && <div className="small muted">{fill(k.decidedBy, { name: r.decidedByName })}</div>}
                        {r.decisionReason && <div className="small">{fill(k.decisionIs, { reason: r.decisionReason })}</div>}
                      </li>
                    ))}
                  </ul>
                )}
                {h.inForce && canRelease && (
                  <div className="form-actions start">
                    {!pending && <Button variant="ghost" disabled={busy} onClick={() => void requestRelease(h)}>{k.requestRelease}</Button>}
                    {pending && pending.requestedBy === session.userId && <span className="small muted">{k.releaseAwaitsAnother}</span>}
                    {pending && pending.requestedBy !== session.userId && <>
                      <Button disabled={busy} onClick={() => void approve(h, pending)}>{k.approveRelease}</Button>
                      <Button variant="ghost" disabled={busy} onClick={() => void reject(h, pending)}>{k.rejectRelease}</Button>
                    </>}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {subject && canPlace && (
        <div className="form-actions start"><Button icon="lock" disabled={busy} onClick={() => void place()}>{k.place}</Button></div>
      )}
    </div>
  )
}
