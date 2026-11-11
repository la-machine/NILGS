-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V30: retention metadata and legal holds, and still no deletion (Slice 3, ADR-041 step 14, decision D18,
-- confirmed reading 2 of 3 Nov 2026). DEMO only.
--
-- SNIGF holds no approved retention schedule, so it stores none, and nothing here deletes anything:
--   retention_class            a code per kind of record, a description, and the reference and approval date of a
--                              schedule. Both stay empty until a schedule is approved; while they are, the class reads
--                              NO_APPROVED_SCHEDULE_KEPT. There is no period column: a schedule's period, trigger event
--                              and disposal procedure need their own ADR and migration. Never updated, never deleted.
--   retention_class on records verification evidence, deposit documents (both channels) and replacement files (V29)
--                              each name their class, NOT NULL, set by the database on insert, never by the caller.
--   legal_hold                 one document, every document of a deposit, or every piece of evidence of a
--                              professional; a reason, who placed it and when. One authorised person places it.
--   legal_hold_release         asked by one authorised person, approved or rejected by another; a hold is never
--                              deleted, its release is recorded beside it.
--   record_disposal_allowed()  the one function a future disposal must call: false while no approved schedule covers
--                              the record's class, and false while any hold covers the record. Today always false.
--
-- Three permissions, never professional, never delegable: records.retention.read (BUSINESS: the auditor and the system
-- administrator both hold it, and separation of duties is kept), records.hold.place and records.hold.release
-- (ADMINISTRATION). Reading retention data opens no document: downloads keep their own permissions.

SET search_path = snigf, public;

