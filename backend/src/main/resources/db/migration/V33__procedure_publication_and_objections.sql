-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V33: publication and objections (Slice 4, ADR-042 step 4, D23, P2, P3). DEMO only: no period, notice or objection
-- here has legal effect.
--
--   procedure_notice           the notice a PUBLICATION record opens: a public number, the parcel's reference and
--                              locality, the office, the opening and the end of the objection period (the period is the
--                              DEMO setting SNIGF_PROCEDURE_OBJECTION_PERIOD, given by the application, bounded here).
--                              Append-only; a corrected publication opens a new notice naming the one it replaces.
--   procedure_objection        an objection received at the counter (P2), recorded by an officer for a named objector:
--                              their name, identity and interest, a statement, files; marked late after the period.
--                              Append-only; the objector's identity is readable only through procedure_objector_identity(),
--                              by those who receive or decide objections at the office (the application audits each read).
--   procedure_objection_decision  the decision on an objection, once: UPHELD or DISMISSED, with grounds; an upheld
--                              objection names the stages to examine again, and may say what the applicant must do
--                              (the only part the applicant reads, through application_procedure_instructions()).
--                              Never by the officer who recorded it.
--   procedure_objection_file   files received with an objection (ADR-033 checks; retention class of their own).
-- The gate (procedure_gate_problems, replaced) adds: the objection period still open, an objection not yet decided, a
-- stage to examine again after an upheld objection, a publication older than a stage it follows. An open objection
-- blocks every recommendation and decision, a refusal included (ADR-042 §3); an upheld one never refuses the case.
-- Also replaced: the stage status (OUTDATED and IN_PROGRESS, so the officers' queue and the applicant see that a
-- record no longer holds), the queue, procedure_may_record, the stage writer (PUBLICATION available) and the claim's
-- separation (whoever handled an objection never reviews nor decides the case).
-- procedure_registration_problems() states what blocks a registration from a case; V34 calls it.

SET search_path = snigf, public;

INSERT INTO retention_class (code, description_en, description_fr) VALUES
    ('PROCEDURE_OBJECTION_EVIDENCE', 'A file received with an objection to a published case (ADR-042), whatever its decision',
     'Fichier reçu avec une opposition à un dossier publié (ADR-042), quelle que soit la décision');

CREATE SEQUENCE procedure_notice_seq;
CREATE SEQUENCE procedure_objection_seq;

-- --- Tables ---------------------------------------------------------------------------------------------

CREATE TABLE procedure_notice (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    number            text NOT NULL UNIQUE CHECK (number ~ '^DEMO-PUB-[0-9]{4}-[0-9]{6,}$'),
    application_id    uuid NOT NULL REFERENCES application (id),
    record_id         uuid NOT NULL UNIQUE REFERENCES procedure_stage_record (id),
    office_id         uuid NOT NULL REFERENCES office (id),
    parcel_reference  text NOT NULL,
    locality          text NOT NULL,
    period            interval NOT NULL CHECK (period BETWEEN interval '1 minute' AND interval '365 days'),
    opens_at          timestamptz NOT NULL,
    closes_at         timestamptz NOT NULL,
    replaces_id       uuid UNIQUE REFERENCES procedure_notice (id),
    demo              boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_notice_period CHECK (closes_at = opens_at + period)
);
CREATE INDEX ix_notice_open ON procedure_notice (closes_at);
CREATE INDEX ix_notice_application ON procedure_notice (application_id);
CREATE TRIGGER procedure_notice_append_only BEFORE UPDATE OR DELETE ON procedure_notice
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_notice_no_truncate BEFORE TRUNCATE ON procedure_notice
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE procedure_objection (
    id                        uuid PRIMARY KEY DEFAULT uuidv7(),
    number                    text NOT NULL UNIQUE CHECK (number ~ '^DEMO-OBJ-[0-9]{4}-[0-9]{6,}$'),
    application_id            uuid NOT NULL REFERENCES application (id),
    notice_id                 uuid NOT NULL REFERENCES procedure_notice (id),
    office_id                 uuid NOT NULL REFERENCES office (id),
    -- Synthetic data only, as the register's parties (ADR-040): every name and identity number says DEMO.
    objector_name             text NOT NULL CHECK (length(btrim(objector_name)) BETWEEN 3 AND 200 AND objector_name LIKE '%DEMO%'),
    objector_identity_kind    text NOT NULL CHECK (objector_identity_kind ~ '^[A-Z_]{2,40}$'),
    objector_identity_number  text NOT NULL CHECK (length(objector_identity_number) BETWEEN 5 AND 64 AND objector_identity_number LIKE '%DEMO%'),
    objector_interest         text NOT NULL CHECK (objector_interest IN ('NEIGHBOUR', 'CLAIMANT', 'HEIR', 'CUSTOMARY_AUTHORITY', 'OTHER')),
    statement                 text NOT NULL CHECK (length(btrim(statement)) BETWEEN 20 AND 4000),
    received_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
    late                      boolean NOT NULL,
    recorded_by               uuid NOT NULL REFERENCES app_user (id),
    -- The counter's retry key: the same officer, case and key give back the first objection.
    client_key                uuid NOT NULL,
    demo                      boolean NOT NULL DEFAULT true CHECK (demo),
    UNIQUE (application_id, recorded_by, client_key)
);
CREATE INDEX ix_objection_application ON procedure_objection (application_id, received_at);
CREATE TRIGGER procedure_objection_append_only BEFORE UPDATE OR DELETE ON procedure_objection
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_objection_no_truncate BEFORE TRUNCATE ON procedure_objection
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE procedure_objection_decision (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    objection_id   uuid NOT NULL UNIQUE REFERENCES procedure_objection (id),
    outcome        text NOT NULL CHECK (outcome IN ('UPHELD', 'DISMISSED')),
    grounds        text NOT NULL CHECK (length(btrim(grounds)) BETWEEN 20 AND 4000),
    review_stages  text[] NOT NULL DEFAULT '{}' CHECK (cardinality(review_stages) <= 6),
    -- What the applicant is asked to do, in words written for them: never the objection's details (decision 5).
    applicant_instructions text CHECK (applicant_instructions IS NULL OR length(btrim(applicant_instructions)) BETWEEN 10 AND 1000),
    decided_by     uuid NOT NULL REFERENCES app_user (id),
    decided_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- An upheld objection says which stages are examined again; a dismissed one names none and instructs nobody.
    CONSTRAINT ck_objection_review CHECK ((outcome = 'UPHELD') = (cardinality(review_stages) > 0)),
    CONSTRAINT ck_objection_instructions CHECK (outcome = 'UPHELD' OR applicant_instructions IS NULL)
);
CREATE TRIGGER procedure_objection_decision_append_only BEFORE UPDATE OR DELETE ON procedure_objection_decision
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_objection_decision_no_truncate BEFORE TRUNCATE ON procedure_objection_decision
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE procedure_objection_file (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    objection_id     uuid NOT NULL REFERENCES procedure_objection (id),
    storage_key      char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256           char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes       bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type    text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name    text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by      uuid NOT NULL REFERENCES app_user (id),
    uploaded_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    retention_class  text NOT NULL DEFAULT 'PROCEDURE_OBJECTION_EVIDENCE' REFERENCES retention_class (code)
);
CREATE INDEX ix_objection_file_objection ON procedure_objection_file (objection_id);
CREATE TRIGGER procedure_objection_file_append_only BEFORE UPDATE OR DELETE ON procedure_objection_file
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_objection_file_no_truncate BEFORE TRUNCATE ON procedure_objection_file
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
        WHEN 'procedure_objection_file' THEN 'PROCEDURE_OBJECTION_EVIDENCE'
    END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procedure_objection_file_retention BEFORE INSERT ON procedure_objection_file
    FOR EACH ROW EXECUTE FUNCTION retention_class_set();

