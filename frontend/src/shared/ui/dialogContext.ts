// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The confirmation and reason dialogs' contract (Dialog.tsx draws them): useDialog() gives
// confirm() and reason(), each a promise of the person's answer.
import { createContext, useContext } from 'react'
import type { ReactNode } from 'react'

export interface Common {
  title: string
  message?: ReactNode
  confirmLabel: string
  cancelLabel: string
  /** danger for irreversible or access-reducing actions. */
  tone?: 'primary' | 'danger'
}

export interface ReasonOptions extends Common {
  label: string
  hint?: string
  minLength?: number
  maxLength?: number
  /** Shown when the reason is too short; {n} is the minimum. */
  tooShort?: string
}

export interface Request {
  options: Common & Partial<ReasonOptions>
  withReason: boolean
  resolve: (value: string | boolean | null) => void
}

export interface DialogApi {
  confirm: (options: Common) => Promise<boolean>
  reason: (options: ReasonOptions) => Promise<string | null>
}

export const DialogContext = createContext<DialogApi | null>(null)

export function useDialog(): DialogApi {
  const api = useContext(DialogContext)
  if (!api) throw new Error('useDialog needs a DialogProvider')
  return api
}
