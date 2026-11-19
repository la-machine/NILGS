// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The persons of a case at intake (ADR-043): what the applicant declared, each identity transcribed from a document
// presented at the counter, the ownership statement and the mandates, each checked by a second officer. Every entry is
// labelled DEMO, not independently verified; "corresponds to the document presented" is all a check says. Corrections
// are new versions with a reason; every earlier version stays. The buttons only offer what applies; the server decides.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { Field } from '../../shared/ui/Field'
import { errorMessage } from '../../shared/ui/errorMessage'
import { EXCEPTION_REASON_MIN, latestMandates, NOTE_MIN, OWNERSHIP_INTERESTS, partiesApi, problemParts, PURPOSES, REASON_MIN } from './partiesApi'
import type { CaseParties, CasePerson, CheckKind, Identity, Mandate, OwnershipInterest, PartyCheck, PartyPurpose, Statement } from './partiesApi'

interface Props {
  texts: Texts; lang: Lang; applicationId: string; viewerId: string; mayRecord: boolean; mayCheck: boolean; mayAuthorise?: boolean
}

export function PartiesPanel({ texts, lang, applicationId, viewerId, mayRecord, mayCheck, mayAuthorise = false }: Props) {
  const k = texts.parties
  const [data, setData] = useState<CaseParties | null>(null)
  const [hidden, setHidden] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    partiesApi.read(applicationId).then((d) => { if (current) setData(d) }).catch(() => { if (current) setHidden(true) })
    return () => { current = false }
  }, [applicationId])

  if (hidden) return null
  if (!data) return <Card title={k.title} icon="users"><Skeleton lines={4} /></Card>
  const run = async (write: () => Promise<CaseParties>) => {
    setFailure(null)
    try {
      setData(await write())
      return true
    } catch (e) {
      setFailure(errorMessage(texts, e))
      return false
    }
  }
  const refOf = (id: string) => data.persons.find((p) => p.id === id)?.ref ?? id
  const nameOf = (ref: string) => data.persons.find((p) => p.ref === ref)?.identities[0]?.fullName ?? ref
  const statement = data.statements[0]
  const context: Ctx = { texts, lang, viewerId, mayRecord, mayCheck, mayAuthorise, data, run, applicationId }
  return (
    <Card title={k.title} icon="users" description={k.intro}>
      <div data-parties />
      <Alert tone="warning" title={k.unverifiedTitle}>{k.unverifiedText}</Alert>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {data.problems.length > 0
        ? (
          <Alert tone="info" title={k.problemsTitle}>
            <ul data-party-problems>{data.problems.map((p) => <li key={p}>{problemText(texts, p, nameOf)}</li>)}</ul>
          </Alert>
        )
        : <Alert tone="success">{k.ready}</Alert>}
      <p className="muted small">{fill(k.capacityLine, { capacity: (k.capacities as Record<string, string>)[data.capacity ?? ''] ?? data.capacity ?? '-' })}</p>

      <h3>{k.filesTitle}</h3>
      {data.files.length === 0 ? <p className="muted small">{k.noFiles}</p> : (
        <ul className="plain-list" data-party-files>
          {data.files.map((f) => (
            <li key={f.id}>
              <Badge tone="neutral">{k.purposes[f.purpose]}</Badge>{' '}
              <a href={partiesApi.fileUrl(applicationId, f.id)}>{f.originalName}</a>{' '}
              <span className="muted small mono">{f.sha256.slice(0, 12)}…</span>
            </li>
          ))}
        </ul>
      )}
      {mayRecord && <FileForm {...context} />}

      <h3>{k.personsTitle}</h3>
      <ul className="plain-list exception-list">
        {data.persons.map((p) => <PersonItem key={`${p.id}-${p.identities[0]?.id}`} {...context} person={p} />)}
      </ul>

      <h3>{k.ownershipTitle}</h3>
      {statement && (
        <div className="exception-item" data-statement={statement.versionNo}>
          <div className="exception-head">
            <SourceBadge texts={texts} source={statement.source} />
            <span className="small muted">{fill(k.versionLine, { n: statement.versionNo, who: statement.enteredByName })}</span>
          </div>
          <ul className="small">
            {statement.shares.map((s) => (
              <li key={`${s.personId}-${s.interest}`}>
                {nameOf(refOf(s.personId))}: {k.interests[s.interest]} {s.shareNum}/{s.shareDen}
                {s.usufructTerm && ` · ${s.usufructTerm === 'LIFE' ? k.termLife : fill(k.termUntil, { date: s.usufructUntil ?? '' })}`}
              </li>
            ))}
          </ul>
          <CheckLine texts={texts} check={statement.check} />
          {statement.source === 'RECORDED' && !statement.check && mayCheck && statement.enteredById !== viewerId && (
            <CheckForm {...context} kind="OWNERSHIP" targetId={statement.id} />
          )}
          {mayRecord && <OwnershipForm {...context} statement={statement} />}
          {data.statements.length > 1 && <p className="muted small">{fill(k.earlier, { n: data.statements.length - 1 })}</p>}
        </div>
      )}

      <h3>{k.mandatesTitle}</h3>
      {latestMandates(data.mandates).length === 0 ? <p className="muted small">{k.noMandates}</p> : (
        <ul className="plain-list exception-list">
          {latestMandates(data.mandates).map((m) => <MandateItem key={m.id} {...context} mandate={m} />)}
        </ul>
      )}
    </Card>
  )
}

