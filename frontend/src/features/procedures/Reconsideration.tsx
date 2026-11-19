// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The reconsideration of an approved case, for staff (ADR-043 step 5b, D41, DEMO only). Each round opened: the decision
// it reconsiders, the objections upheld after that decision it answers, the stages reopened (those the objections named,
// any added, and the publication), the justification, who opened it, and its round's outcome once decided. A supervisor
// the server allows (never one who handled the case or upheld the objection) opens the next round with a written
// justification, adding stages if needed; never fewer. A registered title is never reopened this way (D42).
import { useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { Field } from '../../shared/ui/Field'
import { errorMessage } from '../../shared/ui/errorMessage'
import { JUSTIFICATION_MAX, JUSTIFICATION_MIN, proceduresApi } from './api'
import type { CaseProcedure, StageCode } from './api'

/** Problems worth telling a supervisor who holds the permission: what they, or a conservator, must do first. */
const EXPLAINED = new Set(['CREATION_IN_PROGRESS', 'APPLICANT_CANNOT_REOPEN', 'DECISION_AUTHORITY_CANNOT_REOPEN', 'ASSIGNEE_CANNOT_REOPEN',
  'STAGE_RECORDER_CANNOT_REOPEN', 'OBJECTION_DECIDER_CANNOT_REOPEN'])

export function ReconsiderationSection({ texts, lang, data, onSaved }: {
  texts: Texts; lang: Lang; data: CaseProcedure; onSaved: (d: CaseProcedure) => void
}) {
  const k = texts.procedures
  const r = k.reconsideration
  const [opening, setOpening] = useState(false)
  const reasons = texts.errors as Record<string, string>
  const stageName = (s: string) => (k.stageNames as Record<string, string>)[s] ?? s
  const explained = data.reconsiderProblem && EXPLAINED.has(data.reconsiderProblem)
  if (data.reconsiderations.length === 0 && data.reconsiderProblem !== null && !explained) return null
  return (
    <section className="stack" data-reconsiderations>
      <h3>{r.title}</h3>
      <p className="small muted">{r.intro}</p>
      {data.round > 0 && <Badge tone="info">{fill(r.round, { n: String(data.round) })}</Badge>}
      <ul className="plain-list replacement-list">
        {data.reconsiderations.map((c) => (
          <li key={c.id} data-reconsideration={c.round}>
            <div className="exception-head">
              <Badge tone="warning">{fill(r.round, { n: String(c.round) })}</Badge>
              <span className="small">{c.openedBySelf ? r.openedBySelf : fill(r.openedBy, { name: c.openedByName })}</span>
              <span className="small muted num"> · {new Date(c.openedAt).toLocaleString(lang)}</span>
            </div>
            <div className="small">{fill(r.reconsidered, { outcome: (r.outcomes as Record<string, string>)[c.reconsideredOutcome] ?? c.reconsideredOutcome })}</div>
            <div className="small">{fill(r.answers, { objections: c.objectionNumbers.join(', ') })}</div>
            <div className="small" data-stages>{fill(r.stages, { stages: c.stages.map(stageName).join(', ') })}</div>
            {c.addedStages.length > 0 && <div className="small muted">{fill(r.added, { stages: c.addedStages.map(stageName).join(', ') })}</div>}
            <div className="small pre-line">{c.justification}</div>
            <Badge tone={c.roundOutcome === null ? 'neutral' : c.roundOutcome === 'APPROVED' ? 'success' : 'danger'}>
              {c.roundOutcome === null ? r.pending : fill(r.outcome, { outcome: (r.outcomes as Record<string, string>)[c.roundOutcome] ?? c.roundOutcome })}
            </Badge>
          </li>
        ))}
      </ul>
      {explained && (
        <div data-reconsider-problem={data.reconsiderProblem}>
          <Alert tone="info">{reasons[data.reconsiderProblem!] ?? data.reconsiderProblem}</Alert>
        </div>
      )}
      {data.reconsiderProblem === null && !opening && (
        <div className="form-actions start">
          <Button variant="secondary" onClick={() => setOpening(true)}>{r.open}</Button>
        </div>
      )}
      {data.reconsiderProblem === null && opening && (
        <ReconsiderationForm texts={texts} data={data} onCancel={() => setOpening(false)}
          onSaved={(d) => { setOpening(false); onSaved(d) }} />
      )}
    </section>
  )
}

function ReconsiderationForm({ texts, data, onCancel, onSaved }: {
  texts: Texts; data: CaseProcedure; onCancel: () => void; onSaved: (d: CaseProcedure) => void
}) {
  const k = texts.procedures
  const r = k.reconsideration
  const [added, setAdded] = useState<StageCode[]>([])
  const [justification, setJustification] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const length = justification.trim().length
  const valid = length >= JUSTIFICATION_MIN && length <= JUSTIFICATION_MAX
  const toggle = (s: StageCode) => setAdded((list) => (list.includes(s) ? list.filter((x) => x !== s) : [...list, s]))
  const submit = async () => {
    setBusy(true)
    setFailure(null)
    try {
      onSaved(await proceduresApi.reconsider(data.id, data.version, added, justification.trim()))
    } catch (e) {
      setFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="form" data-reconsideration-form>
      <Alert tone="warning">{r.formIntro}</Alert>
      <fieldset className="stack" data-added-stages>
        <legend className="small">{r.addStages}</legend>
        <p className="small muted">{r.addStagesHint}</p>
        {data.stages.filter((s) => s.code !== 'PUBLICATION').map((s) => (
          <label key={s.code} className="small">
            <input type="checkbox" checked={added.includes(s.code)} onChange={() => toggle(s.code)} />{' '}
            {(k.stageNames as Record<string, string>)[s.code] ?? s.code}
          </label>
        ))}
      </fieldset>
      <Field label={r.justification} hint={r.justificationHint}>
        <textarea rows={4} maxLength={JUSTIFICATION_MAX} value={justification} onChange={(e) => setJustification(e.target.value)} />
      </Field>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <div className="form-actions start">
        <Button variant="primary" disabled={!valid || busy} onClick={() => void submit()}>{r.confirm}</Button>
        <Button variant="ghost" onClick={onCancel}>{k.cancel}</Button>
      </div>
    </div>
  )
}
