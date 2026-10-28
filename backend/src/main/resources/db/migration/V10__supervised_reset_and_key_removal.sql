-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V10: the two gaps found when verifying the recovery procedure (ADR-028).
--
--   1. A lost key is removed by its owner (the application deletes the credential); the last
--      key of an enrolled account can never be removed, so nobody is left enrolled without a
--      key (user_credentials_keep_last).
--   2. A second-factor reset is a request: an administrator holding admin.mfa.reset asks; the
--      person's supervisor, holding identity.reset.confirm at an office where the person holds
--      a role, confirms in the application how the identity was checked. Only the confirmation
--      performs the reset, in the same statement (mfa_reset_request_apply, SECURITY DEFINER).
--      The application's account can no longer clear an enrolment itself.

-- --- Catalogue: the supervisor's confirmation ------------------------------------------------

-- A business permission, not administration: supervisors hold audit permissions, and
-- administration and audit never meet (ADR-025). Never delegable.
INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('identity.reset.confirm', 'BUSINESS', false, ARRAY['OFFICE'],
     'Confirm the identity of a person whose second factor is reset',
     'Confirmer l’identité d’une personne dont le deuxième facteur est réinitialisé');
UPDATE permission SET description_en = 'Request the reset of a lost second factor',
                      description_fr = 'Demander la réinitialisation d’un deuxième facteur perdu'
 WHERE code = 'admin.mfa.reset';

ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES ('SUPERVISOR', 'identity.reset.confirm', 'OFFICE');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- Whether an enabled staff account holds a permission at an office (scope ANY, or OFFICE
-- through an assignment at that office).
CREATE FUNCTION holds_permission_at(p_user uuid, p_permission text, p_office uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1
          FROM app_user u
          JOIN role_assignment a ON a.user_id = u.id AND a.revoked_at IS NULL
          JOIN role_definition d ON d.code = a.role AND d.status = 'ACTIVE'
          JOIN role_permission p ON p.role_code = d.code AND p.permission = p_permission
         WHERE u.id = p_user AND u.enabled AND u.account_type = 'STAFF'
           AND (p.scope = 'ANY' OR (p.scope = 'OFFICE' AND a.office_id = p_office)))
$$;

-- --- 1. The last key of an enrolled account stays -------------------------------------------

CREATE FUNCTION user_credentials_keep_last() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_entities e JOIN app_user u ON u.id = e.app_user_id
                WHERE e.id = OLD.user_entity_user_id AND u.mfa_enrolled_at IS NOT NULL)
       AND NOT EXISTS (SELECT 1 FROM user_credentials c
                        WHERE c.user_entity_user_id = OLD.user_entity_user_id
                          AND c.credential_id <> OLD.credential_id) THEN
        RAISE EXCEPTION 'the last key of an enrolled account cannot be removed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER user_credentials_keep_last BEFORE DELETE ON user_credentials
    FOR EACH ROW EXECUTE FUNCTION user_credentials_keep_last();

-- --- 2. Supervised reset -----------------------------------------------------------------

CREATE TABLE mfa_reset_request (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES app_user (id),
    reason           text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    identity_check   text CHECK (identity_check IS NULL OR length(identity_check) BETWEEN 3 AND 200),
    decision_reason  text CHECK (decision_reason IS NULL OR length(decision_reason) BETWEEN 3 AND 500),
    CONSTRAINT ck_reset_not_self CHECK (requested_by <> user_id),
    CONSTRAINT ck_reset_four_eyes CHECK (decided_by IS NULL OR (decided_by <> requested_by AND decided_by <> user_id)),
    CONSTRAINT ck_reset_decision CHECK ((status = 'PENDING') = (decided_by IS NULL)
                                        AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- A confirmation always says how the identity was checked.
    CONSTRAINT ck_reset_identity_checked CHECK (status <> 'CONFIRMED' OR identity_check IS NOT NULL)
);
CREATE UNIQUE INDEX ux_mfa_reset_request_open ON mfa_reset_request (user_id) WHERE status = 'PENDING';

CREATE FUNCTION mfa_reset_request_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'a request is created pending' USING ERRCODE = 'check_violation';
    END IF;
    NEW.requested_at := now();
    PERFORM require_permission(NEW.requested_by, 'admin.mfa.reset', 'requesting a second-factor reset');
    PERFORM require_staff(NEW.user_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER mfa_reset_request_insert BEFORE INSERT ON mfa_reset_request
    FOR EACH ROW EXECUTE FUNCTION mfa_reset_request_insert();

-- The confirmer holds identity.reset.confirm at an office where the person holds a role.
CREATE FUNCTION mfa_reset_request_decide() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status AND NEW.decided_by IS NOT DISTINCT FROM OLD.decided_by THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'request % is already decided', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    NEW.decided_at := now();
    IF NEW.decided_by IS NULL OR NOT EXISTS (
        SELECT 1 FROM role_assignment t
         WHERE t.user_id = NEW.user_id AND t.revoked_at IS NULL AND t.office_id IS NOT NULL
           AND holds_permission_at(NEW.decided_by, 'identity.reset.confirm', t.office_id)) THEN
        RAISE EXCEPTION 'deciding a reset requires holding identity.reset.confirm at an office of the person'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.status = 'CONFIRMED' THEN
        PERFORM require_fresh(OLD.requested_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER mfa_reset_request_decide BEFORE UPDATE ON mfa_reset_request
    FOR EACH ROW EXECUTE FUNCTION mfa_reset_request_decide();

-- The reset itself: enrolment cleared first (so the last-key guard allows the deletion),
-- unused recovery codes invalidated, every key removed. Runs as the schema owner, the only
-- account (besides the superuser) allowed to clear an enrolment.
CREATE FUNCTION mfa_reset_request_apply() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    UPDATE app_user SET mfa_enrolled_at = NULL, version = version + 1 WHERE id = NEW.user_id;
    UPDATE mfa_recovery_code SET invalidated_at = now()
     WHERE user_id = NEW.user_id AND used_at IS NULL AND invalidated_at IS NULL;
    DELETE FROM user_credentials c USING user_entities e
     WHERE c.user_entity_user_id = e.id AND e.app_user_id = NEW.user_id;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION mfa_reset_request_apply() FROM PUBLIC;
CREATE TRIGGER mfa_reset_request_apply AFTER UPDATE ON mfa_reset_request
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'CONFIRMED')
    EXECUTE FUNCTION mfa_reset_request_apply();

-- Only a confirmed reset (or the superuser) clears an enrolment.
CREATE FUNCTION app_user_enrolment_cleared_by_reset() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF OLD.mfa_enrolled_at IS NOT NULL AND NEW.mfa_enrolled_at IS NULL AND NOT is_superuser()
       AND current_user <> (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'snigf.app_user'::regclass) THEN
        RAISE EXCEPTION 'a second factor is reset only through a confirmed reset request'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_enrolment_cleared_by_reset BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION app_user_enrolment_cleared_by_reset();

GRANT SELECT, INSERT ON mfa_reset_request TO snigf_app;
GRANT UPDATE (status, decided_by, decided_at, identity_check, decision_reason) ON mfa_reset_request TO snigf_app;
