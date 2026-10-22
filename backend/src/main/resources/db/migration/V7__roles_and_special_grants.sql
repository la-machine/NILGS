-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V7: the permission catalogue, roles defined in the database, role change requests and
-- special grants (ADR-025).
--
--   permission                         the catalogue, one row per function of the software,
--                                      initialised from shared.security.Permission. The
--                                      application refuses to start if the code and this
--                                      table disagree (PermissionCatalogueCheck): a new
--                                      permission arrives with a migration.
--   role_definition / role_permission  roles as bundles of catalogue permissions. The nine
--                                      baseline roles (identity.domain.BaselineRole) are
--                                      initialised here as system roles and never change;
--                                      administrators create custom roles through approved
--                                      requests.
--   role_change_request                four-eyes: one administrator proposes, another approves.
--   permission_grant                   special grants of one permission to one person, with
--                                      an optional end date and/or maximum number of uses,
--                                      requested and approved by two other people.

CREATE TABLE permission (
    code            text PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9.-]{2,63}$'),
    family          text NOT NULL CHECK (family IN ('ADMINISTRATION', 'AUDIT', 'BUSINESS')),
    delegable       boolean NOT NULL,
    scopes          text[] NOT NULL CHECK (cardinality(scopes) >= 1
                        AND scopes <@ ARRAY['ANY', 'OFFICE', 'ASSIGNED', 'OWN', 'LINKED']),
    description_en  text NOT NULL CHECK (length(description_en) BETWEEN 3 AND 200),
    description_fr  text NOT NULL CHECK (length(description_fr) BETWEEN 3 AND 200),
    -- Administration is never handed out by special grant.
    CONSTRAINT ck_administration_not_delegable CHECK (family <> 'ADMINISTRATION' OR NOT delegable)
);

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('admin.account.list', 'ADMINISTRATION', false, ARRAY['ANY'], 'List and search accounts', 'Lister et rechercher les comptes'),
    ('admin.role.assign', 'ADMINISTRATION', false, ARRAY['ANY'], 'Assign a role to a staff account', 'Attribuer un rôle à un compte agent'),
    ('admin.role.revoke', 'ADMINISTRATION', false, ARRAY['ANY'], 'Revoke a role from a staff account', 'Retirer un rôle à un compte agent'),
    ('admin.mfa.reset', 'ADMINISTRATION', false, ARRAY['ANY'], 'Reset a lost second factor', 'Réinitialiser un deuxième facteur perdu'),
    ('admin.role.read', 'ADMINISTRATION', false, ARRAY['ANY'], 'Read roles, the permission catalogue and role change requests', 'Consulter les rôles, le catalogue et les demandes'),
    ('admin.role.define', 'ADMINISTRATION', false, ARRAY['ANY'], 'Request the creation, change or retirement of a role', 'Demander la création, la modification ou le retrait d’un rôle'),
    ('admin.role.approve', 'ADMINISTRATION', false, ARRAY['ANY'], 'Approve or reject role change requests', 'Approuver ou rejeter les demandes sur les rôles'),
    ('admin.grant.read', 'ADMINISTRATION', false, ARRAY['ANY'], 'Read special grants', 'Consulter les autorisations spéciales'),
    ('admin.grant.request', 'ADMINISTRATION', false, ARRAY['ANY'], 'Request a special grant for a user', 'Demander une autorisation spéciale pour un utilisateur'),
    ('admin.grant.approve', 'ADMINISTRATION', false, ARRAY['ANY'], 'Approve or reject special grant requests', 'Approuver ou rejeter les autorisations spéciales'),
    ('admin.grant.revoke', 'ADMINISTRATION', false, ARRAY['ANY'], 'Revoke a special grant', 'Révoquer une autorisation spéciale'),
    ('audit.event.search', 'AUDIT', true, ARRAY['ANY', 'OFFICE'], 'Search audit events', 'Rechercher dans la piste d’audit'),
    ('audit.chain.verify', 'AUDIT', true, ARRAY['ANY'], 'Verify the audit chain', 'Vérifier la chaîne d’audit'),
    ('audit.checkpoint.create', 'AUDIT', true, ARRAY['ANY'], 'Sign an audit checkpoint', 'Signer un point de contrôle'),
    ('audit.checkpoint.list', 'AUDIT', true, ARRAY['ANY'], 'List audit checkpoints', 'Lister les points de contrôle'),
    ('audit.checkpoint.download', 'AUDIT', true, ARRAY['ANY'], 'Download checkpoint files', 'Télécharger les points de contrôle'),
    ('audit.key.read', 'AUDIT', true, ARRAY['ANY'], 'Read the checkpoint public keys', 'Lire les clés publiques des points de contrôle'),
    ('parcel.create', 'BUSINESS', true, ARRAY['OFFICE', 'ANY'], 'Create a parcel', 'Créer une parcelle'),
    ('parcel.correct', 'BUSINESS', true, ARRAY['OFFICE', 'ANY'], 'Correct a parcel (new version)', 'Corriger une parcelle'),
    ('parcel.read', 'BUSINESS', true, ARRAY['ANY', 'OFFICE', 'LINKED'], 'Read parcels', 'Consulter les parcelles'),
    ('application.create', 'BUSINESS', true, ARRAY['OWN'], 'Create an application', 'Créer une demande'),
    ('application.submit', 'BUSINESS', true, ARRAY['OWN'], 'Submit an application', 'Soumettre une demande'),
    ('application.respond', 'BUSINESS', true, ARRAY['OWN'], 'Respond to an information request', 'Répondre à une demande d’information'),
    ('application.read', 'BUSINESS', true, ARRAY['ANY', 'OFFICE', 'ASSIGNED', 'OWN'], 'Read applications', 'Consulter les demandes'),
    ('completeness.review', 'BUSINESS', true, ARRAY['OFFICE'], 'Review completeness', 'Contrôler la complétude'),
    ('completeness.request-info', 'BUSINESS', true, ARRAY['OFFICE'], 'Request missing information', 'Demander des informations'),
    ('alert.review', 'BUSINESS', true, ARRAY['OFFICE'], 'Review spatial alerts', 'Examiner les alertes spatiales'),
    ('alert.resolve', 'BUSINESS', true, ARRAY['OFFICE'], 'Resolve spatial alerts', 'Résoudre les alertes spatiales'),
    ('review.recommend', 'BUSINESS', true, ARRAY['ASSIGNED'], 'Record a recommendation', 'Formuler une recommandation'),
    ('decision.record', 'BUSINESS', true, ARRAY['ASSIGNED'], 'Record a decision', 'Enregistrer une décision'),
    ('report.workload', 'BUSINESS', true, ARRAY['OFFICE', 'ANY'], 'Read workload reports', 'Consulter la charge de travail'),
    ('payment.pay', 'BUSINESS', true, ARRAY['OWN'], 'Pay a fee (simulated)', 'Payer des frais (simulé)'),
    ('payment.reconcile', 'BUSINESS', true, ARRAY['ANY'], 'Reconcile payment exceptions', 'Rapprocher les paiements');

