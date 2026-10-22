-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V8: the approval workflow enforced by the database (ADR-026).
--
-- Until V7 the four-eyes rules lived in the application; the database only refused a
-- request decided by its own requester. From here on, the application's database account
-- (snigf_app) cannot give anyone a right without an approved request:
--
--   role_assignment_request  new: every role assignment (except the self-registration role)
--                            is requested by one administrator and approved by another;
--                            approving it creates the assignment, in the same statement.
--   role_assignment          a non-self-registration row can only be inserted for an
--                            approved request (request_id), once.
--   role_change_request      approving it applies the change; snigf_app can no longer write
--                            role_definition or role_permission at all.
--   permission_grant         inserted only as PENDING; status moves only along the allowed
--                            transitions; uses move by one.
--
-- In every case the database checks that the requester, approver or revoker holds the
-- permission for it through an active role (holds_permission), that requester, approver
-- and beneficiary differ, that the request is not older than 30 days (the upper bound of
-- SNIGF_ADMIN_REQUEST_TTL), and that nobody ends up with administration and audit
-- permissions together (mixes_duties). Timestamps are the database's own.
--
-- The PostgreSQL superuser is not bound by the assignment rule: it is the bootstrap path
-- for the first administrators (deployment procedure, two people present; dev-make-staff.sh
-- in development). It can bypass any trigger anyway (docs/audit.md, residual risk).

