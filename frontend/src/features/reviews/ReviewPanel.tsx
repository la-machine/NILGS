// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the reviewer or decision authority can do on this case now (MVP-05):
//   - claim it, when it waits at their stage and nobody has it;
//   - the reviewer assigned: a recommendation with its finding;
//   - the decision authority assigned: return it to review with a reason, or decide APPROVED or
//     REFUSED with grounds and the documents cited, under the simulated notice; blocked while an
//     overlap alert on the parcel is open, and never by whoever recommended.
// Each action is confirmed; a case changed meanwhile overwrites nothing.
import { useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { canAssigned } from '../../shared/session/access'
import type { Session } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert } from '../../shared/ui/Feedback'
import { useDialog } from '../../shared/ui/dialogContext'
import { errorMessage } from '../../shared/ui/errorMessage'
import { OUTCOMES, RECOMMENDATIONS, reviewsApi, TEXT_MAX, TEXT_MIN } from './api'
import type { Outcome, Recommendation, Review } from './api'

/** A document version the decision may cite. */
export interface Citable { id: string; label: string }

export function ReviewPanel({ texts, session, review, citable, onChanged, onReload }: {
  texts: Texts; session: Session; review: Review; citable: Citable[]
  onChanged: (updated: Review) => void
  onReload: () => void
}) {
  const r = texts.reviews
  const dialog = useDialog()
  const assignee = (stage: 'REVIEW' | 'DECISION') => review.assignments.find((a) => a.stage === stage)
  const reviewer = canAssigned(session, 'review.recommend')
  const authority = canAssigned(session, 'decision.record')
  const recommendedByMe = review.youRecommended
  const [recommendation, setRecommendation] = useState<Recommendation | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [text, setText] = useState('')
  const [cited, setCited] = useState<string[]>([])
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  const length = text.trim().length
  const textError = touched && (length < TEXT_MIN || length > TEXT_MAX) ? fill(r.textBounds, { min: TEXT_MIN, max: TEXT_MAX }) : null

  const run = async (title: string, message: string, label: string, action: () => Promise<Review>) => {
    const ok = await dialog.confirm({ title, message, confirmLabel: label, cancelLabel: texts.common.cancel })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      onChanged(await action())
    } catch (error) {
      if (error instanceof ApiError && (error.code === 'STALE_VERSION' || error.code === 'INVALID_TRANSITION')) setStale(true)
      else setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const claimable = (review.state === 'UNDER_REVIEW' && reviewer && !assignee('REVIEW'))
    || (review.state === 'PENDING_DECISION' && authority && !assignee('DECISION') && !recommendedByMe)
  const recommending = review.state === 'UNDER_REVIEW' && reviewer && !!assignee('REVIEW')?.mine
  const deciding = review.state === 'PENDING_DECISION' && authority && !!assignee('DECISION')?.mine

  if (!claimable && !recommending && !deciding) {
    if (review.state === 'PENDING_DECISION' && authority && recommendedByMe) {
      return <Alert tone="info" title={r.sodTitle}>{r.sodText}</Alert>
    }
    return null
  }

  return (
    <Card title={r.actionTitle} icon="shield">
      {stale && (
        <Alert tone="warning" title={r.staleTitle}
          action={<Button size="sm" icon="refresh" onClick={onReload}>{texts.applications.reload}</Button>}>{r.staleText}</Alert>
      )}
      {failure && <Alert tone="danger">{failure}</Alert>}

      {claimable && (
        <div className="decision-row">
          <Button variant="primary" icon="user" busy={busy} disabled={busy}
            onClick={() => run(r.claimTitle, fill(r.claimText, { reference: review.reference }), r.claim,
              () => reviewsApi.claim(review.id, review.state === 'UNDER_REVIEW' ? 'REVIEW' : 'DECISION'))}>
            {r.claim}
          </Button>
          <span className="muted">{r.claimHint}</span>
        </div>
      )}

      {recommending && (
        <div className="form">
          <fieldset className="resolution-choices">
            <legend className="field-label">{r.recommendationLabel}</legend>
            {RECOMMENDATIONS.map((value) => (
              <label key={value} className="resolution-choice">
                <input type="radio" name="recommendation" checked={recommendation === value} onChange={() => setRecommendation(value)} />
                <strong>{r.recommendations[value]}</strong>
              </label>
            ))}
          </fieldset>
          <Field label={r.finding} hint={r.findingHint} error={textError} required requiredLabel={texts.common.required}>
            <textarea rows={5} value={text} maxLength={TEXT_MAX + 100} onChange={(e) => setText(e.target.value)} onBlur={() => setTouched(true)} />
          </Field>
          <div className="form-actions start">
            <Button variant="primary" icon="arrowRight" busy={busy} disabled={busy || stale}
              onClick={() => {
                setTouched(true)
                if (!recommendation || length < TEXT_MIN || length > TEXT_MAX) return
                void run(r.recommendTitle, fill(r.recommendText, { recommendation: r.recommendations[recommendation] }), r.recommend,
                  () => reviewsApi.recommend(review.id, { expectedVersion: review.version, recommendation, finding: text.trim() }))
              }}>{r.recommend}</Button>
          </div>
        </div>
      )}

      {deciding && (
        <div className="form">
          <p className="simulated-notice" role="note">{texts.decision.simulated}</p>
          {review.openAlerts > 0 && <Alert tone="warning">{fill(r.alertText, { n: review.openAlerts })}</Alert>}
          <fieldset className="resolution-choices">
            <legend className="field-label">{r.outcomeLabel}</legend>
            {OUTCOMES.map((value) => (
              <label key={value} className="resolution-choice">
                <input type="radio" name="outcome" checked={outcome === value} onChange={() => setOutcome(value)} />
                <strong>{texts.decision.outcomes[value]}</strong>
              </label>
            ))}
          </fieldset>
          <Field label={r.grounds} hint={r.groundsHint} error={textError} required requiredLabel={texts.common.required}>
            <textarea rows={5} value={text} maxLength={TEXT_MAX + 100} onChange={(e) => setText(e.target.value)} onBlur={() => setTouched(true)} />
          </Field>
          <fieldset className="checkbox-list">
            <legend className="field-label">{r.citedLabel}</legend>
            {citable.map((c) => (
              <label key={c.id} className="checkbox">
                <input type="checkbox" checked={cited.includes(c.id)}
                  onChange={() => setCited((now) => (now.includes(c.id) ? now.filter((x) => x !== c.id) : [...now, c.id]))} />
                <span>{c.label}</span>
              </label>
            ))}
            {touched && cited.length === 0 && <p className="field-error">{r.citeOne}</p>}
          </fieldset>
          <div className="form-actions start">
            <Button variant="primary" icon="checkCircle" busy={busy} disabled={busy || stale || review.openAlerts > 0}
              onClick={() => {
                setTouched(true)
                if (!outcome || length < TEXT_MIN || length > TEXT_MAX || cited.length === 0) return
                void run(r.decideTitle, fill(r.decideText, { outcome: texts.decision.outcomes[outcome] }), r.decide,
                  () => reviewsApi.decide(review.id, { expectedVersion: review.version, outcome, grounds: text.trim(), citedVersions: cited }))
              }}>{r.decide}</Button>
            <Button icon="arrowLeft" busy={busy} disabled={busy || stale}
              onClick={() => {
                setTouched(true)
                if (length < TEXT_MIN || length > TEXT_MAX) return
                void run(r.returnTitle, r.returnText, r.return,
                  () => reviewsApi.returnToReview(review.id, { expectedVersion: review.version, reason: text.trim() }))
              }}>{r.return}</Button>
          </div>
        </div>
      )}
    </Card>
  )
}
