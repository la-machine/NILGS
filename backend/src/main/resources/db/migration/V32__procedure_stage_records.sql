-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V32: stage records and the procedure's gate (Slice 4, ADR-042 step 3, decisions D23 and D26). DEMO only.
--
--   procedure_stage_record   one record of a stage of a case's procedure: outcome (COMPLETED, or NOT_SATISFIED with
--                            the reason in the finding), the finding, the stage's details, the cited stored versions of
--                            the case's documents, the officer and the time. Append-only. A correction is a new record
--                            that names the one it replaces (supersedes_id) and says why; the chain per case and stage
--                            is linear, so the latest record is the one nobody supersedes, and every earlier one stays.
--   procedure_stage_file     a file the officer attaches to a record (ADR-033 checks; its own retention class, D18).
--   procedure_stage_record_add()  the only writer: the stage's permission at the case's office, the case under review,
--                            never the applicant, never the case's reviewer or decision authority, the commission's
--                            report never by the officer of the field visit it reports on (D26). PC001 with the reason.
--   procedure_gate_problems() what stops a case's approval: a stage of its procedure never recorded, not satisfied, a
--                            report on a superseded visit, a parcel changed since its cadastral review.
-- Gates (new triggers, after the existing guards, so a malformed step is still reported as such):
--   review_step   RECOMMEND_APPROVAL refused while the gate has problems; a refusal stays possible;
--   decision      APPROVED refused likewise, re-checked since a stage may have been corrected in between;
--   case_assignment  whoever recorded a stage of a case never reviews nor decides it.
-- Publication (V33) and procedure B's stages (V35) are refused here as not yet available.

SET search_path = snigf, public;

CREATE FUNCTION procedure_refuse(p_code text) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '%', p_code USING ERRCODE = 'PC001';
END;
$$;

INSERT INTO retention_class (code, description_en, description_fr) VALUES
    ('PROCEDURE_STAGE_EVIDENCE', 'A file an officer attached to a stage of a procedure (ADR-042), whatever the stage''s outcome',
     'Fichier joint par un agent à une étape d''une procédure (ADR-042), quelle que soit l''issue de l''étape');

-- --- Tables ---------------------------------------------------------------------------------------------

CREATE TABLE procedure_stage_record (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id     uuid NOT NULL REFERENCES application (id),
    office_id          uuid NOT NULL REFERENCES office (id),
    stage              text NOT NULL REFERENCES procedure_stage (code),
    outcome            text NOT NULL CHECK (outcome IN ('COMPLETED', 'NOT_SATISFIED')),
    finding            text NOT NULL CHECK (length(btrim(finding)) BETWEEN 10 AND 4000),
    details            jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object' AND pg_column_size(details) <= 16384),
    evidence_versions  uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(evidence_versions) <= 20),
    supersedes_id      uuid UNIQUE REFERENCES procedure_stage_record (id),
    correction_reason  text CHECK (correction_reason IS NULL OR length(btrim(correction_reason)) BETWEEN 10 AND 2000),
    recorded_by        uuid NOT NULL REFERENCES app_user (id),
    recorded_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    demo               boolean NOT NULL DEFAULT true CHECK (demo),
    -- A correction always says why; a first record has nothing to correct.
    CONSTRAINT ck_stage_record_correction CHECK ((supersedes_id IS NULL) = (correction_reason IS NULL))
);
-- One first record per case and stage; with supersedes_id unique, the chain is linear.
CREATE UNIQUE INDEX ux_stage_record_first ON procedure_stage_record (application_id, stage) WHERE supersedes_id IS NULL;
CREATE INDEX ix_stage_record_application ON procedure_stage_record (application_id, stage, recorded_at);
CREATE TRIGGER procedure_stage_record_append_only BEFORE UPDATE OR DELETE ON procedure_stage_record
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_stage_record_no_truncate BEFORE TRUNCATE ON procedure_stage_record
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE procedure_stage_file (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    record_id        uuid NOT NULL REFERENCES procedure_stage_record (id),
    storage_key      char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256           char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes       bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type    text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name    text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by      uuid NOT NULL REFERENCES app_user (id),
    uploaded_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    retention_class  text NOT NULL DEFAULT 'PROCEDURE_STAGE_EVIDENCE' REFERENCES retention_class (code)
);
CREATE INDEX ix_stage_file_record ON procedure_stage_file (record_id);
CREATE TRIGGER procedure_stage_file_append_only BEFORE UPDATE OR DELETE ON procedure_stage_file
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_stage_file_no_truncate BEFORE TRUNCATE ON procedure_stage_file
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE OR REPLACE FUNCTION retention_class_set() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    NEW.retention_class := CASE TG_TABLE_NAME
        WHEN 'professional_verification_document' THEN 'PROFESSIONAL_EVIDENCE'
        WHEN 'deposit_document' THEN 'DEPOSIT_DOCUMENT'
        WHEN 'deposit_document_replacement' THEN 'DEPOSIT_DOCUMENT_REPLACEMENT'
        WHEN 'procedure_stage_file' THEN 'PROCEDURE_STAGE_EVIDENCE'
    END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procedure_stage_file_retention BEFORE INSERT ON procedure_stage_file
    FOR EACH ROW EXECUTE FUNCTION retention_class_set();

