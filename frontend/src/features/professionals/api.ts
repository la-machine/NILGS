// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Professional accounts (ADR-041). The professional's side: the public request, their own status,
// the restricted title lookup (number, office, whether it accepts a deposit, nothing else), and
// their own deposits. The administrators' side: the queue, verification with evidence, the second
// administrator's decision, suspension, and changes for another administrator to decide. The
// server and the database decide every step; these calls only carry them.
import { call } from '../../shared/api/client'

export type Profession = 'NOTARY' | 'SWORN_SURVEYOR'
export type ProfessionalStatus = 'REQUESTED' | 'VERIFIED' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED'
export type ChangeKind = 'REINSTATEMENT' | 'REVOCATION' | 'OFFICE_CHANGE'

export const PROFESSIONS: Profession[] = ['NOTARY', 'SWORN_SURVEYOR']
export const STATUSES: ProfessionalStatus[] = ['REQUESTED', 'VERIFIED', 'ACTIVE', 'SUSPENDED', 'REVOKED']
/** A waiting step of the lifecycle: the directory's filter, and the dashboard's links (D20, D21). */
export type PendingStep = 'VERIFICATION' | 'ACTIVATION' | 'CHANGE'
export const PENDING_STEPS: PendingStep[] = ['VERIFICATION', 'ACTIVATION', 'CHANGE']
/** The server's bounds for the directory (ProfessionalService): a page of 1 to 100, a search of 2 to 100 characters. */
export const DIRECTORY_SIZE = 50
export const SEARCH_MIN = 2
export const SEARCH_MAX = 100
export const DEPOSIT_KINDS = ['SALE', 'GIFT', 'INHERITANCE'] as const
export const DEPOSITOR_KINDS = ['NATURAL_PERSON', 'LEGAL_PERSON'] as const
export const IDENTITY_KINDS = ['NATIONAL_ID', 'PASSPORT', 'COMPANY_REGISTRATION'] as const
export const DOCUMENT_CATEGORIES = ['DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER'] as const
/** One deposit travels in one request (ADR-041 §7): at most this many files, a deed among them. */
export const MAX_FILES = 20
export const REASON_MIN = 10
export const REASON_MAX = 2000

export interface Profile {
  userId: string; email: string; displayName: string; profession: Profession; chamber: string; registrationNumber: string; jurisdiction: string
  status: ProfessionalStatus; requestedAt: string; statusChangedAt: string; offices: string[]
}
/** The directory's filters (D20); empty means any. They combine with AND. */
export interface DirectoryFilter { q: string; status: '' | ProfessionalStatus; profession: '' | Profession; office: string; pending: '' | PendingStep }
export const NO_FILTER: DirectoryFilter = { q: '', status: '', profession: '', office: '', pending: '' }
/** One page of the directory, newest account first, and how many accounts match in all. */
export interface DirectoryPage { items: Profile[]; total: number; page: number; size: number }
/**
 * What waits for this administrator (D21), pending items only. The server leaves out a count they may not act on; one's
 * own requests and verifications are in waitingForOthers, since another administrator decides them.
 */
export interface ProfessionalCounts { requests?: number; activations?: number; changes?: number; waitingForOthers?: number }
export interface ChangeRequest {
  id: string; userId: string; professionalName: string; kind: 'ACTIVATION' | ChangeKind; officesAdd: string[]; officesRemove: string[]; reason: string | null
  requestedBy: string; requestedByName: string; requestedAt: string; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'; decidedByName: string | null
  decidedAt: string | null; decisionReason: string | null
}
export interface HistoryEntry { event: string; actorName: string | null; reason: string | null; at: string }
export interface Evidence { id: string; sha256: string; sizeBytes: number; mediaType: string; originalName: string; uploadedByName: string; uploadedAt: string }
export interface VerificationRecord {
  id: string; verifiedBy: string; verifiedByName: string; body: string; reference: string; checkedOn: string; note: string; verifiedAt: string; documents: Evidence[]
}
export interface Detail { profile: Profile; requests: ChangeRequest[]; verification: VerificationRecord | null; history: HistoryEntry[] }
export interface Own { profile: Profile; history: HistoryEntry[] }
export interface NewRequest {
  email: string; displayName: string; password: string; preferredLanguage: string; profession: Profession; chamber: string; registrationNumber: string
  jurisdiction: string
}
export interface NewVerification { body: string; reference: string; checkedOn: string; note: string; offices: string[] }
export interface NewChange { kind: ChangeKind; officesAdd?: string[]; officesRemove?: string[]; reason: string }

