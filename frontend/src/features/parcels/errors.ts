// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Which field a refusal of the parcel API belongs to, so the message appears next to what
// must be corrected. Anything else goes to the page's general error.
import { ApiError } from '../../shared/api/client'
import type { Texts } from '../../shared/i18n'
import { errorMessage } from '../../shared/ui/errorMessage'

export type ParcelField = 'geometry' | 'locality' | 'externalReference' | 'office'

export interface FieldError { field: ParcelField; message: string; detail?: string }

export function fieldError(texts: Texts, error: unknown): FieldError | null {
  if (!(error instanceof ApiError)) return null
  const message = errorMessage(texts, error)
  // PostGIS's own explanation of an invalid geometry, when the server gives one.
  const detail = typeof error.body.reason === 'string' ? error.body.reason : undefined
  if (error.code.startsWith('GEOMETRY_') || error.code.startsWith('CRS_')) return { field: 'geometry', message, detail }
  if (error.code === 'LOCALITY_INVALID') return { field: 'locality', message }
  if (error.code === 'EXTERNAL_REFERENCE_INVALID') return { field: 'externalReference', message }
  if (error.code === 'OFFICE_NOT_FOUND') return { field: 'office', message }
  return null
}
