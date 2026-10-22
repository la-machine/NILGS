// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The signed-in session as GET /api/v1/auth/session returns it. Shared because every
// feature decides what to show from it; the server decides what is allowed.

export type NextStep = 'DONE' | 'MFA_ENROLMENT' | 'MFA_CHALLENGE'

export interface RoleGrant { role: string; officeCode: string | null }

/** A special grant in force for the signed-in user (ADR-025). */
export interface ActiveGrant {
  id: string
  permission: string
  officeCode: string | null
  validUntil: string | null
  usesLeft: number | null
}

export interface Session {
  userId: string
  displayName: string
  accountType: 'APPLICANT' | 'STAFF'
  roles: string[]
  grants: RoleGrant[]
  /** Role permissions: `code` (everywhere) or `code@OFFICE`; empty before every factor is proven. */
  permissions: string[]
  specialGrants: ActiveGrant[]
  authLevel: 'PASSWORD' | 'FULL'
  nextStep: NextStep
  identityVerified: boolean
  authenticatedAt: string
}

/** Runs an action with the shell's busy state and error message (see app/App.tsx). */
export type Run = (action: () => Promise<void>) => Promise<void>
