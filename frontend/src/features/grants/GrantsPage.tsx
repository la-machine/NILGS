// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Special grants (ADR-025, ADR-026): one permission for one person and one task, limited in
// time and/or uses, requested by one administrator and approved by another, never by the
// beneficiary. Administration permissions are never delegable (the catalogue offered here is
// the delegable part). Every decision carries a reason kept in the audit trail.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { accountsApi } from '../accounts'
import { rolesApi } from '../roles'
import type { CataloguePermission } from '../roles'
import { fill } from '../../shared/i18n'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState } from '../../shared/ui/Feedback'
import type { Tone } from '../../shared/ui/Feedback'
import { grantsApi } from './api'
import type { SpecialGrant } from './api'

const STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'REVOKED'] as const
const TONE: Record<SpecialGrant['status'], Tone> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'neutral', REVOKED: 'danger' }

export function GrantsPage({ ctx }: { ctx: PageContext }) {
  const { texts, lang, session, run } = ctx
  const g = texts.grantsPage
  const dialog = useDialog()
  const canRequest = can(session, 'admin.grant.request')
  const canApprove = can(session, 'admin.grant.approve')
  const canRevoke = can(session, 'admin.grant.revoke')
  const canLookUp = can(session, 'admin.account.list')
  const [status, setStatus] = useState<string>('PENDING')
  const [list, setList] = useState<SpecialGrant[] | null>(null)
  const [catalogue, setCatalogue] = useState<CataloguePermission[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [requesting, setRequesting] = useState(false)

  const load = async (filter = status) => setList(await grantsApi.list(filter || undefined))
  useEffect(() => {
    void run(async () => {
      if (canRequest) setCatalogue((await rolesApi.catalogue().catch(() => [])).filter((p) => p.delegable))
      await load()
    })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const decide = async (grant: SpecialGrant, decision: 'approve' | 'reject' | 'revoke') => {
    const why = await dialog.reason({
      title: g.decisionTitles[decision],
      message: fill(g.decisionMessage, { permission: grant.permission, office: grant.officeCode ?? texts.everywhere }),
      label: texts.common.decisionReason, hint: texts.common.auditedHint, minLength: 3, tooShort: texts.common.tooShort,
      confirmLabel: decision === 'approve' ? texts.approve : decision === 'reject' ? texts.reject : texts.revokeGrant,
      cancelLabel: texts.common.cancel, tone: decision === 'approve' ? 'primary' : 'danger',
    })
    if (why === null) return
    await run(async () => {
      await grantsApi.decide(grant.id, decision, why)
      setNotice(texts.done)
      await load()
    })
  }

  return (
    <div className="page">
      <PageHeader title={texts.grantsTitle} description={texts.grantsIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.grants }]}
        actions={canRequest && !requesting && <Button variant="primary" icon="plus" onClick={() => setRequesting(true)}>{texts.newGrant}</Button>} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}

      {canRequest && requesting && (
        <GrantRequestForm ctx={ctx} catalogue={catalogue} canLookUp={canLookUp} onCancel={() => setRequesting(false)}
          onSent={async () => {
            setRequesting(false)
            setNotice(texts.requested)
            setStatus('PENDING')
            await load('PENDING')
          }} />
      )}

      <Card title={g.listTitle} icon="key" padded={false}
        actions={
          <div className="segmented small" role="group" aria-label={texts.status}>
            {['', ...STATUSES].map((s) => (
              <button key={s || 'all'} type="button" className={status === s ? 'segment selected' : 'segment'} aria-pressed={status === s}
                onClick={() => { setStatus(s); void run(() => load(s)) }}>
                {s ? g.statuses[s as SpecialGrant['status']] : texts.all}
              </button>
            ))}
          </div>
        }>
        {list === null ? null : list.length === 0 ? <EmptyState icon="key" title={g.noneWithStatus} /> : (
          <ul className="request-list">
            {list.map((grant) => (
              <li key={grant.id} className="request" data-grant={grant.id} data-beneficiary={grant.userId}>
                <div className="request-main">
                  <p className="request-title">
                    <code>{grant.permission}</code>
                    <span className="muted"> · {grant.officeCode ?? texts.everywhere}</span>
                    <Badge tone={TONE[grant.status]}>{g.statuses[grant.status]}</Badge>
                  </p>
                  <p className="request-meta">
                    {g.beneficiary} <code title={grant.userId}>{grant.userId === session.userId ? texts.common.you : `${grant.userId.slice(0, 8)}…`}</code>
                    {' · '}{texts.uses}: <span className="num">{grant.uses}{grant.maxUses !== null ? ` / ${grant.maxUses}` : ` (${texts.unlimited})`}</span>
                    {grant.validUntil && <> · {fill(texts.until, { date: new Date(grant.validUntil).toLocaleString(lang) })}</>}
                  </p>
                  <p className="request-reason">{grant.reason}</p>
                </div>
                <div className="request-actions">
                  {grant.status === 'PENDING' && canApprove && grant.requestedBy !== session.userId && grant.userId !== session.userId && (
                    <>
                      <Button size="sm" variant="primary" icon="check" onClick={() => void decide(grant, 'approve')}>{texts.approve}</Button>
                      <Button size="sm" variant="ghost" onClick={() => void decide(grant, 'reject')}>{texts.reject}</Button>
                    </>
                  )}
                  {grant.status === 'PENDING' && grant.requestedBy === session.userId && <Badge tone="info" icon="clock">{texts.requestedByYou}</Badge>}
                  {grant.status === 'APPROVED' && canRevoke && grant.userId !== session.userId && (
                    <Button size="sm" variant="danger" onClick={() => void decide(grant, 'revoke')}>{texts.revokeGrant}</Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

function GrantRequestForm({ ctx, catalogue, canLookUp, onCancel, onSent }: {
  ctx: PageContext; catalogue: CataloguePermission[]; canLookUp: boolean; onCancel: () => void; onSent: () => Promise<void>
}) {
  const { texts, lang, run } = ctx
  const g = texts.grantsPage
  const [beneficiary, setBeneficiary] = useState('')
  const [lookup, setLookup] = useState('')
  const [found, setFound] = useState<{ id: string; displayName: string; email: string }[] | null>(null)
  const [permission, setPermission] = useState('')
  const [office, setOffice] = useState('')
  const [validUntil, setValidUntil] = useState('')
  const [maxUses, setMaxUses] = useState('')
  const [reason, setReason] = useState('')

  const find = () => void run(async () => setFound((await accountsApi.search(lookup.trim())).filter((x) => x.accountType === 'STAFF')))

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await grantsApi.request({
        userId: beneficiary.trim(), permission, reason,
        officeCode: office.trim() ? office.trim().toUpperCase() : undefined,
        validUntil: validUntil ? new Date(validUntil).toISOString() : undefined,
        maxUses: maxUses ? Number(maxUses) : undefined,
      })
      await onSent()
    })
  }

  return (
    <Card title={texts.newGrant} icon="key" description={g.requestIntro}>
      <form onSubmit={submit} className="form">
        {canLookUp && (
          <div className="lookup">
            <div className="filter-bar">
              <Field label={g.findPerson} className="grow">
                <input type="search" value={lookup} maxLength={100} onChange={(e) => setLookup(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); find() } }} />
              </Field>
              <Button icon="search" onClick={find}>{texts.find}</Button>
            </div>
            {found && found.length === 0 && <p className="muted">{texts.none}</p>}
            {found && found.length > 0 && (
              <ul className="pick-list">
                {found.map((x) => (
                  <li key={x.id}>
                    <button type="button" className={beneficiary === x.id ? 'pick selected' : 'pick'} aria-pressed={beneficiary === x.id}
                      onClick={() => setBeneficiary(x.id)}>
                      <strong>{x.displayName}</strong> <span className="muted">{x.email}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        <div className="form-grid">
          <Field label={texts.beneficiary} hint={g.beneficiaryHint}>
            <input required pattern="[0-9a-fA-F\-]{36}" maxLength={36} value={beneficiary} className="mono"
              onChange={(e) => setBeneficiary(e.target.value)} />
          </Field>
          <Field label={texts.permission}>
            <select required value={permission} onChange={(e) => setPermission(e.target.value)}>
              <option value="" />
              {catalogue.map((p) => (
                <option key={p.code} value={p.code}>{p.code} — {lang === 'fr' ? p.descriptionFr : p.descriptionEn}</option>
              ))}
            </select>
          </Field>
          <Field label={texts.officeOptional}>
            <input maxLength={16} value={office} className="uppercase" onChange={(e) => setOffice(e.target.value)} />
          </Field>
          <Field label={texts.validUntil}>
            <input type="datetime-local" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
          </Field>
          <Field label={texts.maxUses}>
            <input type="number" min={1} max={100000} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
          </Field>
        </div>
        <Field label={texts.requestReason} hint={texts.common.auditedHint}>
          <textarea required minLength={10} maxLength={500} rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <div className="form-actions">
          <Button onClick={onCancel}>{texts.common.cancel}</Button>
          <Button type="submit" variant="primary" icon="check">{texts.submitRequest}</Button>
        </div>
      </form>
    </Card>
  )
}
