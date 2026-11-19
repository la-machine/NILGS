// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A case's procedure (ADR-042, DEMO only): its stages, each recorded by the officer its permission names, with its
// evidence; a correction is a new record naming the one it replaces, with a reason, and every record stays. The gate
// lists what still stops an approval. The server and the database decide who may record what. Step 4: the publication
// opens a notice; objections are received at the counter and decided by a supervisor; the public notices need no account.
import { call, query } from '../../shared/api/client'

export type StageCode = 'CADASTRAL_REVIEW' | 'FIELD_VISIT' | 'COMMISSION_REPORT' | 'PUBLICATION' | 'TITLE_VERIFICATION' | 'DUTIES_EVIDENCE'
export type StageStatus = 'NOT_STARTED' | 'COMPLETED' | 'NOT_SATISFIED' | 'IN_PROGRESS' | 'OUTDATED'
export type ObjectionOutcome = 'UPHELD' | 'DISMISSED'
export type Interest = 'NEIGHBOUR' | 'CLAIMANT' | 'HEIR' | 'CUSTOMARY_AUTHORITY' | 'OTHER'
export const INTERESTS: Interest[] = ['NEIGHBOUR', 'CLAIMANT', 'HEIR', 'CUSTOMARY_AUTHORITY', 'OTHER']
export const STATEMENT_MIN = 20
export const STATEMENT_MAX = 4000
export const GROUNDS_MIN = 20
export const GROUNDS_MAX = 4000
export type Outcome = 'COMPLETED' | 'NOT_SATISFIED'

export const FINDING_MIN = 10
export const FINDING_MAX = 4000
export const REASON_MIN = 10
export const REASON_MAX = 2000
export const MAX_FILES = 5

export interface Person { name: string; capacity: string }
export interface StageFile { id: string; originalName: string; mediaType: string; sizeBytes: number; sha256: string; uploadedAt: string }
export interface StageRecord {
  id: string; outcome: Outcome; finding: string; details: Record<string, unknown>; evidenceVersions: string[]
  supersedes: string | null; correctionReason: string | null; recordedByName: string; recordedBySelf: boolean; recordedAt: string
  files: StageFile[]
}
export interface Stage {
  code: StageCode; rank: number; requires: string | null; permission: string; labelEn: string; labelFr: string
  status: StageStatus; mayRecord: boolean; records: StageRecord[]
}
export interface Notice {
  id: string; number: string; parcelReference: string; locality: string; period: string; opensAt: string; closesAt: string
  replaces: string | null; current: boolean; periodOpen: boolean
}
/** Never the identity: `identityVisible` says whether this reader may ask for it (one objection at a time, audited). */
export interface Objector { name: string; interest: Interest; identityVisible: boolean }
export interface ObjectorIdentity { identityKind: string; identityNumber: string }
export interface ObjectionDecision {
  outcome: ObjectionOutcome; grounds: string; reviewStages: StageCode[]; applicantInstructions: string | null
  decidedByName: string; decidedBySelf: boolean; decidedAt: string
}
/** What the office asks the applicant's case to go through again, and what the applicant must do; never the reason. */
export interface ProcedureInstruction { stages: string[]; action: string | null; at: string; pending: boolean }
export const INSTRUCTIONS_MAX = 1000
export interface Objection {
  id: string; number: string; noticeNumber: string; objector: Objector; statement: string; receivedAt: string; late: boolean
  recordedByName: string; recordedBySelf: boolean; decision: ObjectionDecision | null; mayDecide: boolean; files: StageFile[]
}
/**
 * A reconsideration of the approved case (ADR-043 step 5b): the round it opened, the outcome it reconsiders, the objections
 * upheld after that decision it answers, the stages reopened (and which were added), why, who opened it, and its round's
 * outcome once decided (null until then).
 */
