// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Retention and legal holds (#/records, ADR-041 D18), for auditors (read only) and system administrators.
// The classes show that no retention schedule is approved, so nothing is disposed of. The holds list
// shows every hold with its release history; a hold is placed on a subject named by its kind and id,
// as the deposit and professional screens show them.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { isUuid, REASON_MAX, REASON_MIN, retentionApi, SUBJECT_KINDS } from './api'
import type { RetentionClass, SubjectKind } from './api'
import { HoldsPanel } from './HoldsPanel'

export function RetentionPage({ ctx }: { ctx: PageContext }) {
  const { texts, lang, session } = ctx
  const k = texts.retention
  const [placed, setPlaced] = useState(0)
  return (
    <div className="page">
      <PageHeader title={k.title} description={k.intro} breadcrumbLabel={texts.shell.breadcrumb} />
      <Alert tone="info" title={k.noScheduleTitle}>{k.noScheduleText}</Alert>
      <Classes texts={texts} lang={lang} />
      {can(session, 'records.hold.place') && <PlaceForm texts={texts} onPlaced={() => setPlaced((n) => n + 1)} />}
      <Card title={k.holdsTitle} icon="lock" description={k.holdsIntro}>
        <HoldsPanel texts={texts} lang={lang} session={session} reloadKey={placed} />
      </Card>
    </div>
  )
}

function Classes({ texts, lang }: { texts: Texts; lang: Lang }) {
  const k = texts.retention
  const [rows, setRows] = useState<RetentionClass[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  useEffect(() => {
    retentionApi.classes().then(setRows).catch((e) => setFailure(errorMessage(texts, e)))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Card title={k.classesTitle} icon="file" description={k.classesIntro}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {rows === null && !failure && <Skeleton lines={3} />}
      {rows && (
        <ul className="plain-list" data-classes>
          {rows.map((c) => (
            <li key={c.code} data-class={c.code} data-standing={c.standing}>
              <span className="mono">{c.code}</span>{' '}
              <Badge tone={c.standing === 'NO_APPROVED_SCHEDULE_KEPT' ? 'info' : 'warning'}>{(k.standings as Record<string, string>)[c.standing]}</Badge>
              <div className="small muted">{lang === 'fr' ? c.descriptionFr : c.descriptionEn}</div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function PlaceForm({ texts, onPlaced }: { texts: Texts; onPlaced: () => void }) {
  const k = texts.retention
  const [kind, setKind] = useState<SubjectKind>('DEPOSIT')
  const [subject, setSubject] = useState('')
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const idError = touched && !isUuid(subject) ? k.subjectIdInvalid : null
  const length = reason.trim().length
  const reasonError = touched && (length < REASON_MIN || length > REASON_MAX) ? k.reasonTooShort.replace('{n}', String(REASON_MIN)) : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    setDone(false)
    if (!isUuid(subject) || length < REASON_MIN || length > REASON_MAX) return
    setBusy(true)
    setFailure(null)
    try {
      await retentionApi.place(kind, subject.trim().toLowerCase(), reason.trim())
      setSubject('')
      setReason('')
      setTouched(false)
      setDone(true)
      onPlaced()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={k.placeTitle} icon="lock" description={k.placeText}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {done && <Alert tone="success">{k.placed}</Alert>}
      <form className="form" onSubmit={submit} data-place-form>
        <Field label={k.subjectKind}>
          <select value={kind} onChange={(e) => setKind(e.target.value as SubjectKind)}>
            {SUBJECT_KINDS.map((s) => <option key={s} value={s}>{(k.subjectKinds as Record<string, string>)[s]}</option>)}
          </select>
        </Field>
        <Field label={k.subjectId} hint={k.subjectIdHint} error={idError}>
          <input className="mono" value={subject} onChange={(e) => setSubject(e.target.value)} />
        </Field>
        <Field label={k.reason} hint={k.reasonHint} error={reasonError}>
          <textarea rows={3} maxLength={REASON_MAX + 100} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <div className="form-actions start"><Button type="submit" icon="lock" busy={busy} disabled={busy}>{k.place}</Button></div>
      </form>
    </Card>
  )
}
