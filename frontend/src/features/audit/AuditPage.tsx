// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The audit trail (ADR-007, ADR-026): chain verification, signed checkpoints and their public
// keys, the event search and the way to a case's or a parcel's timeline (ADR-038), each offered
// only to a session that holds its permission; a supervisor's search shows the events of their
// offices only, as the server filters them. A
// permission held only through a special grant loads nothing by itself: every protected call
// is a button that first says it will spend a use, and how many are left. Search results are
// read-only; the details of an event open on demand.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { accessTo, canSomewhere } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState } from '../../shared/ui/Feedback'
import type { Tone } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { AUDIT_ACTION_CODES } from './actions'
import { auditApi } from './api'
import type { AuditEvent, CheckpointKey, CheckpointSummary, Verification } from './api'

const OUTCOME_TONE: Record<string, Tone> = { SUCCESS: 'success', DENIED: 'danger', FAILURE: 'warning' }

export function AuditPage({ ctx }: { ctx: PageContext }) {
  const { texts, session, run, refreshSession, lang } = ctx
  const t = texts.auditPage
  const dialog = useDialog()
  // Event search at an office (ADR-038) is a role permission: no special grant use to confirm.
  const access = (permission: string) => accessTo(session, permission)
    ?? (permission === 'audit.event.search' && canSomewhere(session, permission) ? { kind: 'role' as const } : null)
  const officeOnly = accessTo(session, 'audit.event.search') === null
  const [timelineType, setTimelineType] = useState<'APPLICATION' | 'PARCEL'>('APPLICATION')
  const [timelineRef, setTimelineRef] = useState('')
  const [action, setAction] = useState('')
  const [actorId, setActorId] = useState('')
  const [page, setPage] = useState(0)
  const [events, setEvents] = useState<AuditEvent[] | null>(null)
  const [more, setMore] = useState(false)
  const [verification, setVerification] = useState<Verification | null>(null)
  const [checkpoints, setCheckpoints] = useState<CheckpointSummary[] | null>(null)
  const [created, setCreated] = useState<CheckpointSummary | null>(null)
  const [keys, setKeys] = useState<CheckpointKey[] | null>(null)

  /** Asks before spending a use of a special grant; a role needs no question. */
  const mayProceed = async (permission: string) => {
    const a = access(permission)
    if (!a) return false
    if (a.kind === 'role') return true
    return dialog.confirm({
      title: t.useGrantTitle,
      message: fill(texts.useGrantConfirm, { permission, n: a.grant.usesLeft ?? texts.unlimited }),
      confirmLabel: t.useGrant, cancelLabel: texts.common.cancel,
    })
  }

  /** Runs a protected action, through a special grant only after confirmation. */
  const perform = async (permission: string, act: () => Promise<void>) => {
    const a = access(permission)
    if (!(await mayProceed(permission))) return
    await run(async () => {
      await act()
      if (a?.kind === 'grant') await refreshSession()
    })
  }

  const badge = (permission: string) => {
    const a = access(permission)
    return a?.kind === 'grant'
      ? <Badge tone="info" icon="key">{texts.viaGrant} · {a.grant.usesLeft === null ? texts.unlimited : fill(texts.usesLeft, { n: a.grant.usesLeft })}</Badge>
      : null
  }

  const search = (next: number) => perform('audit.event.search', async () => {
    const result = await auditApi.events({ action: action.trim().toUpperCase() || undefined, actorId: actorId.trim() || undefined, page: next })
    setEvents(result.events)
    setMore(result.more)
    setPage(next)
  })

  const download = async (c: CheckpointSummary) => {
    if (!(await mayProceed('audit.checkpoint.download'))) return
    const link = document.createElement('a')
    link.href = c.file
    link.download = ''
    document.body.appendChild(link)
    link.click()
    link.remove()
    if (access('audit.checkpoint.download')?.kind === 'grant') window.setTimeout(() => void refreshSession(), 1000)
  }

  const canSearch = access('audit.event.search')

  return (
    <div className="page">
      <PageHeader title={texts.auditTitle} description={t.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.audit }]} />

      <div className="grid-3">
        {access('audit.chain.verify') && (
          <Card title={t.integrity} icon="shield" description={t.integrityIntro}>
            {verification && (verification.verified
              ? <Alert tone="success">{fill(texts.verified, { from: verification.checkedFrom, to: verification.checkedTo, rows: verification.rowsChecked })}</Alert>
              : <Alert tone="danger">{fill(texts.broken, { pos: verification.firstMismatchPos ?? '?', kind: verification.mismatchKind ?? '?' })}</Alert>)}
            <div className="card-footer-actions">
              <Button variant="primary" icon="shield"
                onClick={() => void perform('audit.chain.verify', async () => setVerification(await auditApi.verify()))}>
                {texts.verify}
              </Button>
              {badge('audit.chain.verify')}
            </div>
          </Card>
        )}
        {(access('audit.checkpoint.create') || access('audit.checkpoint.list')) && (
          <Card title={texts.checkpoints} icon="signature" description={t.checkpointsIntro}>
            {created && <Alert tone="success">{fill(t.checkpointCreated, { pos: created.chainPos })}</Alert>}
            <div className="card-footer-actions">
              {access('audit.checkpoint.create') && (
                <Button icon="signature" onClick={() => void perform('audit.checkpoint.create', async () => {
                  setCreated(await auditApi.checkpoint())
                  if (access('audit.checkpoint.list')?.kind === 'role') setCheckpoints(await auditApi.checkpoints())
                })}>{texts.checkpoint}</Button>
              )}
              {badge('audit.checkpoint.create')}
              {access('audit.checkpoint.list') && checkpoints === null && (
                <Button variant="ghost" onClick={() => void perform('audit.checkpoint.list', async () => setCheckpoints(await auditApi.checkpoints()))}>
                  {texts.showCheckpoints}
                </Button>
              )}
              {badge('audit.checkpoint.list')}
            </div>
          </Card>
        )}
        {access('audit.key.read') && (
          <Card title={texts.publicKeys} icon="key" description={t.keysIntro}>
            <div className="card-footer-actions">
              <Button icon="key" onClick={() => void perform('audit.key.read', async () => setKeys(await auditApi.keys()))}>{t.showKeys}</Button>
              {badge('audit.key.read')}
            </div>
          </Card>
        )}
      </div>

      {keys && (
        <Card title={texts.publicKeys} icon="key">
          <ul className="keys">
            {keys.map((k) => <li key={k.version}><p className="strong">{fill(t.keyVersion, { n: k.version })}</p><pre>{k.pem}</pre></li>)}
          </ul>
        </Card>
      )}

      {checkpoints && (
        <Card title={texts.checkpoints} icon="signature" padded={false}>
          {checkpoints.length === 0 ? <EmptyState icon="signature" title={texts.none} /> : (
            <table className="table table-responsive">
              <thead><tr><th scope="col">{t.position}</th><th scope="col">{t.signedAt}</th><th scope="col">{t.keyVersionShort}</th><th scope="col"><span className="visually-hidden">{texts.download}</span></th></tr></thead>
              <tbody>
                {checkpoints.map((c) => (
                  <tr key={c.id}>
                    <td data-label={t.position} className="num">#{c.chainPos}</td>
                    <td data-label={t.signedAt} className="num">{new Date(c.createdAt).toLocaleString(lang)}</td>
                    <td data-label={t.keyVersionShort}>v{c.keyVersion}</td>
                    <td className="actions-col">
                      {access('audit.checkpoint.download') && (
                        <>
                          <Button size="sm" variant="ghost" icon="download" onClick={() => void download(c)}>{texts.download}</Button>
                          {badge('audit.checkpoint.download')}
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {canSearch && (
        <Card title={texts.timeline.title} icon="history" description={texts.timeline.intro}>
          <form className="filter-bar" role="search" aria-label={texts.timeline.find}
            onSubmit={(e: FormEvent) => {
              e.preventDefault()
              if (timelineRef.trim()) navigate(href(['audit', 'timeline'], { type: timelineType, reference: timelineRef.trim() }))
            }}>
            <Field label={texts.timeline.type}>
              <select value={timelineType} onChange={(e) => setTimelineType(e.target.value as 'APPLICATION' | 'PARCEL')}>
                <option value="APPLICATION">{texts.timeline.types.APPLICATION}</option>
                <option value="PARCEL">{texts.timeline.types.PARCEL}</option>
              </select>
            </Field>
            <Field label={texts.timeline.reference} className="grow">
              <input value={timelineRef} maxLength={64} className="mono uppercase" onChange={(e) => setTimelineRef(e.target.value.toUpperCase())} />
            </Field>
            <Button type="submit" icon="history">{texts.timeline.show}</Button>
          </form>
        </Card>
      )}

      {canSearch && (
        <Card title={t.events} icon="audit" padded={false} description={officeOnly ? texts.timeline.officeOnly : undefined}>
          <form className="filter-bar card-pad" role="search" onSubmit={(e: FormEvent) => { e.preventDefault(); void search(0) }}>
            <Field label={texts.action} hint={t.actionHint}>
              <input value={action} maxLength={64} list="audit-actions" className="mono uppercase" onChange={(e) => setAction(e.target.value)} />
            </Field>
            <datalist id="audit-actions">{AUDIT_ACTION_CODES.map((c) => <option key={c} value={c} />)}</datalist>
            <Field label={texts.actor} className="grow">
              <input value={actorId} maxLength={36} className="mono" onChange={(e) => setActorId(e.target.value)} />
            </Field>
            <Button type="submit" variant="primary" icon="search">{texts.find}</Button>
            {badge('audit.event.search')}
          </form>
          {events === null && <EmptyState icon="search" title={t.searchPrompt}>{t.searchPromptText}</EmptyState>}
          {events?.length === 0 && <EmptyState icon="search" title={texts.none} />}
          {events && events.length > 0 && (
            <table className="table events table-responsive">
              <thead>
                <tr>
                  <th scope="col">#</th><th scope="col">{t.when}</th><th scope="col">{texts.action}</th>
                  <th scope="col">{t.outcome}</th><th scope="col">{t.actor}</th><th scope="col">{t.details}</th>
                </tr>
              </thead>
              <tbody>
                {events.map((e) => (
                  <tr key={e.id}>
                    <td data-label="#" className="num">{e.chainPos}</td>
                    <td data-label={t.when} className="num" title={e.occurredAt}>{new Date(e.occurredAt).toLocaleString(lang)}</td>
                    <td data-label={texts.action}><code>{e.action}</code></td>
                    <td data-label={t.outcome}><Badge tone={OUTCOME_TONE[e.outcome] ?? 'neutral'}>{e.outcome}</Badge></td>
                    <td data-label={t.actor}>
                      {e.actorType === 'USER'
                        ? <>{e.actorName && <span>{e.actorName} </span>}<code title={e.actorId ?? ''} className="small">{e.actorId}</code></>
                        : e.actorLabel ?? e.actorType}
                      {(e.subjects ?? []).some((s) => s.officeCode) && (
                        <span className="small muted"> · {Array.from(new Set((e.subjects ?? []).map((s) => s.officeCode).filter(Boolean))).join(', ')}</span>
                      )}
                      {e.intact === false && <> <Badge tone="danger" icon="alert">{texts.timeline.altered}</Badge></>}
                    </td>
                    <td data-label={t.details}>
                      {e.reason && <p className="small">{e.reason}</p>}
                      {Object.keys(e.details ?? {}).length > 0 && (
                        <details className="event-details">
                          <summary><Icon name="chevronRight" size={14} className="disclosure-icon" />{t.showDetails}</summary>
                          <pre><code>{JSON.stringify(e.details, null, 2)}</code></pre>
                        </details>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {events && (page > 0 || more) && (
            <nav className="pagination" aria-label={t.pages}>
              <Button size="sm" icon="chevronLeft" disabled={page === 0} onClick={() => void search(page - 1)}>{texts.parcel.previous}</Button>
              <span className="muted">{fill(texts.parcel.pageN, { n: page + 1 })}</span>
              <Button size="sm" disabled={!more} onClick={() => void search(page + 1)}>{texts.more}</Button>
            </nav>
          )}
        </Card>
      )}
    </div>
  )
}