export interface Reconsideration {
  id: string; round: number; reconsideredOutcome: string; objectionNumbers: string[]; stages: StageCode[]; addedStages: StageCode[]
  justification: string; openedByName: string; openedBySelf: boolean; openedAt: string; roundOutcome: string | null
}
export const JUSTIFICATION_MIN = 20
export const JUSTIFICATION_MAX = 4000
export interface CaseProcedure {
  id: string; reference: string; state: string; typeCode: string; officeCode: string; procedure: 'A' | 'B' | null
  stages: Stage[]; gate: string[]; notices: Notice[]; objections: Objection[]; mayRecordObjection: boolean
  /** The case's version (for a reconsideration's optimistic check) and its current review round (V36). */
  version: number; round: number
  reconsiderations: Reconsideration[]
  /** Why the reader may not open a reconsideration now, or null when they may (the server decides again). */
  reconsiderProblem: string | null
  /** A procedure B case's open deposit and its duty receipts, for whoever records its stages (ADR-042 step 6). */
  caseDeposit?: { id: string; status: string; channel: string; depositedAt: string | null; titleNumber: string; receipts: { id: string; name: string }[] } | null
}
export interface NewObjection {
  objectorName: string; identityKind: string; identityNumber: string; interest: Interest; statement: string
}
export interface ObjectionQueueEntry {
  objectionId: string; number: string; applicationId: string; reference: string; officeCode: string; receivedAt: string; late: boolean
}
/** A notice as the public reads it: what notification needs, never the applicant or the case. */
export interface PublicNotice {
  number: string; procedure: 'A' | 'B'; parcelReference: string; locality: string
  office: { code: string; nameFr: string; nameEn: string }; opensAt: string; closesAt: string
}
export interface QueueEntry {
  applicationId: string; reference: string; officeCode: string; typeCode: string; stage: StageCode; stageStatus: StageStatus; submittedAt: string
}
export interface NewRecord {
  outcome: Outcome; finding: string; details: Record<string, unknown>; evidenceVersions?: string[]
  supersedes?: string; correctionReason?: string
}

const BASE = '/procedures'
const one = (id: string) => encodeURIComponent(id)

export const proceduresApi = {
  read: (id: string) => call<CaseProcedure>('GET', `${BASE}/${one(id)}`),
  record: (id: string, stage: StageCode, record: NewRecord, files: File[] = []) => {
    const form = new FormData()
    form.append('record', new Blob([JSON.stringify(record)], { type: 'application/json' }))
    files.forEach((f) => form.append('files', f))
    return call<CaseProcedure>('POST', `${BASE}/${one(id)}/stages/${stage}`, form)
  },
  fileUrl: (id: string, fileId: string) => `/api/v1${BASE}/${one(id)}/files/${one(fileId)}`,
  queue: (page = 0) => call<QueueEntry[]>('GET', `${BASE}/queue?${query({ page })}`),
  /** Recorded at the counter; the key makes a retry give back the same objection. */
  recordObjection: (id: string, objection: NewObjection, files: File[], key: string) => {
    const form = new FormData()
    form.append('objection', new Blob([JSON.stringify(objection)], { type: 'application/json' }))
    files.forEach((f) => form.append('files', f))
    return call<{ objection: string; procedure: CaseProcedure }>('POST', `${BASE}/${one(id)}/objections`, form, { 'Idempotency-Key': key })
  },
  decideObjection: (id: string, objectionId: string, outcome: ObjectionOutcome, grounds: string, reviewStages: StageCode[],
    applicantInstructions: string | null = null) =>
    call<CaseProcedure>('POST', `${BASE}/${one(id)}/objections/${one(objectionId)}/decision`,
      { outcome, grounds, reviewStages, applicantInstructions }),
  /** Each call is audited on the server. */
  objectorIdentity: (id: string, objectionId: string) =>
    call<ObjectorIdentity>('GET', `${BASE}/${one(id)}/objections/${one(objectionId)}/objector`),
  objectionFileUrl: (id: string, objectionId: string, fileId: string) =>
    `/api/v1${BASE}/${one(id)}/objections/${one(objectionId)}/files/${one(fileId)}`,
  objectionQueue: (page = 0) => call<ObjectionQueueEntry[]>('GET', `${BASE}/objection-queue?${query({ page })}`),
  /** Opens the next round (ADR-043 step 5b); audited on the server with the justification. */
  reconsider: (id: string, expectedVersion: number, addedStages: StageCode[], justification: string) =>
    call<CaseProcedure>('POST', `${BASE}/${one(id)}/reconsideration`, { expectedVersion, addedStages, justification }),
  publicNotices: (page = 0) => call<{ demo: boolean; notices: PublicNotice[] }>('GET', `/public/notices?${query({ page })}`),
}

/** The permissions that record a stage; the queue is for their holders. */
export const RECORDING = ['procedure.stage.cadastral', 'procedure.commission.report', 'procedure.stage.publish', 'procedure.stage.title']