-- --- Catalogue and baseline roles --------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('admin.assignment.read', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Read role assignment requests', 'Consulter les demandes d’attribution de rôle'),
    ('admin.assignment.approve', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Approve or reject role assignment requests', 'Approuver ou rejeter les demandes d’attribution de rôle');
UPDATE permission SET description_en = 'Request a role assignment for a staff account',
                      description_fr = 'Demander l’attribution d’un rôle à un compte agent'
 WHERE code = 'admin.role.assign';

-- Baseline roles change only here, reviewed, never at run time.
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('SYSTEM_ADMIN', 'admin.assignment.read', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.assignment.approve', 'ANY'),
    -- Supervisors verify the chain: the result names positions and kinds of mismatch only,
    -- never an event's content (ADR-026).
    ('SUPERVISOR', 'audit.chain.verify', 'ANY');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- --- Helpers ------------------------------------------------------------------------------

CREATE FUNCTION is_superuser() RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce((SELECT rolsuper FROM pg_roles WHERE rolname = current_user), false)
$$;

-- Whether an enabled staff account holds a permission through an active role. Special
-- grants never count: administration permissions are not delegable.
CREATE FUNCTION holds_permission(p_user uuid, p_permission text) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1
          FROM app_user u
          JOIN role_assignment a ON a.user_id = u.id AND a.revoked_at IS NULL
          JOIN role_definition d ON d.code = a.role AND d.status = 'ACTIVE'
          JOIN role_permission p ON p.role_code = d.code AND p.permission = p_permission
         WHERE u.id = p_user AND u.enabled AND u.account_type = 'STAFF')
$$;

-- Whether a person would hold administration and audit permissions together, counting
-- their active roles, their special grants in force, and the extra roles and permissions.
CREATE FUNCTION mixes_duties(p_user uuid, p_extra_roles text[], p_extra_permissions text[]) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    WITH held AS (
        SELECT p.permission
          FROM role_assignment a
          JOIN role_definition d ON d.code = a.role AND d.status = 'ACTIVE'
          JOIN role_permission p ON p.role_code = d.code
         WHERE a.user_id = p_user AND a.revoked_at IS NULL
        UNION
        SELECT p.permission FROM role_permission p WHERE p.role_code = ANY (p_extra_roles)
        UNION
        SELECT g.permission
          FROM permission_grant g
         WHERE g.user_id = p_user AND g.status = 'APPROVED'
           AND (g.valid_until IS NULL OR g.valid_until > now())
           AND (g.max_uses IS NULL OR g.uses < g.max_uses)
        UNION
        SELECT unnest(p_extra_permissions))
    SELECT count(DISTINCT c.family) = 2
      FROM held h JOIN permission c ON c.code = h.permission
     WHERE c.family IN ('ADMINISTRATION', 'AUDIT')
$$;

CREATE FUNCTION require_permission(p_user uuid, p_permission text, p_what text) RETURNS void
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF p_user IS NULL OR NOT holds_permission(p_user, p_permission) THEN
        RAISE EXCEPTION '% requires an account holding %', p_what, p_permission
            USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

CREATE FUNCTION require_staff(p_user uuid) RETURNS void
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user AND enabled AND account_type = 'STAFF') THEN
        RAISE EXCEPTION 'account % is not an enabled staff account', p_user USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- A request can be approved for at most 30 days (the upper bound of SNIGF_ADMIN_REQUEST_TTL;
-- the application applies its own, shorter limit first).
CREATE FUNCTION require_fresh(p_requested_at timestamptz) RETURNS void
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF p_requested_at < now() - interval '30 days' THEN
        RAISE EXCEPTION 'the request expired' USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- --- Role assignment requests --------------------------------------------------------------

CREATE TABLE role_assignment_request (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES app_user (id),
    role             text NOT NULL REFERENCES role_definition (code),
    office_id        uuid NOT NULL REFERENCES office (id),
    reason           text NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    decision_reason  text CHECK (decision_reason IS NULL OR length(decision_reason) BETWEEN 3 AND 500),
    CONSTRAINT ck_assignment_request_not_self CHECK (requested_by <> user_id),
    CONSTRAINT ck_assignment_request_four_eyes CHECK (decided_by IS NULL
                                                      OR (decided_by <> requested_by AND decided_by <> user_id)),
    CONSTRAINT ck_assignment_request_decision CHECK ((status = 'PENDING') = (decided_by IS NULL)
                                                     AND (decided_by IS NULL) = (decided_at IS NULL))
);
-- At most one open request for the same role, person and office.
CREATE UNIQUE INDEX ux_assignment_request_open ON role_assignment_request (user_id, role, office_id)
    WHERE status = 'PENDING';
CREATE INDEX ix_assignment_request_pending ON role_assignment_request (requested_at) WHERE status = 'PENDING';

CREATE FUNCTION role_assignment_request_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'a request is created pending' USING ERRCODE = 'check_violation';
    END IF;
    NEW.requested_at := now();
    PERFORM require_permission(NEW.requested_by, 'admin.role.assign', 'requesting a role assignment');
    PERFORM require_staff(NEW.user_id);
    IF NOT EXISTS (SELECT 1 FROM role_definition WHERE code = NEW.role AND status = 'ACTIVE' AND assignable) THEN
        RAISE EXCEPTION 'role % cannot be assigned', NEW.role USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_request_insert BEFORE INSERT ON role_assignment_request
    FOR EACH ROW EXECUTE FUNCTION role_assignment_request_insert();

CREATE FUNCTION role_assignment_request_decide() RETURNS trigger
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
    PERFORM require_permission(NEW.decided_by, 'admin.assignment.approve', 'deciding a role assignment');
    IF NEW.status = 'APPROVED' THEN
        PERFORM require_fresh(OLD.requested_at);
        PERFORM require_staff(NEW.user_id);
        IF NOT EXISTS (SELECT 1 FROM role_definition WHERE code = NEW.role AND status = 'ACTIVE' AND assignable) THEN
            RAISE EXCEPTION 'role % cannot be assigned', NEW.role USING ERRCODE = 'check_violation';
        END IF;
        IF mixes_duties(NEW.user_id, ARRAY[NEW.role], ARRAY[]::text[]) THEN
            RAISE EXCEPTION 'separation of duties: administration and audit together' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_request_decide BEFORE UPDATE ON role_assignment_request
    FOR EACH ROW EXECUTE FUNCTION role_assignment_request_decide();

-- Approving creates the assignment, in the same statement: an approval without its
-- assignment, or an assignment without its approval, cannot exist. The assignment takes
-- the request's id.
CREATE FUNCTION role_assignment_request_apply() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    INSERT INTO role_assignment (id, user_id, role, office_id, granted_by, grant_reason, request_id)
    VALUES (NEW.id, NEW.user_id, NEW.role, NEW.office_id, NEW.requested_by, NEW.reason, NEW.id);
    RETURN NULL;
END;
$$;
CREATE TRIGGER role_assignment_request_apply AFTER UPDATE ON role_assignment_request
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'APPROVED')
    EXECUTE FUNCTION role_assignment_request_apply();

-- --- Role assignments ------------------------------------------------------------------------

ALTER TABLE role_assignment ADD COLUMN request_id uuid UNIQUE REFERENCES role_assignment_request (id);

