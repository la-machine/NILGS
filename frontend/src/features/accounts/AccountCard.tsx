// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One account in the directory: its roles (each revocable), a role request form, and the
// supervised reset of a lost second factor.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../shared/i18n'
import { accountsApi } from './api'
import type { Account } from './api'

export function AccountCard({ texts, account, roles, act }: {
  texts: Texts; account: Account; roles: string[]
  act: (action: () => Promise<unknown>, message?: string) => Promise<void>
}) {
  const [role, setRole] = useState(roles[0] ?? '')
  const [office, setOffice] = useState('')
  const [reason, setReason] = useState('')
  const [resetReason, setResetReason] = useState('')
  const [confirmedBy, setConfirmedBy] = useState('')

  const requestRole = (e: FormEvent) => {
    e.preventDefault()
    void act(() => accountsApi.requestRole(account.id, role, office.trim().toUpperCase(), reason), texts.roleRequested)
    setReason('')
  }
  const revoke = (assignmentId: string) => {
    const why = window.prompt(texts.revokeReason)
    if (why) void act(() => accountsApi.revoke(account.id, assignmentId, why))
  }
  const reset = (e: FormEvent) => {
    e.preventDefault()
    void act(() => accountsApi.resetSecondFactor(account.id, resetReason, confirmedBy))
    setResetReason('')
    setConfirmedBy('')
  }

  return (
    <article className="account">
      <h3>{account.displayName} <small>{account.email}</small></h3>
      <p className="tags">
        <span>{account.accountType}</span>
        <span>{account.secondFactorReady ? texts.enrolled : texts.notEnrolled}</span>
        {account.locked && <span className="warn">{texts.lockedLabel}</span>}
      </p>
      <p><small>{texts.accountId}: <code>{account.id}</code></small></p>
      <ul>
        {account.assignments.map((a) => (
          <li key={a.id}>{a.role}{a.officeCode ? ` @ ${a.officeCode}` : ''}{' '}
            {a.role !== 'APPLICANT' && <button type="button" className="link" onClick={() => revoke(a.id)}>{texts.revoke}</button>}
          </li>
        ))}
      </ul>
      {account.accountType === 'STAFF' && (
        <>
          <form onSubmit={requestRole} className="grid">
            <label>{texts.role}<select value={role} onChange={(e) => setRole(e.target.value)}>
              {roles.map((r) => <option key={r} value={r}>{r}</option>)}
            </select></label>
            <label>{texts.office}<input required maxLength={16} value={office} onChange={(e) => setOffice(e.target.value)} /></label>
            <label>{texts.reason}<input required minLength={3} maxLength={500} value={reason}
              onChange={(e) => setReason(e.target.value)} /></label>
            <button type="submit">{texts.requestRoleButton}</button>
          </form>
          <details>
            <summary>{texts.resetTitle}</summary>
            <p>{texts.resetIntro}</p>
            <form onSubmit={reset} className="grid">
              <label>{texts.reason}<input required minLength={10} maxLength={500} value={resetReason}
                onChange={(e) => setResetReason(e.target.value)} /></label>
              <label>{texts.confirmedBy}<input required minLength={3} maxLength={120} value={confirmedBy}
                onChange={(e) => setConfirmedBy(e.target.value)} /></label>
              <button type="submit" className="danger">{texts.reset}</button>
            </form>
          </details>
        </>
      )}
    </article>
  )
}
