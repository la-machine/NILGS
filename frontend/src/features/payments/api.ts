// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The DEMO payment (MVP-08, ADR-037). NO REAL MONEY MOVES: the only provider is a simulator.
// The applicant pays the DEMO fee of their own application (payment.pay), with an Idempotency-Key;
// the provider's answer arrives later, as a signed callback the server reconciles. The finance
// officer (payment.reconcile) reads the requests and the exceptions, and resolves exceptions;
// a resolution never pays a request.
import { call, query } from '../../shared/api/client'

/** What the DEMO simulator does with a charge: it exists only because the provider is simulated. */
export const SCENARIOS = ['SUCCEED', 'FAIL', 'NO_RESPONSE', 'DUPLICATE', 'WRONG_AMOUNT', 'UNKNOWN_REFERENCE'] as const
export type Scenario = (typeof SCENARIOS)[number]

export type PaymentStatus = 'PENDING' | 'PAID' | 'FAILED' | 'EXPIRED'
/** NOT_REQUIRED: no fee applies; DUE: nothing paid or pending; PENDING; PAID. */
export type Standing = 'NOT_REQUIRED' | 'DUE' | 'PENDING' | 'PAID'

export interface Fee {
  amount: number
  currency: string
  labelFr: string
  labelEn: string
}

export interface PaymentRequest {
  id: string
  reference: string
  amount: number
  currency: string
  /** As of now: a pending request past its expiry reads EXPIRED. */
  status: PaymentStatus
  scenario: Scenario
  createdAt: string
  expiresAt: string
  settledAt: string | null
}

export interface PaymentView {
  applicationId: string
  demo: true
  /** null when no fee applies. */
  fee: Fee | null
  requiredBeforeAcceptance: boolean
  standing: Standing
  current: PaymentRequest | null
  history: PaymentRequest[]
}

export interface FinanceSummary {
  demo: true
  pending: number
  paid: number
  failed: number
  expired: number
  openExceptions: number
}

export interface FinanceRequest {
  id: string
  reference: string
  applicationReference: string
  amount: number
  currency: string
  status: PaymentStatus
  createdAt: string
  expiresAt: string
  settledAt: string | null
  transactionId: string | null
}

export type Outcome = 'AMOUNT_MISMATCH' | 'UNMATCHED_REFERENCE' | 'AFTER_EXPIRY' | 'ALREADY_SETTLED'
export const RESOLUTIONS = ['REFUND_SIMULATED', 'NO_ACTION'] as const
export type Resolution = (typeof RESOLUTIONS)[number]

export interface FinanceException {
  id: string
  transactionId: string
  reportedReference: string
  reportedAmount: number
  reportedCurrency: string
  outcome: Outcome
  receivedAt: string
  /** null when no request matches the reference. */
  requestReference: string | null
  expectedAmount: number | null
  requestStatus: PaymentStatus | null
  applicationReference: string | null
  /** null while open. */
  resolution: Resolution | null
  resolutionNote: string | null
  resolvedByName: string | null
  resolvedAt: string | null
}

/** Bounds of a resolution note, as the server checks them. */
export const NOTE_MIN = 10
export const NOTE_MAX = 1000

const application = (id: string) => `/applications/${encodeURIComponent(id)}`

export const paymentsApi = {
  view: (applicationId: string) => call<PaymentView>('GET', `${application(applicationId)}/payment`),
  pay: (applicationId: string, scenario: Scenario, idempotencyKey: string) =>
    call<PaymentView>('POST', `${application(applicationId)}/payments`, { scenario }, { 'Idempotency-Key': idempotencyKey }),
  summary: () => call<FinanceSummary>('GET', '/payments/summary'),
  requests: (status: PaymentStatus | '', page: number) =>
    call<FinanceRequest[]>('GET', `/payments?${query({ status, page })}`),
  exceptions: (resolved: boolean | null, page: number) =>
    call<FinanceException[]>('GET', `/payments/exceptions?${query({ resolved: resolved === null ? undefined : String(resolved), page })}`),
  resolve: (id: string, body: { resolution: Resolution; note: string }, idempotencyKey: string) =>
    call<{ id: string }>('POST', `/payments/exceptions/${encodeURIComponent(id)}/resolution`, body,
      { 'Idempotency-Key': idempotencyKey }),
}
