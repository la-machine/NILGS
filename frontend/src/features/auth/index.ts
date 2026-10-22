// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the auth feature: what the app shell may use. Other features import only
// from here, never from its files (scripts/check-architecture.mjs).
export { authApi } from './api'
export type { KeyRegistered, Registration } from './api'
export { createCredential, getAssertion } from './webauthn'
export { EnrolScreen } from './screens/EnrolScreen'
export { LoginScreen } from './screens/LoginScreen'
export { RecoveryCodesScreen } from './screens/RecoveryCodesScreen'
export { RecoveryScreen } from './screens/RecoveryScreen'
export { RegisterScreen } from './screens/RegisterScreen'
export { SessionScreen } from './screens/SessionScreen'
export { VerifyScreen } from './screens/VerifyScreen'