-- The orphan cleanup (ADR-033) keeps every attached file.
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
    UNION ALL
    SELECT storage_key::text FROM procedure_stage_file WHERE storage_key = ANY (p_keys)
$$;

-- --- Reading ----------------------------------------------------------------------------------------------

-- The latest record of a stage of a case: the one no other record supersedes.
CREATE FUNCTION procedure_stage_latest(p_application uuid, p_stage text) RETURNS procedure_stage_record
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT r.* FROM procedure_stage_record r
     WHERE r.application_id = p_application AND r.stage = p_stage
       AND NOT EXISTS (SELECT 1 FROM procedure_stage_record n WHERE n.supersedes_id = r.id)
$$;

-- Whether the actor may read a case's stage records: the case's readers at its office, its assignees, and
-- whoever may record one of its stages. Never the applicant through this path.
CREATE FUNCTION procedure_readable(p_application uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND EXISTS (
        SELECT 1 FROM application a
         WHERE a.id = p_application AND a.office_id IS NOT NULL AND a.applicant_id <> p_actor
           AND (holds_at(p_actor, 'application.read', a.office_id)
                OR case_visible_to_assignee(a.id, a.office_id, a.state, p_actor)
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(coalesce(application_procedure(a.id) -> 'stages', '[]')) s
                             JOIN procedure_stage ps ON ps.code = s
                            WHERE holds_at(p_actor, ps.permission, a.office_id))))
$$;

