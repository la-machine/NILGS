// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The colour of each payment standing and status, everywhere they are shown.
import type { Tone } from '../../shared/ui/Feedback'
import type { PaymentStatus, Standing } from './api'

export const STANDING_TONE: Record<Standing, Tone> = { NOT_REQUIRED: 'neutral', DUE: 'warning', PENDING: 'info', PAID: 'success' }
export const STATUS_TONE: Record<PaymentStatus, Tone> = { PENDING: 'info', PAID: 'success', FAILED: 'danger', EXPIRED: 'neutral' }
