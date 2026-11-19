// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The objections waiting for this supervisor (#/objections, ADR-042 step 4): objections to published cases at their
// offices, not yet decided, that they may decide (never one they recorded), oldest first. Each opens the case, where
// the objection is read and decided.
import { useEffect, useState } from 'react'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { proceduresApi } from './api'
import type { ObjectionQueueEntry } from './api'

export function ObjectionQueuePage({ ctx }: { ctx: PageContext }) {
  const { texts, lang } = ctx
  const k = texts.procedures
  const [rows, setRows] = useState<ObjectionQueueEntry[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    proceduresApi.objectionQueue()
      .then((r) => { if (current) { setRows(r); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page">
      <PageHeader title={k.objectionQueueTitle} description={k.objectionQueueIntro} breadcrumbLabel={texts.shell.breadcrumb} />
      <Alert tone="info">{k.demoNotice}</Alert>
      {failure && (
        <EmptyState icon="alert" title={failure}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{null}</EmptyState>
      )}
      {!failure && rows === null && <Skeleton lines={5} />}
      {rows && rows.length === 0 && <EmptyState icon="checkCircle" title={k.objectionQueueEmpty}>{null}</EmptyState>}
      {rows && rows.length > 0 && (
        <Card title={k.objectionQueueTitle} icon="file">
          <table className="table" data-objection-queue>
            <thead>
              <tr><th>{k.objectionNumber}</th><th>{k.queueCase}</th><th>{k.queueOffice}</th><th>{k.received}</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.objectionId} data-queue-objection={r.number}>
                  <td data-label={k.objectionNumber} className="mono">
                    {r.number}{r.late && <> <Badge tone="info">{k.late}</Badge></>}
                  </td>
                  <td data-label={k.queueCase}><a href={href(['cases', r.applicationId])} className="mono">{r.reference}</a></td>
                  <td data-label={k.queueOffice}>{r.officeCode}</td>
                  <td data-label={k.received} className="num">{new Date(r.receivedAt).toLocaleString(lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}
