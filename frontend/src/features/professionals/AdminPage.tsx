// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The administrators' page of professional accounts (#/professionals): the requests waiting for a
// second administrator, then the accounts by status, each opening its own page. Reading needs
// admin.professional.read; deciding needs admin.professional.approve, and the database refuses the
// requester and the verifier whatever the screen shows.
import { useEffect, useState } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { professionalApi, STATUSES } from './api'
import type { ChangeRequest, Profile } from './api'
import { useDecision } from './decisions'
import { RequestItem } from './RequestItem'
import { STATUS_TONE } from './tones'

/** The server's page of accounts (ProfessionalService.PAGE_SIZE): a full page may have a next one. */
const PAGE_SIZE = 50

export function AdminPage({ ctx }: { ctx: PageContext }) {
  const { texts, session, lang, run } = ctx
  const p = texts.professionals
  const [queue, setQueue] = useState<ChangeRequest[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const loadQueue = async () => setQueue(await professionalApi.pending())
  useEffect(() => { void run(loadQueue) }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const decision = useDecision(ctx, async () => { setNotice(p.decided); await loadQueue() })

  return (
    <div className="page">
      <PageHeader title={p.adminTitle} description={p.adminIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: href(['dashboard']) }, { label: p.adminTitle }]} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}
      <Card title={p.queueTitle} icon="userCheck" description={p.queueIntro}>
        {queue === null ? <Skeleton lines={3} /> : queue.length === 0 ? <EmptyState icon="checkCircle" title={p.noQueue} /> : (
          <ul className="request-list" data-professional-queue>
            {queue.map((r) => (
              <RequestItem key={r.id} texts={texts} lang={lang} request={r} viewerId={session.userId} showName
                canDecide={can(session, 'admin.professional.approve')} onApprove={() => void decision.approve(r)} onReject={() => void decision.reject(r)} />
            ))}
          </ul>
        )}
      </Card>
      <Directory texts={texts} lang={lang} run={ctx.run} />
    </div>
  )
}

function Directory({ texts, lang, run }: { texts: Texts; lang: Lang; run: PageContext['run'] }) {
  const p = texts.professionals
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<Profile[] | null>(null)
  useEffect(() => {
    void run(async () => setRows(await professionalApi.profiles(status, page)))
  }, [status, page]) // eslint-disable-line react-hooks/exhaustive-deps
  const go = (to: number) => { setRows(null); setPage(to) }
  return (
    <Card title={p.directoryTitle} icon="users" padded={false}
      actions={(
        <Field label={p.status}>
          <select value={status} onChange={(e) => { setRows(null); setPage(0); setStatus(e.target.value) }}>
            <option value="">{p.all}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{p.statuses[s]}</option>)}
          </select>
        </Field>
      )}>
      {rows === null && <div className="card-pad"><Skeleton lines={4} /></div>}
      {rows !== null && rows.length === 0 && <EmptyState icon="users" title={p.noProfiles} />}
      {rows !== null && rows.length > 0 && (
        <table className="table table-responsive" data-professionals>
          <thead>
            <tr>
              <th scope="col">{p.name}</th><th scope="col">{p.profession}</th><th scope="col">{p.status}</th>
              <th scope="col">{p.offices}</th><th scope="col">{p.requestedAt}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.userId} data-professional={r.email}>
                <td data-label={p.name}>
                  <a className="strong" href={href(['professionals', r.userId])}>{r.displayName}</a>
                  <div className="small muted">{r.email}</div>
                </td>
                <td data-label={p.profession}>{p.professions[r.profession]}</td>
                <td data-label={p.status}><Badge tone={STATUS_TONE[r.status]}>{p.statuses[r.status]}</Badge></td>
                <td data-label={p.offices}>{r.offices.length > 0 ? r.offices.join(', ') : p.none}</td>
                <td data-label={p.requestedAt} className="num">{new Date(r.requestedAt).toLocaleDateString(lang)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rows !== null && (page > 0 || rows.length >= PAGE_SIZE) && (
        <div className="form-actions card-pad">
          <Button variant="ghost" icon="chevronLeft" disabled={page === 0} onClick={() => go(page - 1)}>{p.previous}</Button>
          <Button variant="ghost" disabled={rows.length < PAGE_SIZE} onClick={() => go(page + 1)}>{p.next}</Button>
        </div>
      )}
    </Card>
  )
}
