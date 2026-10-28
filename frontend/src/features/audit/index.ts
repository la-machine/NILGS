// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the audit feature; other features and the app shell import only from here.
export { auditApi } from './api'
export { AuditPage } from './AuditPage'

/** The permissions the audit panel offers actions for. */
export const AUDIT_ACTIONS = ['audit.event.search', 'audit.chain.verify', 'audit.checkpoint.create',
  'audit.checkpoint.list', 'audit.key.read']