-- The orphan cleanup (ADR-033) keeps every file received with an objection.
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
    UNION ALL
    SELECT storage_key::text FROM procedure_objection_file WHERE storage_key = ANY (p_keys)
$$;

-- --- Stage status: what a stage's latest record still holds ----------------------------------------------

-- Objections still waiting for a decision.
CREATE FUNCTION procedure_objections_open(p_application uuid) RETURNS int
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT count(*)::int FROM procedure_objection o
     WHERE o.application_id = p_application
       AND NOT EXISTS (SELECT 1 FROM procedure_objection_decision d WHERE d.objection_id = o.id)
$$;

-- Why a stage's latest record does not hold (yet), as reason codes; empty when it holds.
--   STAGE_MISSING / STAGE_NOT_SATISFIED (as V32);
--   REPORT_ON_SUPERSEDED_VISIT, PARCEL_CHANGED_SINCE_REVIEW (as V32);
--   OBJECTION_REVIEW_PENDING:<stage>  an objection upheld after the record names this stage to examine again;
--   PUBLISHED_BEFORE_CHANGE           an earlier stage was recorded again after the publication: publish anew;
--   OBJECTION_PERIOD_OPEN             the publication's period is not over.
CREATE FUNCTION procedure_stage_problems(p_application uuid, p_stage text) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    latest    procedure_stage_record := procedure_stage_latest(p_application, p_stage);
    visit     procedure_stage_record;
    notice    procedure_notice;
    current_v int;
    problems  text[] := ARRAY[]::text[];