-- What stops the approval of a case, as reason codes; empty for a case without a procedure.
CREATE FUNCTION procedure_gate_problems(p_application uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    proc      jsonb := application_procedure(p_application);
    problems  text[] := ARRAY[]::text[];
    stage     text;
    latest    procedure_stage_record;
    visit     procedure_stage_record;
    current_v int;
BEGIN
    IF proc IS NULL THEN
        RETURN problems;
    END IF;
    FOR stage IN SELECT value FROM jsonb_array_elements_text(proc -> 'stages') LOOP
        latest := procedure_stage_latest(p_application, stage);
        IF latest.id IS NULL THEN
            problems := problems || ('STAGE_MISSING:' || stage);
        ELSIF latest.outcome <> 'COMPLETED' THEN
            problems := problems || ('STAGE_NOT_SATISFIED:' || stage);
        ELSIF stage = 'COMMISSION_REPORT' THEN
            visit := procedure_stage_latest(p_application, 'FIELD_VISIT');
            IF visit.id IS NULL OR (latest.details ->> 'fieldVisitRecord')::uuid IS DISTINCT FROM visit.id THEN
                problems := problems || 'REPORT_ON_SUPERSEDED_VISIT'::text;
            END IF;
        ELSIF stage = 'CADASTRAL_REVIEW' THEN
            SELECT p.current_version_no INTO current_v FROM application a JOIN parcel p ON p.id = a.parcel_id WHERE a.id = p_application;
            IF (latest.details ->> 'parcelVersionNo')::int IS DISTINCT FROM current_v THEN
                problems := problems || 'PARCEL_CHANGED_SINCE_REVIEW'::text;
            END IF;
        END IF;
    END LOOP;
    RETURN problems;
END;
$$;

-- --- Writing: the only path -------------------------------------------------------------------------------

CREATE FUNCTION procedure_text_ok(p_value text, p_min int, p_max int) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_value IS NOT NULL AND length(btrim(p_value)) BETWEEN p_min AND p_max AND p_value !~ '[[:cntrl:]]'
$$;

-- A date of the form YYYY-MM-DD, or NULL.
CREATE FUNCTION procedure_date(p_value text) RETURNS date
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF p_value IS NULL OR p_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
        RETURN NULL;
    END IF;
    RETURN p_value::date;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$;

-- People named in a record: 1 to 12 objects with exactly the given keys, each a text of 2 to 120 characters.
CREATE FUNCTION procedure_people_ok(p_people jsonb, p_keys text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_typeof(p_people) = 'array' AND jsonb_array_length(p_people) BETWEEN 1 AND 12
       AND NOT EXISTS (
           SELECT 1 FROM jsonb_array_elements(p_people) m
            WHERE jsonb_typeof(m) <> 'object'
               OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(m) k) IS DISTINCT FROM (SELECT array_agg(k ORDER BY k) FROM unnest(p_keys) k)
               OR EXISTS (SELECT 1 FROM unnest(p_keys) k
                           WHERE jsonb_typeof(m -> k) IS DISTINCT FROM 'string' OR NOT procedure_text_ok(m ->> k, 2, 120)))
$$;

CREATE FUNCTION procedure_stage_record_add(p_application uuid, p_stage text, p_outcome text, p_finding text, p_details jsonb,
                                           p_evidence uuid[], p_supersedes uuid, p_correction_reason text, p_files jsonb)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    proc      jsonb;
    catalogue procedure_stage;
    latest    procedure_stage_record;
    visit     procedure_stage_record;
    given     jsonb := coalesce(p_details, '{}'::jsonb);
    details   jsonb;
    evidence  uuid[] := coalesce(p_evidence, '{}');
    files     jsonb := coalesce(p_files, '[]'::jsonb);
    f         jsonb;
    visited   date;
    reported  date;
    new_id    uuid;
BEGIN
    SELECT * INTO catalogue FROM procedure_stage WHERE code = p_stage;
    IF catalogue.code IS NULL THEN
        PERFORM procedure_refuse('STAGE_UNKNOWN');
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        PERFORM procedure_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, catalogue.permission, a.office_id) THEN
        PERFORM procedure_refuse('PROCEDURE_FORBIDDEN');
    END IF;
    -- The case's row lock: a record and the recommendation's move never interleave.
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    proc := application_procedure(a.id);
    IF proc IS NULL OR NOT (proc -> 'stages') ? p_stage THEN
        PERFORM procedure_refuse('STAGE_NOT_IN_PROCEDURE');
    END IF;
    IF p_stage IN ('PUBLICATION', 'TITLE_VERIFICATION', 'DUTIES_EVIDENCE') THEN
        PERFORM procedure_refuse('STAGE_NOT_YET_AVAILABLE');
    END IF;
    IF a.state <> 'UNDER_REVIEW' THEN
        PERFORM procedure_refuse('CASE_NOT_UNDER_REVIEW');
    END IF;
    IF actor = a.applicant_id THEN
        PERFORM procedure_refuse('APPLICANT_CANNOT_RECORD');
    END IF;
    IF actor IN (case_assignee(a.id, 'REVIEW'), case_assignee(a.id, 'DECISION')) THEN
        PERFORM procedure_refuse('ASSIGNEE_CANNOT_RECORD');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('COMPLETED', 'NOT_SATISFIED') THEN
        PERFORM procedure_refuse('OUTCOME_REQUIRED');
    END IF;
    IF NOT procedure_text_ok(p_finding, 10, 4000) THEN
        PERFORM procedure_refuse('FINDING_REQUIRED');
    END IF;

    -- Corrections: every record after the first names the latest one and says why.
    latest := procedure_stage_latest(a.id, p_stage);
    IF latest.id IS NULL AND p_supersedes IS NOT NULL THEN
        PERFORM procedure_refuse('NOTHING_TO_CORRECT');
    END IF;
    IF latest.id IS NOT NULL AND p_supersedes IS NULL THEN
        PERFORM procedure_refuse('STAGE_ALREADY_RECORDED');
    END IF;
    IF latest.id IS NOT NULL AND p_supersedes IS DISTINCT FROM latest.id THEN
        PERFORM procedure_refuse('STALE_RECORD');
    END IF;
    IF p_supersedes IS NOT NULL AND NOT procedure_text_ok(p_correction_reason, 10, 2000) THEN
        PERFORM procedure_refuse('CORRECTION_REASON_REQUIRED');
    END IF;

    -- Evidence: stored versions of the case's own documents, and at most five attached files.
    IF cardinality(evidence) > 20 OR EXISTS (SELECT 1 FROM unnest(evidence) v
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv JOIN document d ON d.id = dv.document_id
                                   WHERE dv.id = v AND d.application_id = a.id AND dv.status = 'STORED')) THEN
        PERFORM procedure_refuse('EVIDENCE_NOT_OF_CASE');
    END IF;
    IF jsonb_typeof(files) <> 'array' OR jsonb_array_length(files) > 5 THEN
        PERFORM procedure_refuse('TOO_MANY_FILES');
    END IF;

    -- The stage's own details; the database adds what it knows itself.
    IF jsonb_typeof(given) <> 'object' THEN
        PERFORM procedure_refuse('DETAILS_INVALID');
    END IF;
    CASE p_stage
    WHEN 'CADASTRAL_REVIEW' THEN
        IF given <> '{}'::jsonb THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        IF p_outcome = 'COMPLETED' AND application_open_alerts(a.id) > 0 THEN
            PERFORM procedure_refuse('OPEN_ALERT');
        END IF;
        SELECT jsonb_build_object('parcelId', p.id, 'parcelReference', p.reference, 'parcelVersionNo', p.current_version_no,
                                  'openAlerts', application_open_alerts(a.id))
          INTO details FROM parcel p WHERE p.id = a.parcel_id;
    WHEN 'FIELD_VISIT' THEN
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k NOT IN ('visitedOn', 'members')) THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        visited := procedure_date(given ->> 'visitedOn');
        IF visited IS NULL OR visited > current_date OR visited < a.submitted_at::date THEN
            PERFORM procedure_refuse('VISIT_DATE_INVALID');
        END IF;
        IF NOT procedure_people_ok(given -> 'members', ARRAY['name', 'capacity']) THEN
            PERFORM procedure_refuse('MEMBERS_INVALID');
        END IF;
        details := jsonb_build_object('visitedOn', visited::text, 'members', given -> 'members');
    WHEN 'COMMISSION_REPORT' THEN
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k NOT IN ('reportReference', 'reportDate', 'signatories')) THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        visit := procedure_stage_latest(a.id, 'FIELD_VISIT');
        IF visit.id IS NULL OR visit.outcome <> 'COMPLETED' THEN
            PERFORM procedure_refuse('FIELD_VISIT_REQUIRED');
        END IF;
        -- D26: the report is validated by someone other than the officer of the visit it reports on.
        IF visit.recorded_by = actor THEN
            PERFORM procedure_refuse('VISIT_RECORDER_CANNOT_REPORT');
        END IF;
        IF NOT procedure_text_ok(given ->> 'reportReference', 3, 60) THEN
            PERFORM procedure_refuse('REPORT_REFERENCE_REQUIRED');
        END IF;
        reported := procedure_date(given ->> 'reportDate');
        IF reported IS NULL OR reported > current_date OR reported < (visit.details ->> 'visitedOn')::date THEN
            PERFORM procedure_refuse('REPORT_DATE_INVALID');
        END IF;
        IF NOT procedure_people_ok(given -> 'signatories', ARRAY['name', 'capacity']) THEN
            PERFORM procedure_refuse('SIGNATORIES_INVALID');
        END IF;
        IF cardinality(evidence) = 0 AND jsonb_array_length(files) = 0 THEN
            PERFORM procedure_refuse('REPORT_EVIDENCE_REQUIRED');
        END IF;
        details := jsonb_build_object('reportReference', btrim(given ->> 'reportReference'), 'reportDate', reported::text,
                                      'signatories', given -> 'signatories', 'fieldVisitRecord', visit.id);
    END CASE;

    INSERT INTO procedure_stage_record (application_id, office_id, stage, outcome, finding, details, evidence_versions,
                                        supersedes_id, correction_reason, recorded_by)
    VALUES (a.id, a.office_id, p_stage, p_outcome, btrim(p_finding), details, evidence, p_supersedes,
            CASE WHEN p_supersedes IS NULL THEN NULL ELSE btrim(p_correction_reason) END, actor)
    RETURNING id INTO new_id;
    FOR f IN SELECT value FROM jsonb_array_elements(files) LOOP
        INSERT INTO procedure_stage_file (record_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, f ->> 'storageKey', f ->> 'sha256', (f ->> 'size')::bigint, f ->> 'type', f ->> 'name', actor);
    END LOOP;
    RETURN new_id;
