// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of the audit trail: event search, a case's or a parcel's timeline, chain
// verification, checkpoints and their public keys (/api/v1/audit, ADR-007, ADR-038). Search and
// timeline answer an office's reader with the events of their offices only.
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
  entityRef?: string | null
  /** The account's current name, for reading only (the event records its id). */
  actorName?: string | null
  /** What the event is about, with each record's office at the time (ADR-038). */
  subjects?: { type: 'APPLICATION' | 'PARCEL'; id: string; officeCode: string | null }[]
  /** Its hash recomputes from its own row: false means the row was altered. */
  intact?: boolean
}

export type TimelineType = 'APPLICATION' | 'PARCEL'

export interface Timeline {
  subjectType: TimelineType
  subjectId: string
  reference: string | null
  events: AuditEvent[]
  /** More events exist than the 500 shown. */
  truncated: boolean
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
  timeline: (type: TimelineType, reference: string) =>
    call<Timeline>('GET', `/audit/timeline?${query({ type, reference })}`),
  verify: () => call<Verification>('POST', '/audit/verify', {}),
  checkpoint: () => call<CheckpointSummary>('POST', '/audit/checkpoints', {}),
  checkpoints: () => call<CheckpointSummary[]>('GET', '/audit/checkpoints'),
  keys: () => call<CheckpointKey[]>('GET', '/audit/checkpoint-keys'),
}
