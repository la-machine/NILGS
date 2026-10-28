// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's applications, most recently changed first: drafts (no reference yet) and
// submitted ones with their reference and state. Only theirs exist here, as on the server.
import { useEffect, useState } from 'react'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { applicationsApi } from './api'
import type { ApplicationSummary, ApplicationType } from './api'
import { typeName } from './form'
import { STATE_TONE } from './states'

export function ApplicationListPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, lang } = ctx
  const a = texts.applications
  const [items, setItems] = useState<ApplicationSummary[] | null>(null)
  const [types, setTypes] = useState<ApplicationType[]>([])
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([applicationsApi.mine(), applicationsApi.types().catch(() => [] as ApplicationType[])])
      .then(([list, known]) => { if (current) { setItems(list); setTypes(known); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const nameOf = (code: string) => {
    const type = types.find((t) => t.code === code)
    return type ? typeName(lang, type) : code
  }
  const create = <a className="btn btn-primary" href={href(['applications', 'new'])}>{a.newTitle}</a>

  return (
    <div className="page">
      <PageHeader title={a.listTitle} description={a.listIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.applications }]} actions={create} />
      {params.get('deleted') && <Alert tone="success">{a.deleted}</Alert>}
      <Card padded={false} title={a.listTitle}>
        {failure && (
          <EmptyState icon="alert" title={a.loadFailed}
            action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{failure}</EmptyState>
        )}
        {!failure && items === null && <div className="card-pad"><Skeleton lines={4} /></div>}
        {items && items.length === 0 && <EmptyState icon="file" title={a.none} action={create}>{a.noneText}</EmptyState>}
        {items && items.length > 0 && (
          <table className="table table-responsive">
            <thead>
              <tr>
                <th scope="col">{a.reference}</th>
                <th scope="col">{a.type}</th>
                <th scope="col">{a.parcelReference}</th>
                <th scope="col">{a.state}</th>
                <th scope="col">{a.lastChange}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td data-label={a.reference}>
                    <a href={href(['applications', item.id])} className={item.reference ? 'mono strong' : 'strong'}>
                      {item.reference ?? a.draft}
                    </a>
                  </td>
                  <td data-label={a.type}>{nameOf(item.typeCode)}</td>
                  <td data-label={a.parcelReference} className="mono">{item.parcelReference ?? '—'}</td>
                  <td data-label={a.state}><Badge tone={STATE_TONE[item.state]}>{a.states[item.state]}</Badge></td>
                  <td data-label={a.lastChange} className="num">{new Date(item.updatedAt).toLocaleString(lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}