BEGIN
    IF latest.id IS NULL THEN
        RETURN ARRAY['STAGE_MISSING:' || p_stage];
    END IF;
    IF latest.outcome <> 'COMPLETED' THEN
        RETURN ARRAY['STAGE_NOT_SATISFIED:' || p_stage];
    END IF;
    IF p_stage = 'COMMISSION_REPORT' THEN
        visit := procedure_stage_latest(p_application, 'FIELD_VISIT');
        IF visit.id IS NULL OR (latest.details ->> 'fieldVisitRecord')::uuid IS DISTINCT FROM visit.id THEN
            problems := problems || 'REPORT_ON_SUPERSEDED_VISIT'::text;
        END IF;
    ELSIF p_stage = 'CADASTRAL_REVIEW' THEN
        SELECT p.current_version_no INTO current_v FROM application a JOIN parcel p ON p.id = a.parcel_id WHERE a.id = p_application;
        IF (latest.details ->> 'parcelVersionNo')::int IS DISTINCT FROM current_v THEN
            problems := problems || 'PARCEL_CHANGED_SINCE_REVIEW'::text;
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision d JOIN procedure_objection o ON o.id = d.objection_id
                WHERE o.application_id = p_application AND d.outcome = 'UPHELD' AND p_stage = ANY (d.review_stages)
                  AND d.decided_at > latest.recorded_at) THEN
        problems := problems || ('OBJECTION_REVIEW_PENDING:' || p_stage);
    END IF;
    IF p_stage = 'PUBLICATION' THEN
        IF EXISTS (SELECT 1 FROM jsonb_array_elements_text(coalesce(application_procedure(p_application) -> 'stages', '[]')) s
                     JOIN procedure_stage ps ON ps.code = s.value
                    WHERE ps.rank < (SELECT rank FROM procedure_stage WHERE code = 'PUBLICATION')
                      AND (procedure_stage_latest(p_application, s.value)).recorded_at > latest.recorded_at) THEN
            problems := problems || 'PUBLISHED_BEFORE_CHANGE'::text;
        END IF;
        SELECT * INTO notice FROM procedure_notice WHERE record_id = latest.id;
        IF notice.id IS NULL OR notice.closes_at > now() THEN
            problems := problems || 'OBJECTION_PERIOD_OPEN'::text;
        END IF;
    END IF;
    RETURN problems;
END;
$$;

-- A stage's status: NOT_STARTED, NOT_SATISFIED, COMPLETED, IN_PROGRESS (a publication whose period is open), or
-- OUTDATED (completed once, no longer holding: it is to be recorded again).
CREATE FUNCTION procedure_stage_status(p_application uuid, p_stage text) RETURNS text
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    latest   procedure_stage_record := procedure_stage_latest(p_application, p_stage);
    problems text[];
BEGIN
    IF latest.id IS NULL THEN
        RETURN 'NOT_STARTED';
    END IF;
    IF latest.outcome <> 'COMPLETED' THEN
        RETURN 'NOT_SATISFIED';
    END IF;
    problems := procedure_stage_problems(p_application, p_stage);
    RETURN CASE WHEN cardinality(problems) = 0 THEN 'COMPLETED'
                WHEN problems = ARRAY['OBJECTION_PERIOD_OPEN'] THEN 'IN_PROGRESS'
                ELSE 'OUTDATED' END;
END;
$$;

-- Whether every stage of the case's procedure ranked before this one is completed and still holds.
CREATE FUNCTION procedure_earlier_stages_complete(p_application uuid, p_stage text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(coalesce(application_procedure(p_application) -> 'stages', '[]')) s
          JOIN procedure_stage ps ON ps.code = s.value
         WHERE ps.rank < (SELECT rank FROM procedure_stage WHERE code = p_stage)
           AND procedure_stage_status(p_application, s.value) <> 'COMPLETED')
$$;

