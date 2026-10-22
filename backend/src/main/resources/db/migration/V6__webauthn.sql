-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V6: WebAuthn second factor (ADR-016), the tables of Spring Security's JDBC repositories
-- (user-entities-schema.sql, user-credentials-schema-postgres.sql) with integrity added:
--   * a WebAuthn user entity belongs to exactly one account: its name is the account id,
--     and the generated column app_user_id makes that a foreign key;
--   * a credential belongs to a user entity, and carries a label of reasonable length.
-- Only public keys are stored here; private keys never leave the authenticators.

CREATE TABLE user_entities (
    id            varchar(1000) NOT NULL,   -- WebAuthn user handle (random, base64url)
    name          varchar(100)  NOT NULL,   -- the account id
    display_name  varchar(200),
    app_user_id   uuid GENERATED ALWAYS AS (name::uuid) STORED NOT NULL REFERENCES app_user (id),
    CONSTRAINT user_entities_pk PRIMARY KEY (id),
    CONSTRAINT user_entities_one_per_account UNIQUE (name),
    CONSTRAINT user_entities_name_is_uuid CHECK (name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
);

CREATE TABLE user_credentials (
    credential_id                 varchar(1000) NOT NULL,
    user_entity_user_id           varchar(1000) NOT NULL REFERENCES user_entities (id),
    public_key                    bytea         NOT NULL,
    signature_count               bigint,
    uv_initialized                boolean,
    backup_eligible               boolean       NOT NULL,
    authenticator_transports      varchar(1000),
    public_key_credential_type    varchar(100),
    backup_state                  boolean       NOT NULL,
    attestation_object            bytea,
    attestation_client_data_json  bytea,
    created                       timestamptz,
    last_used                     timestamptz,
    label                         varchar(1000) NOT NULL CHECK (length(label) BETWEEN 1 AND 64),
    CONSTRAINT user_credentials_pk PRIMARY KEY (credential_id)
);
CREATE INDEX ix_user_credentials_user ON user_credentials (user_entity_user_id);

-- The application registers authenticators, updates signature counters and last use,
-- and removes credentials (admin reset). The auditor sees nothing of this.
GRANT SELECT, INSERT, UPDATE, DELETE ON user_entities, user_credentials TO snigf_app;

-- Enrolment of the second factor (ADR-016). V1 tied app_user.mfa_enrolled_at to a TOTP
-- secret; with WebAuthn, an enrolled account has registered authenticators instead. The
-- rule spans two tables, so a trigger enforces it: an account can be marked enrolled only
-- if it holds a WebAuthn credential or a TOTP secret.
ALTER TABLE app_user DROP CONSTRAINT ck_mfa_enrolled_has_secret;

CREATE FUNCTION app_user_mfa_enrolment_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.mfa_enrolled_at IS NOT NULL AND NEW.mfa_secret_enc IS NULL AND NOT EXISTS (
        SELECT 1 FROM user_credentials c JOIN user_entities e ON e.id = c.user_entity_user_id
         WHERE e.app_user_id = NEW.id) THEN
        RAISE EXCEPTION 'account % has no second factor to be enrolled with', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_mfa_enrolment_guard BEFORE INSERT OR UPDATE OF mfa_enrolled_at, mfa_secret_enc ON app_user
    FOR EACH ROW EXECUTE FUNCTION app_user_mfa_enrolment_guard();
