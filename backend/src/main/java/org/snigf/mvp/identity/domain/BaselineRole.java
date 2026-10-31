// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import org.snigf.mvp.shared.security.Permission;
import org.snigf.mvp.shared.security.Permission.Scope;

import static org.snigf.mvp.shared.security.Permission.ADMIN_ACCOUNT_LIST;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ASSIGNMENT_APPROVE;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ASSIGNMENT_READ;
import static org.snigf.mvp.shared.security.Permission.ADMIN_GRANT_APPROVE;
import static org.snigf.mvp.shared.security.Permission.ADMIN_GRANT_READ;
import static org.snigf.mvp.shared.security.Permission.ADMIN_GRANT_REQUEST;
import static org.snigf.mvp.shared.security.Permission.ADMIN_GRANT_REVOKE;
import static org.snigf.mvp.shared.security.Permission.ADMIN_MFA_RESET;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ROLE_APPROVE;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ROLE_ASSIGN;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ROLE_DEFINE;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ROLE_READ;
import static org.snigf.mvp.shared.security.Permission.ADMIN_ROLE_REVOKE;
import static org.snigf.mvp.shared.security.Permission.ALERT_RESOLVE;
import static org.snigf.mvp.shared.security.Permission.ALERT_REVIEW;
import static org.snigf.mvp.shared.security.Permission.APPLICATION_CREATE;
import static org.snigf.mvp.shared.security.Permission.APPLICATION_READ;
import static org.snigf.mvp.shared.security.Permission.APPLICATION_RESPOND;
import static org.snigf.mvp.shared.security.Permission.APPLICATION_SUBMIT;
import static org.snigf.mvp.shared.security.Permission.AUDIT_CHAIN_VERIFY;
import static org.snigf.mvp.shared.security.Permission.AUDIT_CHECKPOINT_CREATE;
import static org.snigf.mvp.shared.security.Permission.AUDIT_CHECKPOINT_DOWNLOAD;
import static org.snigf.mvp.shared.security.Permission.AUDIT_CHECKPOINT_LIST;
import static org.snigf.mvp.shared.security.Permission.AUDIT_EVENT_SEARCH;
import static org.snigf.mvp.shared.security.Permission.AUDIT_KEY_READ;
import static org.snigf.mvp.shared.security.Permission.COMPLETENESS_REQUEST_INFO;
import static org.snigf.mvp.shared.security.Permission.IDENTITY_RESET_CONFIRM;
import static org.snigf.mvp.shared.security.Permission.COMPLETENESS_REVIEW;
import static org.snigf.mvp.shared.security.Permission.DECISION_RECORD;
import static org.snigf.mvp.shared.security.Permission.PARCEL_CORRECT;
import static org.snigf.mvp.shared.security.Permission.PARCEL_CREATE;
import static org.snigf.mvp.shared.security.Permission.PARCEL_READ;
import static org.snigf.mvp.shared.security.Permission.PAYMENT_PAY;
import static org.snigf.mvp.shared.security.Permission.PAYMENT_RECONCILE;
import static org.snigf.mvp.shared.security.Permission.DOCUMENT_INCIDENT_REVIEW;
import static org.snigf.mvp.shared.security.Permission.DOCUMENT_ISSUED_MANAGE;
import static org.snigf.mvp.shared.security.Permission.REPORT_WORKLOAD;
import static org.snigf.mvp.shared.security.Permission.REGISTER_CONFIRM;
import static org.snigf.mvp.shared.security.Permission.REGISTER_INSCRIBE;
import static org.snigf.mvp.shared.security.Permission.REGISTER_PROPOSE;
import static org.snigf.mvp.shared.security.Permission.REGISTER_READ;
import static org.snigf.mvp.shared.security.Permission.REGISTER_TRANSACTION_DECIDE;
import static org.snigf.mvp.shared.security.Permission.REVIEW_RECOMMEND;

/**
 * The baseline roles of the Baseline permission matrix, expressed in the fine-grained
 * catalogue (ADR-025). Migration V7 seeds them (V8 adds the assignment approval permissions and
 * the supervisor's chain verification, ADR-026) into {@code role_definition} as system roles,
 * which nobody can change at run time; {@code RoleSeedIT} proves the database holds exactly
 * these, and docs/permission-matrix.md shows them ({@code PermissionMatrixTest}).
 * Administrators define further roles in the database.
 */
