// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What an application holds once submitted, shown alike to its applicant and to staff of its
// office: the answers as submitted, the completeness review step by step (what the automatic
// check found, what was asked, what was said), the history of its states, and the decision,
// always labelled "Simulated decision — no legal effect", on screen and when printed. Read only.
// Staff read who acted; the applicant reads only which party (themselves, the office, the
// system), as the server sends it (MVP-06).
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Badge } from '../../shared/ui/Feedback'
import type { Tone } from '../../shared/ui/Feedback'
import type { ApplicantCompletenessStep, ApplicationType, CompletenessKind, CompletenessStep, Party, Step, Transition } from './api'
import { documentLabel, label, messages } from './form'

const KIND_TONE: Record<CompletenessKind, Tone> = { AUTOMATIC: 'neutral', COMPLETE: 'success', INFO_REQUESTED: 'warning', RESPONSE: 'info' }

/** Who acted: the person for staff, the party for the applicant; the system when nobody did. */
function who(texts: Texts, step: { actorName: string | null } | { by: Party }): string {
  return 'by' in step ? texts.tracking.parties[step.by] : step.actorName ?? texts.completeness.system
}

export function AnswersCard({ texts, lang, type, data }: { texts: Texts; lang: Lang; type: ApplicationType; data: Record<string, string> }) {
  const a = texts.applications
  const shown = (key: string, value: string) => {
    const option = type.requirements.fields.find((f) => f.key === key)?.options?.find((o) => o.code === value)
    return option ? label(lang, option) : value
  }
  return (
    <Card title={a.answersTitle} icon="file" description={a.answersIntro}>
      <dl className="properties">
        {type.requirements.fields.filter((f) => data[f.key]).map((f) => (
          <div key={f.key}><dt>{label(lang, f)}</dt><dd className="pre-line">{shown(f.key, data[f.key])}</dd></div>
        ))}
      </dl>
    </Card>
  )
}

export function CompletenessCard({ texts, lang, type, steps }: {
  texts: Texts; lang: Lang; type: ApplicationType; steps: CompletenessStep[] | ApplicantCompletenessStep[]
}) {
  const c = texts.completeness
  if (steps.length === 0) return null
  return (
    <Card title={c.historyTitle} icon="audit">
      <ol className="timeline">
        {steps.map((step, n) => (
          <li key={n} className={n === steps.length - 1 ? 'timeline-item active' : 'timeline-item'} data-step={step.kind}>
            <span className="timeline-dot" aria-hidden="true" />
            <div className="timeline-body">
              <div className="timeline-head"><Badge tone={KIND_TONE[step.kind]}>{c.kinds[step.kind]}</Badge></div>
              <p className="timeline-meta">
                {who(texts, step)} · <span className="num">{new Date(step.at).toLocaleString(lang)}</span>
              </p>
              {step.kind === 'AUTOMATIC' && (
                <p className="timeline-reason">
                  {step.missing.length === 0
                    ? c.nothingMissing
                    : fill(c.missingThen, { list: messages(texts, lang, type, step.missing).map((m) => m.text).join(' ') })}
                </p>
              )}
              {step.requested.length > 0 && (
                <p className="timeline-reason">
                  {fill(c.requestedList, { list: step.requested.map((code) => documentLabel(lang, type, code)).join(', ') })}
                </p>
              )}
              {step.message && <p className="timeline-reason pre-line">« {step.message} »</p>}
            </div>
          </li>
        ))}
      </ol>
    </Card>
  )
}

export function HistoryCard({ texts, lang, history }: { texts: Texts; lang: Lang; history: Transition[] | Step[] }) {
  const a = texts.applications
  return (
    <Card title={a.historyTitle} icon="history">
      <ol className="timeline">
        {history.map((t, n) => (
          <li key={n} className={n === history.length - 1 ? 'timeline-item active' : 'timeline-item'}>
            <span className="timeline-dot" aria-hidden="true" />
            <div className="timeline-body">
              <div className="timeline-head"><span className="strong">{a.states[t.to]}</span></div>
              <p className="timeline-meta">
                {who(texts, t)} · <span className="num">{new Date(t.at).toLocaleString(lang)}</span>
              </p>
            </div>
          </li>
        ))}
      </ol>
    </Card>
  )
}

/**
 * The decision (MVP-05): its outcome and grounds, under the notice the Baseline requires on every
 * screen and printout. {@code by} and {@code cited} are for staff; the applicant sees neither.
 */
export function DecisionCard({ texts, lang, decision, by, cited }: {
  texts: Texts; lang: Lang
  decision: { outcome: 'APPROVED' | 'REFUSED'; grounds: string; decidedAt: string }
  by?: string
  cited?: string[]
}) {
  const d = texts.decision
  return (
    <section className="card decision-card" aria-labelledby="decision-title" data-outcome={decision.outcome}>
      <p className="simulated-notice" role="note">{d.simulated}</p>
      <div className="card-header">
        <h2 id="decision-title" className="card-title">{d.title}</h2>
        <Button size="sm" variant="ghost" icon="download" className="no-print" onClick={() => window.print()}>{d.print}</Button>
      </div>
      <div className="card-body">
        <p className="decision-outcome">
          <Badge tone={decision.outcome === 'APPROVED' ? 'success' : 'danger'}>{d.outcomes[decision.outcome]}</Badge>
          <span className="muted num"> · {new Date(decision.decidedAt).toLocaleString(lang)}</span>
          {by && <span className="muted"> · {by}</span>}
        </p>
        <p className="pre-line">{decision.grounds}</p>
        {cited && cited.length > 0 && <p className="muted">{fill(d.cited, { list: cited.join(', ') })}</p>}
      </div>
    </section>
  )
}
