-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V25: professional permissions (Slice 3, ADR-041 step 3). DEMO only.
--
--   permission.professional       marks the four permissions a professional may hold; nobody else holds them
--   role_definition.professional  NOTARY and SWORN_SURVEYOR: computed from an ACTIVE profile and its approved
--                                 offices, never assigned through role_assignment, never changed at run time
--   holds_professional_at()       the professional's own permission function
--   professional_grants()         the roles and offices of an ACTIVE professional, for the application's session
--
-- The staff functions (holds_permission, holds_permission_at, holds_grant_at, holds_assignable) are not
-- changed and keep refusing professionals. Professional roles hold professional permissions only, other
-- roles never hold them, and they are never delegable, so no special grant can carry them.

SET search_path = snigf, public;

ALTER TABLE permission ADD COLUMN professional boolean NOT NULL DEFAULT false;
ALTER TABLE permission ADD CONSTRAINT ck_professional_permission CHECK (NOT professional OR (family = 'BUSINESS' AND NOT delegable));
ALTER TABLE role_definition ADD COLUMN professional boolean NOT NULL DEFAULT false;
ALTER TABLE role_definition ADD CONSTRAINT ck_professional_role CHECK (NOT professional OR (system AND NOT assignable));

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr, professional) VALUES
    ('register.deposit.submit', 'BUSINESS', false, ARRAY['OFFICE'],
     'Look up a title and deposit a transaction on it, as a professional', 'Rechercher un titre et y déposer une transaction, en tant que professionnel', true),
    ('register.deposit.read', 'BUSINESS', false, ARRAY['OWN'],
     'Read one''s own deposits and their documents', 'Consulter ses propres dépôts et leurs pièces', true),
    ('register.deposit.document', 'BUSINESS', false, ARRAY['OWN'],
     'Add or replace documents on one''s own open deposit', 'Ajouter ou remplacer des pièces sur son propre dépôt ouvert', true),
    ('register.deposit.withdraw', 'BUSINESS', false, ARRAY['OWN'],
     'Withdraw one''s own deposit', 'Retirer son propre dépôt', true);

INSERT INTO role_definition (code, name_fr, name_en, system, assignable, professional) VALUES
    ('NOTARY', 'Notaire (démonstration)', 'Notary (demonstration)', true, false, true),
    ('SWORN_SURVEYOR', 'Géomètre assermenté (démonstration)', 'Sworn surveyor (demonstration)', true, false, true);

-- Professional roles hold professional permissions only; every other role, never.
CREATE FUNCTION role_permission_professional_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    -- An unknown role or permission is left to the existing checks and foreign keys, with their own messages.
    IF EXISTS (SELECT 1 FROM role_definition WHERE code = NEW.role_code) AND EXISTS (SELECT 1 FROM permission WHERE code = NEW.permission)
       AND (SELECT professional FROM role_definition WHERE code = NEW.role_code)
           IS DISTINCT FROM (SELECT professional FROM permission WHERE code = NEW.permission) THEN
        RAISE EXCEPTION 'permission % and role % are not both professional, or both not', NEW.permission, NEW.role_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_permission_professional_guard BEFORE INSERT OR UPDATE ON role_permission
    FOR EACH ROW EXECUTE FUNCTION role_permission_professional_guard();

ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('NOTARY', 'register.deposit.submit', 'OFFICE'),
    ('NOTARY', 'register.deposit.read', 'OWN'),
    ('NOTARY', 'register.deposit.document', 'OWN'),
    ('NOTARY', 'register.deposit.withdraw', 'OWN');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- A professional role is never assigned, to anyone, by anyone (the superuser included): it is computed.
CREATE FUNCTION role_assignment_not_professional() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM role_definition WHERE code = NEW.role AND professional) THEN
        RAISE EXCEPTION 'role % comes from an approved professional account, never from an assignment', NEW.role
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_not_professional BEFORE INSERT OR UPDATE ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_not_professional();
CREATE TRIGGER role_assignment_request_not_professional BEFORE INSERT ON role_assignment_request
    FOR EACH ROW EXECUTE FUNCTION role_assignment_not_professional();

-- --- The professional's permission function ------------------------------------------------------------

-- True only for an ACTIVE professional whose profession's role holds the permission: at an approved office
-- for an office-scoped permission (p_office required), or for their own records (p_office NULL, scope OWN).
CREATE FUNCTION holds_professional_at(p_user uuid, p_permission text, p_office uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_user IS NOT NULL AND EXISTS (
        SELECT 1
          FROM app_user u
          JOIN professional_profile pp ON pp.user_id = u.id AND pp.status = 'ACTIVE'
          JOIN role_definition d ON d.code = pp.profession AND d.professional AND d.status = 'ACTIVE'
          JOIN role_permission rp ON rp.role_code = d.code AND rp.permission = p_permission
          JOIN permission p ON p.code = rp.permission AND p.professional
         WHERE u.id = p_user AND u.enabled AND u.account_type = 'PROFESSIONAL'
           AND ((rp.scope = 'OFFICE' AND p_office IS NOT NULL
                 AND EXISTS (SELECT 1 FROM professional_office o WHERE o.user_id = u.id AND o.office_id = p_office AND o.removed_at IS NULL))
                OR (rp.scope = 'OWN' AND p_office IS NULL)))
$$;

-- The session's view: one (role, office) per approved office of an ACTIVE professional; nothing otherwise.
CREATE FUNCTION professional_grants(p_user uuid) RETURNS TABLE (role text, office_code text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT pp.profession, o.code
      FROM app_user u
      JOIN professional_profile pp ON pp.user_id = u.id AND pp.status = 'ACTIVE'
      JOIN professional_office x ON x.user_id = u.id AND x.removed_at IS NULL
      JOIN office o ON o.id = x.office_id
     WHERE u.id = p_user AND u.enabled AND u.account_type = 'PROFESSIONAL'
     ORDER BY o.code
$$;

REVOKE EXECUTE ON FUNCTION holds_professional_at(uuid, text, uuid), professional_grants(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION holds_professional_at(uuid, text, uuid), professional_grants(uuid) TO snigf_app;
