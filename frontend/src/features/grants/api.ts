// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of special grants: request, four-eyes decision, revocation (/api/v1/admin/grants,
// ADR-025).
import { call, query } from '../../shared/api/client'

export interface SpecialGrant {
  id: string
  userId: string
  permission: string
  officeCode: string | null
  reason: string
  validUntil: string | null
  maxUses: number | null
  uses: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
}

export interface GrantRequest {
  userId: string
  permission: string
  officeCode?: string
  reason: string
  validUntil?: string
  maxUses?: number
}

export const grantsApi = {
  list: (status?: string) => call<SpecialGrant[]>('GET', `/admin/grants?${query({ status })}`),
  request: (body: GrantRequest) => call<{ grantId: string }>('POST', '/admin/grants', body),
  decide: (id: string, decision: 'approve' | 'reject' | 'revoke', reason: string) =>
    call<void>('POST', `/admin/grants/${id}/${decision}`, { reason }),
}