/** The whole answer of the lookup (D16): never holders, charges, history or pending deposits. */
export interface TitleLookup { id: string; number: string; officeCode: string; acceptsDeposit: boolean }
/**
 * One's own deposit (ADR-041 D17): its real status, whether a conflict naming it is under review or resolved, and
 * what is asked of the professional (nothing yet). Never anything of the competing deposit; a conflict decision's
 * reason is the conservators' and is not sent.
 */
export interface Deposit {
  id: string; titleId: string; titleNumber: string; officeCode: string; kind: string
  status: 'HOLDING' | 'IN_CONFLICT' | 'REGISTERED' | 'WITHDRAWN' | 'REFUSED'
  conflict: 'NONE' | 'UNDER_REVIEW' | 'RESOLVED'; reviewDueAt: string | null; actionRequired: 'NONE'
  depositorName: string; capacity: string; depositedAt: string; closedAt: string | null; closedReason: string | null; documents: number
}

/** Open: not yet registered or refused by conservators, nor withdrawn. */
export const isOpen = (d: Pick<Deposit, 'status'>) => d.status === 'HOLDING' || d.status === 'IN_CONFLICT'
/** A file of one's own deposit; who else added one is not shown. */
export interface OwnDocument {
  id: string; transactionId: string; category: string; sha256: string; sizeBytes: number; mediaType: string; originalName: string; uploadedBySelf: boolean
  uploadedAt: string; replacesId: string | null; replaceReason: string | null; replacedById: string | null
}
export interface NewDepositor { kind: (typeof DEPOSITOR_KINDS)[number]; fullName: string; identityKind: string; identityNumber: string }
export interface DepositFile { category: string; file: File }
export interface NewDeposit { titleId: string; kind: string; depositor: NewDepositor; files: DepositFile[] }

const idempotent = () => ({ 'Idempotency-Key': crypto.randomUUID() })
const one = (id: string) => encodeURIComponent(id)
const ADMIN = '/admin/professionals'
const OWN = '/professional/deposits'

/** A plain form with one file: the browser sets the boundary. */
function withFile(file: File, fields: Record<string, string> = {}): FormData {
  const form = new FormData()
  form.append('file', file, file.name)
  for (const [k, v] of Object.entries(fields)) form.append(k, v)
  return form
}

export const professionalApi = {
  /** Public, throttled; the same answer whether or not the address was new. */
  request: (body: NewRequest) => call<void>('POST', '/professional-requests', body),
  me: () => call<Own>('GET', '/professional/me'),
  directory: (filter: DirectoryFilter, page = 0, size = DIRECTORY_SIZE) => call<DirectoryPage>('GET', `${ADMIN}?${directoryQuery(filter, page, size)}`),
  counts: () => call<ProfessionalCounts>('GET', `${ADMIN}/counts`),
  detail: (userId: string) => call<Detail>('GET', `${ADMIN}/${one(userId)}`),
  verify: (userId: string, body: NewVerification) => call<{ id: string }>('POST', `${ADMIN}/${one(userId)}/verification`, body),
  addEvidence: (userId: string, file: File) => call<{ id: string }>('POST', `${ADMIN}/${one(userId)}/verification/documents`, withFile(file), idempotent()),
  /** A plain link: an attachment, its reading audited by the server. */
  evidenceUrl: (userId: string, documentId: string) => `/api/v1${ADMIN}/${one(userId)}/verification/documents/${one(documentId)}/content`,
  suspend: (userId: string, reason: string) => call<void>('POST', `${ADMIN}/${one(userId)}/suspension`, { reason }),
  requestChange: (userId: string, body: NewChange) => call<{ id: string }>('POST', `${ADMIN}/${one(userId)}/requests`, body),
  pending: (page = 0) => call<ChangeRequest[]>('GET', `/admin/professional-requests?page=${page}`),
  approve: (requestId: string, reason?: string) =>
    call<ChangeRequest>('POST', `/admin/professional-requests/${one(requestId)}/approval`, reason ? { reason } : undefined),
  reject: (requestId: string, reason: string) => call<ChangeRequest>('POST', `/admin/professional-requests/${one(requestId)}/rejection`, { reason }),
}

