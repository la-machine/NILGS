-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V26: the restricted title lookup and professional deposits (Slice 3, ADR-041 step 4, §6 and §7). DEMO only.
--
--   pending_transaction.submitted_by_account  the professional who deposited, an authenticated actor; with
--                                             submitted_capacity, the capacity they acted in. A CHECK ties
--                                             received_by to COUNTER and these two to PROFESSIONAL_ACCOUNT.
--   transaction_deposit_professional()        one call: the transaction and its documents together, a DEED
--                                             required; any refusal records nothing and locks nothing
--   professional_title_lookup()               the number, its office, whether it accepts a deposit; nothing
--                                             else, and the same empty answer for unknown or elsewhere
--   professional_deposits(), professional_deposit_documents()
--                                             the professional's own deposits and their files; an open deposit
--                                             reads OPEN, never HOLDING or IN_CONFLICT
--   transaction_close(), deposit_document_add()
--                                             replaced: a professional also withdraws, and adds documents to,
--                                             their own open deposit; every other rule is unchanged
--
-- The V21 rules apply unchanged once a deposit is valid: HOLDING if none holds the title, otherwise IN_CONFLICT
-- with a conflict for conservators. Depositing registers nothing. The tables keep their staff-only row-level
-- security: professionals read through the functions above, which check the actor themselves.

SET search_path = snigf, public;

-- --- Who deposited ---------------------------------------------------------------------------------------

ALTER TABLE pending_transaction ADD COLUMN submitted_by_account uuid REFERENCES app_user (id);
ALTER TABLE pending_transaction ADD COLUMN submitted_capacity text CHECK (submitted_capacity IN ('NOTARY', 'SWORN_SURVEYOR'));
ALTER TABLE pending_transaction ALTER COLUMN received_by DROP NOT NULL;
ALTER TABLE pending_transaction ADD CONSTRAINT ck_transaction_channel CHECK (
    (channel = 'COUNTER' AND received_by IS NOT NULL AND submitted_by_account IS NULL AND submitted_capacity IS NULL)
    OR (channel = 'PROFESSIONAL_ACCOUNT' AND received_by IS NULL AND submitted_by_account IS NOT NULL
        AND submitted_capacity IS NOT NULL AND submitted_by_party_id IS NOT NULL));
CREATE INDEX ix_pending_transaction_account ON pending_transaction (submitted_by_account, deposited_at)
    WHERE submitted_by_account IS NOT NULL;

COMMENT ON COLUMN pending_transaction.submitted_by_party_id IS
    'The notary as a party: named at the counter (never an actor), or the depositing professional''s own party';

CREATE OR REPLACE FUNCTION pending_transaction_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.id, NEW.title_id, NEW.office_id, NEW.kind, NEW.channel, NEW.depositor_party_id, NEW.submitted_by_party_id, NEW.received_by,
        NEW.submitted_by_account, NEW.submitted_capacity, NEW.application_id, NEW.evidence_references, NEW.deposited_at, NEW.review_due_at)
       IS DISTINCT FROM
       (OLD.id, OLD.title_id, OLD.office_id, OLD.kind, OLD.channel, OLD.depositor_party_id, OLD.submitted_by_party_id, OLD.received_by,
        OLD.submitted_by_account, OLD.submitted_capacity, OLD.application_id, OLD.evidence_references, OLD.deposited_at, OLD.review_due_at) THEN
        RAISE EXCEPTION 'what was deposited never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        RAISE EXCEPTION 'transaction % is final', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT ((OLD.status = 'HOLDING' AND NEW.status IN ('REGISTERED', 'WITHDRAWN', 'REFUSED'))
                OR (OLD.status = 'IN_CONFLICT' AND NEW.status IN ('HOLDING', 'WITHDRAWN', 'REFUSED'))) THEN
        RAISE EXCEPTION 'a transaction moves only forward' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.overdue_recorded_at IS NOT NULL AND NEW.overdue_recorded_at IS DISTINCT FROM OLD.overdue_recorded_at THEN
        RAISE EXCEPTION 'an overdue lock is recorded once' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- The professional's own open or closed deposit, while they still hold the permission.
CREATE FUNCTION professional_owns_deposit(p_actor uuid, p_transaction pending_transaction, p_permission text) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_transaction.id IS NOT NULL AND p_transaction.submitted_by_account = p_actor
           AND holds_professional_at(p_actor, p_permission, NULL)
$$;

-- --- The lookup (§6) -----------------------------------------------------------------------------------------

