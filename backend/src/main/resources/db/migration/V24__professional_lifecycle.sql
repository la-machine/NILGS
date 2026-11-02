-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V24: the professional account lifecycle (Slice 3, ADR-041 step 2). DEMO only.
--
--   professional_verification           how a credential and identity were checked, by whom, when
--   professional_verification_document  the files supporting it (ADR-033 checks), administrators only
--   professional_request                a change that needs a second administrator: ACTIVATION (made by
--                                       the verifier, with the offices), REINSTATEMENT, REVOCATION,
--                                       OFFICE_CHANGE. One open request per professional
--   professional_office                 the offices a professional serves; added and removed only by an
--                                       approved request
--   professional_history                every step, append-only
--
-- One administrator suspends; every other change needs a second administrator, who is never the
-- requester (the verifier, for an activation) and never the professional. The application writes
-- these tables only through the functions below. Granting no business permission is still the rule:
-- the professional's permission function arrives in step 3. A supervised reset of a professional's
-- keys is confirmed by a supervisor of one of their offices.

SET search_path = snigf, public;

-- --- Permissions: administration family, never delegable ------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('admin.professional.read', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Read professional accounts, their requests and history', 'Consulter les comptes professionnels, leurs demandes et leur historique'),
    ('admin.professional.verify', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Verify a professional''s credential and propose their offices', 'Vérifier la qualité d''un professionnel et proposer ses bureaux'),
    ('admin.professional.approve', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Approve or reject a professional account request', 'Approuver ou rejeter une demande sur un compte professionnel'),
    ('admin.professional.suspend', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Suspend a professional account, or request its reinstatement, revocation or office change',
     'Suspendre un compte professionnel, ou demander sa réintégration, sa révocation ou un changement de bureaux');
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('SYSTEM_ADMIN', 'admin.professional.read', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.professional.verify', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.professional.approve', 'ANY'),
    ('SYSTEM_ADMIN', 'admin.professional.suspend', 'ANY');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- --- Tables -------------------------------------------------------------------------------------------

CREATE TABLE professional_verification (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id      uuid NOT NULL REFERENCES professional_profile (user_id),
    verified_by  uuid NOT NULL REFERENCES app_user (id),
    body         text NOT NULL CHECK (length(btrim(body)) BETWEEN 2 AND 200),
    reference    text NOT NULL CHECK (length(btrim(reference)) BETWEEN 2 AND 200),
    checked_on   date NOT NULL,
    note         text NOT NULL CHECK (length(btrim(note)) BETWEEN 10 AND 2000),
    verified_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (verified_by <> user_id)
);

CREATE TABLE professional_verification_document (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    verification_id  uuid NOT NULL REFERENCES professional_verification (id),
    storage_key      char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256           char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes       bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type    text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name    text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by      uuid NOT NULL REFERENCES app_user (id),
    uploaded_at      timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE professional_request (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id          uuid NOT NULL REFERENCES professional_profile (user_id),
    kind             text NOT NULL CHECK (kind IN ('ACTIVATION', 'REINSTATEMENT', 'REVOCATION', 'OFFICE_CHANGE')),
    offices_add      uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(offices_add) <= 50),
    offices_remove   uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(offices_remove) <= 50),
    reason           text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    verification_id  uuid REFERENCES professional_verification (id),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    decision_reason  text CHECK (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 10 AND 2000),
    CHECK (requested_by <> user_id),
    CONSTRAINT ck_professional_request_four_eyes CHECK (decided_by IS NULL OR (decided_by <> requested_by AND decided_by <> user_id)),
    CONSTRAINT ck_professional_request_activation CHECK ((kind = 'ACTIVATION') = (verification_id IS NOT NULL)),
    CONSTRAINT ck_professional_request_decided CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL)
                                                     AND (status = 'PENDING') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX ux_professional_request_open ON professional_request (user_id) WHERE status = 'PENDING';

CREATE TABLE professional_office (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id            uuid NOT NULL REFERENCES professional_profile (user_id),
    office_id          uuid NOT NULL REFERENCES office (id),
    added_by_request   uuid NOT NULL REFERENCES professional_request (id),
    added_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    removed_by_request uuid REFERENCES professional_request (id),
    removed_at         timestamptz,
    CHECK ((removed_at IS NULL) = (removed_by_request IS NULL))
);
CREATE UNIQUE INDEX ux_professional_office_active ON professional_office (user_id, office_id) WHERE removed_at IS NULL;

CREATE TABLE professional_history (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid NOT NULL REFERENCES professional_profile (user_id),
    event       text NOT NULL CHECK (event ~ '^[A-Z][A-Z_]{2,40}$'),
    actor_id    uuid REFERENCES app_user (id),
    reason      text CHECK (reason IS NULL OR length(reason) <= 2000),
    request_id  uuid REFERENCES professional_request (id),
    at          timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_professional_history_user ON professional_history (user_id, at);

-- Records never change, except a request's decision and an office's removal, each once.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['professional_verification', 'professional_verification_document', 'professional_history'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_modification()',
                       t || '_append_only', t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['professional_verification', 'professional_verification_document', 'professional_history',
                             'professional_request', 'professional_office'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_modification()',
                       t || '_no_truncate', t);
    END LOOP;
END;
$$;

CREATE FUNCTION professional_request_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.id, NEW.user_id, NEW.kind, NEW.offices_add, NEW.offices_remove, NEW.reason, NEW.verification_id, NEW.requested_by, NEW.requested_at)
       IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.kind, OLD.offices_add, OLD.offices_remove, OLD.reason, OLD.verification_id, OLD.requested_by,
                         OLD.requested_at) THEN
        RAISE EXCEPTION 'what a request asks never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'request % is already decided', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_request_guard BEFORE UPDATE ON professional_request
    FOR EACH ROW EXECUTE FUNCTION professional_request_guard();
CREATE TRIGGER professional_request_no_delete BEFORE DELETE ON professional_request
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE FUNCTION professional_office_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.id, NEW.user_id, NEW.office_id, NEW.added_by_request, NEW.added_at)
       IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.office_id, OLD.added_by_request, OLD.added_at) OR OLD.removed_at IS NOT NULL THEN
        RAISE EXCEPTION 'an office of a professional is only removed, once' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_office_guard BEFORE UPDATE ON professional_office
    FOR EACH ROW EXECUTE FUNCTION professional_office_guard();