interface Ctx extends Props { data: CaseParties; run: (write: () => Promise<CaseParties>) => Promise<boolean> }

function problemText(texts: Texts, code: string, nameOf: (ref: string) => string): string {
  const { kind, ref } = problemParts(code)
  const text = (texts.parties.problems as Record<string, string>)[kind] ?? kind
  return ref ? `${text} (${nameOf(ref)})` : text
}

function SourceBadge({ texts, source }: { texts: Texts; source: 'DECLARED' | 'RECORDED' }) {
  return <Badge tone={source === 'RECORDED' ? 'info' : 'neutral'}>{texts.parties.sources[source]}</Badge>
}

function CheckLine({ texts, check }: { texts: Texts; check: PartyCheck | null }) {
  const k = texts.parties
  if (!check) return <p className="small muted">{k.notChecked}</p>
  return check.outcome === 'MATCHES'
    ? <p className="small" data-check="MATCHES"><Badge tone="success">{k.matches}</Badge> {fill(k.checkedBy, { who: check.checkedByName })}</p>
    : <p className="small" data-check="DOES_NOT_MATCH"><Badge tone="danger">{k.doesNotMatch}</Badge> {check.note}</p>
}

function PersonItem(props: Ctx & { person: CasePerson }) {
  const { texts, person, data, viewerId, mayRecord, mayCheck, run, applicationId } = props
  const k = texts.parties
  const latest: Identity | undefined = person.identities[0]
  const [open, setOpen] = useState(false)
  if (!latest) return null
  return (
    <li className="exception-item" data-person={person.ref}>
      <div className="exception-head">
        <strong>{latest.fullName}</strong>
        {person.roles.map((r) => <Badge key={r} tone="neutral">{k.roles[r]}</Badge>)}
        <SourceBadge texts={texts} source={latest.source} />
      </div>
      <dl className="properties small">
        <div><dt>{k.identity}</dt><dd className="mono">{latest.identityKind} {latest.identityNumber}</dd></div>
        {latest.phone && <div><dt>{k.phone}</dt><dd>{latest.phone}</dd></div>}
        {latest.validUntil && (
          <div>
            <dt>{k.validUntil}</dt>
            <dd>{latest.validUntil}{latest.expiredAtTranscription && <> <Badge tone="danger">{k.expiredAtTranscription}</Badge></>}</dd>
          </div>
        )}
        {latest.exception && (
          <div><dt>{k.exceptionTitle}</dt><dd>{fill(k.exceptionLine, { who: latest.exception.authorisedByName, reason: latest.exception.reason })}</dd></div>
        )}
        <div><dt>{k.verification}</dt><dd>{k.unverifiedShort}</dd></div>
        <div><dt>{k.entered}</dt><dd>{fill(k.versionLine, { n: latest.versionNo, who: latest.enteredByName })}</dd></div>
        {latest.correctionReason && <div><dt>{k.reason}</dt><dd>{latest.correctionReason}</dd></div>}
      </dl>
      {latest.source === 'RECORDED' && <CheckLine texts={texts} check={latest.check} />}
      {latest.source === 'RECORDED' && !latest.check && mayCheck && latest.enteredById !== viewerId && (
        <CheckForm {...props} kind="IDENTITY" targetId={latest.id} />
      )}
      {latest.expiredAtTranscription && !latest.exception && props.mayAuthorise && latest.enteredById !== viewerId && (
        <ExceptionForm texts={texts} applicationId={applicationId} identityId={latest.id} run={run} />
      )}
      {mayRecord && (open
        ? <IdentityForm texts={texts} data={data} person={person} latest={latest} applicationId={applicationId} run={run} onDone={() => setOpen(false)} />
        : <div className="form-actions start">
            <Button variant="secondary" onClick={() => setOpen(true)}>{latest.source === 'DECLARED' ? k.transcribe : k.correct}</Button>
          </div>)}
      {person.identities.length > 1 && <p className="muted small">{fill(k.earlier, { n: person.identities.length - 1 })}</p>}
    </li>
  )
}