-- --- Permissions ------------------------------------------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('records.retention.read', 'BUSINESS', false, ARRAY['ANY'],
     'Read retention classes, legal holds and their release history', 'Consulter les classes de conservation, les gels juridiques et l''historique de leur levée'),
    ('records.hold.place', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Place a legal hold on records', 'Placer un gel juridique sur des pièces'),
    ('records.hold.release', 'ADMINISTRATION', false, ARRAY['ANY'],
     'Ask for, approve or reject the release of a legal hold (two people)', 'Demander, approuver ou rejeter la levée d''un gel juridique (deux personnes)');
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('SYSTEM_ADMIN', 'records.retention.read', 'ANY'),
    ('SYSTEM_ADMIN', 'records.hold.place', 'ANY'),
    ('SYSTEM_ADMIN', 'records.hold.release', 'ANY'),
    ('AUDITOR', 'records.retention.read', 'ANY');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

CREATE FUNCTION retention_refuse(p_code text) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '%', p_code USING ERRCODE = 'RT001';
END;
$$;

-- --- Retention classes: no period, no schedule ---------------------------------------------------------------

CREATE TABLE retention_class (
    code                 text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    description_en       text NOT NULL CHECK (length(description_en) BETWEEN 1 AND 500),
    description_fr       text NOT NULL CHECK (length(description_fr) BETWEEN 1 AND 500),
    schedule_reference   text CHECK (schedule_reference IS NULL OR length(btrim(schedule_reference)) BETWEEN 3 AND 200),
    schedule_approved_on date,
    standing             text GENERATED ALWAYS AS (CASE WHEN schedule_reference IS NULL THEN 'NO_APPROVED_SCHEDULE_KEPT'
                                                        ELSE 'APPROVED_SCHEDULE' END) STORED,
    -- A class cannot claim a schedule without both its reference and its approval date.
    CONSTRAINT ck_retention_schedule CHECK ((schedule_reference IS NULL) = (schedule_approved_on IS NULL))
);
CREATE TRIGGER retention_class_append_only BEFORE UPDATE OR DELETE ON retention_class
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER retention_class_no_truncate BEFORE TRUNCATE ON retention_class
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

INSERT INTO retention_class (code, description_en, description_fr) VALUES
    ('PROFESSIONAL_EVIDENCE', 'Evidence of how a professional''s credential and identity were checked (ADR-041 §8)',
     'Preuves de la vérification de la qualité et de l''identité d''un professionnel (ADR-041 §8)'),
    ('DEPOSIT_DOCUMENT', 'A deposit''s document, counter or professional channel (ADR-040 §4.7)',
     'Pièce d''un dépôt, au guichet ou par un professionnel (ADR-040 §4.7)'),
    ('DEPOSIT_DOCUMENT_REPLACEMENT', 'The file of a request to replace a deposit''s document, whatever its outcome (ADR-041 D19)',
     'Fichier d''une demande de remplacement d''une pièce de dépôt, quelle qu''en soit l''issue (ADR-041 D19)');

-- Each covered record names its class; the database sets it, whatever the caller sent.
ALTER TABLE professional_verification_document ADD COLUMN retention_class text NOT NULL DEFAULT 'PROFESSIONAL_EVIDENCE' REFERENCES retention_class (code);
ALTER TABLE deposit_document ADD COLUMN retention_class text NOT NULL DEFAULT 'DEPOSIT_DOCUMENT' REFERENCES retention_class (code);
ALTER TABLE deposit_document_replacement ADD COLUMN retention_class text NOT NULL DEFAULT 'DEPOSIT_DOCUMENT_REPLACEMENT' REFERENCES retention_class (code);

CREATE FUNCTION retention_class_set() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    NEW.retention_class := CASE TG_TABLE_NAME
        WHEN 'professional_verification_document' THEN 'PROFESSIONAL_EVIDENCE'
        WHEN 'deposit_document' THEN 'DEPOSIT_DOCUMENT'
        WHEN 'deposit_document_replacement' THEN 'DEPOSIT_DOCUMENT_REPLACEMENT'
    END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER professional_verification_document_retention BEFORE INSERT ON professional_verification_document
    FOR EACH ROW EXECUTE FUNCTION retention_class_set();
CREATE TRIGGER deposit_document_retention BEFORE INSERT ON deposit_document
    FOR EACH ROW EXECUTE FUNCTION retention_class_set();
CREATE TRIGGER deposit_document_replacement_retention BEFORE INSERT ON deposit_document_replacement
    FOR EACH ROW EXECUTE FUNCTION retention_class_set();
-- A record's class never changes: deposit documents and evidence are never updated at all; a replacement request's
-- decision (V29) writes only its decision columns, and this keeps its class out of them.
CREATE FUNCTION retention_class_fixed() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.retention_class IS DISTINCT FROM OLD.retention_class THEN
        RAISE EXCEPTION 'a record''s retention class is set once, by the database' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deposit_document_replacement_retention_fixed BEFORE UPDATE ON deposit_document_replacement
    FOR EACH ROW EXECUTE FUNCTION retention_class_fixed();

-- --- Legal holds ------------------------------------------------------------------------------------------

CREATE TABLE legal_hold (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    subject_kind  text NOT NULL CHECK (subject_kind IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'DEPOSIT',
                                                        'PROFESSIONAL_EVIDENCE', 'PROFESSIONAL')),
    subject_id    uuid NOT NULL,
    reason        text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    placed_by     uuid NOT NULL REFERENCES app_user (id),
    placed_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    demo          boolean NOT NULL DEFAULT true CHECK (demo)
);
CREATE INDEX ix_legal_hold_subject ON legal_hold (subject_kind, subject_id);
CREATE TRIGGER legal_hold_append_only BEFORE UPDATE OR DELETE ON legal_hold
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER legal_hold_no_truncate BEFORE TRUNCATE ON legal_hold
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE legal_hold_release (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    hold_id          uuid NOT NULL REFERENCES legal_hold (id),
    reason           text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    requested_by     uuid NOT NULL REFERENCES app_user (id),
    requested_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by       uuid REFERENCES app_user (id),
    decided_at       timestamptz,
    decision_reason  text CHECK (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 10 AND 2000),
    CONSTRAINT ck_hold_release_decided CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_hold_release_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_hold_release_rejection CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL)
);
CREATE UNIQUE INDEX ux_legal_hold_release_open ON legal_hold_release (hold_id) WHERE status IN ('PENDING', 'APPROVED');
CREATE INDEX ix_legal_hold_release_hold ON legal_hold_release (hold_id, requested_at);

