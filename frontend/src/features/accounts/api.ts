// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of the account directory, role requests and their four-eyes decisions,
// revocation and second-factor reset (/api/v1/admin/users, /admin/assignment-requests,
// ADR-026).
import { call, query } from '../../shared/api/client'

export interface Account {
  id: string
  email: string
  displayName: string
  accountType: 'APPLICANT' | 'STAFF'
  enabled: boolean
  secondFactorReady: boolean
  locked: boolean
  assignments: { id: string; role: string; officeCode: string | null }[]
}

export interface AssignmentRequest {
  id: string
  userId: string
  role: string
  officeCode: string
  reason: string
  requestedBy: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
}

export const accountsApi = {
  search: (q: string) => call<Account[]>('GET', `/admin/users?${query({ q })}`),
  /** Requests a role; another administrator approves it (ADR-026). */
  requestRole: (userId: string, role: string, officeCode: string, reason: string) =>
    call<{ requestId: string }>('POST', `/admin/users/${userId}/roles`, { role, officeCode, reason }),
  assignmentRequests: (status?: string) =>
    call<AssignmentRequest[]>('GET', `/admin/assignment-requests?${query({ status })}`),
  decideAssignment: (id: string, decision: 'approve' | 'reject', reason: string) =>
    call<void>('POST', `/admin/assignment-requests/${id}/${decision}`, { reason }),
  revoke: (userId: string, assignmentId: string, reason: string) =>
    call<void>('DELETE', `/admin/users/${userId}/roles/${assignmentId}`, { reason }),
  resetSecondFactor: (userId: string, reason: string, confirmedBy: string) =>
    call<void>('POST', `/admin/users/${userId}/mfa-reset`, { reason, confirmedBy }),
}
