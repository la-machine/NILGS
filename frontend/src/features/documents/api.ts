// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The documents of the applicant's own application (MVP-02, ADR-033). The server recognises a
// file from its bytes (PDF, JPEG, PNG only) and keeps every version; a download is always an
// attachment. What the browser says about a file is a convenience, never trusted.
import { call } from '../../shared/api/client'

export interface DocumentVersion {
  id: string
  versionNo: number
  status: 'PENDING' | 'STORED' | 'REJECTED'
  originalName: string
  sizeBytes: number
  type: string
  sha256: string
  uploadedAt: string
}

export interface DocumentCategory {
  code: string
  required: boolean
  labelFr: string
  labelEn: string
  /** Newest first. */
  versions: DocumentVersion[]
}

export interface Documents {
  acceptsDocuments: boolean
  /** The categories that take files now: all of a draft's, the ones asked for during an information request. */
  openCategories: string[]
  maxBytes: number
  allowedTypes: string[]
  missingRequired: string[]
  categories: DocumentCategory[]
}

export interface Uploaded { id: string; category: string; versionNo: number; sizeBytes: number; type: string; sha256: string }

/** The applicant's own application, a case seen by staff of its office, or a case assigned to a reviewer (read only). */
export type DocumentSource = 'own' | 'case' | 'assigned'

const base = (applicationId: string, source: DocumentSource = 'own') =>
  `/${{ own: 'applications', case: 'cases', assigned: 'assigned-cases' }[source]}/${encodeURIComponent(applicationId)}/documents`

export const documentsApi = {
  list: (applicationId: string, source: DocumentSource = 'own') => call<Documents>('GET', base(applicationId, source)),
  upload: (applicationId: string, category: string, file: File, idempotencyKey: string) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return call<Uploaded>('POST', `${base(applicationId)}/${encodeURIComponent(category)}`, form,
      { 'Idempotency-Key': idempotencyKey })
  },
  /** A plain link: the browser downloads it as an attachment, with the session cookie. */
  contentUrl: (applicationId: string, versionId: string, source: DocumentSource = 'own') =>
    `/api/v1${base(applicationId, source)}/${encodeURIComponent(versionId)}/content`,
}