-- A release is decided once, and only its decision is written.
CREATE FUNCTION legal_hold_release_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING'
       OR (NEW.id, NEW.hold_id, NEW.reason, NEW.requested_by, NEW.requested_at)
          IS DISTINCT FROM (OLD.id, OLD.hold_id, OLD.reason, OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'a release is decided once, and only its decision is written' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER legal_hold_release_guard BEFORE UPDATE ON legal_hold_release
    FOR EACH ROW EXECUTE FUNCTION legal_hold_release_guard();
CREATE TRIGGER legal_hold_release_no_delete BEFORE DELETE ON legal_hold_release
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER legal_hold_release_no_truncate BEFORE TRUNCATE ON legal_hold_release
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Whether a hold is still in force: no approved release.
CREATE FUNCTION legal_hold_in_force(p_hold uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM legal_hold h WHERE h.id = p_hold)
       AND NOT EXISTS (SELECT 1 FROM legal_hold_release r WHERE r.hold_id = p_hold AND r.status = 'APPROVED')
$$;

-- Whether a subject exists, as its kind names it.
CREATE FUNCTION legal_hold_subject_exists(p_kind text, p_id uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_kind
        WHEN 'DEPOSIT_DOCUMENT' THEN EXISTS (SELECT 1 FROM deposit_document WHERE id = p_id)
        WHEN 'DEPOSIT_DOCUMENT_REPLACEMENT' THEN EXISTS (SELECT 1 FROM deposit_document_replacement WHERE id = p_id)
        WHEN 'DEPOSIT' THEN EXISTS (SELECT 1 FROM pending_transaction WHERE id = p_id)
        WHEN 'PROFESSIONAL_EVIDENCE' THEN EXISTS (SELECT 1 FROM professional_verification_document WHERE id = p_id)
        WHEN 'PROFESSIONAL' THEN EXISTS (SELECT 1 FROM professional_profile WHERE user_id = p_id)
        ELSE false
    END
$$;

-- The holds in force covering one record: on itself, or on what it belongs to.
CREATE FUNCTION record_holds(p_kind text, p_id uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT h.id FROM legal_hold h
     WHERE legal_hold_in_force(h.id)
       AND ((h.subject_kind = p_kind AND h.subject_id = p_id)
            OR (h.subject_kind = 'DEPOSIT' AND p_kind = 'DEPOSIT_DOCUMENT'
                AND h.subject_id = (SELECT transaction_id FROM deposit_document WHERE id = p_id))
            OR (h.subject_kind = 'DEPOSIT' AND p_kind = 'DEPOSIT_DOCUMENT_REPLACEMENT'
                AND h.subject_id = (SELECT transaction_id FROM deposit_document_replacement WHERE id = p_id))
            OR (h.subject_kind = 'PROFESSIONAL' AND p_kind = 'PROFESSIONAL_EVIDENCE'
                AND h.subject_id = (SELECT v.user_id FROM professional_verification_document d
                                      JOIN professional_verification v ON v.id = d.verification_id WHERE d.id = p_id)))
$$;

-- The record's class, as the database set it.
CREATE FUNCTION record_retention_class(p_kind text, p_id uuid) RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_kind
        WHEN 'DEPOSIT_DOCUMENT' THEN (SELECT retention_class FROM deposit_document WHERE id = p_id)
        WHEN 'DEPOSIT_DOCUMENT_REPLACEMENT' THEN (SELECT retention_class FROM deposit_document_replacement WHERE id = p_id)
        WHEN 'PROFESSIONAL_EVIDENCE' THEN (SELECT retention_class FROM professional_verification_document WHERE id = p_id)
    END
$$;

-- The one check a future disposal must make. False without an approved schedule for the class; false under any hold.
CREATE FUNCTION record_disposal_allowed(p_kind text, p_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce((SELECT c.schedule_reference IS NOT NULL FROM retention_class c WHERE c.code = record_retention_class(p_kind, p_id)), false)
       AND NOT EXISTS (SELECT 1 FROM record_holds(p_kind, p_id))
$$;

-- --- Placing and releasing -------------------------------------------------------------------------------

CREATE FUNCTION legal_hold_place(p_kind text, p_subject uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    new_id uuid;
BEGIN
    IF NOT holds_permission(actor, 'records.hold.place') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'DEPOSIT', 'PROFESSIONAL_EVIDENCE', 'PROFESSIONAL') THEN
        PERFORM retention_refuse('SUBJECT_KIND_INVALID');
    END IF;
    IF p_subject IS NULL OR NOT legal_hold_subject_exists(p_kind, p_subject) THEN
        PERFORM retention_refuse('SUBJECT_NOT_FOUND');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM retention_refuse('REASON_REQUIRED');
    END IF;
    INSERT INTO legal_hold (subject_kind, subject_id, reason, placed_by) VALUES (p_kind, p_subject, btrim(p_reason), actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

CREATE FUNCTION legal_hold_release_request(p_hold uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    new_id uuid;
BEGIN
    IF NOT holds_permission(actor, 'records.hold.release') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    PERFORM 1 FROM legal_hold WHERE id = p_hold FOR UPDATE;
    IF NOT FOUND THEN
        PERFORM retention_refuse('HOLD_NOT_FOUND');
    END IF;
    IF NOT legal_hold_in_force(p_hold) THEN
        PERFORM retention_refuse('HOLD_RELEASED');
    END IF;
    IF EXISTS (SELECT 1 FROM legal_hold_release r WHERE r.hold_id = p_hold AND r.status = 'PENDING') THEN
        PERFORM retention_refuse('RELEASE_PENDING');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM retention_refuse('REASON_REQUIRED');
    END IF;
    INSERT INTO legal_hold_release (hold_id, reason, requested_by) VALUES (p_hold, btrim(p_reason), actor) RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

CREATE FUNCTION legal_hold_release_decide(p_release uuid, p_outcome text, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    r     legal_hold_release;
BEGIN
    IF NOT holds_permission(actor, 'records.hold.release') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    SELECT * INTO r FROM legal_hold_release WHERE id = p_release FOR UPDATE;
    IF r.id IS NULL THEN
        PERFORM retention_refuse('RELEASE_NOT_FOUND');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('APPROVED', 'REJECTED') THEN
        PERFORM retention_refuse('OUTCOME_REQUIRED');
    END IF;
    IF r.status <> 'PENDING' THEN
        PERFORM retention_refuse('RELEASE_CLOSED');
    END IF;
    IF r.requested_by = actor THEN
        PERFORM retention_refuse('REQUESTER_CANNOT_DECIDE');
    END IF;
    IF (p_outcome = 'REJECTED' AND p_reason IS NULL) OR (p_reason IS NOT NULL AND length(btrim(p_reason)) NOT BETWEEN 10 AND 2000) THEN
        PERFORM retention_refuse('REASON_REQUIRED');
    END IF;
    UPDATE legal_hold_release SET status = p_outcome, decided_by = actor, decided_at = clock_timestamp(), decision_reason = btrim(p_reason)
     WHERE id = r.id;
END;
$$;

-- --- Reading: the retention record of one subject, and the holds -----------------------------------------------

-- The class and standing of a record, the holds in force over it, and whether disposal would be allowed.
CREATE FUNCTION record_retention(p_kind text, p_id uuid)
    RETURNS TABLE (retention_class text, standing text, schedule_reference text, schedule_approved_on date, holds_in_force bigint,
                   disposal_allowed boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT holds_permission(current_actor(), 'records.retention.read') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    IF p_kind NOT IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'PROFESSIONAL_EVIDENCE') OR NOT legal_hold_subject_exists(p_kind, p_id) THEN
        PERFORM retention_refuse('SUBJECT_NOT_FOUND');
    END IF;
    RETURN QUERY
        SELECT c.code, c.standing, c.schedule_reference, c.schedule_approved_on, (SELECT count(*) FROM record_holds(p_kind, p_id)),
               record_disposal_allowed(p_kind, p_id)
          FROM retention_class c WHERE c.code = record_retention_class(p_kind, p_id);
END;
$$;

-- --- The orphan cleanup: unchanged since V29, which already keeps every replacement file ----------------------------

-- --- Row-level security and grants: read with records.retention.read; write only through the functions ----------

ALTER TABLE retention_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE legal_hold_release ENABLE ROW LEVEL SECURITY;
CREATE POLICY retention_class_read ON retention_class FOR SELECT TO snigf_app USING (holds_permission(current_actor(), 'records.retention.read'));
CREATE POLICY legal_hold_read ON legal_hold FOR SELECT TO snigf_app USING (holds_permission(current_actor(), 'records.retention.read'));
CREATE POLICY legal_hold_release_read ON legal_hold_release FOR SELECT TO snigf_app USING (holds_permission(current_actor(), 'records.retention.read'));

REVOKE ALL ON retention_class, legal_hold, legal_hold_release FROM PUBLIC;
GRANT SELECT ON retention_class, legal_hold, legal_hold_release TO snigf_app;

REVOKE EXECUTE ON FUNCTION retention_refuse(text), retention_class_set(), retention_class_fixed(), legal_hold_release_guard(), legal_hold_in_force(uuid),
                           legal_hold_subject_exists(text, uuid), record_holds(text, uuid), record_retention_class(text, uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION record_disposal_allowed(text, uuid), legal_hold_place(text, uuid, text), legal_hold_release_request(uuid, text),
                           legal_hold_release_decide(uuid, text, text), record_retention(text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION legal_hold_in_force(uuid), record_disposal_allowed(text, uuid), legal_hold_place(text, uuid, text),
                          legal_hold_release_request(uuid, text), legal_hold_release_decide(uuid, text, text), record_retention(text, uuid) TO snigf_app;
