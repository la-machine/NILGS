// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

/**
 * What happened. A closed list: an event cannot be recorded under a name nobody reviewed.
 * Names match the database check {@code ^[A-Z][A-Z0-9_]{2,63}$}; add new actions here with
 * the feature that emits them (Slice 1 design §7).
 */
public enum AuditAction {
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    MFA_ENROLLED,
    MFA_VERIFIED,
    MFA_RECOVERY_CODE_USED,
    MFA_RESET,
    LOGOUT,
    ROLE_ASSIGNMENT_REQUESTED,
    ROLE_ASSIGNMENT_REJECTED,
    ROLE_GRANTED,
    ROLE_REVOKED,
    ROLE_DEFINITION_REQUESTED,
    ROLE_DEFINITION_APPROVED,
    ROLE_DEFINITION_REJECTED,
    PERMISSION_GRANT_REQUESTED,
    PERMISSION_GRANT_APPROVED,
    PERMISSION_GRANT_REJECTED,
    PERMISSION_GRANT_REVOKED,
    PERMISSION_GRANT_USED,
    PARCEL_CREATED,
    PARCEL_CREATE_REJECTED,
    ACCESS_DENIED,
    AUDIT_EVENTS_READ,
    AUDIT_VERIFIED,
    AUDIT_CHECKPOINT_CREATED
}
