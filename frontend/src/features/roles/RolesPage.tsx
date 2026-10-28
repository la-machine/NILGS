// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Roles and permissions (ADR-025): the nine baseline roles are fixed in code; administrators
// propose custom roles, changes and retirements, which exist only once a second administrator
// approves. Each role shows its permissions grouped by family with their scope. Proposals and
// decisions carry a reason, kept in the audit trail.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { rolesApi } from './api'
import type { CataloguePermission, RoleChangeRequest, RoleDefinition, RoleProposal, Scope } from './api'

type Draft = { kind: 'CREATE' | 'UPDATE'; code: string; nameFr: string; nameEn: string; description: string; permissions: Record<string, Scope> }

const EMPTY: Draft = { kind: 'CREATE', code: '', nameFr: '', nameEn: '', description: '', permissions: {} }
const FAMILIES: CataloguePermission['family'][] = ['BUSINESS', 'ADMINISTRATION', 'AUDIT']

export function RolesPage({ ctx }: { ctx: PageContext }) {
  const { texts, lang, session, run } = ctx
  const r = texts.rolesPage
  const dialog = useDialog()
  const canDefine = can(session, 'admin.role.define')
  const canApprove = can(session, 'admin.role.approve')
  const [catalogue, setCatalogue] = useState<CataloguePermission[]>([])
  const [list, setList] = useState<RoleDefinition[] | null>(null)
  const [pending, setPending] = useState<RoleChangeRequest[]>([])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [reason, setReason] = useState('')
  const [notice, setNotice] = useState<string | null>(null)

  const load = async () => {
    const [c, l, p] = await Promise.all([rolesApi.catalogue(), rolesApi.list(), rolesApi.requests('PENDING')])
    setCatalogue(c)
    setList(l)
    setPending(p)
  }
  useEffect(() => { void run(load) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const send = (body: RoleProposal) => run(async () => {
    await rolesApi.propose(body)
    setDraft(null)
    setReason('')
    setNotice(texts.requested)
    await load()
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!draft) return
    void send({ kind: draft.kind, roleCode: draft.code.trim().toUpperCase(), nameFr: draft.nameFr, nameEn: draft.nameEn,
      description: draft.description || undefined, permissions: draft.permissions, reason })
  }

  const retire = async (role: RoleDefinition) => {
    const why = await dialog.reason({
      title: r.retireTitle, message: fill(r.retireMessage, { role: name(role) }),
      label: texts.requestReason, hint: texts.common.auditedHint, minLength: 10, tooShort: texts.common.tooShort,
      confirmLabel: texts.retireRole, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (why) await send({ kind: 'RETIRE', roleCode: role.code, reason: why })
  }

  const decide = async (request: RoleChangeRequest, decision: 'approve' | 'reject') => {
    const why = await dialog.reason({
      title: decision === 'approve' ? r.approveTitle : r.rejectTitle,
      message: fill(r.decideMessage, { kind: r.kinds[request.kind], role: request.roleCode }),
      label: texts.common.decisionReason, hint: texts.common.auditedHint, minLength: 3, tooShort: texts.common.tooShort,
      confirmLabel: decision === 'approve' ? texts.approve : texts.reject, cancelLabel: texts.common.cancel,
      tone: decision === 'approve' ? 'primary' : 'danger',
    })
    if (why === null) return
    await run(async () => {
      await rolesApi.decide(request.id, decision, why)
      setNotice(texts.done)
      await load()
    })
  }

  const describe = (code: string) => {
    const p = catalogue.find((c) => c.code === code)
    return p ? (lang === 'fr' ? p.descriptionFr : p.descriptionEn) : code
  }
  const familyOf = (code: string) => catalogue.find((c) => c.code === code)?.family ?? 'BUSINESS'
  const name = (role: RoleDefinition) => (lang === 'fr' ? role.nameFr : role.nameEn)

  return (
    <div className="page">
      <PageHeader title={texts.rolesTitle} description={texts.rolesIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.roles }]}
        actions={canDefine && !draft && <Button variant="primary" icon="plus" onClick={() => setDraft(EMPTY)}>{texts.newRole}</Button>} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}

      {pending.length > 0 && (
        <Card title={texts.pendingRequests} icon="clock" actions={<Badge tone="warning">{pending.length}</Badge>}>
          <ul className="request-list">
            {pending.map((q) => (
              <li key={q.id} className="request" data-role-request={q.id}>
                <div className="request-main">
                  <p className="request-title"><Badge tone="info">{r.kinds[q.kind]}</Badge> <strong className="mono">{q.roleCode}</strong></p>
                  <p className="request-reason">{q.reason}</p>
                  {q.kind !== 'RETIRE' && (
                    <p className="request-meta">
                      {Object.entries(q.proposal.permissions).map(([c, s]) => <code key={c} className="perm-chip">{c}: {s.toLowerCase()}</code>)}
                    </p>
                  )}
                </div>
                <div className="request-actions">
                  {q.requestedBy === session.userId
                    ? <Badge tone="info" icon="clock">{texts.requestedByYou}</Badge>
                    : canApprove && (
                      <>
                        <Button size="sm" variant="primary" icon="check" onClick={() => void decide(q, 'approve')}>{texts.approve}</Button>
                        <Button size="sm" variant="ghost" onClick={() => void decide(q, 'reject')}>{texts.reject}</Button>
                      </>
                    )}
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {canDefine && draft && (
        <Card title={draft.kind === 'CREATE' ? texts.newRole : fill(r.changeTitle, { role: draft.code })} icon="shield" description={r.proposalIntro}>
          <form onSubmit={submit} className="form">
            <div className="form-grid">
              <Field label={texts.roleCode} hint={r.codeHint}>
                <input required pattern="[A-Z][A-Z0-9_]{2,39}" maxLength={40} value={draft.code} className="mono uppercase"
                  disabled={draft.kind === 'UPDATE'} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} />
              </Field>
              <Field label={texts.description}>
                <input maxLength={500} value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
              </Field>
              <Field label={texts.nameFr}>
                <input required minLength={2} maxLength={100} value={draft.nameFr} onChange={(e) => setDraft({ ...draft, nameFr: e.target.value })} />
              </Field>
              <Field label={texts.nameEn}>
                <input required minLength={2} maxLength={100} value={draft.nameEn} onChange={(e) => setDraft({ ...draft, nameEn: e.target.value })} />
              </Field>
            </div>
            <fieldset className="permission-matrix">
              <legend className="field-label">{texts.permissionsLabel}</legend>
              {FAMILIES.map((family) => (
                <div key={family} className="permission-family">
                  <h4>{r.families[family]}</h4>
                  <table className="table compact">
                    <tbody>
                      {catalogue.filter((p) => p.family === family).map((p) => (
                        <tr key={p.code}>
                          <td><code>{p.code}</code><div className="muted small">{lang === 'fr' ? p.descriptionFr : p.descriptionEn}</div></td>
                          <td className="scope-cell">
                            <label className="visually-hidden" htmlFor={`scope-${p.code}`}>{fill(r.scopeFor, { permission: p.code })}</label>
                            <select id={`scope-${p.code}`} value={draft.permissions[p.code] ?? ''} onChange={(e) => {
                              const permissions = { ...draft.permissions }
                              if (e.target.value) permissions[p.code] = e.target.value as Scope
                              else delete permissions[p.code]
                              setDraft({ ...draft, permissions })
                            }}>
                              <option value="">{texts.scopeNone}</option>
                              {p.scopes.map((s) => <option key={s} value={s}>{r.scopes[s]}</option>)}
                            </select>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </fieldset>
            <Field label={texts.requestReason} hint={texts.common.auditedHint}>
              <textarea required minLength={10} maxLength={500} rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <div className="form-actions">
              <Button onClick={() => setDraft(null)}>{texts.common.cancel}</Button>
              <Button type="submit" variant="primary" icon="check">{texts.submitRequest}</Button>
            </div>
          </form>
        </Card>
      )}

      <Card title={r.allRoles} icon="shield" padded={false}>
        {list === null ? <div className="card-pad"><Skeleton lines={6} /></div> : list.length === 0 ? <EmptyState icon="shield" title={texts.none} /> : (
          <ul className="role-cards">
            {list.map((role) => (
              <li key={role.code}>
                <details className="disclosure role-card">
                  <summary>
                    <Icon name="chevronRight" size={16} className="disclosure-icon" />
                    <span className="role-card-title">
                      <strong>{name(role)}</strong>
                      <code>{role.code}</code>
                    </span>
                    <span className="role-card-tags">
                      <Badge tone={role.system ? 'neutral' : 'info'}>{role.system ? texts.baseline : texts.custom}</Badge>
                      {!role.active && <Badge tone="warning">{texts.retired}</Badge>}
                      <span className="muted small">{fill(r.permissionCount, { n: Object.keys(role.permissions).length })}</span>
                    </span>
                  </summary>
                  <div className="role-card-body">
                    {role.description && <p>{role.description}</p>}
                    {FAMILIES.map((family) => {
                      const entries = Object.entries(role.permissions).filter(([c]) => familyOf(c) === family).sort()
                      if (entries.length === 0) return null
                      return (
                        <div key={family} className="permission-family">
                          <h4>{r.families[family]}</h4>
                          <ul className="permission-list">
                            {entries.map(([code, scope]) => (
                              <li key={code}>
                                <code>{code}</code>
                                <Badge tone={scope === 'ANY' ? 'info' : 'neutral'}>{r.scopes[scope]}</Badge>
                                <span className="muted">{describe(code)}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )
                    })}
                    {canDefine && !role.system && role.active && (
                      <div className="form-actions start">
                        <Button icon="edit" onClick={() => setDraft({ kind: 'UPDATE', code: role.code, nameFr: role.nameFr,
                          nameEn: role.nameEn, description: role.description ?? '', permissions: { ...role.permissions } })}>
                          {texts.changeRole}
                        </Button>
                        <Button variant="ghost" icon="trash" onClick={() => void retire(role)}>{texts.retireRole}</Button>
                      </div>
                    )}
                  </div>
                </details>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

export type RolesTexts = Texts['rolesPage']