-- One row for a title at an office the professional serves; none for an unknown number or another office.
CREATE FUNCTION professional_title_lookup(p_number text) RETURNS TABLE (id uuid, number text, office_code text, accepts_deposit boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.id, t.number, o.code, title_state(t.id, title_current_version(t.id))->>'status' <> 'CLOSED'
      FROM land_title t JOIN office o ON o.id = t.office_id
     WHERE t.number = upper(btrim(p_number))
       AND holds_professional_at(current_actor(), 'register.deposit.submit', t.office_id)
$$;

-- --- The deposit (§7) ------------------------------------------------------------------------------------------

-- p_depositor: {kind, fullName, identityKind, identityNumber}, the professional's client, recorded as a party of
-- the office (the same party again when every field matches). p_documents: [{category, storageKey, sha256,
-- sizeBytes, mediaType, name}], files already kept through the checks of ADR-033. Returns the transaction and,
-- when it arrives on a held title, the conflict it opened.
CREATE FUNCTION transaction_deposit_professional(p_title uuid, p_kind text, p_depositor jsonb, p_documents jsonb, p_review_after interval)
    RETURNS TABLE (transaction_id uuid, conflict_id uuid)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor      uuid := current_actor();
    title      land_title;
    profile    professional_profile;
    d_kind     text := btrim(p_depositor->>'kind');
    d_name     text := btrim(p_depositor->>'fullName');
    d_id_kind  text := btrim(p_depositor->>'identityKind');
    d_id_no    text := btrim(p_depositor->>'identityNumber');
    depositor  register_party;
    notary     uuid;
    party_name text;
    party_id_no text;
    holding    uuid;
    new_id     uuid;
    opened     uuid;
    doc        jsonb;
BEGIN
    -- The title row's lock orders every deposit on it, the duplicate check included.
    SELECT * INTO title FROM land_title WHERE land_title.id = p_title FOR UPDATE;
    -- Unknown, or at an office the professional does not serve: the same answer.
    IF title.id IS NULL OR NOT holds_professional_at(actor, 'register.deposit.submit', title.office_id) THEN
        PERFORM register_refuse('TITLE_NOT_FOUND');
    END IF;
    IF title_state(p_title, title_current_version(p_title))->>'status' = 'CLOSED' THEN
        PERFORM register_refuse('TITLE_CLOSED');
    END IF;
    IF p_review_after IS NULL OR p_review_after <= interval '0' THEN
        PERFORM register_refuse('REVIEW_PERIOD_INVALID');
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('SALE', 'GIFT', 'INHERITANCE') THEN
        PERFORM register_refuse('DEPOSIT_INVALID');
    END IF;
    IF d_kind IS NULL OR d_kind NOT IN ('NATURAL_PERSON', 'LEGAL_PERSON')
       OR d_name IS NULL OR length(d_name) NOT BETWEEN 3 AND 200 OR d_name NOT LIKE '%DEMO%'
       OR d_id_kind IS NULL OR d_id_kind !~ '^[A-Z_]{2,40}$'
       OR d_id_no IS NULL OR length(d_id_no) NOT BETWEEN 5 AND 64 OR d_id_no NOT LIKE '%DEMO%' THEN
        PERFORM register_refuse('DEPOSITOR_INVALID');
    END IF;
    IF p_documents IS NULL OR jsonb_typeof(p_documents) <> 'array' OR jsonb_array_length(p_documents) NOT BETWEEN 1 AND 20 THEN
        PERFORM register_refuse('DOCUMENTS_INVALID');
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_documents) x
                WHERE x->>'category' IS NULL OR x->>'category' NOT IN ('DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER')) THEN
        PERFORM register_refuse('CATEGORY_INVALID');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p_documents) x WHERE x->>'category' = 'DEED') THEN
        PERFORM register_refuse('DEED_REQUIRED');
    END IF;
    -- The holder rule: never on a title in which the professional, as a party linked to their account, has an interest.
    IF register_actor_interested(actor, p_title, NULL) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    -- The same deed, by the same professional, on the same title, while their earlier deposit is open.
    IF EXISTS (SELECT 1 FROM pending_transaction t JOIN deposit_document d ON d.transaction_id = t.id
                WHERE t.title_id = p_title AND t.submitted_by_account = actor AND t.status IN ('HOLDING', 'IN_CONFLICT')
                  AND d.category = 'DEED'
                  AND d.sha256 IN (SELECT x->>'sha256' FROM jsonb_array_elements(p_documents) x WHERE x->>'category' = 'DEED')) THEN
        PERFORM register_refuse('DUPLICATE_DEPOSIT');
    END IF;

    SELECT * INTO depositor FROM register_party rp
     WHERE rp.office_id = title.office_id AND rp.kind = d_kind AND rp.full_name = d_name
       AND rp.identity_kind = d_id_kind AND rp.identity_number = d_id_no
     ORDER BY rp.created_at, rp.id LIMIT 1;
    IF depositor.id IS NULL THEN
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, created_by)
        VALUES (title.office_id, d_kind, d_name, d_id_kind, d_id_no, actor)
        RETURNING * INTO depositor;
    ELSIF depositor.account_id = actor THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;

    -- The professional as a party of the office, linked to their account: the one recorded, or one from the profile.
    SELECT * INTO profile FROM professional_profile WHERE user_id = actor;
    SELECT rp.id INTO notary FROM register_party rp
     WHERE rp.office_id = title.office_id AND rp.kind = 'NOTARY' AND rp.account_id = actor
     ORDER BY rp.created_at DESC, rp.id DESC LIMIT 1;
    IF notary IS NULL THEN
        SELECT CASE WHEN u.display_name LIKE '%DEMO%' THEN btrim(u.display_name) ELSE left('DEMO ' || btrim(u.display_name), 200) END
          INTO party_name FROM app_user u WHERE u.id = actor;
        party_id_no := CASE WHEN length(profile.registration_number) >= 5 THEN profile.registration_number ELSE 'PRO-' || profile.registration_number END;
        IF party_name IS NULL OR length(party_name) < 3 OR length(party_id_no) > 64 THEN
            PERFORM register_refuse('NOTARY_PARTY_INVALID');
        END IF;
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, chamber, registration_number, jurisdiction,
                                    account_id, created_by)
        VALUES (title.office_id, 'NOTARY', party_name, 'PROFESSIONAL_REGISTRATION', party_id_no, profile.chamber, profile.registration_number,
                profile.jurisdiction, actor, actor)
        RETURNING id INTO notary;
    END IF;

    SELECT t.id INTO holding FROM pending_transaction t WHERE t.title_id = p_title AND t.status = 'HOLDING';
    -- With a lock freed but a conflict still open, a new deposit waits for the same decision.
    IF holding IS NULL THEN
        SELECT c.holding_id INTO holding FROM transaction_conflict c WHERE c.title_id = p_title AND c.status = 'OPEN' ORDER BY c.opened_at LIMIT 1;
    END IF;
    INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, submitted_by_party_id, received_by,
                                     submitted_by_account, submitted_capacity, evidence_references, status, review_due_at)
    VALUES (p_title, title.office_id, p_kind, 'PROFESSIONAL_ACCOUNT', depositor.id, notary, NULL, actor, profile.profession,
            ARRAY(SELECT left((x->>'category') || ': ' || coalesce(x->>'name', ''), 200) FROM jsonb_array_elements(p_documents) x),
            CASE WHEN holding IS NULL THEN 'HOLDING' ELSE 'IN_CONFLICT' END, clock_timestamp() + p_review_after)
    RETURNING id INTO new_id;
    FOR doc IN SELECT x FROM jsonb_array_elements(p_documents) x LOOP
        INSERT INTO deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, title.office_id, doc->>'category', doc->>'storageKey', doc->>'sha256', (doc->>'sizeBytes')::bigint, doc->>'mediaType',
                doc->>'name', actor);
    END LOOP;
    IF holding IS NOT NULL THEN
        INSERT INTO transaction_conflict (title_id, office_id, holding_id, arriving_id) VALUES (p_title, title.office_id, holding, new_id)
        RETURNING id INTO opened;
    END IF;
    RETURN QUERY SELECT new_id, opened;
