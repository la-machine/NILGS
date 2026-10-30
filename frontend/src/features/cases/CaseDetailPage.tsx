// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One case for staff of its office: who applied, for which parcel; what the automatic check
// finds missing now; where its DEMO fee stands (MVP-08, simulated); the answers and documents as
// submitted (downloads are audited); the
// completeness review step by step and the history; and, for the intake officer while it is in
// completeness review, the decision. Read only for everyone else.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { canAt, canSomewhere } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { AnswersCard, CompletenessCard, HistoryCard, messages, STATE_TONE, typeName } from '../applications'
import { DocumentsPanel } from '../documents'
import { AcknowledgmentCard } from '../issuance'
import { formatAmount, STANDING_TONE } from '../payments'
import { ReviewRecord, reviewsApi } from '../reviews'
import type { Review } from '../reviews'
import { casesApi } from './api'
import type { Case } from './api'
import { CompletenessDecision } from './CompletenessDecision'

export function CaseDetailPage({ ctx, id }: { ctx: PageContext; id: string }) {
  const { texts, session, lang } = ctx
  const c = texts.cases
  const a = texts.applications
  const [found, setFound] = useState<Case | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [decided, setDecided] = useState<string | null>(null)
  const [review, setReview] = useState<Review | null>(null)

  useEffect(() => {
    let current = true
    casesApi.read(id)
      .then((loaded) => {
        if (!current) return
        setFound(loaded)
        setFailure(null)
        // The review exists from the moment the case is accepted as complete.
        if (['UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED', 'REFUSED'].includes(loaded.state)) {
          reviewsApi.readForOffice(id).then((r) => { if (current) setReview(r) }).catch(() => { if (current) setReview(null) })
        }
      })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const crumbs = [{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.cases, href: href(['cases']) },
    { label: found?.reference ?? c.caseCrumb }]
  if (failure) {
    return (
      <div className="page">
        <PageHeader title={c.caseCrumb} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <EmptyState icon="alert" title={c.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      </div>
    )
  }
  if (!found) return <div className="page" aria-busy="true"><Skeleton lines={8} /></div>

  const inReview = found.state === 'COMPLETENESS_REVIEW'
  const canAccept = inReview && canAt(session, 'completeness.review', found.officeCode)
  const canRequest = inReview && canAt(session, 'completeness.request-info', found.officeCode)
  const missing = messages(texts, lang, found.type, found.missing)

  return (
    <div className="page">
      <PageHeader title={found.reference} description={typeName(lang, found.type)} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={crumbs} meta={<Badge tone={STATE_TONE[found.state]}>{a.states[found.state]}</Badge>}
        actions={canSomewhere(session, 'audit.event.search')
          ? <a className="btn btn-secondary" href={href(['audit', 'timeline'], { type: 'APPLICATION', reference: found.reference })}>
            {texts.timeline.open}</a>
          : undefined} />
      {decided && <Alert tone="success">{decided}</Alert>}
      <div className="detail-layout">
        <div className="detail-main">
          <AnswersCard texts={texts} lang={lang} type={found.type} data={found.data} />
          <DocumentsPanel texts={texts} lang={lang} applicationId={found.id} editable={false} source="case" />
          <AcknowledgmentCard texts={texts} lang={lang} applicationId={found.id} source="case" />
          {review && <ReviewRecord texts={texts} lang={lang} review={review} />}
          <CompletenessCard texts={texts} lang={lang} type={found.type} steps={found.completeness} />
          <HistoryCard texts={texts} lang={lang} history={found.history} />
        </div>
        <div className="detail-aside">
          <Card title={c.summaryTitle} icon="info">
            <dl className="properties">
              <div><dt>{c.applicant}</dt><dd>{found.applicantName}</dd></div>
              <div>
                <dt>{a.parcelReference}</dt>
                <dd><a href={href(['parcels', found.parcelReference])} className="mono">{found.parcelReference}</a></dd>
              </div>
              <div><dt>{texts.office}</dt><dd>{found.officeCode}</dd></div>
              <div><dt>{a.submittedOn}</dt><dd className="num">{new Date(found.submittedAt).toLocaleString(lang)}</dd></div>
              {found.payment && (
                <div data-payment={found.payment.standing}>
                  <dt>{c.payment}</dt>
                  <dd>
                    <Badge tone={STANDING_TONE[found.payment.standing]}>{texts.payments.standing[found.payment.standing]}</Badge>
                    {found.payment.amount !== null && found.payment.currency && (
                      <> <span className="num">{formatAmount(lang, found.payment.amount, found.payment.currency)}</span></>
                    )}
                    {found.payment.reference && <><br /><span className="mono small">{found.payment.reference}</span></>}
                  </dd>
                </div>
              )}
            </dl>
          </Card>
          <Card title={c.checkTitle} icon="checkCircle" description={c.checkIntro}>
            {missing.length === 0
              ? <p className="all-clear">{c.nothingMissing}</p>
              : <ul className="item-summary">{missing.map((m, n) => <li key={n}>{m.text}</li>)}</ul>}
          </Card>
          {(canAccept || canRequest) && (
            <CompletenessDecision key={found.version} texts={texts} lang={lang} found={found}
              canAccept={canAccept} canRequest={canRequest} onReload={() => setAttempt((n) => n + 1)}
              onDecided={(updated) => {
                setFound(updated)
                setDecided(updated.state === 'UNDER_REVIEW' ? fill(c.acceptedText, { reference: updated.reference }) : c.requestedText)
              }} />
          )}
        </div>
      </div>
    </div>
  )
}
