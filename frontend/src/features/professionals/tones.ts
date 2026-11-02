// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { ApiError } from '../../shared/api/client'
import type { Texts } from '../../shared/i18n'
import type { Tone } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import type { ChangeRequest, Deposit, ProfessionalStatus } from './api'

export const STATUS_TONE: Record<ProfessionalStatus, Tone> = {
  REQUESTED: 'info', VERIFIED: 'info', ACTIVE: 'success', SUSPENDED: 'warning', REVOKED: 'danger',
}

export const DEPOSIT_TONE: Record<Deposit['status'], Tone> = {
  OPEN: 'warning', REGISTERED: 'success', WITHDRAWN: 'neutral', REFUSED: 'danger',
}

export const REQUEST_TONE: Record<ChangeRequest['status'], Tone> = {
  PENDING: 'warning', APPROVED: 'success', REJECTED: 'neutral', EXPIRED: 'neutral',
}

/** A refusal in the professional's words first, then a file's (ADR-033), then the shared message. */
export function refusal(texts: Texts, error: unknown): string {
  const own: Record<string, string> = texts.professionals.codes
  const files: Record<string, string> = texts.documents.codes
  if (error instanceof ApiError) {
    if (error.code in own) return own[error.code]
    if (error.code in files) return files[error.code]
  }
  return errorMessage(texts, error)
}

/** A history event by its code; one not yet named here shows as recorded. */
export function eventLabel(texts: Texts, event: string): string {
  return (texts.professionals.events as Record<string, string>)[event] ?? event
}
