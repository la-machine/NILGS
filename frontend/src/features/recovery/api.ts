// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The supervisor's side of a second-factor reset (/api/v1/identity/reset-confirmations,
// ADR-028): the pending requests for people of their offices, confirm with how the identity
// was checked, or reject.
import { call } from '../../shared/api/client'

export interface ResetToConfirm {
  id: string
  userId: string
  displayName: string
  email: string
  offices: string[]
  reason: string
  requestedBy: string
  requestedAt: string
}

export const recoveryApi = {
  toConfirm: () => call<ResetToConfirm[]>('GET', '/identity/reset-confirmations'),
  confirm: (id: string, identityCheck: string) =>
    call<void>('POST', `/identity/reset-confirmations/${id}/confirm`, { identityCheck }),
  reject: (id: string, reason: string) => call<void>('POST', `/identity/reset-confirmations/${id}/reject`, { reason }),
}