CREATE TRIGGER professional_office_no_delete BEFORE DELETE ON professional_office
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- --- Helpers -------------------------------------------------------------------------------------------

CREATE FUNCTION professional_refuse(p_code text) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '%', p_code USING ERRCODE = 'PR001';
END;
$$;

CREATE FUNCTION professional_record(p_user uuid, p_event text, p_actor uuid, p_reason text, p_request uuid) RETURNS void
LANGUAGE sql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    INSERT INTO professional_history (user_id, event, actor_id, reason, request_id) VALUES (p_user, p_event, p_actor, p_reason, p_request)
$$;

CREATE FUNCTION professional_set_status(p_user uuid, p_status text) RETURNS void
LANGUAGE sql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    UPDATE professional_profile SET status = p_status, status_changed_at = clock_timestamp(), version = version + 1 WHERE user_id = p_user
$$;

CREATE FUNCTION professional_reason_ok(p_reason text) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_reason IS NOT NULL AND length(btrim(p_reason)) BETWEEN 10 AND 2000
$$;

-- The professional, locked; refused unless the actor holds p_permission and is someone else.
CREATE FUNCTION professional_lock(p_user uuid, p_permission text) RETURNS professional_profile
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    p professional_profile;
BEGIN
    IF NOT holds_permission(current_actor(), p_permission) THEN
        PERFORM professional_refuse('PROFESSIONAL_FORBIDDEN');
    END IF;
    SELECT * INTO p FROM professional_profile WHERE user_id = p_user FOR UPDATE;
    IF p.user_id IS NULL THEN
        PERFORM professional_refuse('PROFESSIONAL_NOT_FOUND');
    END IF;
    IF p_user = current_actor() THEN
        PERFORM professional_refuse('SELF_ACTION');
    END IF;
    RETURN p;
END;
$$;

-- Distinct, existing offices, or a refusal.
CREATE FUNCTION professional_offices_valid(p_offices uuid[]) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT cardinality(p_offices) = (SELECT count(DISTINCT x) FROM unnest(p_offices) x)
       AND NOT EXISTS (SELECT 1 FROM unnest(p_offices) x WHERE NOT EXISTS (SELECT 1 FROM office o WHERE o.id = x))
$$;

-- --- The lifecycle -------------------------------------------------------------------------------------

