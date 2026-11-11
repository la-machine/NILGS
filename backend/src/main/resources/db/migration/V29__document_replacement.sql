-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V29: replacing a formally submitted document needs a second person (Slice 3, ADR-041 step 13, decision D19,
-- confirmed reading 3 of 3 Nov 2026). DEMO only.
--
-- Deposit documents, both channels:
--   deposit_document_replacement            a request: the document to replace, the new file (already kept
--                                           through the checks of ADR-033), who asked, when and why. Pending,
--                                           then approved, rejected or withdrawn; never deleted, its file kept.
--   deposit_document_replacement_request()  the professional for their own deposit, or a conservator of the
--                                           office; replaces V22's immediate replacement, now refused.
--   deposit_document_replacement_decide()   a conservator holding register.confirm at the office, never the
--                                           requester, never a professional. Approval adds the deposit_document
--                                           row naming the original, under the title's lock; the original is
--                                           never changed.
--   deposit_document_replacement_withdraw() the requester, while it is pending.
--   register_apply()                        a TRANSFER is refused while a replacement of its deposit is pending
--                                           (REPLACEMENT_PENDING), so what a conservator signs never changes.
--
-- Application documents (ADR-033, ADR-034):
--   completeness_replacement                an information request names each document it asks to be replaced,
--                                           with the reason; its officer never uploaded that document.
--   document_version.replacement_id         a new version of a document that already has one, during an
--                                           information request, needs an unused replacement naming it in the
--                                           latest request, and records it. Set by the database.
--
-- Verification evidence (professional_verification_document) keeps no replacement path: it is only added.

SET search_path = snigf, public;

-- --- Deposit documents ---------------------------------------------------------------------------------------

CREATE TABLE deposit_document_replacement (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    document_id        uuid NOT NULL REFERENCES deposit_document (id),
    transaction_id     uuid NOT NULL REFERENCES pending_transaction (id),
    office_id          uuid NOT NULL REFERENCES office (id),
    storage_key        char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256             char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes         bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type      text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name      text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    reason             text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    requested_by       uuid NOT NULL REFERENCES app_user (id),
    requested_as       text NOT NULL CHECK (requested_as IN ('STAFF', 'PROFESSIONAL')),
    requested_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    status             text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    decided_by         uuid REFERENCES app_user (id),
    decided_at         timestamptz,
    decision_reason    text CHECK (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 10 AND 2000),
    result_document_id uuid UNIQUE REFERENCES deposit_document (id),
    demo               boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_replacement_decided CHECK ((status = 'PENDING') = (decided_at IS NULL) AND (status = 'PENDING') = (decided_by IS NULL)),
    CONSTRAINT ck_replacement_result CHECK ((status = 'APPROVED') = (result_document_id IS NOT NULL)),
    CONSTRAINT ck_replacement_rejection CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL),
    CONSTRAINT ck_replacement_four_eyes CHECK (status IN ('PENDING', 'WITHDRAWN') OR decided_by <> requested_by),
    CONSTRAINT ck_replacement_withdrawal CHECK (status <> 'WITHDRAWN' OR decided_by = requested_by)
);
-- One pending request per document: two could not both become its single replacement.
CREATE UNIQUE INDEX ux_deposit_document_replacement_pending ON deposit_document_replacement (document_id) WHERE status = 'PENDING';
CREATE INDEX ix_deposit_document_replacement_transaction ON deposit_document_replacement (transaction_id, requested_at);
CREATE INDEX ix_deposit_document_replacement_office ON deposit_document_replacement (office_id, requested_at) WHERE status = 'PENDING';

-- A request is decided once: only its decision columns, and only from PENDING.
CREATE FUNCTION deposit_document_replacement_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING'
       OR (NEW.id, NEW.document_id, NEW.transaction_id, NEW.office_id, NEW.storage_key, NEW.sha256, NEW.size_bytes, NEW.detected_type,
           NEW.original_name, NEW.reason, NEW.requested_by, NEW.requested_as, NEW.requested_at, NEW.demo)
          IS DISTINCT FROM
          (OLD.id, OLD.document_id, OLD.transaction_id, OLD.office_id, OLD.storage_key, OLD.sha256, OLD.size_bytes, OLD.detected_type,
           OLD.original_name, OLD.reason, OLD.requested_by, OLD.requested_as, OLD.requested_at, OLD.demo) THEN
        RAISE EXCEPTION 'a replacement request is decided once, and only its decision is written' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deposit_document_replacement_guard BEFORE UPDATE ON deposit_document_replacement
    FOR EACH ROW EXECUTE FUNCTION deposit_document_replacement_guard();
