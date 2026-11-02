// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One professional account, for administrators (#/professionals/<userId>): the credential; the
// verification by administrator A, with its evidence (readable by verifiers and approvers only,
// each reading audited); the requests a second administrator decides; suspension by one
// administrator; reinstatement, revocation and office changes requested for another to decide; the
// history. Each control appears only with its permission; the database decides every step.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { officeCodes, professionalApi, REASON_MAX, REASON_MIN } from './api'
import type { ChangeKind, Detail, Profile } from './api'
import { useDecision } from './decisions'
import { History } from './History'
import { RequestItem } from './RequestItem'
import { refusal, STATUS_TONE } from './tones'

export function AdminDetailPage({ ctx, userId }: { ctx: PageContext; userId: string }) {
  const { texts, session, lang, run } = ctx
  const p = texts.professionals
  const dialog = useDialog()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const load = async () => setDetail(await professionalApi.detail(userId))
  useEffect(() => { void run(load) }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps
  const done = (message: string) => async () => { setNotice(message); await load() }
  const decision = useDecision(ctx, done(p.decided))

  if (!detail) return <div className="page" aria-busy="true"><Skeleton lines={8} /></div>
  const { profile, verification } = detail
  const verifies = can(session, 'admin.professional.verify')
  const suspends = can(session, 'admin.professional.suspend')
  const activationPending = detail.requests.some((r) => r.kind === 'ACTIVATION' && r.status === 'PENDING')

  const suspend = async () => {
    const reason = await dialog.reason({
      title: fill(p.suspendTitle, { name: profile.displayName }), message: p.suspendText, label: p.reason, hint: p.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: p.reasonTooShort, confirmLabel: p.suspend, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (reason === null) return
    await run(async () => { await professionalApi.suspend(userId, reason.trim()); await done(p.suspended)() })
  }

  return (
    <div className="page" data-professional-detail={profile.email} data-status={profile.status}>
      <PageHeader title={profile.displayName} description={p.detailIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: p.adminTitle, href: href(['professionals']) }, { label: profile.displayName }]} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}

      <Card title={p.profileTitle} icon="userCheck"
        actions={suspends && profile.status === 'ACTIVE' ? <Button variant="danger" onClick={suspend}>{p.suspend}</Button> : undefined}>
        <dl className="properties">
          <div><dt>{p.status}</dt><dd data-status={profile.status}><Badge tone={STATUS_TONE[profile.status]}>{p.statuses[profile.status]}</Badge></dd></div>
          <div><dt>{texts.email}</dt><dd>{profile.email}</dd></div>
          <div><dt>{p.profession}</dt><dd>{p.professions[profile.profession]}</dd></div>
          <div><dt>{p.chamber}</dt><dd>{profile.chamber}</dd></div>
          <div><dt>{p.registrationNumber}</dt><dd className="mono">{profile.registrationNumber}</dd></div>
          <div><dt>{p.jurisdiction}</dt><dd>{profile.jurisdiction}</dd></div>
          <div><dt>{p.offices}</dt><dd>{profile.offices.length > 0 ? profile.offices.join(', ') : p.none}</dd></div>
          <div><dt>{p.requestedAt}</dt><dd className="num">{new Date(profile.requestedAt).toLocaleString(lang)}</dd></div>
        </dl>
      </Card>

      {verifies && profile.status === 'REQUESTED' && (
        <VerifyForm texts={texts} userId={userId} onDone={done(p.verified)} />
      )}

      {verification && (
        <Card title={p.verificationTitle} icon="shield">
          <dl className="properties">
            <div><dt>{p.verifiedBy}</dt><dd>{verification.verifiedByName} · <span className="num">{new Date(verification.verifiedAt).toLocaleString(lang)}</span></dd></div>
            <div><dt>{p.body}</dt><dd>{verification.body}</dd></div>
            <div><dt>{p.reference}</dt><dd className="mono">{verification.reference}</dd></div>
            <div><dt>{p.checkedOn}</dt><dd className="num">{new Date(verification.checkedOn).toLocaleDateString(lang)}</dd></div>
            <div><dt>{p.note}</dt><dd>{verification.note}</dd></div>
          </dl>
          <h3>{p.evidence}</h3>
          <p className="small muted">{p.evidenceHint}</p>
          {verification.documents.length === 0 ? <p className="small muted">{p.noEvidence}</p> : (
            <ul className="plain-list" data-evidence>
              {verification.documents.map((d) => (
                <li key={d.id}>
                  <a href={professionalApi.evidenceUrl(userId, d.id)}>{d.originalName}</a>
                  <div className="small muted">{d.uploadedByName} · <span className="num">{new Date(d.uploadedAt).toLocaleString(lang)}</span> · <span className="mono">{d.sha256.slice(0, 16)}…</span></div>
                </li>
              ))}
            </ul>
          )}
          {verifies && activationPending && verification.verifiedBy === session.userId && (
            <EvidenceForm texts={texts} userId={userId} onDone={load} />
          )}
        </Card>
      )}

      <Card title={p.requestsTitle} icon="users">
        {detail.requests.length === 0 ? <p className="muted">{p.noRequests}</p> : (
          <ul className="request-list">
            {detail.requests.map((r) => (
              <RequestItem key={r.id} texts={texts} lang={lang} request={r} viewerId={session.userId} showName={false}
                canDecide={can(session, 'admin.professional.approve')} onApprove={() => void decision.approve(r)} onReject={() => void decision.reject(r)} />
            ))}
          </ul>
        )}
      </Card>

      {suspends && profile.status !== 'REQUESTED' && profile.status !== 'REVOKED' && (
        <ChangeForm key={profile.status} texts={texts} profile={profile} onDone={done(p.changeRequested)} />
      )}

      <Card title={p.historyTitle} icon="clock">
        <History texts={texts} lang={lang} entries={detail.history} />
      </Card>
    </div>
  )
}

