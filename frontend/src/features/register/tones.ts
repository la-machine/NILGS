// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import type { Tone } from '../../shared/ui/Feedback'
import type { Inscription } from './api'

export const STATUS_TONE: Record<Inscription['status'], Tone> = {
  PROPOSED: 'info', CONFIRMATION_PENDING: 'warning', REGISTERED: 'success', REJECTED: 'neutral', WITHDRAWN: 'neutral',
}
