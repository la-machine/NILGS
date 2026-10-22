// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Turns any failure into a sentence for the user: an API problem by its stable code, a
// WebAuthn DOMException by its name, anything else as the generic message. Never shows a
// server message, a stack trace or an exception name.
import { ApiError } from '../api/client'
import { fill } from '../i18n'
import type { Texts } from '../i18n'

export function errorMessage(texts: Texts, error: unknown): string {
  const errors: Record<string, string> = texts.errors
  if (error instanceof ApiError) {
    const violations = Array.isArray(error.body.violations) ? (error.body.violations as string[]).join(', ') : ''
    return fill(errors[error.code] ?? errors.generic, { violations })
  }
  if (error instanceof DOMException && errors[error.name]) return errors[error.name]
  return errors.generic
}