CREATE TRIGGER deposit_document_replacement_no_delete BEFORE DELETE ON deposit_document_replacement
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER deposit_document_replacement_no_truncate BEFORE TRUNCATE ON deposit_document_replacement
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- A replacement row names the request a second person approved, and who approved it. Rows replaced before V29
-- (one conservator, ADR-040 §4.7) keep what they had: NOT VALID checks every row written from now on.
ALTER TABLE deposit_document ADD COLUMN replacement_request_id uuid UNIQUE REFERENCES deposit_document_replacement (id);
ALTER TABLE deposit_document ADD COLUMN approved_by uuid REFERENCES app_user (id);
ALTER TABLE deposit_document ADD CONSTRAINT ck_deposit_document_approved
    CHECK (replaces_id IS NULL OR (replacement_request_id IS NOT NULL AND approved_by IS NOT NULL AND approved_by <> uploaded_by)) NOT VALID;
ALTER TABLE deposit_document ADD CONSTRAINT ck_deposit_document_request_replaces
    CHECK (replacement_request_id IS NULL OR replaces_id IS NOT NULL);

-- Adding a document is unchanged; replacing one is now a request (below).
CREATE OR REPLACE FUNCTION deposit_document_add(p_transaction uuid, p_category text, p_storage_key text, p_sha256 text, p_size bigint, p_type text,
                                                p_name text, p_replaces uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    t      pending_transaction;
    own    boolean;
    new_id uuid;
BEGIN
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    own := professional_owns_deposit(actor, t, 'register.deposit.document');
    IF t.id IS NULL OR NOT (holds_at(actor, 'register.read', t.office_id) OR own) THEN
        PERFORM register_refuse('TRANSACTION_NOT_FOUND');
    END IF;
    IF p_replaces IS NOT NULL OR p_reason IS NOT NULL THEN
        PERFORM register_refuse('REPLACEMENT_APPROVAL_REQUIRED');
    END IF;
    -- The same lock as registration: a document and a signature over the documents never interleave.
    PERFORM 1 FROM land_title WHERE id = t.title_id FOR UPDATE;
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    IF NOT (holds_at(actor, 'register.propose', t.office_id) OR own) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        PERFORM register_refuse('TRANSACTION_CLOSED');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF (SELECT count(*) FROM deposit_document d WHERE d.transaction_id = p_transaction)
       + (SELECT count(*) FROM deposit_document_replacement r WHERE r.transaction_id = p_transaction AND r.status = 'PENDING') >= 100 THEN
        PERFORM register_refuse('DEPOSIT_DOCUMENT_LIMIT');
    END IF;
    IF p_category IS NULL OR p_category NOT IN ('DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER') THEN
        PERFORM register_refuse('CATEGORY_INVALID');
    END IF;
    INSERT INTO deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES (p_transaction, t.office_id, p_category, p_storage_key, p_sha256, p_size, p_type, p_name, actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- Asks for a document to be replaced. The original stays the current document until another person approves.
CREATE FUNCTION deposit_document_replacement_request(p_document uuid, p_storage_key text, p_sha256 text, p_size bigint, p_type text,
                                                     p_name text, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    d      deposit_document;
    t      pending_transaction;
    own    boolean;
    new_id uuid;
BEGIN
    SELECT * INTO d FROM deposit_document WHERE id = p_document;
    SELECT * INTO t FROM pending_transaction WHERE id = d.transaction_id;
    own := professional_owns_deposit(actor, t, 'register.deposit.document');
    IF d.id IS NULL OR NOT (holds_at(actor, 'register.read', t.office_id) OR own) THEN
        PERFORM register_refuse('DOCUMENT_NOT_FOUND');
    END IF;
    PERFORM 1 FROM land_title WHERE id = t.title_id FOR UPDATE;
    SELECT * INTO t FROM pending_transaction WHERE id = d.transaction_id;
    IF NOT (holds_at(actor, 'register.propose', t.office_id) OR own) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        PERFORM register_refuse('TRANSACTION_CLOSED');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF EXISTS (SELECT 1 FROM deposit_document x WHERE x.replaces_id = d.id) THEN
        PERFORM register_refuse('DOCUMENT_ALREADY_REPLACED');
    END IF;
    IF EXISTS (SELECT 1 FROM deposit_document_replacement r WHERE r.document_id = d.id AND r.status = 'PENDING') THEN
        PERFORM register_refuse('REPLACEMENT_PENDING');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF (SELECT count(*) FROM deposit_document x WHERE x.transaction_id = t.id)
       + (SELECT count(*) FROM deposit_document_replacement r WHERE r.transaction_id = t.id AND r.status = 'PENDING') >= 100 THEN
        PERFORM register_refuse('DEPOSIT_DOCUMENT_LIMIT');
    END IF;
    INSERT INTO deposit_document_replacement (document_id, transaction_id, office_id, storage_key, sha256, size_bytes, detected_type, original_name,
                                              reason, requested_by, requested_as)
    VALUES (d.id, t.id, t.office_id, p_storage_key, p_sha256, p_size, p_type, p_name, btrim(p_reason), actor,
            CASE WHEN own THEN 'PROFESSIONAL' ELSE 'STAFF' END)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- The second person. Returns the new document on approval, NULL on rejection.
CREATE FUNCTION deposit_document_replacement_decide(p_replacement uuid, p_outcome text, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    r        deposit_document_replacement;
    d        deposit_document;
    t        pending_transaction;
    new_id   uuid;
BEGIN
    SELECT * INTO r FROM deposit_document_replacement WHERE id = p_replacement;
    IF r.id IS NULL OR NOT holds_at(actor, 'register.read', r.office_id) THEN
        PERFORM register_refuse('REPLACEMENT_NOT_FOUND');
    END IF;
    IF EXISTS (SELECT 1 FROM app_user u WHERE u.id = actor AND u.account_type = 'PROFESSIONAL') THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('APPROVED', 'REJECTED') THEN
        PERFORM register_refuse('OUTCOME_REQUIRED');
    END IF;
    -- The title's lock, as registration and deposit_document_add take it: an approval during a signature makes it fail.
    PERFORM 1 FROM land_title lt JOIN pending_transaction x ON x.title_id = lt.id WHERE x.id = r.transaction_id FOR UPDATE OF lt;
    SELECT * INTO r FROM deposit_document_replacement WHERE id = p_replacement FOR UPDATE;
    IF NOT holds_at(actor, 'register.confirm', r.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF r.status <> 'PENDING' THEN
        PERFORM register_refuse('REPLACEMENT_CLOSED');
    END IF;
    IF r.requested_by = actor THEN
        PERFORM register_refuse('REQUESTER_CANNOT_DECIDE');
    END IF;
    SELECT * INTO t FROM pending_transaction WHERE id = r.transaction_id;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF p_outcome = 'REJECTED' AND (p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000) THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF p_reason IS NOT NULL AND length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF p_outcome = 'APPROVED' THEN
        IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
            PERFORM register_refuse('TRANSACTION_CLOSED');
        END IF;
        SELECT * INTO d FROM deposit_document WHERE id = r.document_id;
        IF EXISTS (SELECT 1 FROM deposit_document x WHERE x.replaces_id = d.id) THEN
            PERFORM register_refuse('DOCUMENT_ALREADY_REPLACED');
        END IF;
        INSERT INTO deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name,
                                      uploaded_by, uploaded_at, replaces_id, replace_reason, replacement_request_id, approved_by)
        VALUES (r.transaction_id, r.office_id, d.category, r.storage_key, r.sha256, r.size_bytes, r.detected_type, r.original_name,
                r.requested_by, r.requested_at, d.id, r.reason, r.id, actor)
        RETURNING id INTO new_id;
    END IF;
    UPDATE deposit_document_replacement
       SET status = p_outcome, decided_by = actor, decided_at = clock_timestamp(), decision_reason = nullif(btrim(p_reason), ''),
           result_document_id = new_id
     WHERE id = r.id;
    RETURN new_id;
END;
$$;

-- The requester takes their request back while it waits; the file is kept.
CREATE FUNCTION deposit_document_replacement_withdraw(p_replacement uuid, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    r     deposit_document_replacement;
    t     pending_transaction;
BEGIN
    SELECT * INTO r FROM deposit_document_replacement WHERE id = p_replacement;
    SELECT * INTO t FROM pending_transaction WHERE id = r.transaction_id;
    IF r.id IS NULL OR NOT (holds_at(actor, 'register.read', r.office_id) OR professional_owns_deposit(actor, t, 'register.deposit.document')) THEN
        PERFORM register_refuse('REPLACEMENT_NOT_FOUND');
    END IF;
    SELECT * INTO r FROM deposit_document_replacement WHERE id = p_replacement FOR UPDATE;
    IF r.requested_by <> actor THEN
        PERFORM register_refuse('NOT_THE_REQUESTER');
    END IF;
    IF r.status <> 'PENDING' THEN
        PERFORM register_refuse('REPLACEMENT_CLOSED');
    END IF;
    IF p_reason IS NOT NULL AND length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    UPDATE deposit_document_replacement
       SET status = 'WITHDRAWN', decided_by = actor, decided_at = clock_timestamp(), decision_reason = nullif(btrim(p_reason), '')
     WHERE id = r.id;
END;
$$;

-- The professional's own requests; never another deposit's. No conservator is named.
CREATE FUNCTION professional_deposit_replacements(p_transaction uuid)
    RETURNS TABLE (id uuid, document_id uuid, sha256 text, size_bytes bigint, detected_type text, original_name text, reason text,
                   requested_by_self boolean, requested_at timestamptz, status text, decided_at timestamptz, decision_reason text,
                   result_document_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT r.id, r.document_id, r.sha256::text, r.size_bytes, r.detected_type, r.original_name, r.reason,
           r.requested_by = current_actor(), r.requested_at, r.status, r.decided_at, r.decision_reason, r.result_document_id
      FROM deposit_document_replacement r
      JOIN pending_transaction t ON t.id = r.transaction_id
     WHERE r.transaction_id = p_transaction AND t.submitted_by_account = current_actor()
       AND holds_professional_at(current_actor(), 'register.deposit.read', NULL)
     ORDER BY r.requested_at, r.id
$$;

-- The bytes of the professional's own request, for downloading what they sent.
CREATE FUNCTION professional_replacement_file(p_replacement uuid)
    RETURNS TABLE (id uuid, storage_key text, sha256 text, size_bytes bigint, detected_type text, original_name text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT r.id, r.storage_key::text, r.sha256::text, r.size_bytes, r.detected_type, r.original_name
      FROM deposit_document_replacement r
      JOIN pending_transaction t ON t.id = r.transaction_id
     WHERE r.id = p_replacement AND t.submitted_by_account = current_actor()
       AND holds_professional_at(current_actor(), 'register.deposit.read', NULL)
$$;

-- --- Registration waits for every pending replacement of its deposit -----------------------------------------

CREATE OR REPLACE FUNCTION register_apply(p_inscription uuid, p_actor uuid) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    i        inscription;
    title    uuid;
    state    jsonb;
    previous bytea;
    chain    bytea;
    s        record;
    next_no  int;
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription;
    title := coalesce(i.title_id, i.new_title_id);
    next_no := i.expected_version + 1;
    IF i.kind = 'TRANSFER' THEN
        IF NOT EXISTS (SELECT 1 FROM pending_transaction t WHERE t.id = i.completes_transaction_id AND t.title_id = title AND t.status = 'HOLDING') THEN
            PERFORM register_refuse('TRANSACTION_NOT_HOLDING');
        END IF;
        IF EXISTS (SELECT 1 FROM transaction_conflict c WHERE c.title_id = title AND c.status = 'OPEN') THEN
            PERFORM register_refuse('OPEN_CONFLICT');
        END IF;
        IF EXISTS (SELECT 1 FROM title_version v, jsonb_array_elements(v.restrictions) r
                    WHERE v.title_id = title AND v.version_no = i.expected_version AND (r->>'blocksTransfer')::boolean) THEN
            PERFORM register_refuse('BLOCKING_RESTRICTION');
        END IF;
        -- D19: what the signature binds must not change underneath it.
        IF EXISTS (SELECT 1 FROM deposit_document_replacement r WHERE r.transaction_id = i.completes_transaction_id AND r.status = 'PENDING') THEN
            PERFORM register_refuse('REPLACEMENT_PENDING');
        END IF;
    END IF;
    IF i.kind = 'CLOSURE' AND EXISTS (SELECT 1 FROM pending_transaction t WHERE t.title_id = title AND t.status IN ('HOLDING', 'IN_CONFLICT')) THEN
        PERFORM register_refuse('TITLE_LOCKED');
    END IF;
    SELECT p.hash INTO previous FROM inscription p WHERE p.title_id = title AND p.status = 'REGISTERED' AND p.number = i.expected_version;
    chain := coalesce(previous, ''::bytea) || inscription_canonical(i.id);
    FOR s IN SELECT officer_signature, server_signature FROM inscription_signature WHERE inscription_id = i.id ORDER BY act LOOP
        chain := chain || s.officer_signature || convert_to(s.server_signature, 'UTF8');
    END LOOP;
    IF i.kind = 'CREATION' THEN
        INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id)
        SELECT title,
               format('DEMO-TF-%s-%s-%s', o.code, lpad(q.n::text, 6, '0'), substr('ABCDEFGHJKMNPQRSTVWXYZ', 1 + (q.n % 22)::int, 1)),
               i.office_id, 'SYNTHETIC_DEMO', i.id
          FROM office o, (SELECT nextval('land_title_seq') AS n) q WHERE o.id = i.office_id;
    END IF;
    UPDATE inscription SET title_id = title, number = next_no, status = 'REGISTERED', registered_by = p_actor, registered_at = clock_timestamp(),
                           previous_hash = previous, hash = sha256(chain),
                           confirmed_by = CASE WHEN status = 'CONFIRMATION_PENDING' THEN p_actor END
     WHERE id = i.id;
    state := title_state(title, next_no);
    INSERT INTO title_version (title_id, version_no, inscription_id, status, area_m2, parcel_version_id, holders, charges, restrictions)
    VALUES (title, next_no, i.id, state->>'status', (state->>'areaM2')::numeric, (state->>'parcelVersion')::uuid,
            state->'holders', state->'charges', state->'restrictions');
    IF state->>'parcelVersion' IS NOT NULL THEN
        INSERT INTO title_parcel (title_id, version_no, parcel_version_id) VALUES (title, next_no, (state->>'parcelVersion')::uuid);
    END IF;
    IF i.kind = 'TRANSFER' THEN
        UPDATE pending_transaction SET status = 'REGISTERED', closed_by = p_actor, closed_at = clock_timestamp() WHERE id = i.completes_transaction_id;
    END IF;
END;
$$;

-- --- Application documents: the information request names the document and the reason -----------------------

CREATE TABLE completeness_replacement (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    record_id    uuid NOT NULL REFERENCES completeness_record (id),
    document_id  uuid NOT NULL REFERENCES document (id),
    reason       text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (record_id, document_id)
);
CREATE INDEX ix_completeness_replacement_document ON completeness_replacement (document_id);

-- Written with its information request, by its officer, for a document of that application in a category asked
-- for, that has a version and that the officer never uploaded.
CREATE FUNCTION completeness_replacement_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    rec completeness_record;
    d   document;
BEGIN
    SELECT * INTO rec FROM completeness_record WHERE id = NEW.record_id;
    SELECT * INTO d FROM document WHERE id = NEW.document_id;
    IF rec.id IS NULL OR rec.kind <> 'INFO_REQUESTED' OR rec.actor_id IS DISTINCT FROM current_actor() OR rec.at < now() THEN
        RAISE EXCEPTION 'a replacement is named by its information request, in the same transaction, by its officer'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF d.id IS NULL OR d.application_id <> rec.application_id OR NOT (d.category = ANY (rec.requested)) THEN
        RAISE EXCEPTION 'the document is not one the request asks for' USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id) THEN
        RAISE EXCEPTION 'a document without a version has nothing to replace' USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id AND v.uploaded_by = rec.actor_id) THEN
        RAISE EXCEPTION 'the officer who uploaded a document never asks for its replacement' USING ERRCODE = 'insufficient_privilege';
    END IF;
    NEW.reason := btrim(NEW.reason);
    NEW.at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER completeness_replacement_guard BEFORE INSERT ON completeness_replacement
    FOR EACH ROW EXECUTE FUNCTION completeness_replacement_guard();
CREATE TRIGGER completeness_replacement_append_only BEFORE UPDATE OR DELETE ON completeness_replacement
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER completeness_replacement_no_truncate BEFORE TRUNCATE ON completeness_replacement
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Each replacement allows one version, which records it.
ALTER TABLE document_version ADD COLUMN replacement_id uuid UNIQUE REFERENCES completeness_replacement (id);

-- The replacement an upload may use now: named in the application's latest information request, still unused.
CREATE FUNCTION document_replacement_open(p_document uuid) RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT cr.id
      FROM document d
      JOIN application a ON a.id = d.application_id AND a.state = 'INFO_REQUESTED'
      JOIN completeness_replacement cr ON cr.document_id = d.id
     WHERE d.id = p_document
       AND cr.record_id = (SELECT r.id FROM completeness_record r WHERE r.application_id = a.id AND r.kind = 'INFO_REQUESTED'
                            ORDER BY r.at DESC, r.id DESC LIMIT 1)
       AND NOT EXISTS (SELECT 1 FROM document_version v WHERE v.replacement_id = cr.id)
$$;

CREATE OR REPLACE FUNCTION document_version_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    d     document;
    state text;
BEGIN
    SELECT * INTO d FROM document WHERE id = NEW.document_id;
    IF d.id IS NULL OR NOT document_accepted(d.application_id, d.category, current_actor())
       OR NEW.uploaded_by IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'this document does not take a new version from this person now'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Numbered here, one after the other, whatever the application asked.
    PERFORM pg_advisory_xact_lock(hashtext('snigf.document:' || NEW.document_id));
    -- D19: after submission, a document that has a version is replaced only as its information request names it.
    SELECT a.state INTO state FROM application a WHERE a.id = d.application_id;
    NEW.replacement_id := NULL;
    IF state <> 'DRAFT' AND EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = NEW.document_id) THEN
        NEW.replacement_id := document_replacement_open(NEW.document_id);
        IF NEW.replacement_id IS NULL THEN
            RAISE EXCEPTION 'REPLACEMENT_NOT_REQUESTED: no information request names this document for replacement'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    NEW.version_no := coalesce((SELECT max(version_no) FROM document_version WHERE document_id = NEW.document_id), 0) + 1;
    NEW.uploaded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- --- The orphan cleanup keeps every file a request names (D18) --------------------------------------------------

CREATE OR REPLACE FUNCTION document_storage_keys_known(p_keys text[]) RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT storage_key::text FROM document_version WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM professional_verification_document WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document_replacement WHERE storage_key = ANY (p_keys)
$$;

-- --- The catalogue: the professional now asks; a conservator approves --------------------------------------------

UPDATE permission SET description_en = 'Add documents to one''s own open deposit, or ask for one to be replaced',
                      description_fr = 'Ajouter des pièces à son propre dépôt ouvert, ou demander le remplacement d''une pièce'
 WHERE code = 'register.deposit.document';

-- --- Row-level security and grants ----------------------------------------------------------------------------

ALTER TABLE deposit_document_replacement ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_document_replacement_read ON deposit_document_replacement FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));

ALTER TABLE completeness_replacement ENABLE ROW LEVEL SECURITY;
CREATE POLICY completeness_replacement_read ON completeness_replacement FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM completeness_record r WHERE r.id = record_id));
CREATE POLICY completeness_replacement_add ON completeness_replacement FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM completeness_record r WHERE r.id = record_id));

REVOKE ALL ON deposit_document_replacement, completeness_replacement FROM PUBLIC;
GRANT SELECT ON deposit_document_replacement TO snigf_app;
GRANT SELECT, INSERT ON completeness_replacement TO snigf_app;

REVOKE EXECUTE ON FUNCTION deposit_document_replacement_guard(), completeness_replacement_guard(), document_replacement_open(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION deposit_document_replacement_request(uuid, text, text, bigint, text, text, text),
                           deposit_document_replacement_decide(uuid, text, text),
                           deposit_document_replacement_withdraw(uuid, text),
                           professional_deposit_replacements(uuid), professional_replacement_file(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION deposit_document_replacement_request(uuid, text, text, bigint, text, text, text),
                          deposit_document_replacement_decide(uuid, text, text),
                          deposit_document_replacement_withdraw(uuid, text),
                          professional_deposit_replacements(uuid), professional_replacement_file(uuid),
                          document_replacement_open(uuid) TO snigf_app;