export const depositsApi = {
  lookup: (number: string) => call<TitleLookup>('GET', `/professional/titles?number=${encodeURIComponent(number)}`),
  /** The title, the kind, the client and every file in one request: all of it is recorded, or none. */
  deposit: (d: NewDeposit, key: string) => {
    const form = new FormData()
    form.append('titleId', d.titleId)
    form.append('kind', d.kind)
    form.append('depositorKind', d.depositor.kind)
    form.append('depositorName', d.depositor.fullName)
    form.append('depositorIdentityKind', d.depositor.identityKind)
    form.append('depositorIdentityNumber', d.depositor.identityNumber)
    for (const f of d.files) {
      form.append('categories', f.category)
      form.append('files', f.file, f.file.name)
    }
    return call<{ id: string }>('POST', OWN, form, { 'Idempotency-Key': key })
  },
  list: (page = 0) => call<Deposit[]>('GET', `${OWN}?page=${page}`),
  one: (id: string) => call<Deposit>('GET', `${OWN}/${one(id)}`),
  withdraw: (id: string, reason: string) => call<Deposit>('POST', `${OWN}/${one(id)}/withdrawal`, { reason }),
  documents: (id: string) => call<OwnDocument[]>('GET', `${OWN}/${one(id)}/documents`),
  addDocument: (id: string, category: string, file: File) =>
    call<{ id: string }>('POST', `${OWN}/${one(id)}/documents?category=${encodeURIComponent(category)}`, withFile(file), idempotent()),
  replaceDocument: (id: string, documentId: string, category: string, file: File, reason: string) =>
    call<{ id: string }>('POST', `${OWN}/${one(id)}/documents/${one(documentId)}/replacement`, withFile(file, { category, reason }), idempotent()),
  documentUrl: (id: string, documentId: string) => `/api/v1${OWN}/${one(id)}/documents/${one(documentId)}/content`,
}

/** What the form still lacks before a deposit can be sent; the server checks all of it again. */
export function depositProblems(d: Pick<NewDeposit, 'depositor' | 'files'>): ('DEED_REQUIRED' | 'TOO_MANY_FILES' | 'DEPOSITOR_INVALID')[] {
  const problems: ('DEED_REQUIRED' | 'TOO_MANY_FILES' | 'DEPOSITOR_INVALID')[] = []
  if (!d.files.some((f) => f.category === 'DEED')) problems.push('DEED_REQUIRED')
  if (d.files.length > MAX_FILES) problems.push('TOO_MANY_FILES')
  const { fullName, identityNumber } = d.depositor
  if (fullName.trim().length < 3 || !fullName.includes('DEMO') || identityNumber.trim().length < 5 || !identityNumber.includes('DEMO')) {
    problems.push('DEPOSITOR_INVALID')
  }
  return problems
}

/** The directory's query string: only the filters set, each encoded, the search trimmed. */
export function directoryQuery(filter: DirectoryFilter, page: number, size: number): string {
  const params = new URLSearchParams()
  const q = filter.q.trim()
  if (q) params.set('q', q)
  if (filter.status) params.set('status', filter.status)
  if (filter.profession) params.set('profession', filter.profession)
  const office = filter.office.trim().toUpperCase()
  if (office) params.set('office', office)
  if (filter.pending) params.set('pending', filter.pending)
  params.set('page', String(page))
  params.set('size', String(size))
  // URLSearchParams writes a space as '+'; %20 reads the same everywhere.
  return params.toString().replace(/\+/g, '%20')
}

/** The filters read from the address; a value the screen does not offer is dropped, and the server checks all again. */
export function directoryFilterFrom(params: URLSearchParams): { filter: DirectoryFilter; page: number } {
  const pick = <T extends string>(name: string, allowed: readonly T[]): T | '' => allowed.find((v) => v === params.get(name)) ?? ''
  return {
    filter: {
      q: (params.get('q') ?? '').slice(0, SEARCH_MAX), status: pick('status', STATUSES), profession: pick('profession', PROFESSIONS),
      office: (params.get('office') ?? '').toUpperCase(), pending: pick('pending', PENDING_STEPS),
    },
    page: Math.max(Number(params.get('page') ?? 0) || 0, 0),
  }
}

/** Office codes typed as a list, one per comma or line, upper-cased. */
export function officeCodes(text: string): string[] {
  return Array.from(new Set(text.split(/[\n,]/).map((c) => c.trim().toUpperCase()).filter(Boolean)))
}
