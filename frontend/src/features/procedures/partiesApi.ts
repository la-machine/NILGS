// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The persons of a case at intake (ADR-043, DEMO only): the documents presented at the counter, each person's identity
// transcribed from one of them, the ownership statement and the mandates; a second officer checks each version, never
// their own. Every version is UNVERIFIED_DEMO: a check that MATCHES says the entry corresponds to the document presented,
// never that the identity was authenticated. The server and the database decide who may do what.
import { call } from '../../shared/api/client'

export type PartyRole = 'APPLICANT' | 'REPRESENTATIVE' | 'INTENDED_OWNER' | 'TRANSFEROR' | 'TRANSFEREE'
export type PartyPurpose = 'IDENTITY_DOCUMENT' | 'MANDATE' | 'OWNERSHIP_EVIDENCE'
export const PURPOSES: PartyPurpose[] = ['IDENTITY_DOCUMENT', 'OWNERSHIP_EVIDENCE', 'MANDATE']
export type CheckKind = 'IDENTITY' | 'OWNERSHIP' | 'MANDATE'
export type CheckOutcome = 'MATCHES' | 'DOES_NOT_MATCH'
export type OwnershipInterest = 'OWNERSHIP' | 'CO_OWNERSHIP' | 'USUFRUCT' | 'BARE_OWNERSHIP'
export const OWNERSHIP_INTERESTS: OwnershipInterest[] = ['OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP']
export const REASON_MIN = 10
export const NOTE_MIN = 10

export interface PartyFile {
  id: string; purpose: PartyPurpose; sha256: string; sizeBytes: number; mediaType: string; originalName: string; uploadedByName: string; uploadedAt: string
}
export interface PartyCheck { id: string; outcome: CheckOutcome; note: string | null; checkedById: string; checkedByName: string; checkedAt: string }
export interface Identity {
  id: string; versionNo: number; source: 'DECLARED' | 'RECORDED'; fullName: string; identityKind: string; identityNumber: string
  phone: string | null; postalAddress: string | null; validUntil: string | null; expiredAtTranscription: boolean
  sourceFileId: string | null; sourceVersionId: string | null
  enteredById: string; enteredByName: string; enteredAt: string; supersedesId: string | null; correctionReason: string | null
  verification: 'UNVERIFIED_DEMO'; check: PartyCheck | null
  exception: { id: string; policy: string; reason: string; authorisedById: string; authorisedByName: string; authorisedAt: string } | null
}
export interface CasePerson { id: string; ref: string; kind: string; roles: PartyRole[]; applicantAccount: boolean; identities: Identity[] }
export interface Share {
  personId: string; personRef: string; interest: OwnershipInterest; shareNum: number; shareDen: number
  usufructTerm: 'LIFE' | 'UNTIL' | null; usufructUntil: string | null; basisFileId: string | null; basisVersionId: string | null
}
export interface Statement {
  id: string; versionNo: number; source: 'DECLARED' | 'RECORDED'; enteredById: string; enteredByName: string; enteredAt: string
  correctionReason: string | null; verification: 'UNVERIFIED_DEMO'; shares: Share[]; check: PartyCheck | null
}
export interface Mandate {
  id: string; representativeId: string; representativeRef: string; versionNo: number; source: 'DECLARED' | 'RECORDED'; scope: string
  represents: string[]; documentFileId: string | null; enteredById: string; enteredByName: string; enteredAt: string
  correctionReason: string | null; verification: 'UNVERIFIED_DEMO'; check: PartyCheck | null
}
export interface CaseParties {
  capacity: string | null; persons: CasePerson[]; statements: Statement[]; mandates: Mandate[]; files: PartyFile[]; problems: string[]
}
export interface NewIdentity {
  fullName: string; identityKind: string; identityNumber: string; phone?: string | null; postalAddress?: string | null
  validUntil?: string | null; sourceFile: string; supersedes: string; correctionReason?: string | null
}
export interface NewShare {
  person: string; interest: OwnershipInterest; shareNum: number; shareDen: number; basisFile: string
  usufructTerm?: 'LIFE' | 'UNTIL' | null; usufructUntil?: string | null
}
export const EXPIRED_DOCUMENT_POLICY = 'DEMO_EXPIRED_IDENTITY_DOCUMENT'
export const EXCEPTION_REASON_MIN = 20

const one = (id: string) => encodeURIComponent(id)
const base = (id: string) => `/procedures/${one(id)}/parties`

export const partiesApi = {
  read: (id: string) => call<CaseParties>('GET', base(id)),
  addFile: (id: string, purpose: PartyPurpose, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return call<CaseParties>('POST', `${base(id)}/files?purpose=${encodeURIComponent(purpose)}`, form)
  },
  /** A plain link: the browser downloads it as an attachment, with the session cookie; the server audits the read. */
  fileUrl: (id: string, fileId: string) => `/api/v1${base(id)}/files/${one(fileId)}`,
  recordIdentity: (id: string, personId: string, identity: NewIdentity) =>
    call<CaseParties>('POST', `${base(id)}/persons/${one(personId)}/identity`, identity),
  recordOwnership: (id: string, shares: NewShare[], supersedes: string, correctionReason: string | null) =>
    call<CaseParties>('POST', `${base(id)}/ownership`, { shares, supersedes, correctionReason }),
  recordMandate: (id: string, representativeId: string, represents: string[], scope: string, documentFile: string, supersedes: string,
    correctionReason: string | null) =>
    call<CaseParties>('POST', `${base(id)}/persons/${one(representativeId)}/mandate`, { represents, scope, documentFile, supersedes, correctionReason }),
  check: (id: string, targetKind: CheckKind, targetId: string, outcome: CheckOutcome, note: string | null) =>
    call<CaseParties>('POST', `${base(id)}/checks`, { targetKind, targetId, outcome, note }),
  authoriseException: (id: string, identityVersion: string, reason: string) =>
    call<CaseParties>('POST', `${base(id)}/exceptions`, { identityVersion, policy: EXPIRED_DOCUMENT_POLICY, reason }),
}

/** A problem code as the database says it ("IDENTITY_NOT_CHECKED:p2"): its kind, and the person's reference if any. */
export function problemParts(code: string): { kind: string; ref: string | null } {
  const at = code.indexOf(':')
  return at < 0 ? { kind: code, ref: null } : { kind: code.slice(0, at), ref: code.slice(at + 1) }
}

/** The latest mandate of each representative (the view lists every version, newest first). */
export function latestMandates(mandates: Mandate[]): Mandate[] {
  const seen = new Set<string>()
  return mandates.filter((m) => (seen.has(m.representativeId) ? false : (seen.add(m.representativeId), true)))
}
