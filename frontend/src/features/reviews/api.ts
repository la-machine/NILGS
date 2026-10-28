// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Review and simulated decision (MVP-05, ADR-035). Reviewers and decision authorities work on the
// cases assigned to them, or claim one waiting at their stage; whoever recommended never decides;
// an open overlap alert on the parcel blocks the decision. Every decision is simulated.
import { call } from '../../shared/api/client'
import type { ApplicationState } from '../applications'
import type { Case } from '../cases'

export type Stage = 'REVIEW' | 'DECISION'
export type Recommendation = 'RECOMMEND_APPROVAL' | 'RECOMMEND_REFUSAL' | 'RETURN_FOR_INFORMATION'
export type Outcome = 'APPROVED' | 'REFUSED'

export const RECOMMENDATIONS: Recommendation[] = ['RECOMMEND_APPROVAL', 'RECOMMEND_REFUSAL', 'RETURN_FOR_INFORMATION']
export const OUTCOMES: Outcome[] = ['APPROVED', 'REFUSED']
/** Bounds of a finding, a reason or grounds, as the server checks them. */
export const TEXT_MIN = 10
export const TEXT_MAX = 4000

export interface QueueEntry {
  id: string
  reference: string
  state: ApplicationState
  parcelReference: string
  officeCode: string
  applicantName: string
  updatedAt: string
  assigneeName: string | null
  mine: boolean
}

export interface ReviewStep { kind: 'RECOMMENDATION' | 'RETURN'; recommendation: Recommendation | null; text: string; actorName: string; at: string }

export interface Decision {
  outcome: Outcome
  grounds: string
  citedVersions: string[]
  simulated: boolean
  decidedByName: string
  at: string
}

export interface Review {
  id: string
  reference: string
  state: ApplicationState
  version: number
  officeCode: string
  /** mine: assigned to the person asking. */
  assignments: { stage: Stage; assigneeName: string; at: string; mine: boolean }[]
  steps: ReviewStep[]
  decision: Decision | null
  /** Overlap and duplicate alerts still open on the parcel: a decision waits for them. */
  openAlerts: number
  /** Whether the person asking recommended on this case: then they never decide it. */
  youRecommended: boolean
}

const one = (id: string) => `/reviews/${encodeURIComponent(id)}`

export const reviewsApi = {
  queue: (stage: Stage, mine: boolean, page = 0) =>
    call<QueueEntry[]>('GET', `/reviews?stage=${stage}&mine=${mine}&page=${page}`),
  read: (id: string) => call<Review>('GET', one(id)),
  /** The case itself (answers, documents, completeness, history), as the assignee sees it. */
  readCase: (id: string) => call<Case>('GET', `/assigned-cases/${encodeURIComponent(id)}`),
  /** A case's review as office readers see it. */
  readForOffice: (id: string) => call<Review>('GET', `/cases/${encodeURIComponent(id)}/review`),
  claim: (id: string, stage: Stage) => call<Review>('POST', `${one(id)}/${stage === 'REVIEW' ? 'review-claim' : 'decision-claim'}`),
  recommend: (id: string, body: { expectedVersion: number; recommendation: Recommendation; finding: string }) =>
    call<Review>('POST', `${one(id)}/recommendation`, body),
  returnToReview: (id: string, body: { expectedVersion: number; reason: string }) => call<Review>('POST', `${one(id)}/return`, body),
  decide: (id: string, body: { expectedVersion: number; outcome: Outcome; grounds: string; citedVersions: string[] }) =>
    call<Review>('POST', `${one(id)}/decision`, body),
}
