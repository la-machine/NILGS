// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Starting an application: the type (the demonstration catalogue has one), then its form. No
// reference is issued yet: the draft is the applicant's alone until submitted.
import { useEffect, useState } from 'react'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { PageHeader } from '../../shared/ui/Card'
import { EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { applicationsApi } from './api'
import type { ApplicationType } from './api'
import { DraftForm } from './DraftForm'
import { typeName } from './form'

export function ApplicationNewPage({ ctx, typeCode }: { ctx: PageContext; typeCode: string | null }) {
  const { texts, lang } = ctx
  const a = texts.applications
  const [types, setTypes] = useState<ApplicationType[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    applicationsApi.types()
      .then((list) => { if (current) { setTypes(list); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const chosen = types && (types.find((t) => t.code === typeCode) ?? (types.length === 1 ? types[0] : null))
  const crumbs = [{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.applications, href: href(['applications']) },
    { label: a.newTitle }]

  return (
    <div className="page page-narrow">
      <PageHeader title={chosen ? typeName(lang, chosen) : a.newTitle} description={a.newIntro}
        breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
      {failure && (
        <EmptyState icon="alert" title={a.loadFailed}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      )}
      {!failure && types === null && <Skeleton lines={6} />}
      {types && types.length === 0 && <EmptyState icon="file" title={a.noType}>{a.noTypeText}</EmptyState>}
      {types && !chosen && types.length > 1 && (
        <ul className="action-tiles">
          {types.map((t) => (
            <li key={t.code}>
              <a className="action-tile" href={href(['applications', 'new'], { type: t.code })}>
                <span className="action-tile-title">{typeName(lang, t)}</span>
              </a>
            </li>
          ))}
        </ul>
      )}
      {chosen && <DraftForm ctx={ctx} type={chosen} draft={null} />}
    </div>
  )
}
