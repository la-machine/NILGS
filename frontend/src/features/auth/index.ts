// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the auth feature: what the app shell may use. Other features import only
// from here, never from its files (scripts/check-architecture.mjs).
export { authApi } from './api'
export type { KeyRegistered, Registration, SecurityKey } from './api'
export { createCredential, getAssertion } from './webauthn'
export { EnrolScreen } from './screens/EnrolScreen'
export { LoginScreen } from './screens/LoginScreen'
export { RecoveryCodesScreen } from './screens/RecoveryCodesScreen'
export { RecoveryScreen } from './screens/RecoveryScreen'
export { RegisterScreen } from './screens/RegisterScreen'
export { AccountPage } from './screens/AccountPage'
export { VerifyScreen } from './screens/VerifyScreen'
