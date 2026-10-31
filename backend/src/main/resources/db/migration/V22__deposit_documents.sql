-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V22: the documents of a counter deposit (Slice 2, ADR-040 §4.7, decided 31 Oct 2026), owned by the
-- register and independent of any application. DEMO only. The files use the storage and the checks of
-- ADR-033; only their records are the register's.
--
--   deposit_document   one file of a deposit: category, display name, type read from its bytes, size,
--                      SHA-256, storage key, uploader, time. Never updated, never deleted. A correction
--                      is a new row naming the row it replaces, with a reason; the original stays.
--
-- Storing a document changes nothing else: no inscription, no status, no holder. A deposit takes
-- documents only while it is HOLDING or IN_CONFLICT. A TRANSFER's signed bytes name every document of
-- the deposit it completes, so evidence cannot change behind a signature. The orphan cleanup knows
-- these files, or it would remove them.

SET search_path = snigf, public;

CREATE TABLE deposit_document (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    transaction_id  uuid NOT NULL REFERENCES pending_transaction (id),
    office_id       uuid NOT NULL REFERENCES office (id),
    category        text NOT NULL CHECK (category IN ('DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER')),
    storage_key     char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256          char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes      bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type   text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name   text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by     uuid NOT NULL REFERENCES app_user (id),
    uploaded_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    replaces_id     uuid UNIQUE REFERENCES deposit_document (id),
    replace_reason  text CHECK (replace_reason IS NULL OR length(btrim(replace_reason)) BETWEEN 10 AND 2000),
    demo            boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_deposit_document_replacement CHECK ((replaces_id IS NULL) = (replace_reason IS NULL))
);
CREATE INDEX ix_deposit_document_transaction ON deposit_document (transaction_id, uploaded_at);

