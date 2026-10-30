// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One of the applicant's applications: a draft opens as its form; once submitted, where it
// stands (state, since when, whose turn: MVP-06) and its DEMO payment (MVP-08, simulated); while
// information is requested, the request,
// the files to add and the response; otherwise its reference, parcel, office, the answers and
// documents as submitted (never editable again), the completeness review and the history.
// Someone else's application is "not found", as the server answers.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { DocumentsPanel } from '../documents'
import { AcknowledgmentCard } from '../issuance'
import { PaymentPanel } from '../payments'
import { applicationsApi } from './api'
import type { Application } from './api'
import { AnswersCard, CompletenessCard, DecisionCard, HistoryCard } from './CaseRecord'
import { DraftForm } from './DraftForm'
import { typeName } from './form'
import { InfoRequestPanel } from './InfoRequestPanel'
import { STATE_TONE } from './states'
import { TrackingCard } from './TrackingCard'

export function ApplicationDetailPage({ ctx, id, params }: { ctx: PageContext; id: string; params: URLSearchParams }) {
  const { texts, lang } = ctx
  const a = texts.applications
  const [application, setApplication] = useState<Application | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [responded, setResponded] = useState(false)

  useEffect(() => {
    let current = true
    applicationsApi.read(id)
      .then((loaded) => { if (current) { setApplication(loaded); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const crumbs = [{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.applications, href: href(['applications']) },
    { label: application?.reference ?? a.draft }]

  if (failure) {
    return (
      <div className="page page-narrow">
        <PageHeader title={a.draft} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <EmptyState icon="alert" title={a.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      </div>
    )
  }
  if (!application) return <div className="page page-narrow" aria-busy="true"><Skeleton lines={8} /></div>

  const submittedRef = params.get('submitted')
  return (
    <div className="page page-narrow">
      <PageHeader title={application.reference ?? a.draft} description={typeName(lang, application.type)}
        breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs}
        meta={<Badge tone={STATE_TONE[application.state]}>{a.states[application.state]}</Badge>} />
      {submittedRef && application.reference === submittedRef && (
        <Alert tone="success" title={fill(a.submittedTitle, { reference: submittedRef })}>{a.submittedText}</Alert>
      )}
      {responded && <Alert tone="success" title={texts.completeness.respondedTitle}>{texts.completeness.respondedText}</Alert>}
      {params.get('saved') && application.state === 'DRAFT' && <Alert tone="success">{a.saved}</Alert>}
      {application.state !== 'DRAFT' && <TrackingCard texts={texts} lang={lang} application={application} />}
      {application.state !== 'DRAFT' && <AcknowledgmentCard texts={texts} lang={lang} applicationId={application.id} />}
      {application.state !== 'DRAFT' && <PaymentPanel texts={texts} lang={lang} applicationId={application.id} />}
      {application.state === 'DRAFT' && <DraftForm ctx={ctx} type={application.type} draft={application} />}
      {application.state === 'INFO_REQUESTED' && (
        <InfoRequestPanel key={application.version} texts={texts} lang={lang} application={application}
          onReload={() => setAttempt((n) => n + 1)}
          onResponded={(updated) => { setApplication(updated); setResponded(true) }} />
      )}
      {application.state !== 'DRAFT' && <Submitted texts={texts} lang={lang} application={application} />}
    </div>
  )
}

function Submitted({ texts, lang, application }: { texts: Texts; lang: Lang; application: Application }) {
  const a = texts.applications
  return (
    <>
      {application.decision && (
        <DecisionCard texts={texts} lang={lang}
          decision={{ outcome: application.decision.outcome, grounds: application.decision.grounds, decidedAt: application.decision.decidedAt }} />
      )}
      <Card title={a.summaryTitle} icon="info">
        <dl className="properties">
          <div><dt>{a.reference}</dt><dd className="mono strong">{application.reference}</dd></div>
          <div><dt>{a.parcelReference}</dt><dd className="mono">{application.parcelReference}</dd></div>
          <div><dt>{texts.office}</dt><dd>{application.officeCode}</dd></div>
          <div>
            <dt>{a.submittedOn}</dt>
            <dd className="num">{application.submittedAt && new Date(application.submittedAt).toLocaleString(lang)}</dd>
          </div>
        </dl>
      </Card>
      <AnswersCard texts={texts} lang={lang} type={application.type} data={application.data} />
      {application.state !== 'INFO_REQUESTED' && (
        <DocumentsPanel texts={texts} lang={lang} applicationId={application.id} editable={false} />
      )}
      <CompletenessCard texts={texts} lang={lang} type={application.type} steps={application.completeness} />
      <HistoryCard texts={texts} lang={lang} history={application.history} />
    </>
  )
}