END;
$$;

-- --- The gates ----------------------------------------------------------------------------------------------

CREATE FUNCTION procedure_gate_check(p_application uuid) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems text[] := procedure_gate_problems(p_application);
BEGIN
    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION 'PROCEDURE_INCOMPLETE: %', array_to_string(problems, ', ') USING ERRCODE = 'PC001';
    END IF;
END;
$$;

CREATE FUNCTION review_step_procedure_gate() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.kind = 'RECOMMENDATION' AND NEW.recommendation = 'RECOMMEND_APPROVAL' THEN
        PERFORM procedure_gate_check(NEW.application_id);
    END IF;
    RETURN NEW;
END;
$$;
-- Named after review_step_guard, so the guard runs first (triggers fire in name order).
CREATE TRIGGER review_step_procedure_gate BEFORE INSERT ON review_step
    FOR EACH ROW EXECUTE FUNCTION review_step_procedure_gate();

CREATE FUNCTION decision_procedure_gate() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.outcome = 'APPROVED' THEN
        PERFORM procedure_gate_check(NEW.application_id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_procedure_gate BEFORE INSERT ON decision
    FOR EACH ROW EXECUTE FUNCTION decision_procedure_gate();

CREATE FUNCTION case_assignment_procedure_separation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = NEW.application_id AND r.recorded_by = NEW.assignee_id) THEN
        PERFORM procedure_refuse('STAGE_RECORDER_CANNOT_REVIEW');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_assignment_procedure_separation BEFORE INSERT ON case_assignment
    FOR EACH ROW EXECUTE FUNCTION case_assignment_procedure_separation();

