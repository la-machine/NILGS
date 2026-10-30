// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The document officer's page (#/issued-documents, ADR-039). With document.issued.manage: look
// a document up by number, see what it states and its status, and revoke, cancel or re-issue
// it with a reason, after confirmation (never one's own: the server and the database refuse).
// With document.incident.review: the verification incidents (altered or fabricated files, a
// registry row that no longer agrees with its signature), each resolved once with a note.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { issuanceApi, normaliseNumber, NOTE_MAX, NOTE_MIN, NUMBER_PATTERN, REASON_MAX, REASON_MIN, RESOLUTIONS } from './api'
import type { Incident, IssuedDocument, Resolution } from './api'
import { STATUS_TONE } from './tones'

export function IssuedDocumentsPage({ ctx }: { ctx: PageContext }) {
  const { texts, session } = ctx
  const k = texts.issuance
  return (
    <div className="page">
      <PageHeader title={k.officerTitle} description={k.officerIntro} breadcrumbLabel={texts.shell.breadcrumb} />
      <Alert tone="warning" title={k.demoTitle}>{k.demoText}</Alert>
      {can(session, 'document.issued.manage') && <Lookup ctx={ctx} />}
      {can(session, 'document.incident.review') && <Incidents texts={texts} lang={ctx.lang} />}
    </div>
  )
}

type Action = 'revoke' | 'cancel' | 'reissue'