-- A role an administrator assigns exists only through an approved request. The
-- self-registration role (not assignable) goes only to an applicant account, by the system.
CREATE FUNCTION role_assignment_insert_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF is_superuser() THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM role_definition WHERE code = NEW.role AND NOT assignable) THEN
        IF NEW.granted_by IS NOT NULL OR NEW.office_id IS NOT NULL OR NEW.request_id IS NOT NULL
           OR NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.user_id AND account_type = 'APPLICANT') THEN
            RAISE EXCEPTION 'role % is given only to applicant accounts at registration', NEW.role
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM role_assignment_request r
                    WHERE r.id = NEW.request_id AND r.status = 'APPROVED' AND r.user_id = NEW.user_id
                      AND r.role = NEW.role AND r.office_id IS NOT DISTINCT FROM NEW.office_id
                      AND r.requested_by IS NOT DISTINCT FROM NEW.granted_by) THEN
        RAISE EXCEPTION 'a role assignment requires an approved request' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_insert_guard BEFORE INSERT ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_insert_guard();

-- Revocation stays a one-person action (taking a right away must be fast), by someone
-- holding admin.role.revoke, at the database's time.
CREATE FUNCTION role_assignment_revoke_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.revoked_by IS NOT NULL AND OLD.revoked_by IS NULL AND NOT is_superuser() THEN
        PERFORM require_permission(NEW.revoked_by, 'admin.role.revoke', 'revoking a role');
        NEW.revoked_at := now();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_revoke_guard BEFORE UPDATE ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_revoke_guard();

-- --- Role change requests ---------------------------------------------------------------------

CREATE FUNCTION role_change_request_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'a request is created pending' USING ERRCODE = 'check_violation';
    END IF;
    NEW.requested_at := now();
    PERFORM require_permission(NEW.requested_by, 'admin.role.define', 'requesting a role change');
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_change_request_insert BEFORE INSERT ON role_change_request
    FOR EACH ROW EXECUTE FUNCTION role_change_request_insert();

CREATE FUNCTION role_change_request_decide() RETURNS trigger
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
    PERFORM require_permission(NEW.decided_by, 'admin.role.approve', 'deciding a role change');
    IF NEW.status = 'APPROVED' THEN
        PERFORM require_fresh(OLD.requested_at);
        IF EXISTS (SELECT 1 FROM role_assignment
                    WHERE user_id = NEW.decided_by AND role = NEW.role_code AND revoked_at IS NULL) THEN
            RAISE EXCEPTION 'the approver holds role %', NEW.role_code USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_change_request_decide BEFORE UPDATE ON role_change_request
    FOR EACH ROW EXECUTE FUNCTION role_change_request_decide();

-- Applies an approved change. SECURITY DEFINER: it runs as the schema owner, because the
-- application's account can no longer write role definitions itself.
CREATE FUNCTION role_change_request_apply() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    holder uuid;
BEGIN
    IF NEW.kind = 'CREATE' THEN
        INSERT INTO role_definition (code, name_fr, name_en, description, created_by)
        VALUES (NEW.role_code, NEW.proposal->>'nameFr', NEW.proposal->>'nameEn', NEW.proposal->>'description',
                NEW.requested_by);
    ELSIF NEW.kind = 'UPDATE' THEN
        UPDATE role_definition
           SET name_fr = NEW.proposal->>'nameFr', name_en = NEW.proposal->>'nameEn',
               description = NEW.proposal->>'description', version = version + 1
         WHERE code = NEW.role_code AND status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'role % is not an active role', NEW.role_code USING ERRCODE = 'check_violation';
        END IF;
        DELETE FROM role_permission WHERE role_code = NEW.role_code;
    ELSE
        IF EXISTS (SELECT 1 FROM role_assignment WHERE role = NEW.role_code AND revoked_at IS NULL) THEN
            RAISE EXCEPTION 'role % is still assigned', NEW.role_code USING ERRCODE = 'check_violation';
        END IF;
        UPDATE role_definition SET status = 'RETIRED', version = version + 1
         WHERE code = NEW.role_code AND status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'role % is not an active role', NEW.role_code USING ERRCODE = 'check_violation';
        END IF;
        RETURN NULL;
    END IF;

    INSERT INTO role_permission (role_code, permission, scope)
    SELECT NEW.role_code, e.key, e.value FROM jsonb_each_text(NEW.proposal->'permissions') e;
    IF NOT EXISTS (SELECT 1 FROM role_permission WHERE role_code = NEW.role_code) THEN
        RAISE EXCEPTION 'role % has no permission', NEW.role_code USING ERRCODE = 'check_violation';
    END IF;
    IF (SELECT count(DISTINCT c.family) FROM role_permission p JOIN permission c ON c.code = p.permission
         WHERE p.role_code = NEW.role_code AND c.family IN ('ADMINISTRATION', 'AUDIT')) = 2 THEN
        RAISE EXCEPTION 'separation of duties: role % mixes administration and audit', NEW.role_code
            USING ERRCODE = 'check_violation';
    END IF;
    FOR holder IN SELECT DISTINCT user_id FROM role_assignment WHERE role = NEW.role_code AND revoked_at IS NULL LOOP
        IF mixes_duties(holder, ARRAY[]::text[], ARRAY[]::text[]) THEN
            RAISE EXCEPTION 'separation of duties: a holder of % would mix administration and audit', NEW.role_code
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION role_change_request_apply() FROM PUBLIC;
CREATE TRIGGER role_change_request_apply AFTER UPDATE ON role_change_request
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'APPROVED')
    EXECUTE FUNCTION role_change_request_apply();

