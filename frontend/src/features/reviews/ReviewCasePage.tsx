// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A case for its reviewer or decision authority (MVP-05): the case itself (applicant, parcel,
// answers, documents, completeness, history) beside its review, and what they can do now. Only
// cases assigned to them or waiting at their stage exist here, as the server answers.
import { useEffect, useState } from 'react'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { AnswersCard, CompletenessCard, HistoryCard, STATE_TONE, typeName } from '../applications'
import type { Case } from '../cases'
import { DocumentsPanel, documentsApi } from '../documents'
import { reviewsApi } from './api'
import type { Review } from './api'
import { ReviewPanel } from './ReviewPanel'
import type { Citable } from './ReviewPanel'
import { ReviewRecord } from './ReviewRecord'

export function ReviewCasePage({ ctx, id }: { ctx: PageContext; id: string }) {
  const { texts, session, lang } = ctx
  const a = texts.applications
  const [found, setFound] = useState<Case | null>(null)
  const [review, setReview] = useState<Review | null>(null)
  const [citable, setCitable] = useState<Citable[]>([])
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([reviewsApi.readCase(id), reviewsApi.read(id), documentsApi.list(id, 'assigned')])
      .then(([c, r, docs]) => {
        if (!current) return
        setFound(c)
        setReview(r)
        // The decision cites what was submitted: every stored version, latest first in each category.
        setCitable(docs.categories.flatMap((cat) => cat.versions.filter((v) => v.status === 'STORED').map((v) => ({
          id: v.id, label: `${lang === 'fr' ? cat.labelFr : cat.labelEn} — ${v.originalName} (v${v.versionNo})`,
        }))))
        setFailure(null)
      })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const crumbs = [{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.reviews, href: href(['reviews']) },
    { label: found?.reference ?? texts.cases.caseCrumb }]
  if (failure) {
    return (
      <div className="page">
        <PageHeader title={texts.cases.caseCrumb} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <EmptyState icon="alert" title={texts.cases.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      </div>
    )
  }
  if (!found || !review) return <div className="page" aria-busy="true"><Skeleton lines={8} /></div>

  const citedNames = Object.fromEntries(citable.map((c) => [c.id, c.label]))
  return (
    <div className="page">
      <PageHeader title={found.reference} description={typeName(lang, found.type)} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={crumbs} meta={<Badge tone={STATE_TONE[review.state]}>{a.states[review.state]}</Badge>} />
      <div className="detail-layout">
        <div className="detail-main">
          <AnswersCard texts={texts} lang={lang} type={found.type} data={found.data} />
          <DocumentsPanel texts={texts} lang={lang} applicationId={found.id} editable={false} source="assigned" />
          <CompletenessCard texts={texts} lang={lang} type={found.type} steps={found.completeness} />
          <HistoryCard texts={texts} lang={lang} history={found.history} />
        </div>
        <div className="detail-aside">
          <Card title={texts.cases.summaryTitle} icon="info">
            <dl className="properties">
              <div><dt>{texts.cases.applicant}</dt><dd>{found.applicantName}</dd></div>
              <div>
                <dt>{a.parcelReference}</dt>
                <dd><a href={href(['parcels', found.parcelReference])} className="mono">{found.parcelReference}</a></dd>
              </div>
              <div><dt>{texts.office}</dt><dd>{found.officeCode}</dd></div>
            </dl>
          </Card>
          <ReviewPanel key={review.version} texts={texts} session={session} review={review} citable={citable}
            onChanged={(updated) => { setReview(updated); setAttempt((n) => n + 1) }} onReload={() => setAttempt((n) => n + 1)} />
          <ReviewRecord texts={texts} lang={lang} review={review} citedNames={citedNames} />
        </div>
      </div>
    </div>
  )
}