-- The professional's own request, on the public form: the account was just created as PROFESSIONAL.
CREATE FUNCTION professional_profile_create(p_user uuid, p_profession text, p_chamber text, p_registration text, p_jurisdiction text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user AND account_type = 'PROFESSIONAL')
       OR EXISTS (SELECT 1 FROM professional_profile WHERE user_id = p_user) THEN
        PERFORM professional_refuse('PROFESSIONAL_NOT_FOUND');
    END IF;
    INSERT INTO professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
    VALUES (p_user, p_profession, btrim(p_chamber), btrim(p_registration), btrim(p_jurisdiction));
    PERFORM professional_record(p_user, 'REQUESTED', p_user, NULL, NULL);
END;
$$;

-- Administrator A: the credential and identity were checked; proposes the offices. Opens the activation
-- request, which another administrator decides.
CREATE FUNCTION professional_verify(p_user uuid, p_body text, p_reference text, p_checked_on date, p_note text, p_offices uuid[]) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    p     professional_profile := professional_lock(p_user, 'admin.professional.verify');
    v     uuid;
    r     uuid;
BEGIN
    IF p.status <> 'REQUESTED' THEN
        PERFORM professional_refuse('INVALID_STATUS');
    END IF;
    IF p_checked_on IS NULL OR p_checked_on > current_date OR NOT professional_reason_ok(p_note)
       OR coalesce(length(btrim(p_body)), 0) < 2 OR coalesce(length(btrim(p_reference)), 0) < 2 THEN
        PERFORM professional_refuse('VERIFICATION_INCOMPLETE');
    END IF;
    IF coalesce(cardinality(p_offices), 0) = 0 OR NOT professional_offices_valid(p_offices) THEN
        PERFORM professional_refuse('OFFICES_INVALID');
    END IF;
    INSERT INTO professional_verification (user_id, verified_by, body, reference, checked_on, note)
    VALUES (p_user, actor, btrim(p_body), btrim(p_reference), p_checked_on, btrim(p_note)) RETURNING id INTO v;
    INSERT INTO professional_request (user_id, kind, offices_add, reason, verification_id, requested_by)
    VALUES (p_user, 'ACTIVATION', p_offices, btrim(p_note), v, actor) RETURNING id INTO r;
    PERFORM professional_set_status(p_user, 'VERIFIED');
    PERFORM professional_record(p_user, 'VERIFIED', actor, NULL, r);
    RETURN r;
END;
$$;

