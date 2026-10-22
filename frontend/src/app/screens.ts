// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The screens of the sign-in flow. The server says which comes next (`nextStep`); the
// interface never decides on its own that a person is signed in.
import type { Session } from '../shared/session/types'

export type Screen =
  | { name: 'loading' }
  | { name: 'login' }
  | { name: 'register' }
  | { name: 'enrol'; registered: number; required: number }
  | { name: 'verify' }
  | { name: 'recovery' }
  | { name: 'codes'; codes: string[] }
  | { name: 'home'; session: Session; notice?: string }