function IdentityForm({ texts, data, person, latest, applicationId, run, onDone }: {
  texts: Texts; data: CaseParties; person: CasePerson; latest: Identity; applicationId: string
  run: Ctx['run']; onDone: () => void
}) {
  const k = texts.parties
  const files = data.files.filter((f) => f.purpose === 'IDENTITY_DOCUMENT')
  const [fullName, setFullName] = useState(latest.fullName)
  const [identityKind, setIdentityKind] = useState(latest.identityKind)
  const [identityNumber, setIdentityNumber] = useState(latest.identityNumber)
  const [phone, setPhone] = useState(latest.phone ?? '')
  const [validUntil, setValidUntil] = useState(latest.validUntil ?? '')
  const [source, setSource] = useState(files[files.length - 1]?.id ?? '')
  const [reason, setReason] = useState('')
  const correcting = latest.source === 'RECORDED'
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await run(() => partiesApi.recordIdentity(applicationId, person.id, {
      fullName: fullName.trim(), identityKind, identityNumber: identityNumber.trim(), phone: phone.trim() || null, validUntil: validUntil || null,
      sourceFile: source,
      supersedes: latest.id, correctionReason: correcting ? reason.trim() : null,
    }))) onDone()
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)} noValidate data-identity-form>
      <p className="muted small">{k.transcribeIntro}</p>
      <Field label={k.fullName}><input value={fullName} maxLength={200} onChange={(e) => setFullName(e.target.value)} /></Field>
      <Field label={k.identityKind}>
        <select value={identityKind} onChange={(e) => setIdentityKind(e.target.value)}>
          {['NATIONAL_ID', 'PASSPORT', 'RESIDENCE_PERMIT', 'COMPANY_REGISTRATION'].map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      </Field>
      <Field label={k.identityNumber}><input value={identityNumber} maxLength={64} onChange={(e) => setIdentityNumber(e.target.value)} /></Field>
      {person.roles.includes('APPLICANT') && <Field label={k.phone}><input value={phone} maxLength={24} onChange={(e) => setPhone(e.target.value)} /></Field>}
      <Field label={k.validUntil} hint={k.validUntilHint}><input type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} /></Field>
      <Field label={k.sourceFile} hint={files.length === 0 ? k.scanFirst : undefined}>
        <select value={source} onChange={(e) => setSource(e.target.value)}>
          {files.map((f) => <option key={f.id} value={f.id}>{f.originalName}</option>)}
        </select>
      </Field>
      {correcting && <Field label={k.reason} hint={k.reasonHint}><textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>}
      <div className="form-actions start">
        <Button type="submit" variant="primary" disabled={!source || (correcting && reason.trim().length < REASON_MIN)}>{k.save}</Button>
        <Button variant="ghost" onClick={onDone}>{texts.common.cancel}</Button>
      </div>
    </form>
  )
}

function CheckForm({ texts, applicationId, run, kind, targetId }: Ctx & { kind: CheckKind; targetId: string }) {
  const k = texts.parties
  const [mismatch, setMismatch] = useState(false)
  const [note, setNote] = useState('')
  return (
    <div className="form" data-check-form={kind}>
      <p className="muted small">{k.checkIntro}</p>
      {mismatch && <Field label={k.note}><textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></Field>}
      <div className="form-actions start">
        {!mismatch && <Button variant="primary" onClick={() => void run(() => partiesApi.check(applicationId, kind, targetId, 'MATCHES', null))}>{k.confirmMatches}</Button>}
        {!mismatch && <Button variant="danger" onClick={() => setMismatch(true)}>{k.reportMismatch}</Button>}
        {mismatch && (
          <Button variant="danger" disabled={note.trim().length < NOTE_MIN}
            onClick={() => void run(() => partiesApi.check(applicationId, kind, targetId, 'DOES_NOT_MATCH', note.trim()))}>{k.recordMismatch}</Button>
        )}
      </div>
    </div>
  )
}

