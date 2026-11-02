-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V23: the professional account type (Slice 3, ADR-041 step 1). DEMO only.
--
--   app_user.account_type   gains PROFESSIONAL: notaries and sworn surveyors, with a second factor
--                           like staff, and no staff right of any kind
--   professional_profile    one per professional account: profession, credential, status
--
-- Nothing here grants a permission. The staff permission functions (holds_permission,
-- holds_permission_at, holds_grant_at, holds_assignable) and require_staff are deliberately left
-- unchanged: they require STAFF, so a professional fails every one of them. The lifecycle (verify,
-- approve, suspend, revoke) and the professional's own permission function arrive in the next steps;
-- until then the application cannot write a profile at all.

SET search_path = snigf, public;

ALTER TABLE app_user DROP CONSTRAINT app_user_account_type_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_account_type_check CHECK (account_type IN ('APPLICANT', 'STAFF', 'PROFESSIONAL'));

CREATE TABLE professional_profile (
    user_id              uuid PRIMARY KEY REFERENCES app_user (id),
    profession           text NOT NULL CHECK (profession IN ('NOTARY', 'SWORN_SURVEYOR')),
    chamber              text NOT NULL CHECK (length(btrim(chamber)) BETWEEN 2 AND 200),
    -- Synthetic data only in this MVP: the number says so.
    registration_number  text NOT NULL CHECK (length(btrim(registration_number)) BETWEEN 2 AND 64 AND registration_number LIKE '%DEMO%'),
    jurisdiction         text NOT NULL CHECK (length(btrim(jurisdiction)) BETWEEN 2 AND 200),
    status               text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'VERIFIED', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    requested_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    status_changed_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    version              bigint NOT NULL DEFAULT 0,
    demo                 boolean NOT NULL DEFAULT true CHECK (demo),
    UNIQUE (profession, registration_number)
);

-- A profile belongs to a professional account, and only a professional account has one.
CREATE FUNCTION professional_profile_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = NEW.user_id AND account_type = 'PROFESSIONAL') THEN
        RAISE EXCEPTION 'account % is not a professional account', NEW.user_id USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.user_id, NEW.profession, NEW.requested_at, NEW.demo)
                            IS DISTINCT FROM (OLD.user_id, OLD.profession, OLD.requested_at, OLD.demo) THEN
        RAISE EXCEPTION 'a professional''s identity and profession never change' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'REVOKED' THEN
        RAISE EXCEPTION 'a revoked professional account is final' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_profile_guard BEFORE INSERT OR UPDATE ON professional_profile
    FOR EACH ROW EXECUTE FUNCTION professional_profile_guard();
CREATE TRIGGER professional_profile_no_delete BEFORE DELETE ON professional_profile
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER professional_profile_no_truncate BEFORE TRUNCATE ON professional_profile
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- An account's type never changes once it is professional, and nothing becomes professional later:
-- a staff account turned professional, or the reverse, would carry rights across the boundary.
CREATE FUNCTION app_user_professional_type_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (OLD.account_type = 'PROFESSIONAL') <> (NEW.account_type = 'PROFESSIONAL') THEN
        RAISE EXCEPTION 'an account becomes or stops being professional only when created' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_professional_type_guard BEFORE UPDATE OF account_type ON app_user
    FOR EACH ROW EXECUTE FUNCTION app_user_professional_type_guard();

-- Row-level security: the professional reads their own profile; administrators read them from step 2.
ALTER TABLE professional_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY professional_profile_own ON professional_profile FOR SELECT TO snigf_app
    USING (user_id = current_actor());

REVOKE ALL ON professional_profile FROM PUBLIC;
GRANT SELECT ON professional_profile TO snigf_app;