CREATE TABLE role_definition (
    code         text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    name_fr      text NOT NULL CHECK (length(name_fr) BETWEEN 2 AND 100),
    name_en      text NOT NULL CHECK (length(name_en) BETWEEN 2 AND 100),
    description  text CHECK (description IS NULL OR length(description) <= 500),
    system       boolean NOT NULL DEFAULT false,
    assignable   boolean NOT NULL DEFAULT true,
    status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_by   uuid REFERENCES app_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE TABLE role_permission (
    role_code   text NOT NULL REFERENCES role_definition (code),
    permission  text NOT NULL REFERENCES permission (code),
    scope       text NOT NULL CHECK (scope IN ('ANY', 'OFFICE', 'ASSIGNED', 'OWN', 'LINKED')),
    PRIMARY KEY (role_code, permission)
);

-- Baseline roles, initialised from identity.domain.BaselineRole (RoleSeedIT compares them).
INSERT INTO role_definition (code, name_fr, name_en, system, assignable) VALUES
    ('APPLICANT', 'Demandeur', 'Applicant', true, false),
    ('INTAKE_OFFICER', 'Agent d’accueil', 'Intake officer', true, true),
    ('CADASTRAL_OFFICER', 'Agent du cadastre', 'Cadastral officer', true, true),
    ('REVIEWER', 'Instructeur', 'Reviewer', true, true),
    ('DECISION_AUTHORITY', 'Autorité de décision (simulée)', 'Decision authority (simulated)', true, true),
    ('SUPERVISOR', 'Superviseur', 'Supervisor', true, true),
    ('AUDITOR', 'Auditeur', 'Auditor', true, true),
    ('FINANCE_OFFICER', 'Agent financier', 'Finance officer', true, true),
    ('SYSTEM_ADMIN', 'Administrateur du système', 'System administrator', true, true);

INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('APPLICANT', 'parcel.read', 'LINKED'),
    ('APPLICANT', 'application.create', 'OWN'),
    ('APPLICANT', 'application.submit', 'OWN'),
    ('APPLICANT', 'application.respond', 'OWN'),
    ('APPLICANT', 'application.read', 'OWN'),
    ('APPLICANT', 'payment.pay', 'OWN'),
    ('INTAKE_OFFICER', 'parcel.read', 'ANY'),
    ('INTAKE_OFFICER', 'application.read', 'OFFICE'),
    ('INTAKE_OFFICER', 'completeness.review', 'OFFICE'),
    ('INTAKE_OFFICER', 'completeness.request-info', 'OFFICE'),
    ('CADASTRAL_OFFICER', 'parcel.create', 'OFFICE'),
    ('CADASTRAL_OFFICER', 'parcel.correct', 'OFFICE'),
    ('CADASTRAL_OFFICER', 'parcel.read', 'ANY'),
    ('CADASTRAL_OFFICER', 'application.read', 'OFFICE'),
    ('CADASTRAL_OFFICER', 'alert.review', 'OFFICE'),
    ('CADASTRAL_OFFICER', 'alert.resolve', 'OFFICE'),
    ('REVIEWER', 'parcel.read', 'ANY'),
    ('REVIEWER', 'application.read', 'ASSIGNED'),
    ('REVIEWER', 'review.recommend', 'ASSIGNED'),
    ('DECISION_AUTHORITY', 'parcel.read', 'ANY'),
    ('DECISION_AUTHORITY', 'application.read', 'ASSIGNED'),
    ('DECISION_AUTHORITY', 'decision.record', 'ASSIGNED'),
    ('SUPERVISOR', 'parcel.read', 'ANY'),
    ('SUPERVISOR', 'application.read', 'OFFICE'),
    ('SUPERVISOR', 'report.workload', 'OFFICE'),
    ('SUPERVISOR', 'audit.event.search', 'OFFICE'),
    ('AUDITOR', 'parcel.read', 'ANY'),
    ('AUDITOR', 'application.read', 'ANY'),
    ('AUDITOR', 'audit.event.search', 'ANY'),
    ('AUDITOR', 'audit.chain.verify', 'ANY'),
    ('AUDITOR', 'audit.checkpoint.create', 'ANY'),
    ('AUDITOR', 'audit.checkpoint.list', 'ANY'),
    ('AUDITOR', 'audit.checkpoint.download', 'ANY'),
    ('AUDITOR', 'audit.key.read', 'ANY'),
    ('FINANCE_OFFICER', 'payment.reconcile', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.account.list', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.role.assign', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.role.revoke', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.mfa.reset', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.role.read', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.role.define', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.role.approve', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.grant.read', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.grant.request', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.grant.approve', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.grant.revoke', 'ANY');

-- A role grants a permission only with a scope the catalogue allows for it.
CREATE FUNCTION role_permission_scope_allowed() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM permission p WHERE p.code = NEW.permission AND NEW.scope = ANY (p.scopes)) THEN
        RAISE EXCEPTION 'scope % is not allowed for permission %', NEW.scope, NEW.permission
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_permission_scope_allowed BEFORE INSERT OR UPDATE ON role_permission
    FOR EACH ROW EXECUTE FUNCTION role_permission_scope_allowed();

-- System roles never change, whoever asks; custom roles change only through the
-- application's approved requests (the application role cannot delete definitions).
CREATE FUNCTION role_definition_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    target text;
BEGIN
    IF TG_TABLE_NAME = 'role_definition' THEN
        target := OLD.code;
    ELSIF TG_OP = 'INSERT' THEN
        target := NEW.role_code;
    ELSE
        target := OLD.role_code;
    END IF;
    IF EXISTS (SELECT 1 FROM role_definition d WHERE d.code = target AND d.system) THEN
        RAISE EXCEPTION 'system role % cannot be changed', target USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER role_definition_guard BEFORE UPDATE OR DELETE ON role_definition
    FOR EACH ROW EXECUTE FUNCTION role_definition_guard();
CREATE TRIGGER role_permission_guard BEFORE INSERT OR UPDATE OR DELETE ON role_permission
    FOR EACH ROW EXECUTE FUNCTION role_definition_guard();

-- Role assignments now name a defined role; a retired role cannot be newly assigned.
ALTER TABLE role_assignment DROP CONSTRAINT role_assignment_role_check;
ALTER TABLE role_assignment ADD CONSTRAINT fk_role_assignment_role
    FOREIGN KEY (role) REFERENCES role_definition (code);

CREATE FUNCTION role_assignment_active_role() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM role_definition d WHERE d.code = NEW.role AND d.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'role % is not defined or is retired', NEW.role USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_active_role BEFORE INSERT ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_active_role();

-- Four-eyes requests on custom roles. The decision is made by someone else.
CREATE TABLE role_change_request (
    id               uuid PRIMARY KEY,
    kind             text NOT NULL CHECK (kind IN ('CREATE', 'UPDATE', 'RETIRE')),
    role_code        text NOT NULL CHECK (role_code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    proposal         jsonb NOT NULL CHECK (jsonb_typeof(proposal) = 'object'),
    reason           text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    decision_reason  text CHECK (decision_reason IS NULL OR length(decision_reason) BETWEEN 3 AND 500),
    CONSTRAINT ck_role_request_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_role_request_decision CHECK ((status = 'PENDING') = (decided_by IS NULL)
                                               AND (decided_by IS NULL) = (decided_at IS NULL))
);
-- At most one open request per role.
CREATE UNIQUE INDEX ux_role_change_request_open ON role_change_request (role_code) WHERE status = 'PENDING';

-- Special grants: one permission, one person, requested by one administrator, approved by
-- another, neither of them the beneficiary. Limits: end date, maximum uses, both or none.
CREATE TABLE permission_grant (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES app_user (id),
    permission       text NOT NULL REFERENCES permission (code),
    office_id        uuid REFERENCES office (id),
    reason           text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
    valid_until      timestamptz,
    max_uses         int CHECK (max_uses IS NULL OR max_uses BETWEEN 1 AND 100000),
    uses             int NOT NULL DEFAULT 0 CHECK (uses >= 0 AND (max_uses IS NULL OR uses <= max_uses)),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    decision_reason  text CHECK (decision_reason IS NULL OR length(decision_reason) BETWEEN 3 AND 500),
    revoked_by       uuid REFERENCES app_user (id),
    revoked_at       timestamptz,
    revoke_reason    text CHECK (revoke_reason IS NULL OR length(revoke_reason) BETWEEN 3 AND 500),
    CONSTRAINT ck_grant_not_self_requested CHECK (requested_by <> user_id),
    CONSTRAINT ck_grant_four_eyes CHECK (decided_by IS NULL OR (decided_by <> requested_by AND decided_by <> user_id)),
    CONSTRAINT ck_grant_not_self_revoked CHECK (revoked_by IS NULL OR revoked_by <> user_id),
    CONSTRAINT ck_grant_limit_in_future CHECK (valid_until IS NULL OR valid_until > requested_at),
    CONSTRAINT ck_grant_revocation CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL)
                                          AND (revoked_by IS NULL) = (revoked_at IS NULL))
);
CREATE INDEX ix_permission_grant_user ON permission_grant (user_id) WHERE status = 'APPROVED';
CREATE INDEX ix_permission_grant_pending ON permission_grant (requested_at) WHERE status = 'PENDING';

-- Administration is never handed out by special grant, whatever the application does.
CREATE FUNCTION permission_grant_delegable() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM permission p WHERE p.code = NEW.permission AND p.delegable) THEN
        RAISE EXCEPTION 'permission % cannot be granted specially', NEW.permission USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER permission_grant_delegable BEFORE INSERT ON permission_grant
    FOR EACH ROW EXECUTE FUNCTION permission_grant_delegable();

GRANT SELECT ON permission TO snigf_app, snigf_auditor;
GRANT SELECT, INSERT ON role_definition TO snigf_app;
GRANT UPDATE (name_fr, name_en, description, status, version) ON role_definition TO snigf_app;
GRANT SELECT, INSERT, DELETE ON role_permission TO snigf_app;
GRANT SELECT, INSERT ON role_change_request TO snigf_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason) ON role_change_request TO snigf_app;
GRANT SELECT, INSERT ON permission_grant TO snigf_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason, uses, revoked_by, revoked_at, revoke_reason)
    ON permission_grant TO snigf_app;
GRANT SELECT ON role_definition, role_permission TO snigf_auditor;