function ExceptionForm({ texts, applicationId, identityId, run }: { texts: Texts; applicationId: string; identityId: string; run: Ctx['run'] }) {
  const k = texts.parties
  const [reason, setReason] = useState('')
  return (
    <div className="form" data-exception-form>
      <p className="muted small">{k.exceptionIntro}</p>
      <Field label={k.exceptionReason} hint={k.exceptionHint}><textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
      <div className="form-actions start">
        <Button variant="secondary" disabled={reason.trim().length < EXCEPTION_REASON_MIN}
          onClick={() => void run(() => partiesApi.authoriseException(applicationId, identityId, reason.trim()))}>{k.authoriseException}</Button>
      </div>
    </div>
  )
}

function FileForm({ texts, applicationId, run }: Ctx) {
  const k = texts.parties
  const [purpose, setPurpose] = useState<PartyPurpose>('IDENTITY_DOCUMENT')
  const [file, setFile] = useState<File | null>(null)
  return (
    <form className="form form-inline" data-party-file-form
      onSubmit={(e) => { e.preventDefault(); if (file) void run(() => partiesApi.addFile(applicationId, purpose, file)).then((ok) => ok && setFile(null)) }}>
      <Field label={k.purpose}>
        <select value={purpose} onChange={(e) => setPurpose(e.target.value as PartyPurpose)}>
          {PURPOSES.map((p) => <option key={p} value={p}>{k.purposes[p]}</option>)}
        </select>
      </Field>
      <Field label={k.file}><input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
      <div className="form-actions start"><Button type="submit" icon="upload" disabled={!file}>{k.scan}</Button></div>
    </form>
  )
}

function OwnershipForm({ texts, data, applicationId, run, statement }: Ctx & { statement: Statement }) {
  const k = texts.parties
  const owners = data.persons.filter((p) => p.roles.includes('INTENDED_OWNER'))
  const files = data.files.filter((f) => f.purpose === 'OWNERSHIP_EVIDENCE')
  const [open, setOpen] = useState(false)
  // Starts from the statement as it stands, never from an equal split.
  const [rows, setRows] = useState(() => owners.map((o) => {
    const s = statement.shares.find((x) => x.personId === o.id)
    return { person: o.id, interest: (s?.interest ?? 'CO_OWNERSHIP') as OwnershipInterest, num: s ? String(s.shareNum) : '', den: s ? String(s.shareDen) : '',
      term: s?.usufructTerm ?? 'LIFE', until: s?.usufructUntil ?? '' }
  }))
  const [basis, setBasis] = useState(files[files.length - 1]?.id ?? '')
  const [reason, setReason] = useState('')
  const correcting = statement.source === 'RECORDED'
  if (!open) return <div className="form-actions start"><Button variant="secondary" onClick={() => setOpen(true)}>{correcting ? k.correct : k.recordOwnership}</Button></div>
  const set = (i: number, patch: Partial<(typeof rows)[number]>) => setRows((r) => r.map((x, n) => (n === i ? { ...x, ...patch } : x)))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await run(() => partiesApi.recordOwnership(applicationId, rows.map((r) => ({
      person: r.person, interest: r.interest, shareNum: Number(r.num), shareDen: Number(r.den), basisFile: basis,
      ...(r.interest === 'USUFRUCT' ? { usufructTerm: r.term, usufructUntil: r.term === 'UNTIL' ? r.until : null } : {}),
    })), statement.id, correcting ? reason.trim() : null))) setOpen(false)
  }
  return (
    <form className="form" onSubmit={(e) => void submit(e)} noValidate data-ownership-form>
      <p className="muted small">{k.ownershipIntro}</p>
      {rows.map((r, i) => (
        <div key={r.person} className="form form-inline">
          <span>{owners[i].identities[0]?.fullName}</span>
          <select aria-label={k.interest} value={r.interest} onChange={(e) => set(i, { interest: e.target.value as OwnershipInterest })}>
            {OWNERSHIP_INTERESTS.map((o) => <option key={o} value={o}>{k.interests[o]}</option>)}
          </select>
          <input aria-label={k.shareNum} inputMode="numeric" value={r.num} onChange={(e) => set(i, { num: e.target.value })} />
          <span>/</span>
          <input aria-label={k.shareDen} inputMode="numeric" value={r.den} onChange={(e) => set(i, { den: e.target.value })} />
          {r.interest === 'USUFRUCT' && (
            <>
              <select aria-label={k.term} value={r.term} onChange={(e) => set(i, { term: e.target.value as 'LIFE' | 'UNTIL' })}>
                <option value="LIFE">{k.termLife}</option><option value="UNTIL">{k.termUntilOption}</option>
              </select>
              {r.term === 'UNTIL' && <input aria-label={k.termDate} type="date" value={r.until} onChange={(e) => set(i, { until: e.target.value })} />}
            </>
          )}
        </div>
      ))}
      <Field label={k.basisFile} hint={files.length === 0 ? k.scanFirst : undefined}>
        <select value={basis} onChange={(e) => setBasis(e.target.value)}>{files.map((f) => <option key={f.id} value={f.id}>{f.originalName}</option>)}</select>
      </Field>
      {correcting && <Field label={k.reason} hint={k.reasonHint}><textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>}
      <div className="form-actions start">
        <Button type="submit" variant="primary" disabled={!basis || rows.some((r) => !r.num || !r.den) || (correcting && reason.trim().length < REASON_MIN)}>
          {k.save}
        </Button>
        <Button variant="ghost" onClick={() => setOpen(false)}>{texts.common.cancel}</Button>
      </div>
    </form>
  )
}

