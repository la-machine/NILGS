// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Endpoints of registration, sign-in, the WebAuthn second factor and recovery codes
// (/api/v1/auth, ADR-022, ADR-023).
import { call } from '../../shared/api/client'
import type { NextStep, Session } from '../../shared/session/types'

export interface Registration {
  email: string
  displayName: string
  password: string
  preferredLanguage: string
}

export interface KeyRegistered {
  registered: number
  required: number
  enrolmentComplete: boolean
  nextStep: NextStep
  /** Shown once, when enrolment completes. */
  recoveryCodes?: string[]
}

/** A registered security key, as its owner sees it: never key material. */
export interface SecurityKey {
  id: string
  label: string
  created: string | null
  lastUsed: string | null
}

export const authApi = {
  session: () => call<Session>('GET', '/auth/session'),
  register: (body: Registration) => call<void>('POST', '/auth/register', body),
  login: (email: string, password: string) =>
    call<{ nextStep: NextStep }>('POST', '/auth/login', { email, password }),
  logout: () => call<void>('POST', '/auth/logout'),
  registrationOptions: () => call<Record<string, unknown>>('POST', '/auth/mfa/webauthn/registration/options'),
  registerKey: (credential: unknown, label: string) =>
    call<KeyRegistered>('POST', '/auth/mfa/webauthn/registration', { publicKey: { credential, label } }),
  assertionOptions: () => call<Record<string, unknown>>('POST', '/auth/mfa/webauthn/assertion/options'),
  proveKey: (credential: unknown) => call<{ nextStep: NextStep }>('POST', '/auth/mfa/webauthn/assertion', credential),
  keys: () => call<{ keys: SecurityKey[]; required: number }>('GET', '/auth/mfa/keys'),
  removeKey: (id: string) =>
    call<{ remaining: number; required: number }>('DELETE', `/auth/mfa/keys/${encodeURIComponent(id)}`),
  recover: (code: string) =>
    call<{ nextStep: NextStep; remainingRecoveryCodes: number; replaceAuthenticator: boolean }>(
      'POST', '/auth/mfa/recovery', { code }),
}
