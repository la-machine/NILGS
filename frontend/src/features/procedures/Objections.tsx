// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The publication's notices and the objections of a case, for staff (ADR-042 step 4, DEMO only). The current notice and
// its period; each objection with its objector (the identity only for those who receive or decide objections: the
// server leaves it out for everyone else), its statement, files and decision. An intake officer records an objection
// received at the counter; a supervisor who did not record it decides it, with grounds, naming the stages to examine
// again when it is upheld. The server and the database decide who may do what; the screens only follow their hints.
import { useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { Field } from '../../shared/ui/Field'
import { errorMessage } from '../../shared/ui/errorMessage'
import { GROUNDS_MAX, GROUNDS_MIN, INSTRUCTIONS_MAX, INTERESTS, MAX_FILES, proceduresApi, STATEMENT_MAX, STATEMENT_MIN } from './api'
import type { CaseProcedure, Interest, Notice, Objection, ObjectionOutcome, ObjectorIdentity, StageCode } from './api'

const newKey = () => (typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`)

export function NoticeSummary({ texts, lang, notices }: { texts: Texts; lang: Lang; notices: Notice[] }) {
  const k = texts.procedures
  const current = notices.find((n) => n.current)
  if (!current) return null
  return (
    <div className="small" data-notice={current.number} data-period-open={current.periodOpen}>
      <Badge tone={current.periodOpen ? 'info' : 'neutral'}>{current.periodOpen ? k.periodOpen : k.periodOver}</Badge>{' '}
      {fill(k.noticeLine, {
        number: current.number, parcel: current.parcelReference, locality: current.locality,
        closes: new Date(current.closesAt).toLocaleString(lang),
      })}
      {notices.length > 1 && <span className="muted"> · {fill(k.noticesBefore, { n: String(notices.length - 1) })}</span>}
    </div>
  )
}

export function ObjectionsSection({ texts, lang, data, onSaved }: {
  texts: Texts; lang: Lang; data: CaseProcedure; onSaved: (d: CaseProcedure) => void
}) {
  const k = texts.procedures
  const [recording, setRecording] = useState(false)
  const [deciding, setDeciding] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  if (data.notices.length === 0 && data.objections.length === 0) return null
  const stages = data.stages.map((s) => s.code)
  return (
    <section className="stack" data-objections>
      <h3>{k.objectionsTitle}</h3>
      <p className="small muted">{k.objectionsIntro}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {data.objections.length === 0 && <p className="small muted">{k.noObjection}</p>}
      <ul className="plain-list replacement-list">
        {data.objections.map((o) => (
          <ObjectionItem key={o.id} texts={texts} lang={lang} applicationId={data.id} objection={o}
            deciding={deciding === o.id} onDecide={() => setDeciding(o.id)} onCancel={() => setDeciding(null)}
            stages={stages} onFailure={setFailure} onSaved={(d) => { setDeciding(null); setFailure(null); onSaved(d) }} />
        ))}
      </ul>
      {data.mayRecordObjection && !recording && (
        <div className="form-actions start">
          <Button variant="ghost" onClick={() => setRecording(true)}>{k.recordObjection}</Button>
        </div>
      )}
      {recording && (
        <ObjectionForm texts={texts} applicationId={data.id} onFailure={setFailure} onCancel={() => setRecording(false)}
          onSaved={(d) => { setRecording(false); setFailure(null); onSaved(d) }} />
      )}
    </section>
  )
}

function ObjectionItem({ texts, lang, applicationId, objection, deciding, onDecide, onCancel, stages, onFailure, onSaved }: {
  texts: Texts; lang: Lang; applicationId: string; objection: Objection; deciding: boolean; onDecide: () => void; onCancel: () => void
  stages: StageCode[]; onFailure: (m: string | null) => void; onSaved: (d: CaseProcedure) => void
}) {
  const k = texts.procedures
  const o = objection
  const d = o.decision
  const [identity, setIdentity] = useState<ObjectorIdentity | null>(null)
  const showIdentity = async () => {
    try {
      setIdentity(await proceduresApi.objectorIdentity(applicationId, o.id))
    } catch (e) {
      onFailure(errorMessage(texts, e))
    }
  }
  return (
    <li data-objection={o.number} data-decided={d ? d.outcome : 'OPEN'} data-late={o.late}>
      <Badge tone={!d ? 'warning' : d.outcome === 'UPHELD' ? 'danger' : 'neutral'}>
        {!d ? k.objectionOpen : k.objectionOutcomes[d.outcome]}
      </Badge>{' '}
      <strong className="mono">{o.number}</strong>
      {o.late && <> <Badge tone="info">{k.late}</Badge></>}
      <div className="small">
        {fill(k.objectorLine, { name: o.objector.name, interest: k.interests[o.objector.interest] })}
        {identity && <span className="mono" data-objector-identity> · {identity.identityKind} {identity.identityNumber}</span>}
        {o.objector.identityVisible && !identity && (
          <> · <Button variant="ghost" onClick={() => void showIdentity()}>{k.showIdentity}</Button></>
        )}
      </div>
      <div className="small">{o.statement}</div>
      <div className="small muted">
        {fill(k.receivedLine, {
          date: new Date(o.receivedAt).toLocaleString(lang), name: o.recordedBySelf ? k.you : o.recordedByName, notice: o.noticeNumber,
        })}
      </div>
      {o.files.length > 0 && (
        <ul className="plain-list">
          {o.files.map((f) => <li key={f.id}><a href={proceduresApi.objectionFileUrl(applicationId, o.id, f.id)}>{f.originalName}</a></li>)}
        </ul>
      )}
      {d && (
        <div className="small" data-objection-decision>
          {fill(k.decidedLine, { name: d.decidedBySelf ? k.you : d.decidedByName, date: new Date(d.decidedAt).toLocaleString(lang) })}
          {': '}{d.grounds}
          {d.reviewStages.length > 0 && (
            <div className="muted">
              {fill(k.reviewStagesLine, { stages: d.reviewStages.map((s) => (k.stageNames as Record<string, string>)[s] ?? s).join(', ') })}
            </div>
          )}
          {d.applicantInstructions && <div className="muted">{fill(k.applicantInstructionsLine, { text: d.applicantInstructions })}</div>}
        </div>
      )}
      {!d && o.mayDecide && !deciding && (
        <div className="form-actions start"><Button variant="ghost" onClick={onDecide}>{k.decideObjection}</Button></div>
      )}
      {deciding && (
        <DecisionForm texts={texts} applicationId={applicationId} objectionId={o.id} stages={stages}
          onCancel={onCancel} onFailure={onFailure} onSaved={onSaved} />
      )}
    </li>
  )
}

function ObjectionForm({ texts, applicationId, onSaved, onCancel, onFailure }: {
  texts: Texts; applicationId: string; onSaved: (d: CaseProcedure) => void; onCancel: () => void; onFailure: (m: string | null) => void
}) {
  const k = texts.procedures
  // One key per form: a second press, or a retry after a network failure, gives back the same objection.
  const [key] = useState(newKey)
  const [name, setName] = useState('')
  const [identityKind, setIdentityKind] = useState('NATIONAL_ID')
  const [identityNumber, setIdentityNumber] = useState('')
  const [interest, setInterest] = useState<Interest>('NEIGHBOUR')
  const [statement, setStatement] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [tried, setTried] = useState(false)
  const [busy, setBusy] = useState(false)
  const nameError = tried && name.trim().length < 3 ? k.objectorNameRequired : null
  const identityError = tried && identityNumber.trim().length < 5 ? k.identityRequired : null
  const statementError = tried && statement.trim().length < STATEMENT_MIN ? k.statementTooShort : null

  const submit = async () => {
    setTried(true)
    if (name.trim().length < 3 || identityNumber.trim().length < 5 || statement.trim().length < STATEMENT_MIN) return
    setBusy(true)
    onFailure(null)
    try {
      const written = await proceduresApi.recordObjection(applicationId, {
        objectorName: name.trim(), identityKind, identityNumber: identityNumber.trim(), interest, statement: statement.trim(),
      }, files, key)
      onSaved(written.procedure)
    } catch (e) {
      onFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="stack" data-objection-form onSubmit={(e) => { e.preventDefault(); void submit() }}>
      <p className="small muted">{k.counterOnly}</p>
      <Field label={k.objectorName} hint={k.objectorNameHint} error={nameError}>
        <input maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label={k.identityKind}>
        <select value={identityKind} onChange={(e) => setIdentityKind(e.target.value)}>
          {['NATIONAL_ID', 'PASSPORT', 'RESIDENCE_PERMIT'].map((v) => <option key={v} value={v}>{k.identityKinds[v as 'NATIONAL_ID']}</option>)}
        </select>
      </Field>
      <Field label={k.identityNumber} hint={k.identityNumberHint} error={identityError}>
        <input maxLength={64} value={identityNumber} onChange={(e) => setIdentityNumber(e.target.value)} />
      </Field>
      <Field label={k.interest}>
        <select value={interest} onChange={(e) => setInterest(e.target.value as Interest)}>
          {INTERESTS.map((i) => <option key={i} value={i}>{k.interests[i]}</option>)}
        </select>
      </Field>
      <Field label={k.statement} hint={k.statementHint} error={statementError}>
        <textarea rows={3} maxLength={STATEMENT_MAX} value={statement} onChange={(e) => setStatement(e.target.value)} />
      </Field>
      <Field label={k.files}>
        <input type="file" multiple accept="application/pdf,image/jpeg,image/png"
          onChange={(e) => setFiles(Array.from(e.target.files ?? []).slice(0, MAX_FILES))} />
      </Field>
      <div className="form-actions start">
        <Button type="submit" disabled={busy}>{k.saveObjection}</Button>
        <Button variant="ghost" disabled={busy} onClick={onCancel}>{k.cancel}</Button>
      </div>
    </form>
  )
}

function DecisionForm({ texts, applicationId, objectionId, stages, onSaved, onCancel, onFailure }: {
  texts: Texts; applicationId: string; objectionId: string; stages: StageCode[]
  onSaved: (d: CaseProcedure) => void; onCancel: () => void; onFailure: (m: string | null) => void
}) {
  const k = texts.procedures
  const [outcome, setOutcome] = useState<ObjectionOutcome>('DISMISSED')
  const [grounds, setGrounds] = useState('')
  const [review, setReview] = useState<StageCode[]>([])
  const [instructions, setInstructions] = useState('')
  const [tried, setTried] = useState(false)
  const [busy, setBusy] = useState(false)
  const groundsError = tried && grounds.trim().length < GROUNDS_MIN ? k.groundsTooShort : null
  const reviewError = tried && outcome === 'UPHELD' && review.length === 0 ? k.reviewStagesRequired : null
  const instructionsError = tried && outcome === 'UPHELD' && instructions.trim().length > 0 && instructions.trim().length < 10
    ? k.instructionsTooShort : null
  const toggle = (s: StageCode) => setReview(review.includes(s) ? review.filter((x) => x !== s) : [...review, s])

  const submit = async () => {
    setTried(true)
    if (grounds.trim().length < GROUNDS_MIN || (outcome === 'UPHELD' && review.length === 0)) return
    if (outcome === 'UPHELD' && instructions.trim().length > 0 && instructions.trim().length < 10) return
    setBusy(true)
    onFailure(null)
    try {
      onSaved(await proceduresApi.decideObjection(applicationId, objectionId, outcome, grounds.trim(), outcome === 'UPHELD' ? review : [],
        outcome === 'UPHELD' && instructions.trim() ? instructions.trim() : null))
    } catch (e) {
      onFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="stack" data-decision-form onSubmit={(e) => { e.preventDefault(); void submit() }}>
      <Field label={k.objectionOutcome}>
        <select value={outcome} onChange={(e) => setOutcome(e.target.value as ObjectionOutcome)}>
          <option value="DISMISSED">{k.objectionOutcomes.DISMISSED}</option>
          <option value="UPHELD">{k.objectionOutcomes.UPHELD}</option>
        </select>
      </Field>
      {outcome === 'UPHELD' && (
        <fieldset className="stack" data-review-stages>
          <legend className="small">{k.reviewStages}</legend>
          <p className="small muted">{k.upheldNeverRefuses}</p>
          {stages.map((s) => (
            <label key={s} className="small">
              <input type="checkbox" checked={review.includes(s)} onChange={() => toggle(s)} />{' '}
              {(k.stageNames as Record<string, string>)[s] ?? s}
            </label>
          ))}
          {reviewError && <span className="field-error small">{reviewError}</span>}
        </fieldset>
      )}
      {outcome === 'UPHELD' && (
        <Field label={k.applicantInstructions} hint={k.applicantInstructionsHint} error={instructionsError}>
          <textarea rows={2} maxLength={INSTRUCTIONS_MAX} value={instructions} onChange={(e) => setInstructions(e.target.value)} />
        </Field>
      )}
      <Field label={k.grounds} hint={k.groundsHint} error={groundsError}>
        <textarea rows={3} maxLength={GROUNDS_MAX} value={grounds} onChange={(e) => setGrounds(e.target.value)} />
      </Field>
      <div className="form-actions start">
        <Button type="submit" disabled={busy}>{k.saveDecision}</Button>
        <Button variant="ghost" disabled={busy} onClick={onCancel}>{k.cancel}</Button>
      </div>
    </form>
  )
}