function MandateItem(props: Ctx & { mandate: Mandate }) {
  const { texts, data, viewerId, mayRecord, mayCheck, run, applicationId, mandate: m } = props
  const k = texts.parties
  const owners = data.persons.filter((p) => p.roles.includes('INTENDED_OWNER') && p.id !== m.representativeId)
  const files = data.files.filter((f) => f.purpose === 'MANDATE')
  const nameOf = (ref: string) => data.persons.find((p) => p.ref === ref)?.identities[0]?.fullName ?? ref
  const [open, setOpen] = useState(false)
  const [represents, setRepresents] = useState<string[]>(owners.filter((o) => m.represents.includes(o.ref)).map((o) => o.id))
  const [scope, setScope] = useState(m.scope)
  const [doc, setDoc] = useState(files[files.length - 1]?.id ?? '')
  const [reason, setReason] = useState('')
  const correcting = m.source === 'RECORDED'
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (await run(() => partiesApi.recordMandate(applicationId, m.representativeId, represents, scope, doc, m.id, correcting ? reason.trim() : null))) setOpen(false)
  }
  return (
    <li className="exception-item" data-mandate={m.representativeRef}>
      <div className="exception-head">
        <strong>{nameOf(m.representativeRef)}</strong> <SourceBadge texts={texts} source={m.source} />
        <span className="small">{fill(k.represents, { names: m.represents.map(nameOf).join(', ') })} · {k.scopes[m.scope as 'APPLY' | 'APPLY_AND_COLLECT'] ?? m.scope}</span>
      </div>
      {m.source === 'RECORDED' && <CheckLine texts={texts} check={m.check} />}
      {m.source === 'RECORDED' && !m.check && mayCheck && m.enteredById !== viewerId && <CheckForm {...props} kind="MANDATE" targetId={m.id} />}
      {mayRecord && (open
        ? (
          <form className="form" onSubmit={(e) => void submit(e)} noValidate data-mandate-form>
            <fieldset>
              <legend>{k.representsLegend}</legend>
              {owners.map((o) => (
                <label key={o.id} className="small">
                  <input type="checkbox" checked={represents.includes(o.id)}
                    onChange={(e) => setRepresents((r) => (e.target.checked ? [...r, o.id] : r.filter((x) => x !== o.id)))} />
                  {o.identities[0]?.fullName}
                </label>
              ))}
            </fieldset>
            <Field label={k.scope}>
              <select value={scope} onChange={(e) => setScope(e.target.value)}>
                <option value="APPLY">{k.scopes.APPLY}</option><option value="APPLY_AND_COLLECT">{k.scopes.APPLY_AND_COLLECT}</option>
              </select>
            </Field>
            <Field label={k.mandateFile} hint={files.length === 0 ? k.scanFirst : undefined}>
              <select value={doc} onChange={(e) => setDoc(e.target.value)}>{files.map((f) => <option key={f.id} value={f.id}>{f.originalName}</option>)}</select>
            </Field>
            {correcting && <Field label={k.reason} hint={k.reasonHint}><textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>}
            <div className="form-actions start">
              <Button type="submit" variant="primary" disabled={!doc || represents.length === 0 || (correcting && reason.trim().length < REASON_MIN)}>{k.save}</Button>
              <Button variant="ghost" onClick={() => setOpen(false)}>{texts.common.cancel}</Button>
            </div>
          </form>
        )
        : <div className="form-actions start"><Button variant="secondary" onClick={() => setOpen(true)}>{correcting ? k.correct : k.recordMandate}</Button></div>)}
    </li>
  )
}
