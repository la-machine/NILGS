// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * The permission catalogue: one permission per function of the software (ADR-025). Every
 * endpoint and service operation checks the permission of the function it performs, never
 * a role. Administrators combine these permissions into roles and special grants; they can
 * never invent one, because a permission only means something where the code checks it.
 *
 * <p>Authorities: {@code admin.role.read} for a permission held everywhere,
 * {@code parcel.create@DEMO1} for one held at one office.
 */
public enum Permission {
    // --- Administration (never delegable by special grant) ----------------------------------
    ADMIN_ACCOUNT_LIST("admin.account.list", Family.ADMINISTRATION, false, "List and search accounts", "Lister et rechercher les comptes"),
    ADMIN_ROLE_ASSIGN("admin.role.assign", Family.ADMINISTRATION, false, "Request a role assignment for a staff account", "Demander l’attribution d’un rôle à un compte agent"),
    ADMIN_ASSIGNMENT_READ("admin.assignment.read", Family.ADMINISTRATION, false, "Read role assignment requests", "Consulter les demandes d’attribution de rôle"),
    ADMIN_ASSIGNMENT_APPROVE("admin.assignment.approve", Family.ADMINISTRATION, false, "Approve or reject role assignment requests", "Approuver ou rejeter les demandes d’attribution de rôle"),
    ADMIN_ROLE_REVOKE("admin.role.revoke", Family.ADMINISTRATION, false, "Revoke a role from a staff account", "Retirer un rôle à un compte agent"),
    ADMIN_MFA_RESET("admin.mfa.reset", Family.ADMINISTRATION, false, "Request the reset of a lost second factor", "Demander la réinitialisation d’un deuxième facteur perdu"),
    ADMIN_ROLE_READ("admin.role.read", Family.ADMINISTRATION, false, "Read roles, the permission catalogue and role change requests", "Consulter les rôles, le catalogue et les demandes"),
    ADMIN_ROLE_DEFINE("admin.role.define", Family.ADMINISTRATION, false, "Request the creation, change or retirement of a role", "Demander la création, la modification ou le retrait d’un rôle"),
    ADMIN_ROLE_APPROVE("admin.role.approve", Family.ADMINISTRATION, false, "Approve or reject role change requests", "Approuver ou rejeter les demandes sur les rôles"),
    ADMIN_GRANT_READ("admin.grant.read", Family.ADMINISTRATION, false, "Read special grants", "Consulter les autorisations spéciales"),
    ADMIN_GRANT_REQUEST("admin.grant.request", Family.ADMINISTRATION, false, "Request a special grant for a user", "Demander une autorisation spéciale pour un utilisateur"),
    ADMIN_GRANT_APPROVE("admin.grant.approve", Family.ADMINISTRATION, false, "Approve or reject special grant requests", "Approuver ou rejeter les autorisations spéciales"),
    ADMIN_GRANT_REVOKE("admin.grant.revoke", Family.ADMINISTRATION, false, "Revoke a special grant", "Révoquer une autorisation spéciale"),

    // --- Audit ---------------------------------------------------------------------------
    AUDIT_EVENT_SEARCH("audit.event.search", Family.AUDIT, true, "Search audit events", "Rechercher dans la piste d’audit", Scope.ANY, Scope.OFFICE),
    AUDIT_CHAIN_VERIFY("audit.chain.verify", Family.AUDIT, true, "Verify the audit chain", "Vérifier la chaîne d’audit"),
    AUDIT_CHECKPOINT_CREATE("audit.checkpoint.create", Family.AUDIT, true, "Sign an audit checkpoint", "Signer un point de contrôle"),
    AUDIT_CHECKPOINT_LIST("audit.checkpoint.list", Family.AUDIT, true, "List audit checkpoints", "Lister les points de contrôle"),
    AUDIT_CHECKPOINT_DOWNLOAD("audit.checkpoint.download", Family.AUDIT, true, "Download checkpoint files", "Télécharger les points de contrôle"),
    AUDIT_KEY_READ("audit.key.read", Family.AUDIT, true, "Read the checkpoint public keys", "Lire les clés publiques des points de contrôle"),

