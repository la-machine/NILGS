// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of the permission catalogue, roles and four-eyes role change requests
// (/api/v1/admin/permissions, /admin/roles, /admin/role-requests, ADR-025).
import { call, query } from '../../shared/api/client'

export type Scope = 'ANY' | 'OFFICE' | 'ASSIGNED' | 'OWN' | 'LINKED'

export interface CataloguePermission {
  code: string
  family: 'ADMINISTRATION' | 'AUDIT' | 'BUSINESS'
  delegable: boolean
  scopes: Scope[]
  descriptionEn: string
  descriptionFr: string
}

export interface RoleDefinition {
  code: string
  nameFr: string
  nameEn: string
  description: string | null
  system: boolean
  assignable: boolean
  active: boolean
  permissions: Record<string, Scope>
}

export interface RoleChangeRequest {
  id: string
  kind: 'CREATE' | 'UPDATE' | 'RETIRE'
  roleCode: string
  proposal: { nameFr: string; nameEn: string; description: string | null; permissions: Record<string, Scope> }
  reason: string
  requestedBy: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
}

export interface RoleProposal {
  kind: RoleChangeRequest['kind']
  roleCode: string
  nameFr?: string
  nameEn?: string
  description?: string
  permissions?: Record<string, Scope>
  reason: string
}

export const rolesApi = {
  catalogue: () => call<CataloguePermission[]>('GET', '/admin/permissions'),
  list: () => call<RoleDefinition[]>('GET', '/admin/roles'),
  requests: (status?: string) => call<RoleChangeRequest[]>('GET', `/admin/role-requests?${query({ status })}`),
  propose: (body: RoleProposal) => call<{ requestId: string }>('POST', '/admin/role-requests', body),
  decide: (id: string, decision: 'approve' | 'reject', reason: string) =>
    call<void>('POST', `/admin/role-requests/${id}/${decision}`, { reason }),
}
