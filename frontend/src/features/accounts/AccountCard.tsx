// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One account in the directory: who, their state, their roles by office (revocable with a
// reason), and two requests an administrator can make: a role (approved by another
// administrator) and, for a person who lost every key and code, a second-factor reset
// (confirmed by the person's supervisor).
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Badge } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { accountsApi } from './api'
import type { Account } from './api'

export function AccountCard({ ctx, account, roles, canReset, act }: {
  ctx: PageContext; account: Account; roles: string[]; canReset: boolean
  act: (action: () => Promise<unknown>, message?: string) => Promise<void>
}) {
  const { texts } = ctx
  const a = texts.accounts
  const dialog = useDialog()
  const [role, setRole] = useState(roles[0] ?? '')
  const [office, setOffice] = useState('')
  const [reason, setReason] = useState('')
  const [resetReason, setResetReason] = useState('')
  const roleName = (code: string) => texts.roleNames[code as keyof Texts['roleNames']] ?? code

  const requestRole = (e: FormEvent) => {
    e.preventDefault()
    void act(() => accountsApi.requestRole(account.id, role, office.trim().toUpperCase(), reason), texts.roleRequested)
      .then(() => setReason(''))
  }

  const revoke = async (assignment: Account['assignments'][number]) => {
    const why = await dialog.reason({
      title: a.revokeTitle,
      message: fill(a.revokeMessage, { role: roleName(assignment.role), office: assignment.officeCode ?? texts.everywhere, name: account.displayName }),
      label: texts.common.decisionReason, hint: texts.common.auditedHint, minLength: 3, tooShort: texts.common.tooShort,
      confirmLabel: texts.revoke, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (why) await act(() => accountsApi.revoke(account.id, assignment.id, why))
  }

  const reset = (e: FormEvent) => {
    e.preventDefault()
    void act(() => accountsApi.requestReset(account.id, resetReason), texts.resetRequested).then(() => setResetReason(''))
  }

  const initials = account.displayName.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('')

  return (
    <article className="account">
      <header className="account-header">
        <span className="avatar large" aria-hidden="true">{initials}</span>
        <div className="account-identity">
          <h3>{account.displayName} <small>{account.email}</small></h3>
          <p className="tags">
            <Badge>{account.accountType === 'STAFF' ? texts.account.staff : texts.account.applicant}</Badge>
            {account.secondFactorReady
              ? <Badge tone="success" icon="key">{texts.enrolled}</Badge>
              : <Badge tone="warning" icon="alert">{texts.notEnrolled}</Badge>}
            {account.locked && <Badge tone="danger" icon="lock">{texts.lockedLabel}</Badge>}
            {!account.enabled && <Badge tone="danger">{a.disabled}</Badge>}
          </p>
          <p className="muted small">{texts.accountId}: <code>{account.id}</code></p>
        </div>
      </header>

      <div className="account-roles">
        <h4>{texts.roles}</h4>
        <ul className="role-list">
          {account.assignments.map((x) => (
            <li key={x.id}>
              <span className="role-name">{roleName(x.role)}</span>
              <span className="role-office">{x.officeCode ? <><Icon name="office" size={14} />{x.officeCode}</> : texts.account.noOffice}</span>
              {x.role !== 'APPLICANT' && (
                <Button size="sm" variant="ghost" onClick={() => void revoke(x)}>{texts.revoke}</Button>
              )}
            </li>
          ))}
        </ul>
      </div>

      {account.accountType === 'STAFF' && (
        <>
          <form onSubmit={requestRole} className="form-row">
            <Field label={texts.role}>
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                {roles.map((r) => <option key={r} value={r}>{roleName(r)}</option>)}
              </select>
            </Field>
            <Field label={texts.office}>
              <input required maxLength={16} value={office} className="uppercase" onChange={(e) => setOffice(e.target.value)} />
            </Field>
            <Field label={texts.reason} className="grow">
              <input required minLength={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <Button type="submit" variant="secondary" icon="plus">{texts.requestRoleButton}</Button>
          </form>
          {canReset && (
            <details className="disclosure danger-zone">
              <summary><Icon name="chevronRight" size={16} className="disclosure-icon" />{texts.resetTitle}</summary>
              <p>{texts.resetIntro}</p>
              <form onSubmit={reset} className="form-row">
                <Field label={texts.reason} className="grow">
                  <input required minLength={10} maxLength={500} value={resetReason} onChange={(e) => setResetReason(e.target.value)} />
                </Field>
                <Button type="submit" variant="danger">{texts.requestReset}</Button>
              </form>
            </details>
          )}
        </>
      )}
    </article>
  )
}