CREATE OR REPLACE FUNCTION procedure_gate_problems(p_application uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    proc      jsonb := application_procedure(p_application);
    problems  text[] := ARRAY[]::text[];
    stage     text;
BEGIN
    IF proc IS NULL THEN
        RETURN problems;
    END IF;
    FOR stage IN SELECT value FROM jsonb_array_elements_text(proc -> 'stages') LOOP
        problems := problems || procedure_stage_problems(p_application, stage);
    END LOOP;
    IF procedure_objections_open(p_application) > 0 THEN
        problems := problems || 'OBJECTION_OPEN'::text;
    END IF;
    RETURN problems;
END;
$$;

-- What blocks a registration from an approved case (V34 calls it before a CREATION or a TRANSFER from a case):
-- an objection not decided, or one upheld after the case's decision (it was not before the decision authority).
CREATE FUNCTION procedure_registration_problems(p_application uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a         application;
    decided   timestamptz;
    problems  text[] := ARRAY[]::text[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.state <> 'APPROVED' THEN
        problems := problems || 'CASE_NOT_APPROVED'::text;
    END IF;
    IF procedure_objections_open(p_application) > 0 THEN
        problems := problems || 'OBJECTION_OPEN'::text;
    END IF;
    SELECT d.at INTO decided FROM decision d WHERE d.application_id = p_application;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision od JOIN procedure_objection o ON o.id = od.objection_id
                WHERE o.application_id = p_application AND od.outcome = 'UPHELD' AND (decided IS NULL OR od.decided_at > decided)) THEN
        problems := problems || 'OBJECTION_UPHELD_AFTER_DECISION'::text;
    END IF;
    RETURN problems;
END;
$$;

-- --- The stage writer, publication now available ----------------------------------------------------------

CREATE OR REPLACE FUNCTION procedure_stage_record_add(p_application uuid, p_stage text, p_outcome text, p_finding text, p_details jsonb,
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
    period    interval;
    opened    timestamptz;
    replaced  uuid;
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
    -- The case's row lock: a record, an objection and the recommendation's move never interleave.
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    proc := application_procedure(a.id);
    IF proc IS NULL OR NOT (proc -> 'stages') ? p_stage THEN
        PERFORM procedure_refuse('STAGE_NOT_IN_PROCEDURE');
    END IF;
    IF p_stage IN ('TITLE_VERIFICATION', 'DUTIES_EVIDENCE') THEN
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
    WHEN 'PUBLICATION' THEN
        -- The officer says where and how the notice was posted (the finding); the period is the deployment's setting,
        -- passed by the application and bounded here; the parcel and locality are the database's.
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k <> 'objectionPeriod') THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        IF p_outcome <> 'COMPLETED' THEN
            PERFORM procedure_refuse('OUTCOME_INVALID');
        END IF;
        BEGIN
            period := (given ->> 'objectionPeriod')::interval;
        EXCEPTION WHEN others THEN
            period := NULL;
        END;
        IF period IS NULL OR period < interval '1 minute' OR period > interval '365 days' THEN
            PERFORM procedure_refuse('PERIOD_INVALID');
        END IF;
        IF NOT procedure_earlier_stages_complete(a.id, 'PUBLICATION') THEN
            PERFORM procedure_refuse('EARLIER_STAGES_INCOMPLETE');
        END IF;
        SELECT jsonb_build_object('objectionPeriod', period::text, 'parcelReference', p.reference, 'locality', pv.locality)
          INTO details
          FROM parcel p JOIN parcel_version pv ON pv.parcel_id = p.id AND pv.version_no = p.current_version_no
         WHERE p.id = a.parcel_id;
        IF details IS NULL THEN
            PERFORM procedure_refuse('CASE_PARCEL_MISSING');
        END IF;
    END CASE;

    INSERT INTO procedure_stage_record (application_id, office_id, stage, outcome, finding, details, evidence_versions,
                                        supersedes_id, correction_reason, recorded_by)
    VALUES (a.id, a.office_id, p_stage, p_outcome, btrim(p_finding), details, evidence, p_supersedes,
            CASE WHEN p_supersedes IS NULL THEN NULL ELSE btrim(p_correction_reason) END, actor)
    RETURNING id, recorded_at INTO new_id, opened;
    FOR f IN SELECT value FROM jsonb_array_elements(files) LOOP
        INSERT INTO procedure_stage_file (record_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, f ->> 'storageKey', f ->> 'sha256', (f ->> 'size')::bigint, f ->> 'type', f ->> 'name', actor);
    END LOOP;
    IF p_stage = 'PUBLICATION' THEN
        SELECT n.id INTO replaced FROM procedure_notice n WHERE n.record_id = p_supersedes;
        INSERT INTO procedure_notice (number, application_id, record_id, office_id, parcel_reference, locality, period,
                                      opens_at, closes_at, replaces_id)
        VALUES ('DEMO-PUB-' || to_char(opened AT TIME ZONE 'Africa/Douala', 'YYYY') || '-' || lpad(nextval('procedure_notice_seq')::text, 6, '0'),
                a.id, new_id, a.office_id, details ->> 'parcelReference', details ->> 'locality', period, opened, opened + period, replaced);
    END IF;
    RETURN new_id;
END;
$$;

-- --- Objections: recorded at the counter, decided by someone else -----------------------------------------

-- Records an objection for a named objector, or gives back the one the same officer recorded with the same key.
CREATE FUNCTION procedure_objection_add(p_application uuid, p_objector jsonb, p_statement text, p_files jsonb, p_client_key uuid)
RETURNS TABLE (objection_id uuid, replayed boolean)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    pub       procedure_stage_record;
    notice    procedure_notice;
    prior     procedure_objection;
    given     jsonb := coalesce(p_objector, '{}'::jsonb);
    files     jsonb := coalesce(p_files, '[]'::jsonb);
    f         jsonb;
    new_id    uuid;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        PERFORM procedure_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, 'procedure.objection.record', a.office_id) THEN
        PERFORM procedure_refuse('PROCEDURE_FORBIDDEN');
    END IF;
    IF p_client_key IS NULL THEN
        PERFORM procedure_refuse('IDEMPOTENCY_KEY_REQUIRED');
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    SELECT * INTO prior FROM procedure_objection o
     WHERE o.application_id = a.id AND o.recorded_by = actor AND o.client_key = p_client_key;
    IF prior.id IS NOT NULL THEN
        IF prior.statement IS DISTINCT FROM btrim(p_statement) OR prior.objector_name IS DISTINCT FROM btrim(given ->> 'name') THEN
            PERFORM procedure_refuse('IDEMPOTENCY_KEY_REUSED');
        END IF;
        RETURN QUERY SELECT prior.id, true;
        RETURN;
    END IF;
    pub := procedure_stage_latest(a.id, 'PUBLICATION');
    SELECT * INTO notice FROM procedure_notice WHERE record_id = pub.id;
    IF pub.id IS NULL OR notice.id IS NULL THEN
        PERFORM procedure_refuse('NOTICE_NOT_PUBLISHED');
    END IF;
    -- An objection is received until the case is closed; one after the period is recorded, marked late (P3).
    IF a.state NOT IN ('UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED') THEN
        PERFORM procedure_refuse('CASE_CLOSED');
    END IF;
    IF actor = a.applicant_id THEN
        PERFORM procedure_refuse('APPLICANT_CANNOT_RECORD');
    END IF;
    IF actor IN (case_assignee(a.id, 'REVIEW'), case_assignee(a.id, 'DECISION')) THEN
        PERFORM procedure_refuse('ASSIGNEE_CANNOT_RECORD');
    END IF;
    IF jsonb_typeof(given) <> 'object'
       OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(given) k) IS DISTINCT FROM ARRAY['identityKind', 'identityNumber', 'interest', 'name'] THEN
        PERFORM procedure_refuse('OBJECTOR_INVALID');
    END IF;
    IF NOT procedure_text_ok(given ->> 'name', 3, 200) OR (given ->> 'name') NOT LIKE '%DEMO%' THEN
        PERFORM procedure_refuse('OBJECTOR_NAME_INVALID');
    END IF;
    IF coalesce(given ->> 'identityKind', '') !~ '^[A-Z_]{2,40}$' OR NOT procedure_text_ok(given ->> 'identityNumber', 5, 64)
       OR (given ->> 'identityNumber') NOT LIKE '%DEMO%' THEN
        PERFORM procedure_refuse('OBJECTOR_IDENTITY_INVALID');
    END IF;
    IF coalesce(given ->> 'interest', '') NOT IN ('NEIGHBOUR', 'CLAIMANT', 'HEIR', 'CUSTOMARY_AUTHORITY', 'OTHER') THEN
        PERFORM procedure_refuse('OBJECTOR_INTEREST_INVALID');
    END IF;
    IF NOT procedure_text_ok(p_statement, 20, 4000) THEN
        PERFORM procedure_refuse('STATEMENT_REQUIRED');
    END IF;
    IF jsonb_typeof(files) <> 'array' OR jsonb_array_length(files) > 5 THEN
        PERFORM procedure_refuse('TOO_MANY_FILES');
    END IF;

    INSERT INTO procedure_objection (number, application_id, notice_id, office_id, objector_name, objector_identity_kind,
                                     objector_identity_number, objector_interest, statement, late, recorded_by, client_key)
    VALUES ('DEMO-OBJ-' || to_char(clock_timestamp() AT TIME ZONE 'Africa/Douala', 'YYYY') || '-' || lpad(nextval('procedure_objection_seq')::text, 6, '0'),
            a.id, notice.id, a.office_id, btrim(given ->> 'name'), given ->> 'identityKind', btrim(given ->> 'identityNumber'),
            given ->> 'interest', btrim(p_statement), clock_timestamp() > notice.closes_at, actor, p_client_key)
    RETURNING id INTO new_id;
    FOR f IN SELECT value FROM jsonb_array_elements(files) LOOP
        INSERT INTO procedure_objection_file (objection_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, f ->> 'storageKey', f ->> 'sha256', (f ->> 'size')::bigint, f ->> 'type', f ->> 'name', actor);
    END LOOP;
    RETURN QUERY SELECT new_id, false;
