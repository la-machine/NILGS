// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The DEMO land register (ADR-040). Nothing here is a legal registration, and nothing is a
// certificate: the extract is shown on screen only, labelled as not being one. Signing acts run
// the officer's security key over the bytes the server returns; the private key never leaves it.
import { call } from '../../shared/api/client'
import { getAssertion } from '../auth'

export type InscriptionKind = 'CREATION' | 'TRANSFER' | 'CHARGE' | 'CHARGE_RELEASE' | 'RESTRICTION' | 'RESTRICTION_RELEASE' | 'CLOSURE' | 'CANCELLATION' | 'CORRECTION'
export type InscriptionStatus = 'PROPOSED' | 'CONFIRMATION_PENDING' | 'REGISTERED' | 'REJECTED' | 'WITHDRAWN'
export type SigningAct = 'register' | 'request-confirmation' | 'confirm'
export type ConflictOutcome = 'KEEP_HOLDING' | 'PREFER_ARRIVING' | 'REFUSE_BOTH'

/** Registered only by two conservators, each signing. */
export const DUAL_CONTROL: InscriptionKind[] = ['CLOSURE', 'CANCELLATION', 'CORRECTION']
export const PROPOSABLE: InscriptionKind[] = ['TRANSFER', 'CHARGE', 'CHARGE_RELEASE', 'RESTRICTION', 'RESTRICTION_RELEASE', 'CLOSURE', 'CANCELLATION', 'CORRECTION']
export const CHARGE_KINDS = ['MORTGAGE', 'PRIVILEGE', 'SERVITUDE', 'OTHER_CHARGE'] as const
export const RESTRICTION_KINDS = ['PRENOTATION', 'OPPOSITION', 'JUDICIAL_SEIZURE', 'ADMINISTRATIVE'] as const
export const CONFLICT_OUTCOMES: ConflictOutcome[] = ['KEEP_HOLDING', 'PREFER_ARRIVING', 'REFUSE_BOTH']
export const DOCUMENT_CATEGORIES = ['DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER'] as const
export const REASON_MIN = 10
export const REASON_MAX = 2000

export interface Holder { partyId: string; partyName: string; interest: string; shareNum: number; shareDen: number }
export interface Charge {
  inscriptionId: string | null; number: number | null; kind: string; beneficiaryName: string | null; amount: number | null
  blocksTransfer: boolean; endsOn: string | null; note: string | null
}
export interface Title {
  id: string; number: string; officeCode: string; origin: string; version: number; status: 'ACTIVE' | 'CLOSED'; areaM2: number | null
  parcelReference: string | null; holders: Holder[]; charges: Charge[]; restrictions: Charge[]
}
export interface TitleSummary { id: string; number: string; officeCode: string; version: number; status: string; holderNames: string | null; locked: boolean; inConflict: boolean }
export interface Inscription {
  id: string; titleId: string; officeCode: string; number: number | null; kind: InscriptionKind; status: InscriptionStatus; expectedVersion: number
  authorityType: string; authorityReference: string; reason: string; evidenceReferences: string[]; targetInscriptionId: string | null; areaM2: number | null
  proposedBy: string; proposedByName: string; proposedAt: string; confirmedByName: string | null; registeredByName: string | null; registeredAt: string | null
  closedByName: string | null; closedReason: string | null; hash: string | null; holders: Holder[]; charge: Charge | null
  cancelledByNumber: number | null; correctedByNumber: number | null; titleNumber: string | null
}
export interface Transaction {
  id: string; titleId: string; titleNumber: string; officeCode: string; kind: string; channel: string; depositorName: string; submittedByName: string | null
  receivedByName: string; evidenceReferences: string[]; status: string; depositedAt: string; reviewDueAt: string; overdue: boolean; closedReason: string | null
}
export interface Conflict {
  id: string; titleId: string; titleNumber: string; officeCode: string; holdingDepositor: string; holdingStatus: string; arrivingDepositor: string
  arrivingStatus: string; status: 'OPEN' | 'RESOLVED'; openedAt: string; outcome: ConflictOutcome | null; resolvedByName: string | null; resolutionReason: string | null
}
export interface DepositDocument {
  id: string; transactionId: string; category: string; sha256: string; sizeBytes: number; mediaType: string; originalName: string
  uploadedBy: string; uploadedByName: string; uploadedAt: string; replacesId: string | null; replaceReason: string | null; replacedById: string | null
}
export interface Party { id: string; kind: 'NATURAL_PERSON' | 'LEGAL_PERSON' | 'NOTARY'; fullName: string; identityNumber: string }
export interface TitleView { title: Title; inscriptions: Inscription[]; transactions: Transaction[]; conflicts: Conflict[] }
export interface Verification { intact: boolean; inscriptionsChecked: number; signaturesChecked: number; versionsChecked: number; documentsChecked: number; problems: string[] }
export interface Extract { label: string; titleNumber: string; officeCode: string; status: string; version: number; areaM2: number | null; holders: Holder[]; charges: Charge[]; restrictions: Charge[] }
export interface ParcelVersionRef { versionId: string; versionNo: number; areaM2: number }
export interface NewParty { officeCode: string; kind: Party['kind']; fullName: string; identityKind: string; identityNumber: string; chamber?: string; registrationNumber?: string; jurisdiction?: string }
export interface NewHolder { party: string; interest: string; shareNum: number; shareDen: number }
export interface NewCharge { kind: string; beneficiary?: string | null; amount?: number | null; blocksTransfer: boolean; note?: string }
export interface NewInscription {
  officeCode: string; titleId?: string; kind: InscriptionKind; expectedVersion: number; authorityType: string; authorityReference: string; reason: string
  evidenceReferences: string[]; completesTransactionId?: string; targetInscriptionId?: string; areaM2?: number; parcelVersionId?: string
  holders?: NewHolder[]; charge?: NewCharge
}

