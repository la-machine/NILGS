// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of the audit trail: event search, chain verification, checkpoints and their
// public keys (/api/v1/audit, ADR-007).
import { call, query } from '../../shared/api/client'

export interface AuditEvent {
  chainPos: number
  id: string
  occurredAt: string
  actorType: string
  actorId: string | null
  actorLabel: string | null
  actorRoles: string[]
  action: string
  entityType: string | null
  entityId: string | null
  outcome: string
  reason: string | null
  sourceIp: string | null
  details: Record<string, unknown>
}

/** The result of a verification: positions and kinds only, never an event's content. */
export interface Verification {
  verified: boolean
  checkedFrom: number
  checkedTo: number
  rowsChecked: number
  firstMismatchPos: number | null
  mismatchKind: string | null
  checkpoints: unknown[]
}

export interface CheckpointSummary {
  id: string
  chainPos: number
  headHash: string
  createdAt: string
  keyVersion: number
  file: string
}

export interface CheckpointKey {
  version: number
  ed25519: string
  pem: string
}

export const auditApi = {
  events: (filter: { action?: string; actorId?: string; entityType?: string; page?: number }) =>
    call<{ events: AuditEvent[]; page: number; more: boolean }>('GET', `/audit/events?${query({ ...filter, size: 25 })}`),
  verify: () => call<Verification>('POST', '/audit/verify', {}),
  checkpoint: () => call<CheckpointSummary>('POST', '/audit/checkpoints', {}),
  checkpoints: () => call<CheckpointSummary[]>('GET', '/audit/checkpoints'),
  keys: () => call<CheckpointKey[]>('GET', '/audit/checkpoint-keys'),
}