-- Role definitions change only through approved requests (the function above).
REVOKE INSERT, UPDATE ON role_definition FROM snigf_app;
REVOKE INSERT, DELETE ON role_permission FROM snigf_app;

-- --- Special grants -----------------------------------------------------------------------

CREATE FUNCTION permission_grant_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.uses <> 0 OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL
       OR NEW.revoked_by IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'a special grant is created pending and unused' USING ERRCODE = 'check_violation';
    END IF;
    NEW.requested_at := now();
    PERFORM require_permission(NEW.requested_by, 'admin.grant.request', 'requesting a special grant');
    PERFORM require_staff(NEW.user_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER permission_grant_insert BEFORE INSERT ON permission_grant
    FOR EACH ROW EXECUTE FUNCTION permission_grant_insert();

-- The life of a grant: PENDING -> APPROVED | REJECTED | REVOKED, APPROVED -> REVOKED; a
-- decision never changes; a use is taken one at a time while the grant is in force, and
-- given back one at a time.
CREATE FUNCTION permission_grant_transition() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.decided_by IS DISTINCT FROM OLD.decided_by AND OLD.decided_by IS NOT NULL
       OR NEW.revoked_by IS DISTINCT FROM OLD.revoked_by AND OLD.revoked_by IS NOT NULL THEN
        RAISE EXCEPTION 'a decision on grant % cannot change', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NEW.uses <> OLD.uses THEN
            RAISE EXCEPTION 'uses change on their own' USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.status = 'PENDING' AND NEW.status IN ('APPROVED', 'REJECTED') THEN
            NEW.decided_at := now();
            PERFORM require_permission(NEW.decided_by, 'admin.grant.approve', 'deciding a special grant');
            IF NEW.status = 'APPROVED' THEN
                PERFORM require_fresh(OLD.requested_at);
                PERFORM require_staff(NEW.user_id);
                IF NEW.valid_until IS NOT NULL AND NEW.valid_until <= now() THEN
                    RAISE EXCEPTION 'grant % already ended', OLD.id USING ERRCODE = 'check_violation';
                END IF;
                IF mixes_duties(NEW.user_id, ARRAY[]::text[], ARRAY[NEW.permission]) THEN
                    RAISE EXCEPTION 'separation of duties: administration and audit together'
                        USING ERRCODE = 'check_violation';
                END IF;
            END IF;
        ELSIF OLD.status IN ('PENDING', 'APPROVED') AND NEW.status = 'REVOKED' THEN
            NEW.revoked_at := now();
            PERFORM require_permission(NEW.revoked_by, 'admin.grant.revoke', 'revoking a special grant');
        ELSE
            RAISE EXCEPTION 'grant % cannot go from % to %', OLD.id, OLD.status, NEW.status
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF NEW.uses <> OLD.uses THEN
        IF NEW.uses = OLD.uses + 1 THEN
            IF OLD.status <> 'APPROVED' OR (OLD.valid_until IS NOT NULL AND OLD.valid_until <= now()) THEN
                RAISE EXCEPTION 'grant % is not in force', OLD.id USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.uses <> OLD.uses - 1 THEN
            RAISE EXCEPTION 'uses move one at a time' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER permission_grant_transition BEFORE UPDATE ON permission_grant
    FOR EACH ROW EXECUTE FUNCTION permission_grant_transition();

GRANT SELECT, INSERT ON role_assignment_request TO snigf_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason) ON role_assignment_request TO snigf_app;