END;
$$;

-- Why the actor may not decide this objection now, or NULL when they may. Shared by the writer and the screens.
CREATE FUNCTION procedure_objection_decider_problem(p_objection uuid, p_actor uuid) RETURNS text
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    o  procedure_objection;
    a  application;
BEGIN
    SELECT * INTO o FROM procedure_objection WHERE id = p_objection;
    IF o.id IS NULL THEN
        RETURN 'OBJECTION_NOT_FOUND';
    END IF;
    SELECT * INTO a FROM application WHERE id = o.application_id;
    IF NOT holds_at(p_actor, 'procedure.objection.decide', o.office_id) THEN
        RETURN 'PROCEDURE_FORBIDDEN';
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision d WHERE d.objection_id = o.id) THEN
        RETURN 'OBJECTION_ALREADY_DECIDED';
    END IF;
    IF p_actor = o.recorded_by THEN
        RETURN 'OBJECTION_RECORDER_CANNOT_DECIDE';
    END IF;
    IF p_actor = a.applicant_id THEN
        RETURN 'APPLICANT_CANNOT_DECIDE';
    END IF;
    IF p_actor IN (case_assignee(a.id, 'REVIEW'), case_assignee(a.id, 'DECISION')) THEN
        RETURN 'ASSIGNEE_CANNOT_DECIDE';
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = a.id AND r.recorded_by = p_actor) THEN
        RETURN 'STAGE_RECORDER_CANNOT_DECIDE';
    END IF;
    RETURN NULL;
END;
$$;