-- --- What the applicant sees: each stage of their case, its status and date; never who, never why ---------------

CREATE FUNCTION application_stage_progress(p_application uuid)
RETURNS TABLE (stage text, rank int, status text, at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT s.value, ps.rank,
           CASE WHEN l.id IS NULL THEN 'NOT_STARTED' ELSE l.outcome END,
           l.recorded_at
      FROM application a
      CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(application_procedure(a.id) -> 'stages', '[]')) s
      JOIN procedure_stage ps ON ps.code = s.value
      LEFT JOIN LATERAL (SELECT * FROM procedure_stage_latest(a.id, s.value)) l ON l.id IS NOT NULL
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor()))
     ORDER BY ps.rank
$$;

-- --- The officers' queue: cases under review at their offices with a stage they may record, still open ----------

CREATE FUNCTION procedure_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, stage text, stage_status text,
               submitted_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.id, a.reference, o.code, a.type_code, s.value,
           CASE WHEN l.id IS NULL THEN 'NOT_STARTED' ELSE l.outcome END, a.submitted_at
      FROM application a
      JOIN office o ON o.id = a.office_id
      CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(application_procedure(a.id) -> 'stages', '[]')) s
      JOIN procedure_stage ps ON ps.code = s.value
      LEFT JOIN LATERAL (SELECT * FROM procedure_stage_latest(a.id, s.value)) l ON l.id IS NOT NULL
     WHERE a.state = 'UNDER_REVIEW'
       AND holds_at(current_actor(), ps.permission, a.office_id)
       AND a.applicant_id <> current_actor()
       AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'REVIEW')
       AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'DECISION')
       AND (l.id IS NULL OR l.outcome <> 'COMPLETED')
     ORDER BY a.submitted_at, a.id, ps.rank
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;

