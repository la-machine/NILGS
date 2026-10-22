// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Account administration: search accounts, request office-scoped roles with a written
// reason (a second administrator approves them, ADR-026), revoke roles, reset a lost second
// factor. Shown only with admin.account.list; the roles offered are the active assignable
// ones (baseline and custom, ADR-025) when admin.role.read is held. The server and the
// database check every call again, and refuse any change to one's own roles or second
// factor (separation of duties).
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../shared/i18n'
import type { Run } from '../../shared/session/types'
import { rolesApi } from '../roles'
import { AccountCard } from './AccountCard'
import { accountsApi } from './api'
import type { Account, AssignmentRequest } from './api'
import { PendingAssignments } from './PendingAssignments'

const BASELINE = ['INTAKE_OFFICER', 'CADASTRAL_OFFICER', 'REVIEWER', 'DECISION_AUTHORITY', 'SUPERVISOR', 'AUDITOR',
  'FINANCE_OFFICER', 'SYSTEM_ADMIN']

export function AccountsPanel({ texts, userId, canReadRoles, canReadRequests, canApprove, run }: {
  texts: Texts; userId: string; canReadRoles: boolean; canReadRequests: boolean; canApprove: boolean; run: Run
}) {
  const [query, setQuery] = useState('')
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [roleCodes, setRoleCodes] = useState<string[]>(BASELINE)
  const [pending, setPending] = useState<AssignmentRequest[]>([])

  const loadPending = async () => {
    if (canReadRequests) setPending(await accountsApi.assignmentRequests('PENDING'))
  }

  useEffect(() => {
    void run(async () => {
      if (canReadRoles) setRoleCodes((await rolesApi.list()).filter((r) => r.active && r.assignable).map((r) => r.code))
      await loadPending()
    })
  }, [canReadRoles, canReadRequests]) // eslint-disable-line react-hooks/exhaustive-deps

  const decide = (id: string, decision: 'approve' | 'reject') => {
    const why = window.prompt(texts.decisionReason)
    if (why) void run(async () => {
      await accountsApi.decideAssignment(id, decision, why)
      setNotice(texts.done)
      await loadPending()
      if (accounts) setAccounts(await accountsApi.search(query))
    })
  }

  const search = (e?: FormEvent) => {
    e?.preventDefault()
    void run(async () => setAccounts(await accountsApi.search(query)))
  }

  return (
    <section className="panel wide">
      <h2>{texts.adminTitle}</h2>
      <form onSubmit={search} className="inline">
        <label>{texts.search}<input value={query} maxLength={100} onChange={(e) => setQuery(e.target.value)} /></label>
        <button type="submit">{texts.find}</button>
      </form>
      {notice && <p className="notice" role="status">{notice}</p>}
      <PendingAssignments texts={texts} userId={userId} requests={pending} canApprove={canApprove} onDecide={decide} />
      {accounts?.length === 0 && <p>{texts.none}</p>}
      {accounts?.map((account) => (
        <AccountCard key={account.id} texts={texts} account={account} roles={roleCodes}
          act={(action, message) => run(async () => {
            await action()
            setNotice(message ?? texts.done)
            setAccounts(await accountsApi.search(query))
            await loadPending()
          })} />
      ))}
    </section>
  )
}