-- Decides an objection: UPHELD (naming the stages to examine again, and what the applicant must do, if anything) or
-- DISMISSED, with grounds. Never refuses the case: it writes nothing on the application, its review or its decision.
CREATE FUNCTION procedure_objection_decide(p_objection uuid, p_outcome text, p_grounds text, p_review_stages text[],
                                           p_applicant_instructions text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    o         procedure_objection;
    a         application;
    stages    text[] := coalesce(p_review_stages, '{}');
    problem   text;
    new_id    uuid;
BEGIN
    SELECT * INTO o FROM procedure_objection WHERE id = p_objection;
    IF o.id IS NULL THEN
        PERFORM procedure_refuse('OBJECTION_NOT_FOUND');
    END IF;
    -- The case's row lock, as for a stage record: a decision and the case's moves never interleave.
    SELECT * INTO a FROM application WHERE id = o.application_id FOR UPDATE;
    problem := procedure_objection_decider_problem(o.id, actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('UPHELD', 'DISMISSED') THEN
        PERFORM procedure_refuse('OUTCOME_REQUIRED');
    END IF;
    IF NOT procedure_text_ok(p_grounds, 20, 4000) THEN
        PERFORM procedure_refuse('OBJECTION_GROUNDS_REQUIRED');
    END IF;
    IF p_outcome = 'DISMISSED' AND cardinality(stages) > 0 THEN
        PERFORM procedure_refuse('REVIEW_STAGES_ONLY_IF_UPHELD');
    END IF;
    IF p_outcome = 'UPHELD' AND cardinality(stages) = 0 THEN
        PERFORM procedure_refuse('REVIEW_STAGES_REQUIRED');
    END IF;
    IF p_applicant_instructions IS NOT NULL AND p_outcome <> 'UPHELD' THEN
        PERFORM procedure_refuse('INSTRUCTIONS_ONLY_IF_UPHELD');
    END IF;
    IF p_applicant_instructions IS NOT NULL AND NOT procedure_text_ok(p_applicant_instructions, 10, 1000) THEN
        PERFORM procedure_refuse('INSTRUCTIONS_INVALID');
    END IF;
    -- The stages examined again are stages of the case's own procedure, each named once.
    IF cardinality(stages) <> (SELECT count(DISTINCT s) FROM unnest(stages) s)
       OR EXISTS (SELECT 1 FROM unnest(stages) s WHERE NOT (application_procedure(a.id) -> 'stages') ? s) THEN
        PERFORM procedure_refuse('REVIEW_STAGE_INVALID');
    END IF;
    INSERT INTO procedure_objection_decision (objection_id, outcome, grounds, review_stages, applicant_instructions, decided_by)
    VALUES (o.id, p_outcome, btrim(p_grounds), stages, btrim(p_applicant_instructions), actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- --- The gates, replaced: an open objection holds every conclusion -----------------------------------------

CREATE OR REPLACE FUNCTION review_step_procedure_gate() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.kind = 'RECOMMENDATION' AND NEW.recommendation IN ('RECOMMEND_APPROVAL', 'RECOMMEND_REFUSAL')
       AND procedure_objections_open(NEW.application_id) > 0 THEN
        PERFORM procedure_refuse('OBJECTION_OPEN');
    END IF;
    IF NEW.kind = 'RECOMMENDATION' AND NEW.recommendation = 'RECOMMEND_APPROVAL' THEN
        PERFORM procedure_gate_check(NEW.application_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION decision_procedure_gate() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF procedure_objections_open(NEW.application_id) > 0 THEN
        PERFORM procedure_refuse('OBJECTION_OPEN');
    END IF;
    IF NEW.outcome = 'APPROVED' THEN
        PERFORM procedure_gate_check(NEW.application_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION case_assignment_procedure_separation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = NEW.application_id AND r.recorded_by = NEW.assignee_id) THEN
        PERFORM procedure_refuse('STAGE_RECORDER_CANNOT_REVIEW');
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_objection o WHERE o.application_id = NEW.application_id AND o.recorded_by = NEW.assignee_id)
       OR EXISTS (SELECT 1 FROM procedure_objection_decision d JOIN procedure_objection o ON o.id = d.objection_id
                   WHERE o.application_id = NEW.application_id AND d.decided_by = NEW.assignee_id) THEN
        PERFORM procedure_refuse('OBJECTION_HANDLER_CANNOT_REVIEW');
    END IF;
    RETURN NEW;
END;
$$;

-- --- Reading, replaced: statuses that say when a record no longer holds ------------------------------------

CREATE OR REPLACE FUNCTION application_stage_progress(p_application uuid)
RETURNS TABLE (stage text, rank int, status text, at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT s.value, ps.rank, procedure_stage_status(a.id, s.value), l.recorded_at
      FROM application a
      CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(application_procedure(a.id) -> 'stages', '[]')) s
      JOIN procedure_stage ps ON ps.code = s.value
      LEFT JOIN LATERAL (SELECT * FROM procedure_stage_latest(a.id, s.value)) l ON l.id IS NOT NULL
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor()))
     ORDER BY ps.rank
$$;

-- The officers' queue: a stage still to record (never recorded, not satisfied, or no longer holding).
CREATE OR REPLACE FUNCTION procedure_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, stage text, stage_status text,
               submitted_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT q.id, q.reference, q.code, q.type_code, q.stage, q.stage_status, q.submitted_at FROM (
        SELECT a.id, a.reference, o.code, a.type_code, s.value AS stage, procedure_stage_status(a.id, s.value) AS stage_status,
               a.submitted_at, ps.rank
          FROM application a
          JOIN office o ON o.id = a.office_id
          CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(application_procedure(a.id) -> 'stages', '[]')) s
          JOIN procedure_stage ps ON ps.code = s.value
         WHERE a.state = 'UNDER_REVIEW'
           AND holds_at(current_actor(), ps.permission, a.office_id)
           AND a.applicant_id <> current_actor()
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'REVIEW')
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'DECISION')) q
     WHERE q.stage_status IN ('NOT_STARTED', 'NOT_SATISFIED', 'OUTDATED')
     ORDER BY q.submitted_at, q.id, q.rank
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;

