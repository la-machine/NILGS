// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The colour of each document status and verification outcome: green only for a valid
// record or an identical file; red for an altered file; neutral where nothing is asserted.
import type { Tone } from '../../shared/ui/Feedback'
import type { DocumentStatus, Outcome } from './api'

export const STATUS_TONE: Record<DocumentStatus, Tone> = {
  VALID: 'success', SUPERSEDED: 'info', REVOKED: 'danger', CANCELLED: 'neutral',
}

export const OUTCOME_TONE: Record<Outcome, Tone> = {
  AUTHENTIC: 'success', RECORD_FOUND: 'success', ALTERED: 'danger', UNVERIFIED: 'warning',
  REVOKED: 'danger', SUPERSEDED: 'info', CANCELLED: 'neutral',
}
