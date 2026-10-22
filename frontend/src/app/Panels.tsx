// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The back-office panels a session opens. Administration panels need a role
// (administration is never delegable). The audit panel opens for a role or a special grant:
// through a grant it loads nothing by itself, and each action asks before spending a use
// (shared/session/access.ts, ADR-026). The server checks every call again.
import { useState } from 'react'
import { AccountsPanel } from '../features/accounts'
import { AUDIT_ACTIONS, AuditPanel } from '../features/audit'
import { GrantsPanel } from '../features/grants'
import { RolesPanel } from '../features/roles'
import type { Lang, Texts } from '../shared/i18n'
import { accessTo } from '../shared/session/access'
import type { Run, Session } from '../shared/session/types'

export function Panels({ texts, lang, session, run, onGrantUsed }: {
  texts: Texts; lang: Lang; session: Session; run: Run; onGrantUsed: () => Promise<void>
}) {
  const can = (permission: string) => session.permissions.includes(permission)
  const access = (permission: string) => accessTo(session, permission)
  const auditAvailable = AUDIT_ACTIONS.some((p) => access(p) !== null)
  // Once opened, the audit panel stays for this visit, so the result of a grant's last use
  // remains visible after the grant is gone; its buttons follow the current access.
  const [auditSeen, setAuditSeen] = useState(false)
  if (auditAvailable && !auditSeen) setAuditSeen(true)
  return (
    <>
      {can('admin.account.list') && (
        <AccountsPanel texts={texts} userId={session.userId} canReadRoles={can('admin.role.read')}
          canReadRequests={can('admin.assignment.read')} canApprove={can('admin.assignment.approve')} run={run} />
      )}
      {can('admin.role.read') && (
        <RolesPanel texts={texts} lang={lang} userId={session.userId} canDefine={can('admin.role.define')}
          canApprove={can('admin.role.approve')} run={run} />
      )}
      {can('admin.grant.read') && (
        <GrantsPanel texts={texts} lang={lang} userId={session.userId} canRequest={can('admin.grant.request')}
          canApprove={can('admin.grant.approve')} canRevoke={can('admin.grant.revoke')} run={run} />
      )}
      {(auditAvailable || auditSeen) && (
        <AuditPanel texts={texts} access={access} onGrantUsed={onGrantUsed} run={run} />
      )}
    </>
  )
}