const BASE = '/register'
const idempotent = () => ({ 'Idempotency-Key': crypto.randomUUID() })
const office = (code: string, page: number) => `office=${encodeURIComponent(code)}&page=${page}`

export const registerApi = {
  titles: (code: string, page = 0) => call<TitleSummary[]>('GET', `${BASE}/titles?${office(code, page)}`),
  title: (id: string) => call<TitleView>('GET', `${BASE}/titles/${encodeURIComponent(id)}`),
  extract: (id: string) => call<Extract>('GET', `${BASE}/titles/${encodeURIComponent(id)}/extract`),
  verification: (id: string) => call<Verification>('GET', `${BASE}/titles/${encodeURIComponent(id)}/verification`),
  proposals: (code: string, page = 0) => call<Inscription[]>('GET', `${BASE}/proposals?${office(code, page)}`),
  parties: (code: string, page = 0) => call<Party[]>('GET', `${BASE}/parties?${office(code, page)}`),
  overdue: (code: string, page = 0) => call<Transaction[]>('GET', `${BASE}/transactions?overdue=true&${office(code, page)}`),
  conflicts: (code: string, page = 0) => call<Conflict[]>('GET', `${BASE}/conflicts?${office(code, page)}`),
  parcelVersion: (code: string, reference: string) =>
    call<ParcelVersionRef>('GET', `${BASE}/parcels/${encodeURIComponent(reference)}?office=${encodeURIComponent(code)}`),
  createParty: (party: NewParty) => call<{ id: string }>('POST', `${BASE}/parties`, party),
  propose: (proposal: NewInscription) => call<{ id: string }>('POST', `${BASE}/inscriptions`, proposal, idempotent()),
  withdraw: (id: string, reason: string) => call<Inscription>('POST', `${BASE}/inscriptions/${encodeURIComponent(id)}/withdraw`, { reason }),
  reject: (id: string, reason: string) => call<Inscription>('POST', `${BASE}/inscriptions/${encodeURIComponent(id)}/reject`, { reason }),
  deposit: (body: { titleId: string; kind: string; depositorPartyId: string; submittedByPartyId?: string; evidenceReferences: string[] }) =>
    call<{ id: string }>('POST', `${BASE}/transactions`, body, idempotent()),
  withdrawDeposit: (id: string, reason: string) => call<Transaction>('POST', `${BASE}/transactions/${encodeURIComponent(id)}/withdraw`, { reason }),
  refuseDeposit: (id: string, reason: string) => call<Transaction>('POST', `${BASE}/transactions/${encodeURIComponent(id)}/refuse`, { reason }),
  resolve: (id: string, body: { outcome: ConflictOutcome; reason: string; evidenceReferences: string[] }) =>
    call<Conflict>('POST', `${BASE}/conflicts/${encodeURIComponent(id)}/resolution`, body),
  documents: (transactionId: string) => call<DepositDocument[]>('GET', `${BASE}/transactions/${encodeURIComponent(transactionId)}/documents`),
  addDocument: (transactionId: string, category: string, file: File) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return call<{ id: string }>('POST', `${BASE}/transactions/${encodeURIComponent(transactionId)}/documents?category=${encodeURIComponent(category)}`, form, idempotent())
  },
  replaceDocument: (transactionId: string, documentId: string, category: string, file: File, reason: string) => {
    const form = new FormData()
    form.append('file', file, file.name)
    form.append('category', category)
    form.append('reason', reason)
    return call<{ id: string }>('POST', `${BASE}/transactions/${encodeURIComponent(transactionId)}/documents/${encodeURIComponent(documentId)}/replacement`, form, idempotent())
  },
  /** A plain link: the browser downloads it as an attachment, with the session cookie. */
  documentUrl: (transactionId: string, documentId: string) =>
    `/api/v1${BASE}/transactions/${encodeURIComponent(transactionId)}/documents/${encodeURIComponent(documentId)}/content`,
  /** The officer's security key signs the inscription as the server encodes it; the server then seals and registers. */
  sign: async (id: string, act: SigningAct) => {
    const one = `${BASE}/inscriptions/${encodeURIComponent(id)}`
    const options = await call<Record<string, unknown>>('POST', `${one}/signature/options`)
    return call<Inscription>('POST', `${one}/${act}`, await getAssertion(options))
  },
}

/** Which signing act this person may perform on a proposal now, if any. */
export function signingActFor(i: Inscription, viewerId: string): SigningAct | null {
  const dual = DUAL_CONTROL.includes(i.kind)
  if (i.status === 'PROPOSED') {
    if (!dual) return 'register'
    return i.proposedBy === viewerId ? 'request-confirmation' : null
  }
  if (i.status === 'CONFIRMATION_PENDING') return i.proposedBy === viewerId ? null : 'confirm'
  return null
}

/** Splits a free-text list of synthetic references, one per line or comma. */
export function references(text: string): string[] {
  return text.split(/[\n,]/).map((r) => r.trim()).filter(Boolean)
}

/** "2/3" and the like, exactly as registered. */
export function share(h: Pick<Holder, 'shareNum' | 'shareDen'>): string {
  return `${h.shareNum}/${h.shareDen}`
}