function Lookup({ ctx }: { ctx: PageContext }) {
  const { texts, lang } = ctx
  const k = texts.issuance
  const dialog = useDialog()
  const [number, setNumber] = useState('')
  const [touched, setTouched] = useState(false)
  const [doc, setDoc] = useState<IssuedDocument | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const normalised = normaliseNumber(number)
  const numberError = touched && !NUMBER_PATTERN.test(normalised) ? k.numberInvalid : null

  const find = async (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (!NUMBER_PATTERN.test(normalised)) return
    setBusy(true)
    setFailure(null)
    setDone(null)
    setDoc(null)
    try {
      setDoc(await issuanceApi.document(normalised))
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const act = async (action: Action) => {
    if (!doc) return
    const reason = await dialog.reason({
      title: fill(k.actionTitle[action], { number: doc.number }), message: k.actionText[action],
      label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort,
      confirmLabel: k.actions[action], cancelLabel: texts.common.cancel, tone: action === 'reissue' ? 'primary' : 'danger',
    })
    if (reason === null) return
    setBusy(true)
    setFailure(null)
    try {
      const call = action === 'revoke' ? issuanceApi.revoke : action === 'cancel' ? issuanceApi.cancel : issuanceApi.reissue
      const changed = await call(doc.number, reason.trim())
      setDoc(action === 'reissue' ? await issuanceApi.document(doc.number) : changed)
      setDone(action === 'reissue' ? fill(k.reissued, { number: changed.number }) : k.statusChanged)
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={k.lookupTitle} icon="search" description={k.lookupIntro}>
      <form className="form form-inline" onSubmit={find}>
        <Field label={k.number} error={numberError}>
          <input className="mono" value={number} maxLength={40} autoComplete="off" spellCheck={false}
            onChange={(e) => setNumber(e.target.value)} onBlur={() => setTouched(true)} />
        </Field>
        <div className="form-actions start">
          <Button type="submit" icon="search" busy={busy} disabled={busy}>{k.lookUp}</Button>
        </div>
      </form>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {done && <Alert tone="success">{done}</Alert>}
      {doc && (
        <div data-document={doc.number} data-status={doc.status}>
          <dl className="properties">
            <div><dt>{k.number}</dt><dd className="mono">{doc.number}</dd></div>
            <div><dt>{k.type}</dt><dd>{(k.types as Record<string, string>)[doc.type] ?? doc.type}</dd></div>
            <div><dt>{k.status}</dt><dd><Badge tone={STATUS_TONE[doc.status]}>{k.statuses[doc.status]}</Badge></dd></div>
            <div><dt>{k.application}</dt><dd className="mono">{doc.subjectRef}</dd></div>
            <div><dt>{k.issuedAt}</dt><dd className="num">{new Date(doc.issuedAt).toLocaleString(lang)}</dd></div>
            <div><dt>{k.template}</dt><dd className="mono small">{doc.templateVersion} · {fill(k.keyVersion, { n: doc.keyVersion })}</dd></div>
            {doc.statusReason && <div><dt>{k.statusReason}</dt><dd>{doc.statusReason}</dd></div>}
            {doc.replacement && (
              <div><dt>{k.replacedBy}</dt><dd><a className="mono" href={href(['verify', doc.replacement])}>{doc.replacement}</a></dd></div>
            )}
          </dl>
          {doc.status === 'VALID' && (
            <div className="form-actions start">
              <Button icon="refresh" onClick={() => act('reissue')} disabled={busy}>{k.actions.reissue}</Button>
              <Button variant="danger" icon="xCircle" onClick={() => act('revoke')} disabled={busy}>{k.actions.revoke}</Button>
              <Button variant="ghost" onClick={() => act('cancel')} disabled={busy}>{k.actions.cancel}</Button>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}

function Incidents({ texts, lang }: { texts: Texts; lang: Lang }) {
  const k = texts.issuance
  const [status, setStatus] = useState<'OPEN' | 'RESOLVED'>('OPEN')
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<Incident[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    let current = true
    issuanceApi.incidents(status, page)
      .then((loaded) => { if (current) setRows(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [status, page, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Card title={k.incidentsTitle} icon="alert" description={k.incidentsIntro}
      actions={
        <select aria-label={k.incidentsShow} value={status}
          onChange={(e) => { setRows(null); setStatus(e.target.value as 'OPEN' | 'RESOLVED'); setPage(0) }}>
          <option value="OPEN">{k.incidentStatuses.OPEN}</option>
          <option value="RESOLVED">{k.incidentStatuses.RESOLVED}</option>
        </select>
      }>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {!rows && !failure && <Skeleton lines={4} />}
      {rows && rows.length === 0 && <EmptyState icon="checkCircle" title={k.noIncidents[status]} />}
      {rows && rows.length > 0 && (
        <ul className="plain-list exception-list">
          {rows.map((i) => <IncidentItem key={`${i.id}-${i.version}`} texts={texts} lang={lang} incident={i}
            onResolved={() => { setRows(null); setReloads((n) => n + 1) }} />)}
        </ul>
      )}
      {rows && (page > 0 || rows.length > 0) && (
        <div className="form-actions start">
          <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => { setRows(null); setPage((p) => p - 1) }}>{k.previous}</Button>
          <Button size="sm" icon="chevronRight" disabled={rows.length === 0} onClick={() => { setRows(null); setPage((p) => p + 1) }}>{k.next}</Button>
        </div>
      )}
    </Card>
  )
}

function IncidentItem({ texts, lang, incident: i, onResolved }: {
  texts: Texts; lang: Lang; incident: Incident; onResolved: () => void
}) {
  const k = texts.issuance
  const dialog = useDialog()
  const [resolution, setResolution] = useState<Resolution>('CONFIRMED_ALTERATION')
  const [note, setNote] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const length = note.trim().length
  const noteError = touched && (length < NOTE_MIN || length > NOTE_MAX) ? fill(k.noteBounds, { min: NOTE_MIN, max: NOTE_MAX }) : null

  const resolve = async () => {
    setTouched(true)
    if (length < NOTE_MIN || length > NOTE_MAX) return
    const ok = await dialog.confirm({
      title: k.resolveTitle, message: fill(k.resolveText, { resolution: k.resolutions[resolution] }),
      confirmLabel: k.resolve, cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      await issuanceApi.resolve(i.id, { expectedVersion: i.version, resolution, note: note.trim() })
      onResolved()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="exception-item" data-incident={i.id} data-kind={i.kind}>
      <div className="exception-head">
        <Badge tone={i.status === 'OPEN' ? 'danger' : 'neutral'} icon="alert">{k.kinds[i.kind]}</Badge>
        {i.documentNumber && <span className="mono small">{i.documentNumber}</span>}
        <span className="muted num small">{new Date(i.openedAt).toLocaleString(lang)}</span>
      </div>
      <p className="small">{k.kindText[i.kind]}</p>
      <dl className="properties">
        <div><dt>{k.channel}</dt><dd>{k.channels[i.channel]}</dd></div>
        {i.claimedNumber && i.claimedNumber !== i.documentNumber && (
          <div><dt>{k.claimed}</dt><dd className="mono">{i.claimedNumber}</dd></div>
        )}
        {i.presentedSha256 && <div><dt>{k.fingerprint}</dt><dd className="mono small">{i.presentedSha256}</dd></div>}
        {i.resolution && (
          <div>
            <dt>{k.resolution}</dt>
            <dd>{k.resolutions[i.resolution]} · {i.resolvedByName} · <span className="num">{i.resolvedAt && new Date(i.resolvedAt).toLocaleString(lang)}</span>
              <br /><span className="muted">{i.note}</span></dd>
          </div>
        )}
      </dl>
      {i.status === 'OPEN' && (
        <div className="form">
          {failure && <Alert tone="danger">{failure}</Alert>}
          <Field label={k.resolution}>
            <select value={resolution} onChange={(e) => setResolution(e.target.value as Resolution)}>
              {RESOLUTIONS.map((r) => <option key={r} value={r}>{k.resolutions[r]}</option>)}
            </select>
          </Field>
          <Field label={k.note} hint={k.noteHint} error={noteError} required requiredLabel={texts.common.required}>
            <textarea rows={2} maxLength={NOTE_MAX + 50} value={note} onChange={(e) => setNote(e.target.value)}
              onBlur={() => setTouched(true)} />
          </Field>
          <div className="form-actions start">
            <Button icon="checkCircle" onClick={resolve} busy={busy} disabled={busy}>{k.resolve}</Button>
          </div>
        </div>
      )}
    </li>
  )
}