public enum BaselineRole {
    APPLICANT("Demandeur", "Applicant", entries(
            PARCEL_READ, Scope.LINKED,
            APPLICATION_CREATE, Scope.OWN, APPLICATION_SUBMIT, Scope.OWN, APPLICATION_RESPOND, Scope.OWN,
            APPLICATION_READ, Scope.OWN, PAYMENT_PAY, Scope.OWN)),
    INTAKE_OFFICER("Agent d’accueil", "Intake officer", entries(
            PARCEL_READ, Scope.ANY, APPLICATION_READ, Scope.OFFICE,
            COMPLETENESS_REVIEW, Scope.OFFICE, COMPLETENESS_REQUEST_INFO, Scope.OFFICE)),
    CADASTRAL_OFFICER("Agent du cadastre", "Cadastral officer", entries(
            PARCEL_CREATE, Scope.OFFICE, PARCEL_CORRECT, Scope.OFFICE, PARCEL_READ, Scope.ANY,
            APPLICATION_READ, Scope.OFFICE, ALERT_REVIEW, Scope.OFFICE, ALERT_RESOLVE, Scope.OFFICE)),
    REVIEWER("Instructeur", "Reviewer", entries(
            PARCEL_READ, Scope.ANY, APPLICATION_READ, Scope.ASSIGNED, REVIEW_RECOMMEND, Scope.ASSIGNED)),
    DECISION_AUTHORITY("Autorité de décision (simulée)", "Decision authority (simulated)", entries(
            PARCEL_READ, Scope.ANY, APPLICATION_READ, Scope.ASSIGNED, DECISION_RECORD, Scope.ASSIGNED)),
    SUPERVISOR("Superviseur", "Supervisor", entries(
            PARCEL_READ, Scope.ANY, APPLICATION_READ, Scope.OFFICE, REPORT_WORKLOAD, Scope.OFFICE,
            AUDIT_EVENT_SEARCH, Scope.OFFICE, AUDIT_CHAIN_VERIFY, Scope.ANY, IDENTITY_RESET_CONFIRM, Scope.OFFICE)),
    AUDITOR("Auditeur", "Auditor", entries(
            PARCEL_READ, Scope.ANY, APPLICATION_READ, Scope.ANY,
            AUDIT_EVENT_SEARCH, Scope.ANY, AUDIT_CHAIN_VERIFY, Scope.ANY, AUDIT_CHECKPOINT_CREATE, Scope.ANY,
            AUDIT_CHECKPOINT_LIST, Scope.ANY, AUDIT_CHECKPOINT_DOWNLOAD, Scope.ANY, AUDIT_KEY_READ, Scope.ANY)),
    FINANCE_OFFICER("Agent financier", "Finance officer", entries(
            PAYMENT_RECONCILE, Scope.ANY)),
    /** The national document verification service (ADR-039): issued documents' status and verification incidents. */
    DOCUMENT_OFFICER("Agent de vérification documentaire", "Document verification officer", entries(
            DOCUMENT_ISSUED_MANAGE, Scope.ANY, DOCUMENT_INCIDENT_REVIEW, Scope.ANY)),
    /** The keeper of the DEMO land register at a conservation (ADR-040): nothing here is a legal registration. */
    CONSERVATOR("Conservateur (démonstration)", "Conservator (demonstration)", entries(
            PARCEL_READ, Scope.ANY, REGISTER_READ, Scope.OFFICE, REGISTER_PROPOSE, Scope.OFFICE, REGISTER_INSCRIBE, Scope.OFFICE,
            REGISTER_CONFIRM, Scope.OFFICE, REGISTER_TRANSACTION_DECIDE, Scope.OFFICE)),
    SYSTEM_ADMIN("Administrateur du système", "System administrator", entries(
            ADMIN_ACCOUNT_LIST, Scope.ANY, ADMIN_ROLE_ASSIGN, Scope.ANY, ADMIN_ASSIGNMENT_READ, Scope.ANY,
            ADMIN_ASSIGNMENT_APPROVE, Scope.ANY, ADMIN_ROLE_REVOKE, Scope.ANY,
            ADMIN_MFA_RESET, Scope.ANY, ADMIN_ROLE_READ, Scope.ANY, ADMIN_ROLE_DEFINE, Scope.ANY,
            ADMIN_ROLE_APPROVE, Scope.ANY, ADMIN_GRANT_READ, Scope.ANY, ADMIN_GRANT_REQUEST, Scope.ANY,
            ADMIN_GRANT_APPROVE, Scope.ANY, ADMIN_GRANT_REVOKE, Scope.ANY));

    private final String nameFr;
    private final String nameEn;
    private final Map<Permission, Scope> permissions;

    BaselineRole(String nameFr, String nameEn, Map<Permission, Scope> permissions) {
        this.nameFr = nameFr;
        this.nameEn = nameEn;
        this.permissions = Collections.unmodifiableMap(permissions);
    }

    public String nameFr() {
        return nameFr;
    }

    public String nameEn() {
        return nameEn;
    }

    public Map<Permission, Scope> permissions() {
        return permissions;
    }

    private static Map<Permission, Scope> entries(Object... pairs) {
        Map<Permission, Scope> map = new EnumMap<>(Permission.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Permission) pairs[i], (Scope) pairs[i + 1]);
        }
        return map;
    }
}