END;
$$;

-- --- The professional's own deposits ---------------------------------------------------------------------------

-- Newest first. Open deposits read OPEN: whether the title is held, or by whom, is not the professional's to see.
CREATE FUNCTION professional_deposits(p_id uuid, p_limit int, p_offset int)
    RETURNS TABLE (id uuid, title_id uuid, title_number text, office_code text, kind text, status text, depositor_name text,
                   capacity text, deposited_at timestamptz, closed_at timestamptz, closed_reason text, documents bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.id, t.title_id, lt.number, o.code, t.kind,
           CASE WHEN t.status IN ('HOLDING', 'IN_CONFLICT') THEN 'OPEN' ELSE t.status END,
           dp.full_name, t.submitted_capacity, t.deposited_at, t.closed_at, t.closed_reason,
           (SELECT count(*) FROM deposit_document d WHERE d.transaction_id = t.id)
      FROM pending_transaction t
      JOIN land_title lt ON lt.id = t.title_id
      JOIN office o ON o.id = t.office_id
      JOIN register_party dp ON dp.id = t.depositor_party_id
     WHERE t.submitted_by_account = current_actor()
       AND holds_professional_at(current_actor(), 'register.deposit.read', NULL)
       AND (p_id IS NULL OR t.id = p_id)
     ORDER BY t.deposited_at DESC, t.id DESC
     LIMIT p_limit OFFSET p_offset
$$;

-- The files of one of the professional's own deposits; never another deposit's, a conflicting one included.
CREATE FUNCTION professional_deposit_documents(p_transaction uuid)
    RETURNS TABLE (id uuid, transaction_id uuid, category text, storage_key text, sha256 text, size_bytes bigint, detected_type text,
                   original_name text, uploaded_by_self boolean, uploaded_at timestamptz, replaces_id uuid, replace_reason text, replaced_by uuid)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT d.id, d.transaction_id, d.category, d.storage_key::text, d.sha256::text, d.size_bytes, d.detected_type, d.original_name,
           d.uploaded_by = current_actor(), d.uploaded_at, d.replaces_id, d.replace_reason,
           (SELECT r.id FROM deposit_document r WHERE r.replaces_id = d.id)
      FROM deposit_document d
      JOIN pending_transaction t ON t.id = d.transaction_id
     WHERE d.transaction_id = p_transaction AND t.submitted_by_account = current_actor()
       AND holds_professional_at(current_actor(), 'register.deposit.read', NULL)
     ORDER BY d.uploaded_at, d.id
$$;

-- --- Withdrawal and documents: the professional's branch beside the conservator's -------------------------------

CREATE OR REPLACE FUNCTION transaction_close(p_transaction uuid, p_outcome text, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    t     pending_transaction;
    own   boolean;
BEGIN
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    -- A professional withdraws their own deposit only; another one does not exist for them.
    own := professional_owns_deposit(actor, t, 'register.deposit.withdraw');
    IF t.id IS NULL OR NOT (holds_at(actor, 'register.read', t.office_id) OR own) THEN
        PERFORM register_refuse('TRANSACTION_NOT_FOUND');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('WITHDRAWN', 'REFUSED') THEN
        PERFORM register_refuse('OUTCOME_REQUIRED');
    END IF;
    PERFORM 1 FROM land_title WHERE id = t.title_id FOR UPDATE;
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction FOR UPDATE;
    IF NOT (holds_at(actor, CASE p_outcome WHEN 'REFUSED' THEN 'register.transaction.decide' ELSE 'register.propose' END, t.office_id)
            OR (own AND p_outcome = 'WITHDRAWN')) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        PERFORM register_refuse('TRANSACTION_CLOSED');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    UPDATE pending_transaction SET status = p_outcome, closed_by = actor, closed_at = clock_timestamp(), closed_reason = btrim(p_reason)
     WHERE id = p_transaction;
    -- A proposal that would have completed it can no longer be registered.
    UPDATE inscription SET status = 'REJECTED', closed_by = actor, closed_reason = left('The deposit it completes was closed: ' || btrim(p_reason), 2000)
     WHERE completes_transaction_id = p_transaction AND status IN ('PROPOSED', 'CONFIRMATION_PENDING');
END;
$$;

CREATE OR REPLACE FUNCTION deposit_document_add(p_transaction uuid, p_category text, p_storage_key text, p_sha256 text, p_size bigint, p_type text,
                                                p_name text, p_replaces uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    t        pending_transaction;
    own      boolean;
    replaced deposit_document;
    new_id   uuid;
BEGIN
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    own := professional_owns_deposit(actor, t, 'register.deposit.document');
    IF t.id IS NULL OR NOT (holds_at(actor, 'register.read', t.office_id) OR own) THEN
        PERFORM register_refuse('TRANSACTION_NOT_FOUND');
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

-- --- Grants: the functions only; the tables stay as V21 and V22 left them ---------------------------------------

REVOKE EXECUTE ON FUNCTION professional_owns_deposit(uuid, pending_transaction, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION professional_title_lookup(text),
                           transaction_deposit_professional(uuid, text, jsonb, jsonb, interval),
                           professional_deposits(uuid, int, int), professional_deposit_documents(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION professional_title_lookup(text),
                          transaction_deposit_professional(uuid, text, jsonb, jsonb, interval),
                          professional_deposits(uuid, int, int), professional_deposit_documents(uuid) TO snigf_app;
