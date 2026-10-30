// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Documents issued by SNIGF (Slice 1, ADR-039). DEMO acknowledgment only: no legal effect.
// The applicant and the office read the acknowledgment of a submitted application and download
// its exact PDF; anyone verifies a number or a file (minimal answer, throttled, no personal
// data); the document officer revokes, cancels or re-issues (document.issued.manage) and
// reviews the verification incidents (document.incident.review).
import { call, query } from '../../shared/api/client'

export type DocumentStatus = 'VALID' | 'SUPERSEDED' | 'REVOKED' | 'CANCELLED'

export interface Acknowledgment {
  number: string
  type: string
  status: DocumentStatus
  issuedAt: string
  /** The public verification address the QR code names. */
  verifyUrl: string
  /** The number of the document that replaced this one, when superseded. */
  replacement: string | null
  demo: true
}

export type Outcome = 'AUTHENTIC' | 'RECORD_FOUND' | 'ALTERED' | 'UNVERIFIED' | 'REVOKED' | 'SUPERSEDED' | 'CANCELLED'
/** EXACT_FILE: the bytes were compared; RECORD_ONLY: only the number was looked up. */
export type Scope = 'EXACT_FILE' | 'RECORD_ONLY'

export interface Verification {
  verification: string
  demo: boolean
  channel: 'NUMBER' | 'FILE'
  outcome: Outcome
  reason: string
  scope: Scope
  /** What the public may know of a matched document; null when nothing matched or the record is not trusted. */
  document: {
    number: string
    type: string
    status: DocumentStatus
    issuedAt: string
    issuingOffice: { code: string; nameFr: string; nameEn: string } | null
    replacement: string | null
  } | null
}

export interface IssuedDocument {
  id: string
  number: string
  type: string
  templateVersion: string
  subjectType: string
  subjectRef: string
  officeCode: string | null
  issuedAt: string
  printed: Record<string, string>
  keyVersion: number
  status: DocumentStatus
  statusReason: string | null
  statusChangedAt: string | null
  replacement: string | null
  verifyUrl: string
  demo: true
}

export type IncidentKind = 'ALTERED' | 'FABRICATED' | 'REGISTRY_INTEGRITY'
export const RESOLUTIONS = ['CONFIRMED_ALTERATION', 'GENUINE_COPY', 'NOT_A_SNIGF_DOCUMENT', 'REFERRED'] as const
export type Resolution = (typeof RESOLUTIONS)[number]

export interface Incident {
  id: string
  kind: IncidentKind
  status: 'OPEN' | 'RESOLVED'
  openedAt: string
  documentNumber: string | null
  channel: 'NUMBER' | 'FILE'
  claimedNumber: string | null
  presentedSha256: string | null
  reason: string
  resolution: Resolution | null
  note: string | null
  resolvedByName: string | null
  resolvedAt: string | null
  version: number
}

/** Bounds the server checks: a status reason, an incident note. */
export const REASON_MIN = 10
export const REASON_MAX = 1000
export const NOTE_MIN = 10
export const NOTE_MAX = 2000
/** The number's form: SNIGF- and 26 characters of Crockford base 32. */
export const NUMBER_PATTERN = /^SNIGF-[0-9A-HJKMNP-TV-Z]{26}$/

/** Upper case, no spaces, as the server normalises it. */
export function normaliseNumber(value: string): string {
  return value.trim().toUpperCase()
}

export type AcknowledgmentSource = 'own' | 'case'

const base = (applicationId: string, source: AcknowledgmentSource) =>
  `/${source === 'own' ? 'applications' : 'cases'}/${encodeURIComponent(applicationId)}/acknowledgment`
const issued = (number: string) => `/issued-documents/${encodeURIComponent(number)}`

export const issuanceApi = {
  acknowledgment: (applicationId: string, source: AcknowledgmentSource = 'own') =>
    call<Acknowledgment>('GET', base(applicationId, source)),
  /** Issues the acknowledgment if it is missing; idempotent. */
  issue: (applicationId: string) => call<Acknowledgment>('POST', `${base(applicationId, 'own')}`, {}),
  fileUrl: (applicationId: string, source: AcknowledgmentSource = 'own') => `/api/v1${base(applicationId, source)}/file`,
  verifyNumber: (number: string) => call<Verification>('GET', `/public/verifications/${encodeURIComponent(number)}`),
  verifyFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return call<Verification>('POST', '/public/verifications', form)
  },
  document: (number: string) => call<IssuedDocument>('GET', issued(number)),
  revoke: (number: string, reason: string) => call<IssuedDocument>('POST', `${issued(number)}/revocation`, { reason }),
  cancel: (number: string, reason: string) => call<IssuedDocument>('POST', `${issued(number)}/cancellation`, { reason }),
  reissue: (number: string, reason: string) => call<IssuedDocument>('POST', `${issued(number)}/reissue`, { reason }),
  incidents: (status: 'OPEN' | 'RESOLVED' | '', page: number) =>
    call<Incident[]>('GET', `/verification-incidents?${query({ status, page })}`),
  resolve: (id: string, body: { expectedVersion: number; resolution: Resolution; note: string }) =>
    call<Incident>('POST', `/verification-incidents/${encodeURIComponent(id)}/resolution`, body),
}