-- A file supporting a verification, while its activation is still undecided. Only the verifier adds one.
CREATE FUNCTION professional_evidence_add(p_verification uuid, p_storage_key text, p_sha256 text, p_size bigint, p_type text, p_name text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    v      professional_verification;
    new_id uuid;
BEGIN
    SELECT * INTO v FROM professional_verification WHERE id = p_verification;
    IF v.id IS NULL OR NOT holds_permission(actor, 'admin.professional.verify') THEN
        PERFORM professional_refuse('VERIFICATION_NOT_FOUND');
    END IF;
    PERFORM professional_lock(v.user_id, 'admin.professional.verify');
    IF v.verified_by <> actor THEN
        PERFORM professional_refuse('NOT_THE_VERIFIER');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM professional_request r WHERE r.verification_id = v.id AND r.status = 'PENDING') THEN
        PERFORM professional_refuse('VERIFICATION_CLOSED');
    END IF;
    INSERT INTO professional_verification_document (verification_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES (v.id, p_storage_key, p_sha256, p_size, p_type, p_name, actor) RETURNING id INTO new_id;
    PERFORM professional_record(v.user_id, 'EVIDENCE_ADDED', actor, NULL, NULL);
    RETURN new_id;
END;
$$;

-- One administrator stops an active professional at once. Reversible only by two.
CREATE FUNCTION professional_suspend(p_user uuid, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    p professional_profile := professional_lock(p_user, 'admin.professional.suspend');
BEGIN
    IF p.status <> 'ACTIVE' THEN
        PERFORM professional_refuse('INVALID_STATUS');
    END IF;
    IF NOT professional_reason_ok(p_reason) THEN
        PERFORM professional_refuse('REASON_REQUIRED');
    END IF;
    PERFORM professional_set_status(p_user, 'SUSPENDED');
    PERFORM professional_record(p_user, 'SUSPENDED', current_actor(), btrim(p_reason), NULL);
END;
$$;

-- A change that a second administrator decides.
CREATE FUNCTION professional_request_change(p_user uuid, p_kind text, p_add uuid[], p_remove uuid[], p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor   uuid := current_actor();
    p       professional_profile := professional_lock(p_user, 'admin.professional.suspend');
    current uuid[];
    r       uuid;
BEGIN
    IF NOT professional_reason_ok(p_reason) THEN
        PERFORM professional_refuse('REASON_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM professional_request x WHERE x.user_id = p_user AND x.status = 'PENDING') THEN
        PERFORM professional_refuse('REQUEST_ALREADY_OPEN');
    END IF;
    SELECT coalesce(array_agg(office_id), '{}') INTO current FROM professional_office WHERE user_id = p_user AND removed_at IS NULL;
    CASE p_kind
        WHEN 'REINSTATEMENT' THEN
            IF p.status <> 'SUSPENDED' THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
        WHEN 'REVOCATION' THEN
            IF p.status NOT IN ('ACTIVE', 'SUSPENDED') THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
        WHEN 'OFFICE_CHANGE' THEN
            IF p.status NOT IN ('ACTIVE', 'SUSPENDED') THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
            IF coalesce(cardinality(p_add), 0) + coalesce(cardinality(p_remove), 0) = 0
               OR NOT professional_offices_valid(coalesce(p_add, '{}')) OR NOT professional_offices_valid(coalesce(p_remove, '{}'))
               OR coalesce(p_add, '{}') && current OR NOT (coalesce(p_remove, '{}') <@ current)
               OR coalesce(p_add, '{}') && coalesce(p_remove, '{}')
               OR cardinality(current) - cardinality(coalesce(p_remove, '{}')) + cardinality(coalesce(p_add, '{}')) < 1 THEN
                PERFORM professional_refuse('OFFICES_INVALID');
            END IF;
        ELSE
            PERFORM professional_refuse('KIND_INVALID');
    END CASE;
    INSERT INTO professional_request (user_id, kind, offices_add, offices_remove, reason, requested_by)
    VALUES (p_user, p_kind, CASE WHEN p_kind = 'OFFICE_CHANGE' THEN coalesce(p_add, '{}') ELSE '{}' END,
            CASE WHEN p_kind = 'OFFICE_CHANGE' THEN coalesce(p_remove, '{}') ELSE '{}' END, btrim(p_reason), actor)
    RETURNING id INTO r;
    PERFORM professional_record(p_user, p_kind || '_REQUESTED', actor, btrim(p_reason), r);
    RETURN r;
END;
$$;

-- The second administrator's decision. Never the requester (the verifier, for an activation), never the
-- professional; within 30 days.
CREATE FUNCTION professional_decide(p_request uuid, p_approve boolean, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    r     professional_request;
    p     professional_profile;
    keys  int;
BEGIN
    SELECT * INTO r FROM professional_request WHERE id = p_request;
    IF r.id IS NULL OR NOT holds_permission(actor, 'admin.professional.approve') THEN
        PERFORM professional_refuse('REQUEST_NOT_FOUND');
    END IF;
    p := professional_lock(r.user_id, 'admin.professional.approve');
    SELECT * INTO r FROM professional_request WHERE id = p_request FOR UPDATE;
    IF r.status <> 'PENDING' THEN
        PERFORM professional_refuse('REQUEST_CLOSED');
    END IF;
    IF r.requested_by = actor THEN
        PERFORM professional_refuse('FOUR_EYES');
    END IF;
    IF r.requested_at < now() - interval '30 days' THEN
        PERFORM professional_refuse('REQUEST_EXPIRED');
    END IF;
    IF NOT p_approve AND NOT professional_reason_ok(p_reason) THEN
        PERFORM professional_refuse('REASON_REQUIRED');
    END IF;
    IF p_reason IS NOT NULL AND NOT professional_reason_ok(p_reason) THEN
        PERFORM professional_refuse('REASON_REQUIRED');
    END IF;
    IF p_approve THEN
        CASE r.kind
            WHEN 'ACTIVATION' THEN
                IF p.status <> 'VERIFIED' THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
                SELECT count(*) INTO keys FROM user_credentials c JOIN user_entities e ON e.id = c.user_entity_user_id WHERE e.app_user_id = r.user_id;
                IF keys < 2 THEN PERFORM professional_refuse('SECURITY_KEYS_REQUIRED'); END IF;
                INSERT INTO professional_office (user_id, office_id, added_by_request) SELECT r.user_id, x, r.id FROM unnest(r.offices_add) x;
                PERFORM professional_set_status(r.user_id, 'ACTIVE');
            WHEN 'REINSTATEMENT' THEN
                IF p.status <> 'SUSPENDED' THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
                PERFORM professional_set_status(r.user_id, 'ACTIVE');
            WHEN 'REVOCATION' THEN
                IF p.status NOT IN ('ACTIVE', 'SUSPENDED') THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
                UPDATE professional_office SET removed_at = clock_timestamp(), removed_by_request = r.id WHERE user_id = r.user_id AND removed_at IS NULL;
                PERFORM professional_set_status(r.user_id, 'REVOKED');
            WHEN 'OFFICE_CHANGE' THEN
                IF p.status NOT IN ('ACTIVE', 'SUSPENDED') THEN PERFORM professional_refuse('INVALID_STATUS'); END IF;
                IF EXISTS (SELECT 1 FROM unnest(r.offices_remove) x WHERE NOT EXISTS (
                               SELECT 1 FROM professional_office o WHERE o.user_id = r.user_id AND o.office_id = x AND o.removed_at IS NULL))
                   OR EXISTS (SELECT 1 FROM unnest(r.offices_add) x WHERE EXISTS (
                               SELECT 1 FROM professional_office o WHERE o.user_id = r.user_id AND o.office_id = x AND o.removed_at IS NULL)) THEN
                    PERFORM professional_refuse('OFFICES_INVALID');
                END IF;
                UPDATE professional_office SET removed_at = clock_timestamp(), removed_by_request = r.id
                 WHERE user_id = r.user_id AND removed_at IS NULL AND office_id = ANY (r.offices_remove);
                INSERT INTO professional_office (user_id, office_id, added_by_request) SELECT r.user_id, x, r.id FROM unnest(r.offices_add) x;
                IF NOT EXISTS (SELECT 1 FROM professional_office o WHERE o.user_id = r.user_id AND o.removed_at IS NULL) THEN
                    PERFORM professional_refuse('OFFICES_INVALID');
                END IF;
        END CASE;
    ELSIF r.kind = 'ACTIVATION' THEN
        PERFORM professional_set_status(r.user_id, 'REVOKED');
    END IF;
    UPDATE professional_request SET status = CASE WHEN p_approve THEN 'APPROVED' ELSE 'REJECTED' END, decided_by = actor,
                                    decided_at = clock_timestamp(), decision_reason = btrim(p_reason)
     WHERE id = r.id;
    PERFORM professional_record(r.user_id, r.kind || CASE WHEN p_approve THEN '_APPROVED' ELSE '_REJECTED' END, actor, btrim(p_reason), r.id);
END;
$$;

-- A request older than its time to live (SNIGF_ADMIN_REQUEST_TTL, at least an hour) closes as EXPIRED;
-- an expired activation sends the professional back to be verified again.
CREATE FUNCTION professional_request_expire(p_request uuid, p_ttl interval) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    r professional_request;
BEGIN
    IF NOT (holds_permission(current_actor(), 'admin.professional.read')) THEN
        PERFORM professional_refuse('PROFESSIONAL_FORBIDDEN');
    END IF;
    SELECT * INTO r FROM professional_request WHERE id = p_request FOR UPDATE;
    IF r.id IS NULL OR r.status <> 'PENDING' OR r.requested_at >= now() - greatest(coalesce(p_ttl, interval '30 days'), interval '1 hour') THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM professional_profile WHERE user_id = r.user_id FOR UPDATE;
    UPDATE professional_request SET status = 'EXPIRED', decided_at = clock_timestamp() WHERE id = r.id;
    IF r.kind = 'ACTIVATION' THEN
        PERFORM professional_set_status(r.user_id, 'REQUESTED');
    END IF;
    PERFORM professional_record(r.user_id, r.kind || '_EXPIRED', current_actor(), NULL, r.id);
    RETURN true;
END;
$$;

-- --- A professional's lost keys: reset confirmed by a supervisor of one of their offices ----------------

CREATE OR REPLACE FUNCTION mfa_reset_request_decide() RETURNS trigger
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
        SELECT 1 FROM (SELECT t.office_id FROM role_assignment t
                        WHERE t.user_id = NEW.user_id AND t.revoked_at IS NULL AND t.office_id IS NOT NULL
                       UNION ALL
                       SELECT * FROM professional_office_ids(NEW.user_id)) offices (office_id)
         WHERE holds_permission_at(NEW.decided_by, 'identity.reset.confirm', offices.office_id)) THEN
        RAISE EXCEPTION 'deciding a reset requires holding identity.reset.confirm at an office of the person'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.status = 'CONFIRMED' THEN
        PERFORM require_fresh(OLD.requested_at);
    END IF;
    RETURN NEW;
END;
$$;

-- Whether an enabled account is a professional with a profile; read as the owner, past row-level security.
CREATE FUNCTION is_professional(p_user uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM app_user u JOIN professional_profile p ON p.user_id = u.id WHERE u.id = p_user AND u.enabled)
$$;
REVOKE EXECUTE ON FUNCTION is_professional(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION is_professional(uuid) TO snigf_app;

-- The offices a professional serves, for the supervised reset's confirmer (not an administrator).
CREATE FUNCTION professional_office_ids(p_user uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT office_id FROM professional_office WHERE user_id = p_user AND removed_at IS NULL
$$;
REVOKE EXECUTE ON FUNCTION professional_office_ids(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION professional_office_ids(uuid) TO snigf_app;

-- A reset is requested for a staff account or a professional with a profile; nobody else has keys to reset.
CREATE OR REPLACE FUNCTION mfa_reset_request_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'a request is created pending' USING ERRCODE = 'check_violation';
    END IF;
    NEW.requested_at := now();
    PERFORM require_permission(NEW.requested_by, 'admin.mfa.reset', 'requesting a second-factor reset');
    IF NOT is_professional(NEW.user_id) THEN
        PERFORM require_staff(NEW.user_id);
    END IF;
    RETURN NEW;
END;
$$;

-- --- The orphan cleanup must never remove verification evidence -------------------------------------------

CREATE OR REPLACE FUNCTION document_storage_keys_known(p_keys text[]) RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT storage_key::text FROM document_version WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM professional_verification_document WHERE storage_key = ANY (p_keys)
$$;

-- --- Row-level security ------------------------------------------------------------------------------------

CREATE FUNCTION professional_admin(p_user uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT holds_permission(p_user, 'admin.professional.read')
$$;

-- Evidence is for those who verify or approve, never for the professional or a reader only.
CREATE FUNCTION professional_evidence_reader(p_user uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT holds_permission(p_user, 'admin.professional.verify') OR holds_permission(p_user, 'admin.professional.approve')
$$;

DROP POLICY professional_profile_own ON professional_profile;
CREATE POLICY professional_profile_read ON professional_profile FOR SELECT TO snigf_app
    USING (user_id = current_actor() OR professional_admin(current_actor()));

ALTER TABLE professional_verification ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_verification_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_office ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY professional_verification_read ON professional_verification FOR SELECT TO snigf_app
    USING (professional_evidence_reader(current_actor()));
CREATE POLICY professional_verification_document_read ON professional_verification_document FOR SELECT TO snigf_app
    USING (professional_evidence_reader(current_actor()));
CREATE POLICY professional_request_read ON professional_request FOR SELECT TO snigf_app
    USING (professional_admin(current_actor()));
CREATE POLICY professional_office_read ON professional_office FOR SELECT TO snigf_app
    USING (user_id = current_actor() OR professional_admin(current_actor()));
CREATE POLICY professional_history_read ON professional_history FOR SELECT TO snigf_app
    USING (user_id = current_actor() OR professional_admin(current_actor()));

-- --- Grants: reading, and the functions; never a direct write --------------------------------------------------

REVOKE ALL ON professional_verification, professional_verification_document, professional_request, professional_office,
              professional_history FROM PUBLIC;
GRANT SELECT ON professional_verification, professional_verification_document, professional_request, professional_office,
                professional_history TO snigf_app;

REVOKE EXECUTE ON FUNCTION professional_refuse(text), professional_record(uuid, text, uuid, text, uuid), professional_set_status(uuid, text),
                           professional_lock(uuid, text), professional_offices_valid(uuid[]), professional_reason_ok(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION professional_profile_create(uuid, text, text, text, text),
                           professional_verify(uuid, text, text, date, text, uuid[]),
                           professional_evidence_add(uuid, text, text, bigint, text, text), professional_suspend(uuid, text),
                           professional_request_change(uuid, text, uuid[], uuid[], text), professional_decide(uuid, boolean, text),
                           professional_request_expire(uuid, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION professional_profile_create(uuid, text, text, text, text),
                          professional_verify(uuid, text, text, date, text, uuid[]),
                          professional_evidence_add(uuid, text, text, bigint, text, text), professional_suspend(uuid, text),
                          professional_request_change(uuid, text, uuid[], uuid[], text), professional_decide(uuid, boolean, text),
                          professional_request_expire(uuid, interval), professional_admin(uuid), professional_evidence_reader(uuid) TO snigf_app;