CREATE TRIGGER deposit_document_append_only BEFORE UPDATE OR DELETE ON deposit_document
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER deposit_document_no_truncate BEFORE TRUNCATE ON deposit_document
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Records a stored file. Nothing but this row is written.
CREATE FUNCTION deposit_document_add(p_transaction uuid, p_category text, p_storage_key text, p_sha256 text, p_size bigint, p_type text,
                                     p_name text, p_replaces uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    t        pending_transaction;
    replaced deposit_document;
    new_id   uuid;
BEGIN
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    IF t.id IS NULL OR NOT holds_at(actor, 'register.read', t.office_id) THEN
        PERFORM register_refuse('TRANSACTION_NOT_FOUND');
    END IF;
    -- The same lock as registration: a document and a signature over the documents never interleave.
    PERFORM 1 FROM land_title WHERE id = t.title_id FOR UPDATE;
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    IF NOT holds_at(actor, 'register.propose', t.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        PERFORM register_refuse('TRANSACTION_CLOSED');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF (SELECT count(*) FROM deposit_document d WHERE d.transaction_id = p_transaction) >= 100 THEN
        PERFORM register_refuse('DEPOSIT_DOCUMENT_LIMIT');
    END IF;
    IF p_replaces IS NOT NULL THEN
        SELECT * INTO replaced FROM deposit_document d WHERE d.id = p_replaces AND d.transaction_id = p_transaction;
        IF replaced.id IS NULL THEN
            PERFORM register_refuse('DOCUMENT_NOT_FOUND');
        END IF;
        IF EXISTS (SELECT 1 FROM deposit_document d WHERE d.replaces_id = p_replaces) THEN
            PERFORM register_refuse('DOCUMENT_ALREADY_REPLACED');
        END IF;
        IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
            PERFORM register_refuse('REASON_REQUIRED');
        END IF;
    ELSIF p_reason IS NOT NULL THEN
        PERFORM register_refuse('REPLACEMENT_REQUIRED');
    END IF;
    IF p_category IS NULL OR p_category NOT IN ('DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER')
       OR (replaced.id IS NOT NULL AND p_category <> replaced.category) THEN
        PERFORM register_refuse('CATEGORY_INVALID');
    END IF;
    INSERT INTO deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name,
                                  uploaded_by, replaces_id, replace_reason)
    VALUES (p_transaction, t.office_id, p_category, p_storage_key, p_sha256, p_size, p_type, p_name, actor, p_replaces, btrim(p_reason))
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- --- Signed bytes: a TRANSFER also names the documents of its deposit ----------------------------------
-- Appended only when there are some, so the bytes of every inscription signed before V22 are unchanged.

ALTER FUNCTION inscription_canonical(uuid) RENAME TO inscription_canonical_fields;

CREATE FUNCTION inscription_canonical(p_inscription uuid) RETURNS bytea
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    encoded bytea := inscription_canonical_fields(p_inscription);
    n       bigint;
    d       record;
BEGIN
    SELECT count(*) INTO n FROM inscription i JOIN deposit_document x ON x.transaction_id = i.completes_transaction_id WHERE i.id = p_inscription;
    IF n > 0 THEN
        encoded := encoded || canonical_text(n::text);
        FOR d IN SELECT x.id, x.sha256 FROM inscription i JOIN deposit_document x ON x.transaction_id = i.completes_transaction_id
                  WHERE i.id = p_inscription ORDER BY x.id LOOP
            encoded := encoded || canonical_text(d.id::text) || canonical_text(d.sha256::text);
        END LOOP;
    END IF;
    RETURN encoded;
END;
$$;

-- --- The orphan cleanup must never remove a deposit's file ------------------------------------------------

CREATE OR REPLACE FUNCTION document_storage_keys_known(p_keys text[]) RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT storage_key::text FROM document_version WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document WHERE storage_key = ANY (p_keys)
$$;

-- --- The audit subjects: a deposit's document belongs to its title ----------------------------------------

CREATE OR REPLACE FUNCTION audit_subjects(p_type text, p_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE
        WHEN p_id IS NULL THEN NULL
        WHEN p_type = 'APPLICATION' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id)) FROM application a WHERE a.id = p_id)
        WHEN p_type = 'DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM document_version v JOIN document d ON d.id = v.document_id JOIN application a ON a.id = d.application_id
              WHERE v.id = p_id)
        WHEN p_type = 'PAYMENT_REQUEST' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM payment_request pr JOIN application a ON a.id = pr.application_id WHERE pr.id = p_id)
        WHEN p_type = 'PAYMENT_RECORD' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM payment_record rec JOIN payment_request pr ON pr.id = rec.payment_request_id
               JOIN application a ON a.id = pr.application_id WHERE rec.id = p_id)
        WHEN p_type = 'ISSUED_DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM issued_document d JOIN application a ON a.id = d.subject_id WHERE d.id = p_id)
        WHEN p_type = 'VERIFICATION_INCIDENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM verification_incident i JOIN issued_document d ON d.id = i.issued_document_id
               JOIN application a ON a.id = d.subject_id WHERE i.id = p_id)
        WHEN p_type = 'PARCEL' THEN
            (SELECT jsonb_build_array(audit_subject('PARCEL', p.id, p.office_id)) FROM parcel p WHERE p.id = p_id)
        WHEN p_type = 'CONFLICT_ALERT' THEN
            (SELECT jsonb_agg(audit_subject('PARCEL', p.id, p.office_id) ORDER BY p.id)
               FROM conflict_alert c JOIN parcel p ON p.id IN (c.parcel_a_id, c.parcel_b_id) WHERE c.id = p_id)
        WHEN p_type = 'LAND_TITLE' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.id, t.office_id)) FROM land_title t WHERE t.id = p_id)
        WHEN p_type = 'INSCRIPTION' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', coalesce(i.title_id, i.new_title_id), i.office_id))
               FROM inscription i WHERE i.id = p_id)
        WHEN p_type = 'PENDING_TRANSACTION' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id)) FROM pending_transaction t WHERE t.id = p_id)
        WHEN p_type = 'TRANSACTION_CONFLICT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', c.title_id, c.office_id)) FROM transaction_conflict c WHERE c.id = p_id)
        WHEN p_type = 'DEPOSIT_DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id))
               FROM deposit_document d JOIN pending_transaction t ON t.id = d.transaction_id WHERE d.id = p_id)
        ELSE NULL
    END
$$;

-- --- Row-level security and grants: read at the conservation; write only through the function ---------------

ALTER TABLE deposit_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_document_read ON deposit_document FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));

REVOKE ALL ON deposit_document FROM PUBLIC;
GRANT SELECT ON deposit_document TO snigf_app;
REVOKE EXECUTE ON FUNCTION inscription_canonical(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION deposit_document_add(uuid, text, text, text, bigint, text, text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION deposit_document_add(uuid, text, text, text, bigint, text, text, uuid, text) TO snigf_app;