    // --- Business (checked by their modules as they are delivered) --------------------------
    PARCEL_CREATE("parcel.create", Family.BUSINESS, true, "Create a parcel", "Créer une parcelle", Scope.OFFICE, Scope.ANY),
    PARCEL_CORRECT("parcel.correct", Family.BUSINESS, true, "Correct a parcel (new version)", "Corriger une parcelle", Scope.OFFICE, Scope.ANY),
    PARCEL_READ("parcel.read", Family.BUSINESS, true, "Read parcels", "Consulter les parcelles", Scope.ANY, Scope.OFFICE, Scope.LINKED),
    APPLICATION_CREATE("application.create", Family.BUSINESS, true, "Create an application", "Créer une demande", Scope.OWN),
    APPLICATION_SUBMIT("application.submit", Family.BUSINESS, true, "Submit an application", "Soumettre une demande", Scope.OWN),
    APPLICATION_RESPOND("application.respond", Family.BUSINESS, true, "Respond to an information request", "Répondre à une demande d’information", Scope.OWN),
    APPLICATION_READ("application.read", Family.BUSINESS, true, "Read applications", "Consulter les demandes", Scope.ANY, Scope.OFFICE, Scope.ASSIGNED, Scope.OWN),
    COMPLETENESS_REVIEW("completeness.review", Family.BUSINESS, true, "Review completeness", "Contrôler la complétude", Scope.OFFICE),
    COMPLETENESS_REQUEST_INFO("completeness.request-info", Family.BUSINESS, true, "Request missing information", "Demander des informations", Scope.OFFICE),
    ALERT_REVIEW("alert.review", Family.BUSINESS, true, "Review spatial alerts", "Examiner les alertes spatiales", Scope.OFFICE),
    ALERT_RESOLVE("alert.resolve", Family.BUSINESS, true, "Resolve spatial alerts", "Résoudre les alertes spatiales", Scope.OFFICE),
    REVIEW_RECOMMEND("review.recommend", Family.BUSINESS, true, "Record a recommendation", "Formuler une recommandation", Scope.ASSIGNED),
    DECISION_RECORD("decision.record", Family.BUSINESS, true, "Record a decision", "Enregistrer une décision", Scope.ASSIGNED),
    REPORT_WORKLOAD("report.workload", Family.BUSINESS, true, "Read workload reports", "Consulter la charge de travail", Scope.OFFICE, Scope.ANY),
    PAYMENT_PAY("payment.pay", Family.BUSINESS, true, "Pay a fee (simulated)", "Payer des frais (simulé)", Scope.OWN),
    PAYMENT_RECONCILE("payment.reconcile", Family.BUSINESS, true, "Reconcile payment exceptions", "Rapprocher les paiements", Scope.ANY),
    /** The supervisor's in-application confirmation of a second-factor reset (ADR-028); business, so supervisors keep their audit permissions. */
    IDENTITY_RESET_CONFIRM("identity.reset.confirm", Family.BUSINESS, false, "Confirm the identity of a person whose second factor is reset", "Confirmer l’identité d’une personne dont le deuxième facteur est réinitialisé", Scope.OFFICE);

    /** Families that must never meet in one role or one person (separation of duties). */
    public enum Family { ADMINISTRATION, AUDIT, BUSINESS }

    /** How far a permission reaches from where it was granted. */
    public enum Scope {
        /** Everywhere. */
        ANY,
        /** Records of the office of the grant. */
        OFFICE,
        /** Records assigned to the user (checked per record by the service). */
        ASSIGNED,
        /** The user's own records (checked per record by the service). */
        OWN,
        /** Records linked to the user's own records (checked per record by the service). */
        LINKED
    }

    private final String code;
    private final Family family;
    private final boolean delegable;
    private final String descriptionEn;
    private final String descriptionFr;
    private final Set<Scope> scopes;

    Permission(String code, Family family, boolean delegable, String descriptionEn, String descriptionFr, Scope... scopes) {
        this.code = code;
        this.family = family;
        this.delegable = delegable;
        this.descriptionEn = descriptionEn;
        this.descriptionFr = descriptionFr;
        this.scopes = scopes.length == 0 ? EnumSet.of(Scope.ANY) : EnumSet.of(scopes[0], scopes);
    }

    /** The authority for this permission held everywhere. */
    public String code() {
        return code;
    }

    /** The authority for this permission held at one office. */
    public String at(String officeCode) {
        return code + "@" + officeCode;
    }

    /**
     * The authority for this permission held for records related to the user only
     * (ASSIGNED, OWN, LINKED): {@code application.read#assigned}. Never equal to the bare code,
     * so an endpoint requiring the permission everywhere refuses it.
     */
    public String within(Scope scope) {
        if (scope == Scope.ANY || scope == Scope.OFFICE) {
            throw new IllegalArgumentException("not a relative scope: " + scope);
        }
        return code + "#" + scope.name().toLowerCase(java.util.Locale.ROOT);
    }

    public Family family() {
        return family;
    }

    /** May be handed to one person by a special grant (administration never is). */
    public boolean delegable() {
        return delegable;
    }

    public Set<Scope> scopes() {
        return Set.copyOf(scopes);
    }

    public String descriptionEn() {
        return descriptionEn;
    }

    public String descriptionFr() {
        return descriptionFr;
    }

    public static java.util.Optional<Permission> ofCode(String code) {
        for (Permission p : values()) {
            if (p.code.equals(code)) {
                return java.util.Optional.of(p);
            }
        }
        return java.util.Optional.empty();
    }
}
