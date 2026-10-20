-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V1 — identity: offices, users, MFA recovery codes, role assignments, idempotency.
-- Runs as snigf_migrator. Grants to snigf_app are explicit per table and per
-- column: the application can only change what it has a business reason to change.

-- Shared guard used by every append-only table in later migrations.
CREATE FUNCTION reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TABLE office (
    id       uuid PRIMARY KEY,
    code     varchar(16) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,16}$'),
    name_fr  text NOT NULL CHECK (length(name_fr) BETWEEN 2 AND 200),
    name_en  text NOT NULL CHECK (length(name_en) BETWEEN 2 AND 200),
    -- Only synthetic offices may exist until a real office is authorized (gate G1).
    is_demo  boolean NOT NULL DEFAULT true CHECK (is_demo)
);

CREATE TABLE app_user (
    id                  uuid PRIMARY KEY,
    email               text NOT NULL CHECK (length(email) BETWEEN 3 AND 254 AND email LIKE '%@%'),
    display_name        text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 120),
    password_hash       text NOT NULL,
    account_type        text NOT NULL CHECK (account_type IN ('APPLICANT', 'STAFF')),
    preferred_language  char(2) NOT NULL DEFAULT 'fr' CHECK (preferred_language IN ('fr', 'en')),
    enabled             boolean NOT NULL DEFAULT true,
    -- C9: no identity verification exists yet; the column cannot be set true.
    -- Counter verification (P-05) will replace this check in a later migration.
    identity_verified   boolean NOT NULL DEFAULT false CHECK (identity_verified = false),
    mfa_secret_enc      bytea,
    mfa_enrolled_at     timestamptz,
    mfa_last_used_step  bigint,
    failed_logins       int NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_mfa_enrolled_has_secret CHECK (mfa_enrolled_at IS NULL OR mfa_secret_enc IS NOT NULL)
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));

CREATE TABLE mfa_recovery_code (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES app_user (id),
    code_hash       text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    used_at         timestamptz,
    invalidated_at  timestamptz
);
CREATE INDEX ix_mfa_recovery_user ON mfa_recovery_code (user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE role_assignment (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES app_user (id),
    role           text NOT NULL CHECK (role IN (
                       'APPLICANT', 'INTAKE_OFFICER', 'CADASTRAL_OFFICER', 'REVIEWER',
                       'DECISION_AUTHORITY', 'SUPERVISOR', 'AUDITOR', 'FINANCE_OFFICER', 'SYSTEM_ADMIN')),
    office_id      uuid REFERENCES office (id),
    granted_by     uuid REFERENCES app_user (id),   -- NULL = granted by the system (self-registration, seed)
    granted_at     timestamptz NOT NULL DEFAULT now(),
    grant_reason   text NOT NULL CHECK (length(grant_reason) BETWEEN 3 AND 500),
    revoked_by     uuid REFERENCES app_user (id),
    revoked_at     timestamptz,
    revoke_reason  text CHECK (revoke_reason IS NULL OR length(revoke_reason) BETWEEN 3 AND 500),
    -- C6c: nobody grants or revokes their own roles.
    CONSTRAINT ck_no_self_grant  CHECK (granted_by IS NULL OR granted_by <> user_id),
    CONSTRAINT ck_no_self_revoke CHECK (revoked_by IS NULL OR revoked_by <> user_id),
    CONSTRAINT ck_office_scope   CHECK ((role = 'APPLICANT') = (office_id IS NULL)),
    CONSTRAINT ck_revoke_fields  CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)
                                        AND (revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE UNIQUE INDEX ux_role_active ON role_assignment
    (user_id, role, coalesce(office_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE revoked_at IS NULL;
CREATE INDEX ix_role_assignment_user ON role_assignment (user_id) WHERE revoked_at IS NULL;

-- Once revoked, an assignment cannot be changed again; grant fields never change.
CREATE FUNCTION role_assignment_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'role assignment % is already revoked', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER role_assignment_guard BEFORE UPDATE ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_guard();
CREATE TRIGGER role_assignment_no_delete BEFORE DELETE ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TABLE idempotency_record (
    user_id         uuid NOT NULL REFERENCES app_user (id),
    idem_key        varchar(64) NOT NULL CHECK (idem_key ~ '^[A-Za-z0-9._:-]{8,64}$'),
    endpoint        text NOT NULL,
    request_sha256  char(64) NOT NULL,
    status_code     int NOT NULL,
    response_body   jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- Grants: table- and column-level, nothing by default.
GRANT SELECT ON office TO snigf_app;
GRANT SELECT, INSERT ON app_user TO snigf_app;
GRANT UPDATE (display_name, password_hash, preferred_language, enabled, mfa_secret_enc, mfa_enrolled_at,
              mfa_last_used_step, failed_logins, locked_until, version) ON app_user TO snigf_app;
GRANT SELECT, INSERT ON mfa_recovery_code TO snigf_app;
GRANT UPDATE (used_at, invalidated_at) ON mfa_recovery_code TO snigf_app;
GRANT SELECT, INSERT ON role_assignment TO snigf_app;
GRANT UPDATE (revoked_by, revoked_at, revoke_reason) ON role_assignment TO snigf_app;
GRANT SELECT, INSERT, DELETE ON idempotency_record TO snigf_app;