CREATE OR REPLACE FUNCTION procedure_may_record(p_application uuid, p_stage text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM application a JOIN procedure_stage ps ON ps.code = p_stage
         WHERE a.id = p_application AND a.state = 'UNDER_REVIEW'
           AND (application_procedure(a.id) -> 'stages') ? p_stage
           AND p_stage NOT IN ('TITLE_VERIFICATION', 'DUTIES_EVIDENCE')
           AND holds_at(current_actor(), ps.permission, a.office_id)
           AND a.applicant_id <> current_actor()
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'REVIEW')
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'DECISION')
           AND (p_stage <> 'COMMISSION_REPORT' OR EXISTS (
                SELECT 1 FROM procedure_stage_latest(a.id, 'FIELD_VISIT') v
                 WHERE v.id IS NOT NULL AND v.outcome = 'COMPLETED' AND v.recorded_by <> current_actor()))
           AND (p_stage <> 'PUBLICATION' OR procedure_earlier_stages_complete(a.id, 'PUBLICATION')))
$$;

-- Whether the actor may record an objection to this case now, for the screens; procedure_objection_add decides.
CREATE FUNCTION procedure_may_record_objection(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM application a
         WHERE a.id = p_application AND a.state IN ('UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED')
           AND holds_at(current_actor(), 'procedure.objection.record', a.office_id)
           AND a.applicant_id <> current_actor()
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'REVIEW')
           AND current_actor() IS DISTINCT FROM case_assignee(a.id, 'DECISION')
           AND EXISTS (SELECT 1 FROM procedure_notice n WHERE n.record_id = (procedure_stage_latest(a.id, 'PUBLICATION')).id))
$$;

-- Whether the actor may read an objector's identity: they receive or decide objections at its office (decision 1).
CREATE FUNCTION procedure_objector_identity_visible(p_objection uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND EXISTS (
        SELECT 1 FROM procedure_objection o JOIN application a ON a.id = o.application_id
         WHERE o.id = p_objection AND a.applicant_id <> p_actor
           AND (holds_at(p_actor, 'procedure.objection.record', o.office_id) OR holds_at(p_actor, 'procedure.objection.decide', o.office_id)))
$$;

-- The objector's identity, for one objection, to those who may read it; refused to anyone else. Never in a list: each
-- read is a request of its own, which the application audits (PROCEDURE_OBJECTOR_IDENTITY_READ).
CREATE FUNCTION procedure_objector_identity(p_objection uuid)
RETURNS TABLE (identity_kind text, identity_number text)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM procedure_objection WHERE id = p_objection) THEN
        PERFORM procedure_refuse('OBJECTION_NOT_FOUND');
    END IF;
    IF NOT procedure_objector_identity_visible(p_objection, current_actor()) THEN
        PERFORM procedure_refuse('PROCEDURE_FORBIDDEN');
    END IF;
    RETURN QUERY SELECT o.objector_identity_kind, o.objector_identity_number FROM procedure_objection o WHERE o.id = p_objection;
END;
$$;

-- A case's objections for its readers: never the objector's identity, only whether this reader may ask for it.
CREATE FUNCTION procedure_objections(p_application uuid)
RETURNS TABLE (id uuid, number text, notice_number text, objector_name text, objector_interest text, identity_visible boolean,
               statement text, received_at timestamptz, late boolean, recorded_by uuid,
               recorded_by_name text, outcome text, grounds text, review_stages text[], applicant_instructions text, decided_by uuid,
               decided_by_name text, decided_at timestamptz, may_decide boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT o.id, o.number, n.number, o.objector_name, o.objector_interest,
           procedure_objector_identity_visible(o.id, current_actor()),
           o.statement, o.received_at, o.late, o.recorded_by, ru.display_name,
           d.outcome, d.grounds, d.review_stages, d.applicant_instructions, d.decided_by, du.display_name, d.decided_at,
           procedure_objection_decider_problem(o.id, current_actor()) IS NULL
      FROM procedure_objection o
      JOIN procedure_notice n ON n.id = o.notice_id
      JOIN app_user ru ON ru.id = o.recorded_by
      LEFT JOIN procedure_objection_decision d ON d.objection_id = o.id
      LEFT JOIN app_user du ON du.id = d.decided_by
     WHERE o.application_id = p_application AND procedure_readable(p_application, current_actor())
     ORDER BY o.received_at, o.id
$$;

-- What the applicant is told when an upheld objection asks for more work (decision 5): the stages to carry out again,
-- the publication anew after them, and what the applicant must do, if anything; whether any of it is still to do.
-- Never the objection, its number, the objector, the statement or the grounds.
CREATE FUNCTION application_procedure_instructions(p_application uuid)
RETURNS TABLE (stages text[], action text, at timestamptz, pending boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN (application_procedure(a.id) -> 'stages') ? 'PUBLICATION' AND NOT 'PUBLICATION' = ANY (d.review_stages)
                THEN d.review_stages || 'PUBLICATION'::text ELSE d.review_stages END,
           d.applicant_instructions, d.decided_at,
           EXISTS (SELECT 1 FROM unnest(d.review_stages || 'PUBLICATION'::text) s
                    WHERE (application_procedure(a.id) -> 'stages') ? s
                      AND procedure_stage_status(a.id, s) NOT IN ('COMPLETED', 'IN_PROGRESS'))
      FROM application a
      JOIN procedure_objection o ON o.application_id = a.id
      JOIN procedure_objection_decision d ON d.objection_id = o.id AND d.outcome = 'UPHELD'
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor()))
     ORDER BY d.decided_at
