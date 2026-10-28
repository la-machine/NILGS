// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Submitted applications as staff of their office handle them (MVP-03, ADR-034): the office's
// queue, one case with what the automatic check finds missing, and the intake officer's two
// outcomes. The server decides who may read or decide; nobody handles their own application.
import { call } from '../../shared/api/client'
import type { ApplicationState, ApplicationType, CompletenessStep, Transition } from '../applications'

export interface CaseSummary {
  id: string
  reference: string
  typeCode: string
  state: ApplicationState
  parcelReference: string
  officeCode: string
  applicantName: string
  submittedAt: string
  updatedAt: string
  version: number
}

/** Where the case's DEMO fee stands (MVP-08); the review waits while blocksAcceptance. */
export interface CasePayment {
  standing: 'NOT_REQUIRED' | 'DUE' | 'PENDING' | 'PAID'
  reference: string | null
  amount: number | null
  currency: string | null
  blocksAcceptance: boolean
}

export interface Case extends CaseSummary {
  type: ApplicationType
  data: Record<string, string>
  /** What the automatic check finds missing now. */
  missing: string[]
  completeness: CompletenessStep[]
  history: Transition[]
  /** null when the server has no payment module. */
  payment: CasePayment | null
}

/** The states a queue can show (a draft is never a case). */
export const CASE_STATES: ApplicationState[] = [
  'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'SUBMITTED', 'UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED', 'REFUSED', 'WITHDRAWN',
]

/** Bounds of an information request's message, as the server checks them. */
export const MESSAGE_MIN = 10
export const MESSAGE_MAX = 2000

const one = (id: string) => `/cases/${encodeURIComponent(id)}`

export const casesApi = {
  queue: (office: string, state: ApplicationState | '', page: number) => {
    const query = new URLSearchParams({ office, page: String(page) })
    if (state) query.set('state', state)
    return call<CaseSummary[]>('GET', `/cases?${query}`)
  },
  read: (id: string) => call<Case>('GET', one(id)),
  accept: (id: string, expectedVersion: number) => call<Case>('POST', `${one(id)}/acceptance`, { expectedVersion }),
  requestInformation: (id: string, body: { expectedVersion: number; categories: string[]; message: string }) =>
    call<Case>('POST', `${one(id)}/information-request`, body),
}