-- Whether the actor may record this stage of this case now, for the screens; procedure_stage_record_add decides.
CREATE FUNCTION procedure_may_record(p_application uuid, p_stage text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM application a JOIN procedure_stage ps ON ps.code = p_stage
         WHERE a.id = p_application AND a.state = 'UNDER_REVIEW'
           AND (application_procedure(a.id) -> 'stages') ? p_stage
           AND p_stage NOT IN ('PUBLICATION', 'TITLE_VERIFICATION', 'DUTIES_EVIDENCE')
           AND holds_at(current_actor(), ps.permission, a.office_id)
           AND a.applicant_id <> current_actor()
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'REVIEW')
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'DECISION')
           AND (p_stage <> 'COMMISSION_REPORT' OR EXISTS (
                SELECT 1 FROM procedure_stage_latest(a.id, 'FIELD_VISIT') v
                 WHERE v.id IS NOT NULL AND v.outcome = 'COMPLETED' AND v.recorded_by <> current_actor())))
$$;

-- --- Row-level security and grants: read through procedure_readable; write only through the function -----------

ALTER TABLE procedure_stage_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_stage_record_read ON procedure_stage_record FOR SELECT TO snigf_app
    USING (procedure_readable(application_id, current_actor()));
ALTER TABLE procedure_stage_file ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_stage_file_read ON procedure_stage_file FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.id = record_id));

REVOKE ALL ON procedure_stage_record, procedure_stage_file FROM PUBLIC;
GRANT SELECT ON procedure_stage_record, procedure_stage_file TO snigf_app;
REVOKE ALL ON FUNCTION procedure_stage_record_add(uuid, text, text, text, jsonb, uuid[], uuid, text, jsonb),
    procedure_gate_problems(uuid), procedure_stage_latest(uuid, text), procedure_readable(uuid, uuid),
    application_stage_progress(uuid), procedure_queue(int, int), procedure_may_record(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION procedure_stage_record_add(uuid, text, text, text, jsonb, uuid[], uuid, text, jsonb),
    procedure_gate_problems(uuid), procedure_stage_latest(uuid, text), procedure_readable(uuid, uuid),
    application_stage_progress(uuid), procedure_queue(int, int), procedure_may_record(uuid, text) TO snigf_app;