function VerifyForm({ texts, userId, onDone }: { texts: Texts; userId: string; onDone: () => Promise<void> }) {
  const p = texts.professionals
  const [body, setBody] = useState('')
  const [reference, setReference] = useState('')
  const [checkedOn, setCheckedOn] = useState(() => new Date().toISOString().slice(0, 10))
  const [note, setNote] = useState('')
  const [offices, setOffices] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailure(null)
    try {
      await professionalApi.verify(userId, { body, reference, checkedOn, note, offices: officeCodes(offices) })
      await onDone()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }
  const required = { required: true, requiredLabel: texts.common.required }
  return (
    <Card title={p.verifyTitle} icon="shield" description={p.verifyIntro}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form className="form" onSubmit={submit} data-verify-form>
        <Field label={p.body} {...required}><input maxLength={200} value={body} onChange={(e) => setBody(e.target.value)} /></Field>
        <Field label={p.reference} {...required}><input maxLength={200} value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
        <Field label={p.checkedOn} {...required}><input type="date" value={checkedOn} onChange={(e) => setCheckedOn(e.target.value)} /></Field>
        <Field label={p.note} hint={p.reasonHint} {...required}>
          <textarea rows={2} minLength={REASON_MIN} maxLength={REASON_MAX} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <Field label={p.offices} hint={p.officesHint} {...required}><input value={offices} onChange={(e) => setOffices(e.target.value)} /></Field>
        <div className="form-actions start"><Button type="submit" variant="primary" icon="check" busy={busy} disabled={busy}>{p.verifySubmit}</Button></div>
      </form>
    </Card>
  )
}

function EvidenceForm({ texts, userId, onDone }: { texts: Texts; userId: string; onDone: () => Promise<void> }) {
  const p = texts.professionals
  const [file, setFile] = useState<File | null>(null)
  const [chooser, setChooser] = useState(0)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const add = async () => {
    if (!file) return
    setBusy(true)
    setFailure(null)
    try {
      await professionalApi.addEvidence(userId, file)
      setFile(null)
      setChooser((n) => n + 1)
      await onDone()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="form form-inline" data-evidence-form>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <Field label={p.evidenceFile} hint={texts.register.documentFileHint}>
        <input key={chooser} type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </Field>
      <div className="form-actions start"><Button icon="upload" onClick={add} busy={busy} disabled={busy || !file}>{p.addEvidence}</Button></div>
    </div>
  )
}

/** The changes possible from each status; the database refuses any other. */
function changesFor(status: Profile['status']): ChangeKind[] {
  if (status === 'SUSPENDED') return ['REINSTATEMENT', 'REVOCATION', 'OFFICE_CHANGE']
  if (status === 'ACTIVE') return ['OFFICE_CHANGE', 'REVOCATION']
  return ['REVOCATION']
}

function ChangeForm({ texts, profile, onDone }: { texts: Texts; profile: Profile; onDone: () => Promise<void> }) {
  const p = texts.professionals
  const kinds = changesFor(profile.status)
  const [kind, setKind] = useState<ChangeKind>(kinds[0])
  const [add, setAdd] = useState('')
  const [remove, setRemove] = useState('')
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailure(null)
    try {
      const offices = kind === 'OFFICE_CHANGE' ? { officesAdd: officeCodes(add), officesRemove: officeCodes(remove) } : {}
      await professionalApi.requestChange(profile.userId, { kind, reason, ...offices })
      setReason('')
      setAdd('')
      setRemove('')
      await onDone()
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Card title={p.changeTitle} icon="key" description={p.changeIntro}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form className="form" onSubmit={submit} data-change-form>
        <Field label={p.changeKind}>
          <select value={kind} onChange={(e) => setKind(e.target.value as ChangeKind)}>
            {kinds.map((k) => <option key={k} value={k}>{p.changeKinds[k]}</option>)}
          </select>
        </Field>
        {kind === 'OFFICE_CHANGE' && (
          <>
            <Field label={p.officesAdd} hint={p.officesHint}><input value={add} onChange={(e) => setAdd(e.target.value)} /></Field>
            <Field label={p.officesRemove} hint={p.officesHint}><input value={remove} onChange={(e) => setRemove(e.target.value)} /></Field>
          </>
        )}
        <Field label={p.reason} hint={p.reasonHint} required requiredLabel={texts.common.required}>
          <textarea rows={2} minLength={REASON_MIN} maxLength={REASON_MAX} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <div className="form-actions start"><Button type="submit" busy={busy} disabled={busy}>{p.changeSubmit}</Button></div>
      </form>
    </Card>
  )
}
