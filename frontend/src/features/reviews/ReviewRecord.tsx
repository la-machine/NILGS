// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A case's review as staff see it: who is assigned at each stage, the recommendations with their
// findings and the returns with their reasons, and the decision with the documents it cites,
// under the simulated notice. Internal: the applicant never sees the review, only the decision.
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { DecisionCard } from '../applications'
import type { Review } from './api'

export function ReviewRecord({ texts, lang, review, citedNames }: {
  texts: Texts; lang: Lang; review: Review
  /** Display names of the cited versions (file names), by version id. */
  citedNames?: Record<string, string>
}) {
  const r = texts.reviews
  return (
    <>
      {review.decision && (
        <DecisionCard texts={texts} lang={lang} by={review.decision.decidedByName}
          decision={{ outcome: review.decision.outcome, grounds: review.decision.grounds, decidedAt: review.decision.at }}
          cited={review.decision.citedVersions.map((v) => citedNames?.[v] ?? v)} />
      )}
      {review.openAlerts > 0 && !review.decision && (
        <Alert tone="warning" title={r.alertTitle}>{fill(r.alertText, { n: review.openAlerts })}</Alert>
      )}
      <Card title={r.recordTitle} icon="audit">
        {review.assignments.length > 0 && (
          <dl className="properties">
            {review.assignments.map((a) => (
              <div key={a.stage}><dt>{r.assignedAt[a.stage]}</dt><dd>{a.assigneeName}</dd></div>
            ))}
          </dl>
        )}
        {review.steps.length === 0
          ? <p className="muted">{r.noStep}</p>
          : (
            <ol className="timeline">
              {review.steps.map((s, n) => (
                <li key={n} className={n === review.steps.length - 1 ? 'timeline-item active' : 'timeline-item'}>
                  <span className="timeline-dot" aria-hidden="true" />
                  <div className="timeline-body">
                    <div className="timeline-head">
                      <Badge tone={s.kind === 'RETURN' ? 'warning' : 'info'}>
                        {s.kind === 'RETURN' ? r.returned : r.recommendations[s.recommendation!]}
                      </Badge>
                    </div>
                    <p className="timeline-meta">{s.actorName} · <span className="num">{new Date(s.at).toLocaleString(lang)}</span></p>
                    <p className="timeline-reason pre-line">{s.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          )}
      </Card>
    </>
  )
}
