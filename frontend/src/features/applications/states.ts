// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// How each state of an application looks: waiting on the applicant, under way, or ended.
import type { Tone } from '../../shared/ui/Feedback'
import type { ApplicationState } from './api'

export const STATE_TONE: Record<ApplicationState, Tone> = {
  DRAFT: 'neutral', SUBMITTED: 'info', COMPLETENESS_REVIEW: 'info', INFO_REQUESTED: 'warning', UNDER_REVIEW: 'info',
  PENDING_DECISION: 'info', APPROVED: 'success', REFUSED: 'danger', WITHDRAWN: 'neutral',
}
