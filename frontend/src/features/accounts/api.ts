// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of the account directory, role requests and their four-eyes decisions,
// revocation, and second-factor reset requests (/api/v1/admin/users,
// /admin/assignment-requests, /admin/mfa-reset-requests; ADR-026, ADR-028).
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

/** A second-factor reset request (ADR-028). */
export interface ResetRequest {
  id: string
  userId: string
  displayName: string
  email: string
  offices: string[]
  reason: string
  requestedBy: string
  requestedAt: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'
  decidedBy: string | null
  identityCheck: string | null
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
  /** Requests a reset; the person's supervisor confirms it (ADR-028). */
  requestReset: (userId: string, reason: string) =>
    call<{ requestId: string }>('POST', `/admin/users/${userId}/mfa-reset`, { reason }),
  resetRequests: (status?: string) => call<ResetRequest[]>('GET', `/admin/mfa-reset-requests?${query({ status })}`),
}
