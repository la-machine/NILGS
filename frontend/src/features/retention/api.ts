// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Retention metadata and legal holds (ADR-041 D18). SNIGF holds no approved retention schedule, so every
// class reads "no approved schedule: kept" and nothing is ever disposed of. A hold is placed by one system
// administrator and released only when a second one approves the first one's request. Reading retention
// data opens no document. The server and the database decide who may.
import { call, query } from '../../shared/api/client'

export type SubjectKind = 'DEPOSIT_DOCUMENT' | 'DEPOSIT_DOCUMENT_REPLACEMENT' | 'DEPOSIT' | 'PROFESSIONAL_EVIDENCE' | 'PROFESSIONAL'
export const SUBJECT_KINDS: SubjectKind[] = ['DEPOSIT', 'DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'PROFESSIONAL', 'PROFESSIONAL_EVIDENCE']
export type RecordKind = 'DEPOSIT_DOCUMENT' | 'DEPOSIT_DOCUMENT_REPLACEMENT' | 'PROFESSIONAL_EVIDENCE'

export const REASON_MIN = 10
export const REASON_MAX = 2000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const isUuid = (text: string) => UUID.test(text.trim())

export interface RetentionClass {
  code: string; descriptionEn: string; descriptionFr: string; scheduleReference: string | null; scheduleApprovedOn: string | null
  standing: 'NO_APPROVED_SCHEDULE_KEPT' | 'APPROVED_SCHEDULE'
}
export interface Release {
  id: string; holdId: string; reason: string; requestedBy: string; requestedByName: string; requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'; decidedBy: string | null; decidedByName: string | null; decidedAt: string | null; decisionReason: string | null
}
export interface Hold {
  id: string; subjectKind: SubjectKind; subjectId: string; reason: string; placedBy: string; placedByName: string; placedAt: string
  inForce: boolean; releases: Release[]
}
export interface RecordRetention {
  kind: RecordKind; id: string; retentionClass: string; standing: RetentionClass['standing']; scheduleReference: string | null
  scheduleApprovedOn: string | null; holdsInForce: number; disposalAllowed: boolean
}

const BASE = '/records'
const one = (id: string) => encodeURIComponent(id)

export const retentionApi = {
  classes: () => call<RetentionClass[]>('GET', `${BASE}/retention-classes`),
  holds: (subject?: { kind: SubjectKind; id: string }, page = 0) =>
    call<Hold[]>('GET', `${BASE}/holds?${query(subject ? { kind: subject.kind, id: subject.id, page } : { page })}`),
  retentionOf: (kind: RecordKind, id: string) => call<RecordRetention>('GET', `${BASE}/retention?${query({ kind, id })}`),
  place: (subjectKind: SubjectKind, subjectId: string, reason: string) => call<Hold>('POST', `${BASE}/holds`, { subjectKind, subjectId, reason }),
  requestRelease: (holdId: string, reason: string) => call<Hold>('POST', `${BASE}/holds/${one(holdId)}/release-requests`, { reason }),
  decideRelease: (holdId: string, releaseId: string, outcome: 'APPROVED' | 'REJECTED', reason?: string) =>
    call<Hold>('POST', `${BASE}/holds/${one(holdId)}/release-requests/${one(releaseId)}/decision`, { outcome, reason }),
}
