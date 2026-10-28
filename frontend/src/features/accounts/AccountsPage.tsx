// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Account administration (ADR-025, ADR-026, ADR-028): what waits for a decision first (role
// assignments a second administrator must approve, resets waiting for a supervisor), then the
// directory. Every change is a request: a role takes effect only once another administrator
// approves it, and nobody decides their own request. Each decision asks for a reason, kept
// in the audit trail. The server and the database enforce all of this; the page only shows
// the actions the session holds.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { rolesApi } from '../roles'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { can } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState } from '../../shared/ui/Feedback'
import { accountsApi } from './api'
import type { Account, AssignmentRequest, ResetRequest } from './api'
import { AccountCard } from './AccountCard'
import { Person } from './Person'

const BASELINE = ['INTAKE_OFFICER', 'CADASTRAL_OFFICER', 'REVIEWER', 'DECISION_AUTHORITY', 'SUPERVISOR', 'AUDITOR',
  'FINANCE_OFFICER', 'SYSTEM_ADMIN']

export function AccountsPage({ ctx }: { ctx: PageContext }) {
  const { texts, session, run } = ctx
  const a = texts.accounts
  const dialog = useDialog()
  const canReadRoles = can(session, 'admin.role.read')
  const canReadRequests = can(session, 'admin.assignment.read')
  const canApprove = can(session, 'admin.assignment.approve')
  const canReset = can(session, 'admin.mfa.reset')
  const [query, setQuery] = useState('')
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [known, setKnown] = useState<Record<string, Account>>({})
  const [notice, setNotice] = useState<string | null>(null)
  const [roleCodes, setRoleCodes] = useState<string[]>(BASELINE)
  const [pending, setPending] = useState<AssignmentRequest[]>([])
  const [resets, setResets] = useState<ResetRequest[]>([])

  const remember = (list: Account[]) => setKnown((k) => ({ ...k, ...Object.fromEntries(list.map((x) => [x.id, x])) }))

  const loadPending = async () => {
    if (canReadRequests) setPending(await accountsApi.assignmentRequests('PENDING'))
    if (canReset) setResets(await accountsApi.resetRequests('PENDING'))
  }

  useEffect(() => {
    void run(async () => {
      if (canReadRoles) setRoleCodes((await rolesApi.list()).filter((r) => r.active && r.assignable).map((r) => r.code))
      await loadPending()
      // The first page of the directory names most people a request mentions.
      remember(await accountsApi.search(''))
    })
  }, [canReadRoles, canReadRequests, canReset]) // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = async () => {
    await loadPending()
    if (accounts) {
      const list = await accountsApi.search(query)
      setAccounts(list)
      remember(list)
    }
  }

  const decide = async (request: AssignmentRequest, decision: 'approve' | 'reject') => {
    const reason = await dialog.reason({
      title: decision === 'approve' ? a.approveTitle : a.rejectTitle,
      message: fill(decision === 'approve' ? a.approveMessage : a.rejectMessage, {
        role: texts.roleNames[request.role as keyof Texts['roleNames']] ?? request.role, office: request.officeCode,
      }),
      label: texts.common.decisionReason, hint: texts.common.auditedHint, minLength: 3, tooShort: texts.common.tooShort,
      confirmLabel: decision === 'approve' ? texts.approve : texts.reject, cancelLabel: texts.common.cancel,
      tone: decision === 'approve' ? 'primary' : 'danger',
    })
    if (reason === null) return
    await run(async () => {
      await accountsApi.decideAssignment(request.id, decision, reason)
      setNotice(decision === 'approve' ? a.approved : a.rejected)
      await refresh()
    })
  }

  const search = (e?: FormEvent) => {
    e?.preventDefault()
    void run(async () => {
      const list = await accountsApi.search(query)
      setAccounts(list)
      remember(list)
    })
  }

  return (
    <div className="page">
      <PageHeader title={texts.adminTitle} description={a.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.accounts }]} />
      {notice && <Alert tone="success" onDismiss={() => setNotice(null)} dismissLabel={texts.shell.dismiss}>{notice}</Alert>}

      {canReadRequests && (
        <Card title={texts.pendingAssignments} icon="clock" description={a.pendingIntro}
          actions={<Badge tone={pending.length > 0 ? 'warning' : 'neutral'}>{pending.length}</Badge>}>
          {pending.length === 0
            ? <EmptyState icon="checkCircle" title={a.nothingPending} />
            : (
              <ul className="request-list">
                {pending.map((r) => {
                  const own = r.requestedBy === session.userId || r.userId === session.userId
                  return (
                    <li key={r.id} data-request={r.id} className="request">
                      <div className="request-main">
                        <p className="request-title">
                          <strong>{texts.roleNames[r.role as keyof Texts['roleNames']] ?? r.role}</strong>
                          <span className="muted"> · {r.officeCode}</span>
                        </p>
                        <p className="request-meta">
                          {a.forPerson} <Person id={r.userId} known={known} texts={texts} selfId={session.userId} />
                          {' · '}{a.requestedBy} <Person id={r.requestedBy} known={known} texts={texts} selfId={session.userId} />
                          {' · '}<span className="num">{new Date(r.requestedAt).toLocaleString(ctx.lang)}</span>
                        </p>
                        <p className="request-reason">{r.reason}</p>
                      </div>
                      <div className="request-actions">
                        {own
                          ? <Badge tone="info" icon="clock">{texts.requestedByYou}</Badge>
                          : canApprove && (
                            <>
                              <Button size="sm" variant="primary" icon="check" onClick={() => void decide(r, 'approve')}>{texts.approve}</Button>
                              <Button size="sm" variant="ghost" onClick={() => void decide(r, 'reject')}>{texts.reject}</Button>
                            </>
                          )}
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}
        </Card>
      )}

      {canReset && resets.length > 0 && (
        <Card title={texts.pendingResets} icon="userCheck" description={a.resetsIntro}>
          <ul className="request-list">
            {resets.map((r) => (
              <li key={r.id} data-reset={r.id} className="request">
                <div className="request-main">
                  <p className="request-title"><strong>{r.displayName}</strong> <span className="muted">{r.email}</span></p>
                  <p className="request-reason">{r.reason}</p>
                </div>
                <Badge tone="warning" icon="clock">{texts.awaitingSupervisor}</Badge>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card title={a.directory} icon="users" description={a.directoryIntro}>
        <form onSubmit={search} className="filter-bar" role="search">
          <Field label={texts.search} className="grow">
            <input type="search" value={query} maxLength={100} onChange={(e) => setQuery(e.target.value)} />
          </Field>
          <Button type="submit" variant="primary" icon="search">{texts.find}</Button>
        </form>
        {accounts?.length === 0 && <EmptyState icon="search" title={texts.none} />}
        {accounts && accounts.length > 0 && (
          <div className="account-list">
            {accounts.map((account) => (
              <AccountCard key={account.id} ctx={ctx} account={account} roles={roleCodes} canReset={canReset}
                act={(action, message) => run(async () => {
                  await action()
                  setNotice(message ?? texts.done)
                  await refresh()
                })} />
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}
