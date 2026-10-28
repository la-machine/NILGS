// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's applications (MVP-02, ADR-032): drafts only their owner sees, an idempotent
// submission that issues the reference, the type catalogue the form follows. The server decides
// everything: another person's application is simply "not found".
import { call } from '../../shared/api/client'

export type ApplicationState =
  | 'DRAFT' | 'SUBMITTED' | 'COMPLETENESS_REVIEW' | 'INFO_REQUESTED' | 'UNDER_REVIEW'
  | 'PENDING_DECISION' | 'APPROVED' | 'REFUSED' | 'WITHDRAWN'

export type FieldKind = 'TEXT' | 'LONG_TEXT' | 'CHOICE'

export interface FieldOption { code: string; labelFr: string; labelEn: string }

export interface FieldSpec {
  key: string
  kind: FieldKind
  required: boolean
  maxLength: number
  options: FieldOption[] | null
  labelFr: string
  labelEn: string
  hintFr: string | null
  hintEn: string | null
}

export interface DocumentCategory { code: string; required: boolean; labelFr: string; labelEn: string }

export interface ApplicationType {
  code: string
  versionNo: number
  nameFr: string
  nameEn: string
  requirements: { fields: FieldSpec[]; documents: DocumentCategory[] }
  effectiveFrom: string
}

export interface ApplicationSummary {
  id: string
  reference: string | null
  typeCode: string
  state: ApplicationState
  parcelReference: string | null
  officeCode: string | null
  updatedAt: string
  submittedAt: string | null
  version: number
}

/** Who had an application in hand, as its applicant reads it: never a person (MVP-06). */
export type Party = 'APPLICANT' | 'OFFICE' | 'SYSTEM'

/** A state change as staff read it; actorName is null for the automatic check (the system, no person). */
export interface Transition { from: ApplicationState; to: ApplicationState; actorName: string | null; reason: string | null; at: string }

/** A state change as the applicant reads it: which party, never who nor why. */
export interface Step { from: ApplicationState; to: ApplicationState; by: Party; at: string }

export type CompletenessKind = 'AUTOMATIC' | 'COMPLETE' | 'INFO_REQUESTED' | 'RESPONSE'

/** One step of the completeness review (MVP-03): what the database found missing then, what was asked or said. */
export interface CompletenessStep {
  kind: CompletenessKind
  missing: string[]
  requested: string[]
  message: string | null
  actorName: string | null
  at: string
}

/** The same step as the applicant reads it: which party acted, no person named. */
export type ApplicantCompletenessStep = Omit<CompletenessStep, 'actorName'> & { by: Party }

/** The information request the applicant must answer (MVP-03, MVP-06). */
export interface PendingRequest { categories: string[]; message: string; since: string }

export interface Application {
  id: string
  reference: string | null
  type: ApplicationType
  state: ApplicationState
  parcelReference: string | null
  officeCode: string | null
  data: Record<string, string>
  createdAt: string
  updatedAt: string
  submittedAt: string | null
  version: number
  /** When it entered its current state, from the history (MVP-06). */
  since: string
  /** Who acts next; null once it has ended. */
  waitingOn: 'APPLICANT' | 'OFFICE' | null
  pendingRequest: PendingRequest | null
  history: Step[]
  completeness: ApplicantCompletenessStep[]
  /** The decision, once recorded: always simulated in the MVP (no legal effect). */
  decision: { outcome: 'APPROVED' | 'REFUSED'; grounds: string; simulated: boolean; decidedAt: string } | null
}

/** What a creation or a submission answers. */
export interface Written { id: string; reference: string | null; state: ApplicationState; version: number }

/** A parcel reference's shape (the server also checks its check character). */
export const PARCEL_REFERENCE = /^DEMO-PCL-[A-Z0-9]{2,16}-[0-9]{4}-[0-9]{6}-[0-9A-Z]$/

export const applicationsApi = {
  types: () => call<ApplicationType[]>('GET', '/application-types'),
  mine: (page = 0) => call<ApplicationSummary[]>('GET', `/applications?page=${page}`),
  read: (id: string) => call<Application>('GET', `/applications/${encodeURIComponent(id)}`),
  create: (body: { typeCode: string; parcelReference: string | null; data: Record<string, string> }, idempotencyKey: string) =>
    call<Written>('POST', '/applications', body, { 'Idempotency-Key': idempotencyKey }),
  update: (id: string, body: { expectedVersion: number; parcelReference: string | null; data: Record<string, string> }) =>
    call<Application>('PUT', `/applications/${encodeURIComponent(id)}`, body),
  remove: (id: string, expectedVersion: number) =>
    call<void>('DELETE', `/applications/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`),
  /** The answer to an information request: files are already added; the message is optional. */
  respond: (id: string, expectedVersion: number, message: string | null) =>
    call<Application>('POST', `/applications/${encodeURIComponent(id)}/response`, { expectedVersion, message }),
  submit: (id: string, expectedVersion: number, idempotencyKey: string) =>
    call<Written>('POST', `/applications/${encodeURIComponent(id)}/submission`, { expectedVersion }, { 'Idempotency-Key': idempotencyKey }),
}