$$;

-- The supervisor's queue: objections still open at their offices that they may decide, oldest first.
CREATE FUNCTION procedure_objection_queue(p_limit int, p_offset int)
RETURNS TABLE (objection_id uuid, number text, application_id uuid, reference text, office_code text, received_at timestamptz,
               late boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT o.id, o.number, a.id, a.reference, f.code, o.received_at, o.late
      FROM procedure_objection o
      JOIN application a ON a.id = o.application_id
      JOIN office f ON f.id = o.office_id
     WHERE holds_at(current_actor(), 'procedure.objection.decide', o.office_id)
       AND procedure_objection_decider_problem(o.id, current_actor()) IS NULL
     ORDER BY o.received_at, o.id
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;

-- The public notices: those whose period is open, of cases still under examination. Only what the public needs to
-- know and object: the notice's number, the parcel's reference and locality, the office, the period, the procedure.
-- Never the applicant, the case's reference, a document or an objection.
CREATE FUNCTION procedure_public_notices(p_limit int, p_offset int)
RETURNS TABLE (number text, procedure text, parcel_reference text, locality text, office_code text, office_name_fr text,
               office_name_en text, opens_at timestamptz, closes_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT n.number, application_procedure(a.id) ->> 'code', n.parcel_reference, n.locality, f.code, f.name_fr, f.name_en,
           n.opens_at, n.closes_at
      FROM procedure_notice n
      JOIN application a ON a.id = n.application_id
      JOIN office f ON f.id = n.office_id
     WHERE n.closes_at > now()
       AND a.state IN ('UNDER_REVIEW', 'PENDING_DECISION')
       AND n.record_id = (procedure_stage_latest(a.id, 'PUBLICATION')).id
     ORDER BY n.closes_at, n.number
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;

-- --- Row-level security and grants: read under procedure_readable; write only through the functions ----------

ALTER TABLE procedure_notice ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_notice_read ON procedure_notice FOR SELECT TO snigf_app
    USING (procedure_readable(application_id, current_actor()));
ALTER TABLE procedure_objection ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_objection_read ON procedure_objection FOR SELECT TO snigf_app
    USING (procedure_readable(application_id, current_actor()));
ALTER TABLE procedure_objection_decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_objection_decision_read ON procedure_objection_decision FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM procedure_objection o WHERE o.id = objection_id));
ALTER TABLE procedure_objection_file ENABLE ROW LEVEL SECURITY;
CREATE POLICY procedure_objection_file_read ON procedure_objection_file FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM procedure_objection o WHERE o.id = objection_id));

REVOKE ALL ON procedure_notice, procedure_objection, procedure_objection_decision, procedure_objection_file FROM PUBLIC;
REVOKE ALL ON SEQUENCE procedure_notice_seq, procedure_objection_seq FROM PUBLIC;
GRANT SELECT ON procedure_notice, procedure_objection_decision, procedure_objection_file TO snigf_app;
-- Every column but the objector's identity, read through procedure_objections() only.
GRANT SELECT (id, number, application_id, notice_id, office_id, objector_name, objector_interest, statement, received_at, late,
              recorded_by, demo) ON procedure_objection TO snigf_app;

REVOKE ALL ON FUNCTION procedure_objections_open(uuid), procedure_stage_problems(uuid, text), procedure_stage_status(uuid, text),
    procedure_earlier_stages_complete(uuid, text), procedure_registration_problems(uuid),
    procedure_objection_add(uuid, jsonb, text, jsonb, uuid), procedure_objection_decider_problem(uuid, uuid),
    procedure_objection_decide(uuid, text, text, text[], text), procedure_may_record_objection(uuid), procedure_objections(uuid),
    procedure_objector_identity_visible(uuid, uuid), procedure_objector_identity(uuid), application_procedure_instructions(uuid),
    procedure_objection_queue(int, int), procedure_public_notices(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION procedure_objections_open(uuid), procedure_stage_problems(uuid, text), procedure_stage_status(uuid, text),
    procedure_earlier_stages_complete(uuid, text), procedure_registration_problems(uuid),
    procedure_objection_add(uuid, jsonb, text, jsonb, uuid), procedure_objection_decider_problem(uuid, uuid),
    procedure_objection_decide(uuid, text, text, text[], text), procedure_may_record_objection(uuid), procedure_objections(uuid),
    procedure_objector_identity_visible(uuid, uuid), procedure_objector_identity(uuid), application_procedure_instructions(uuid),
    procedure_objection_queue(int, int), procedure_public_notices(int, int) TO snigf_app;
