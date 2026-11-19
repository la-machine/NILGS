// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The stages waiting for this officer (#/procedures, ADR-042): cases under review at their offices, with a stage their
// permissions record and that is not completed yet, oldest case first. Each opens the case, where the stage is recorded.
import { useEffect, useState } from 'react'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { proceduresApi } from './api'
import type { QueueEntry } from './api'
import { STATUS_TONE } from './stages'

export function ProcedureQueuePage({ ctx }: { ctx: PageContext }) {
  const { texts, lang } = ctx
  const k = texts.procedures
  const [rows, setRows] = useState<QueueEntry[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    proceduresApi.queue()
      .then((r) => { if (current) { setRows(r); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page">
      <PageHeader title={k.title} description={k.intro} breadcrumbLabel={texts.shell.breadcrumb} />
      <Alert tone="info">{k.demoNotice}</Alert>
      {failure && (
        <EmptyState icon="alert" title={failure}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{null}</EmptyState>
      )}
      {!failure && rows === null && <Skeleton lines={5} />}
      {rows && rows.length === 0 && <EmptyState icon="checkCircle" title={k.queueEmpty}>{null}</EmptyState>}
      {rows && rows.length > 0 && (
        <Card title={k.title} icon="file">
          <table className="table" data-procedure-queue>
            <thead>
              <tr><th>{k.queueCase}</th><th>{k.queueStage}</th><th>{k.queueStatus}</th><th>{k.queueOffice}</th><th>{k.queueSubmitted}</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.applicationId}:${r.stage}`} data-queue-entry={`${r.reference}:${r.stage}`}>
                  <td data-label={k.queueCase}><a href={href(['cases', r.applicationId])} className="mono">{r.reference}</a></td>
                  <td data-label={k.queueStage}>{(k.stageNames as Record<string, string>)[r.stage] ?? r.stage}</td>
                  <td data-label={k.queueStatus}><Badge tone={STATUS_TONE[r.stageStatus]}>{k.statuses[r.stageStatus]}</Badge></td>
                  <td data-label={k.queueOffice}>{r.officeCode}</td>
                  <td data-label={k.queueSubmitted} className="num">{new Date(r.submittedAt).toLocaleDateString(lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}
