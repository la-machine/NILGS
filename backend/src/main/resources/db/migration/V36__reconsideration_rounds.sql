-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V36: reconsideration rounds of an approved case (Slice 4, ADR-043 step 5b, D41, D42). DEMO only.
--
--   rounds                     the original examination is round 0; each reconsideration opens round 1, 2 and so on.
--                              application.review_round is the current round; case_assignment, review_step and decision
--                              carry the round they belong to, set by the database. One assignment per stage and one
--                              decision per round; nothing of an earlier round is ever changed.
--   case_reconsideration       append-only: the round it opens, the decision it reconsiders, its grounds (every objection
--                              upheld after that decision), the stages it reopens (those the objections named, any the
--                              supervisor adds, and the publication when the procedure has one), the written
--                              justification, who opened it and when. One per decision, so one open at a time.
--   procedure_reconsider()     the only writer and the one new transition: APPROVED -> UNDER_REVIEW (ADR-032 amended).
--                              By a holder of procedure.case.reopen at the office who never applied, reviewed, decided,
--                              recorded a stage of the case or decided one of the objections it answers; refused once a
--                              title is registered (ADR-040's correction or cancellation instead, D42) or while a
--                              CREATION is proposed.
--   the current decision       case_decision_current(): the latest round's. Registration, the stale-proposal check, the
--                              conservators' queue and evidence, "upheld after the decision" and the applicant read it.
-- In the new round, the reopened stages read RECONSIDERATION_PENDING until recorded again (as corrections), the notice is
-- published anew with a whole new period, and a new review and decision follow, by a decision authority who never decided
-- the case before. Whoever handled the case in any round never records a stage of it, nor decides one of its objections.
-- A refusal in the new round ends the case.

SET search_path = snigf, public;

-- --- Permission --------------------------------------------------------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('procedure.case.reopen', 'BUSINESS', false, ARRAY['OFFICE'],
     'Open the reconsideration of an approved case after an objection upheld after its decision',
     'Ouvrir le réexamen d''un dossier approuvé après une opposition admise postérieurement à sa décision');
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES ('SUPERVISOR', 'procedure.case.reopen', 'OFFICE');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- --- Rounds ------------------------------------------------------------------------------------------------------------

ALTER TABLE application ADD COLUMN review_round int NOT NULL DEFAULT 0 CHECK (review_round BETWEEN 0 AND 20);
ALTER TABLE case_assignment ADD COLUMN round int NOT NULL DEFAULT 0 CHECK (round >= 0);
ALTER TABLE review_step ADD COLUMN round int NOT NULL DEFAULT 0 CHECK (round >= 0);
ALTER TABLE decision ADD COLUMN round int NOT NULL DEFAULT 0 CHECK (round >= 0);
DROP INDEX ux_case_assignment_stage;
CREATE UNIQUE INDEX ux_case_assignment_stage ON case_assignment (application_id, round, stage);
ALTER TABLE decision DROP CONSTRAINT decision_application_id_key;
ALTER TABLE decision ADD CONSTRAINT ux_decision_round UNIQUE (application_id, round);
CREATE INDEX ix_review_step_round ON review_step (application_id, round, at);

CREATE TABLE case_reconsideration (
    id                       uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id           uuid NOT NULL REFERENCES application (id),
    round                    int NOT NULL CHECK (round BETWEEN 1 AND 20),
    reconsiders_decision_id  uuid NOT NULL UNIQUE REFERENCES decision (id),
    -- The grounds (D41): the objections upheld after the decision reconsidered, every one of them.
    objection_ids            uuid[] NOT NULL CHECK (cardinality(objection_ids) BETWEEN 1 AND 50),
    stages                   text[] NOT NULL CHECK (cardinality(stages) BETWEEN 1 AND 12),
    added_stages             text[] NOT NULL DEFAULT '{}' CHECK (cardinality(added_stages) <= 12),
    justification            text NOT NULL CHECK (length(btrim(justification)) BETWEEN 20 AND 4000),
    opened_by                uuid NOT NULL REFERENCES app_user (id),
    opened_at                timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (application_id, round),
    CONSTRAINT ck_reconsideration_added CHECK (added_stages <@ stages)
);
CREATE TRIGGER case_reconsideration_append_only BEFORE UPDATE OR DELETE ON case_reconsideration
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER case_reconsideration_no_truncate BEFORE TRUNCATE ON case_reconsideration
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- The decision that counts: the latest round's (the earlier ones stay, unchanged).
CREATE FUNCTION case_decision_current(p_application uuid) RETURNS decision
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT * FROM decision WHERE application_id = p_application ORDER BY round DESC LIMIT 1
$$;

-- Whether a person was assigned to the case at a stage, in any round.
CREATE FUNCTION case_ever_assigned(p_application uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.assignee_id = p_actor)
$$;

-- Who works on a case at a stage in its current round.
CREATE OR REPLACE FUNCTION case_assignee(p_application uuid, p_stage text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT c.assignee_id FROM case_assignment c JOIN application a ON a.id = c.application_id AND c.round = a.review_round
     WHERE c.application_id = p_application AND c.stage = p_stage
$$;

-- Whether the actor sees a case through assignment: assigned to it in any round, or able to claim it now in its current
-- round at their stage. Read by application's row-level policy; it reads application as its owner (no recursion).
CREATE OR REPLACE FUNCTION case_visible_to_assignee(p_application uuid, p_office uuid, p_state text, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND (
        EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.assignee_id = p_actor)
        OR (p_state = 'UNDER_REVIEW' AND holds_assignable(p_actor, 'review.recommend', p_office)
            AND case_assignee(p_application, 'REVIEW') IS NULL)
        OR (p_state = 'PENDING_DECISION' AND holds_assignable(p_actor, 'decision.record', p_office)
            AND case_assignee(p_application, 'DECISION') IS NULL))
$$;

-- Every assignment, review step and decision belongs to the case's current round, set here whatever was given; a decision
-- follows a recommendation of its own round; a new round's decision authority never decided the case before, and whoever
-- opened a reconsideration of the case never reviews or decides it.
CREATE FUNCTION case_round_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    current_round int;
BEGIN
    SELECT review_round INTO current_round FROM application WHERE id = NEW.application_id;
    NEW.round := coalesce(current_round, 0);
    IF TG_TABLE_NAME = 'case_assignment' THEN
        IF EXISTS (SELECT 1 FROM case_reconsideration r WHERE r.application_id = NEW.application_id AND r.opened_by = NEW.assignee_id) THEN
            PERFORM procedure_refuse('RECONSIDERATION_OPENER_CANNOT_REVIEW');
        END IF;
        IF NEW.stage = 'DECISION' AND EXISTS (SELECT 1 FROM decision d WHERE d.application_id = NEW.application_id
                                                AND d.decided_by = NEW.assignee_id) THEN
            PERFORM procedure_refuse('EARLIER_DECIDER_CANNOT_DECIDE');
        END IF;
    ELSIF TG_TABLE_NAME = 'decision' THEN
        IF (SELECT s.round FROM review_step s WHERE s.id = NEW.follows_step_id) IS DISTINCT FROM NEW.round THEN
            PERFORM procedure_refuse('RECOMMENDATION_OF_EARLIER_ROUND');
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_assignment_round BEFORE INSERT ON case_assignment FOR EACH ROW EXECUTE FUNCTION case_round_guard();
CREATE TRIGGER review_step_round BEFORE INSERT ON review_step FOR EACH ROW EXECUTE FUNCTION case_round_guard();
CREATE TRIGGER decision_round BEFORE INSERT ON decision FOR EACH ROW EXECUTE FUNCTION case_round_guard();

-- --- The one new transition: APPROVED -> UNDER_REVIEW, through a reconsideration only (ADR-032 amended) ----------------

CREATE OR REPLACE FUNCTION application_transition_legal(p_from text, p_to text) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT (p_from, p_to) IN (
        ('DRAFT', 'SUBMITTED'),
        ('SUBMITTED', 'COMPLETENESS_REVIEW'),
        ('COMPLETENESS_REVIEW', 'INFO_REQUESTED'),
        ('INFO_REQUESTED', 'COMPLETENESS_REVIEW'),
        ('COMPLETENESS_REVIEW', 'UNDER_REVIEW'),
        ('UNDER_REVIEW', 'PENDING_DECISION'),
        ('PENDING_DECISION', 'UNDER_REVIEW'),
        ('PENDING_DECISION', 'APPROVED'),
        ('PENDING_DECISION', 'REFUSED'),
        ('APPROVED', 'UNDER_REVIEW'),
        ('SUBMITTED', 'WITHDRAWN'),
        ('COMPLETENESS_REVIEW', 'WITHDRAWN'),
        ('INFO_REQUESTED', 'WITHDRAWN'),
        ('UNDER_REVIEW', 'WITHDRAWN'),
        ('PENDING_DECISION', 'WITHDRAWN'))
$$;

CREATE OR REPLACE FUNCTION application_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' OR NEW.version <> 0 OR NEW.state_changed_by IS NOT NULL THEN
            RAISE EXCEPTION 'an application starts as a draft' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.type_code IS DISTINCT FROM (SELECT code FROM application_type WHERE id = NEW.type_version_id)
           OR NOT (SELECT available FROM application_type WHERE id = NEW.type_version_id) THEN
            RAISE EXCEPTION 'type version % is not an available version of %', NEW.type_version_id, NEW.type_code
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
        RETURN NEW;
    END IF;

    IF NEW.id <> OLD.id OR NEW.applicant_id <> OLD.applicant_id OR NEW.type_code <> OLD.type_code
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'the owner, type and creation of application % never change', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.reference IS NOT NULL AND NEW.reference IS DISTINCT FROM OLD.reference THEN
        RAISE EXCEPTION 'the reference of application % never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'application % changed meanwhile', OLD.id USING ERRCODE = 'serialization_failure';
    END IF;
    IF OLD.state <> 'DRAFT'
       AND (NEW.data IS DISTINCT FROM OLD.data OR NEW.parcel_reference IS DISTINCT FROM OLD.parcel_reference
            OR NEW.parcel_id IS DISTINCT FROM OLD.parcel_id OR NEW.office_id IS DISTINCT FROM OLD.office_id
            OR NEW.type_version_id IS DISTINCT FROM OLD.type_version_id
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
        RAISE EXCEPTION 'application % was submitted: what was submitted never changes', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    -- The round moves only with a reconsideration, by one.
    IF NEW.review_round IS DISTINCT FROM OLD.review_round
       AND NOT (OLD.state = 'APPROVED' AND NEW.state = 'UNDER_REVIEW' AND NEW.review_round = OLD.review_round + 1) THEN
        RAISE EXCEPTION 'the review round of application % moves only with a reconsideration', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.state = OLD.state THEN
        IF NEW.state_changed_by IS DISTINCT FROM OLD.state_changed_by OR NEW.state_reason IS DISTINCT FROM OLD.state_reason THEN
            RAISE EXCEPTION 'actor and reason belong to a state change' USING ERRCODE = 'check_violation';
        END IF;
        NEW.updated_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF NOT application_transition_legal(OLD.state, NEW.state) THEN
        RAISE EXCEPTION 'application % cannot go from % to %', OLD.id, OLD.state, NEW.state
            USING ERRCODE = 'restrict_violation';
    END IF;
    actor := NEW.state_changed_by;
    IF actor IS NULL AND NOT (OLD.state IN ('SUBMITTED', 'INFO_REQUESTED') AND NEW.state = 'COMPLETENESS_REVIEW') THEN
        RAISE EXCEPTION 'a state change names its actor' USING ERRCODE = 'check_violation';
    END IF;
    IF actor IS NOT NULL AND actor IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'a state change is made by the actor of the transaction' USING ERRCODE = 'insufficient_privilege';
    END IF;

    CASE
        WHEN OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED' THEN
            IF actor <> OLD.applicant_id THEN
                RAISE EXCEPTION 'only the applicant submits application %', OLD.id USING ERRCODE = 'insufficient_privilege';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM parcel p WHERE p.id = NEW.parcel_id AND p.status = 'ACTIVE'
                             AND p.reference = NEW.parcel_reference AND p.office_id = NEW.office_id) THEN
                RAISE EXCEPTION 'application % names no active parcel of its office', OLD.id USING ERRCODE = 'check_violation';
            END IF;
            IF NEW.type_version_id IS DISTINCT FROM (SELECT t.id FROM application_type t WHERE t.code = NEW.type_code
                                                      ORDER BY t.version_no DESC LIMIT 1)
               OR NOT (SELECT available FROM application_type WHERE id = NEW.type_version_id) THEN
                RAISE EXCEPTION 'application % is submitted under the current version of its type', OLD.id
                    USING ERRCODE = 'check_violation';
            END IF;
            NEW.submitted_at := clock_timestamp();
        WHEN OLD.state = 'SUBMITTED' AND NEW.state = 'COMPLETENESS_REVIEW' THEN
            IF actor IS NOT NULL OR NOT completeness_recorded(OLD.id, 'AUTOMATIC', NULL, OLD.updated_at) THEN
                RAISE EXCEPTION 'completeness review starts with the automatic check' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'COMPLETENESS_REVIEW' AND NEW.state = 'UNDER_REVIEW' THEN
            IF NOT completeness_recorded(OLD.id, 'COMPLETE', actor, OLD.updated_at) THEN
                RAISE EXCEPTION 'acceptance is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'COMPLETENESS_REVIEW' AND NEW.state = 'INFO_REQUESTED' THEN
            IF NOT completeness_recorded(OLD.id, 'INFO_REQUESTED', actor, OLD.updated_at) THEN
                RAISE EXCEPTION 'the information request is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'INFO_REQUESTED' AND NEW.state = 'COMPLETENESS_REVIEW' THEN
            IF actor IS DISTINCT FROM OLD.applicant_id OR NOT completeness_recorded(OLD.id, 'RESPONSE', actor, OLD.updated_at)
               OR NOT completeness_recorded(OLD.id, 'AUTOMATIC', NULL, OLD.updated_at) THEN
                RAISE EXCEPTION 'the applicant''s response and the automatic check are recorded first'
                    USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN NEW.state = 'WITHDRAWN' THEN
            IF actor <> OLD.applicant_id THEN
                RAISE EXCEPTION 'only the applicant withdraws application %', OLD.id USING ERRCODE = 'insufficient_privilege';
            END IF;
        WHEN OLD.state = 'UNDER_REVIEW' AND NEW.state = 'PENDING_DECISION' THEN
            IF NOT EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = OLD.id AND s.kind = 'RECOMMENDATION'
                             AND s.actor_id = actor AND s.at >= OLD.updated_at) THEN
                RAISE EXCEPTION 'the recommendation is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'PENDING_DECISION' AND NEW.state = 'UNDER_REVIEW' THEN
            IF NOT EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = OLD.id AND s.kind = 'RETURN'
                             AND s.actor_id = actor AND s.at >= OLD.updated_at) THEN
                RAISE EXCEPTION 'the return is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'PENDING_DECISION' AND NEW.state IN ('APPROVED', 'REFUSED') THEN
            IF NOT EXISTS (SELECT 1 FROM decision d WHERE d.application_id = OLD.id AND d.outcome = NEW.state
                             AND d.decided_by = actor AND d.round = OLD.review_round) THEN
                RAISE EXCEPTION 'the decision is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        WHEN OLD.state = 'APPROVED' AND NEW.state = 'UNDER_REVIEW' THEN
            IF NEW.review_round <> OLD.review_round + 1
               OR NOT EXISTS (SELECT 1 FROM case_reconsideration r WHERE r.application_id = OLD.id AND r.round = NEW.review_round
                                AND r.opened_by = actor) THEN
                RAISE EXCEPTION 'the reconsideration is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'application % cannot go from % to % here', OLD.id, OLD.state, NEW.state
                USING ERRCODE = 'restrict_violation';
    END CASE;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- --- The reopened stages: to be recorded again ------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION procedure_stage_problems(p_application uuid, p_stage text) RETURNS text[]
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
    -- A reconsideration reopened this stage after its latest record (V36): it is recorded again in the new round.
    IF EXISTS (SELECT 1 FROM case_reconsideration r WHERE r.application_id = p_application AND p_stage = ANY (r.stages)
                AND r.opened_at > latest.recorded_at) THEN
        problems := problems || ('RECONSIDERATION_PENDING:' || p_stage);
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

CREATE OR REPLACE FUNCTION procedure_registration_problems(p_application uuid) RETURNS text[]
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
    decided := (case_decision_current(p_application)).at;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision od JOIN procedure_objection o ON o.id = od.objection_id
                WHERE o.application_id = p_application AND od.outcome = 'UPHELD' AND (decided IS NULL OR od.decided_at > decided)) THEN
        problems := problems || 'OBJECTION_UPHELD_AFTER_DECISION'::text;
    END IF;
    RETURN problems;
END;
$$;

-- --- Whoever handled the case in any round stays apart from its stages and objections -----------------------------

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
    IF case_ever_assigned(a.id, actor) THEN
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

CREATE OR REPLACE FUNCTION procedure_objection_add(p_application uuid, p_objector jsonb, p_statement text, p_files jsonb, p_client_key uuid)
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
    IF case_ever_assigned(a.id, actor) THEN
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

CREATE OR REPLACE FUNCTION procedure_objection_decider_problem(p_objection uuid, p_actor uuid) RETURNS text
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
    IF case_ever_assigned(a.id, p_actor) THEN
        RETURN 'ASSIGNEE_CANNOT_DECIDE';
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = a.id AND r.recorded_by = p_actor) THEN
        RETURN 'STAGE_RECORDER_CANNOT_DECIDE';
    END IF;
    RETURN NULL;
END;
$$;

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
           AND NOT case_ever_assigned(a.id, current_actor())) q
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
           AND NOT case_ever_assigned(a.id, current_actor())
           AND (p_stage <> 'COMMISSION_REPORT' OR EXISTS (
                SELECT 1 FROM procedure_stage_latest(a.id, 'FIELD_VISIT') v
                 WHERE v.id IS NOT NULL AND v.outcome = 'COMPLETED' AND v.recorded_by <> current_actor()))
           AND (p_stage <> 'PUBLICATION' OR procedure_earlier_stages_complete(a.id, 'PUBLICATION')))
$$;

CREATE OR REPLACE FUNCTION procedure_may_record_objection(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM application a
         WHERE a.id = p_application AND a.state IN ('UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED')
           AND holds_at(current_actor(), 'procedure.objection.record', a.office_id)
           AND a.applicant_id <> current_actor()
           AND NOT case_ever_assigned(a.id, current_actor())
           AND EXISTS (SELECT 1 FROM procedure_notice n WHERE n.record_id = (procedure_stage_latest(a.id, 'PUBLICATION')).id))
$$;

CREATE OR REPLACE FUNCTION workload_open_cases(p_office_code text, p_targets jsonb)
RETURNS TABLE (application_id uuid, reference text, parcel_reference text, state text, since timestamptz,
               waited interval, target interval, overdue boolean, assignee_id uuid, assignee_name text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    WITH office AS (SELECT workload_office(p_office_code) AS id),
         asof AS MATERIALIZED (SELECT clock_timestamp() AS t)
    SELECT a.id, a.reference, a.parcel_reference, a.state, s.entered_at,
           asof.t - s.entered_at,
           workload_target(p_targets, a.state),
           coalesce(asof.t - s.entered_at > workload_target(p_targets, a.state), false),
           c.assignee_id, u.display_name
      FROM office
      CROSS JOIN asof
      JOIN application a ON a.office_id = office.id
      JOIN office_stays(office.id) s ON s.application_id = a.id AND s.left_at IS NULL AND s.state = a.state
      LEFT JOIN case_assignment c ON c.application_id = a.id AND c.round = a.review_round
                                 AND c.stage = CASE a.state WHEN 'UNDER_REVIEW' THEN 'REVIEW'
                                                            WHEN 'PENDING_DECISION' THEN 'DECISION' END
      LEFT JOIN app_user u ON u.id = c.assignee_id
     WHERE a.state IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION')
$$;

-- --- The register reads the current decision ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION register_case_proposal_stale(p_inscription uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM inscription i
         WHERE i.id = p_inscription AND i.application_id IS NOT NULL
           AND (i.decision_id IS DISTINCT FROM (case_decision_current(i.application_id)).id
                OR i.ownership_statement_id IS DISTINCT FROM (case_decision_current(i.application_id)).ownership_statement_id
                OR i.ownership_statement_id IS DISTINCT FROM (case_statement_latest(i.application_id)).id
                -- The parcel as signed is still the case's parcel at its current version.
                OR i.parcel_version_id IS DISTINCT FROM (SELECT v.id FROM application a JOIN parcel p ON p.id = a.parcel_id
                                                          JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                                                         WHERE a.id = i.application_id)
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                             JOIN case_person_identity src ON src.id = rp.source_identity_id
                            WHERE h.inscription_id = i.id
                              AND (src.id IS DISTINCT FROM (case_identity_latest(src.person_id)).id
                                   OR case_check_of('IDENTITY', src.id) IS DISTINCT FROM 'MATCHES'))
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                            WHERE h.inscription_id = i.id AND rp.source_identity_id IS NULL)))
$$;

CREATE OR REPLACE FUNCTION register_case_problems(p_application uuid, p_except uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a         application;
    d         decision;
    proc      jsonb;
    problems  text[] := ARRAY[]::text[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN ARRAY['CASE_NOT_FOUND'];
    END IF;
    proc := application_procedure(a.id);
    IF proc IS NULL OR proc ->> 'registers' IS DISTINCT FROM 'CREATION' THEN
        RETURN ARRAY['PROCEDURE_NOT_CREATION'];
    END IF;
    -- The CREATION being signed is still what was approved, or it is stale: withdrawn or rejected, and proposed anew.
    IF p_except IS NOT NULL AND register_case_proposal_stale(p_except) THEN
        RETURN ARRAY['STALE_PROPOSAL'];
    END IF;
    problems := problems || procedure_registration_problems(a.id);
    IF a.state = 'APPROVED' AND EXISTS (SELECT 1 FROM unnest(procedure_gate_problems(a.id)) g(code)
                                         WHERE g.code <> 'OBJECTION_OPEN' AND g.code NOT LIKE 'OBJECTION_REVIEW_PENDING:%') THEN
        problems := problems || 'PROCEDURE_INCOMPLETE'::text;
    END IF;
    d := case_decision_current(a.id);
    IF a.state = 'APPROVED' THEN
        IF cardinality(case_party_problems(a.id)) > 0 OR d.ownership_statement_id IS NULL THEN
            problems := problems || 'PARTIES_INCOMPLETE'::text;
        ELSIF d.ownership_statement_id IS DISTINCT FROM (case_statement_latest(a.id)).id
              OR EXISTS (SELECT 1 FROM case_person_identity i WHERE i.application_id = a.id AND i.entered_at > d.at)
              OR EXISTS (SELECT 1 FROM case_mandate m WHERE m.application_id = a.id AND m.entered_at > d.at) THEN
            problems := problems || 'PARTIES_CHANGED_SINCE_DECISION'::text;
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id) THEN
        problems := problems || 'CASE_ALREADY_REGISTERED'::text;
    ELSIF EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
                    AND i.id IS DISTINCT FROM p_except) THEN
        problems := problems || 'CREATION_ALREADY_PROPOSED'::text;
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t
                 JOIN title_version v ON v.title_id = t.id AND v.version_no = title_current_version(t.id)
                 JOIN parcel_version pv ON pv.id = v.parcel_version_id
                WHERE pv.parcel_id = a.parcel_id AND v.status = 'ACTIVE') THEN
        problems := problems || 'PARCEL_ALREADY_TITLED'::text;
    ELSIF EXISTS (SELECT 1 FROM inscription i JOIN parcel_version pv ON pv.id = i.parcel_version_id
                   WHERE i.kind = 'CREATION' AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING') AND pv.parcel_id = a.parcel_id
                     AND i.application_id IS DISTINCT FROM a.id AND i.id IS DISTINCT FROM p_except) THEN
        problems := problems || 'PARCEL_CREATION_PENDING'::text;
    END IF;
    RETURN problems;
END;
$$;

CREATE OR REPLACE FUNCTION register_propose_from_case(p_application uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    d         decision;
    pv        parcel_version;
    problems  text[];
    s         record;
    party     uuid;
    new_id    uuid;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    IF a.id IS NULL OR a.office_id IS NULL OR NOT holds_at(actor, 'register.read', a.office_id) THEN
        PERFORM register_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, 'register.propose', a.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF register_case_handler(a.id, actor) THEN
        PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
    END IF;
    problems := register_case_problems(a.id, NULL);
    IF cardinality(problems) > 0 THEN
        PERFORM register_refuse(problems[1]);
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    d := case_decision_current(a.id);
    SELECT v.* INTO pv FROM parcel_version v JOIN parcel p ON p.id = v.parcel_id
     WHERE p.id = a.parcel_id AND v.version_no = p.current_version_no AND p.office_id = a.office_id;
    IF pv.id IS NULL THEN
        PERFORM register_refuse('PARCEL_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(d.cited_versions) e(v)
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv WHERE dv.id = e.v AND dv.status = 'STORED')) THEN
        PERFORM register_refuse('EVIDENCE_NOT_FOUND');
    END IF;
    INSERT INTO inscription (title_id, new_title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
                             evidence_references, evidence_versions, area_m2, parcel_version_id, proposed_by, application_id, decision_id,
                             ownership_statement_id, ownership_statement_version)
    VALUES (NULL, uuidv7(), a.office_id, 'CREATION', 0, 'DECISION', a.reference, btrim(p_reason),
            ARRAY[a.reference, 'DECISION:' || d.id::text, 'OWNERSHIP_STATEMENT:' || d.ownership_statement_id::text], d.cited_versions,
            pv.area_m2, pv.id, actor, a.id, d.id, d.ownership_statement_id,
            (SELECT version_no FROM case_ownership_statement WHERE id = d.ownership_statement_id))
    RETURNING id INTO new_id;
    FOR s IN SELECT sh.interest, sh.share_num, sh.share_den, sh.usufruct_term, sh.usufruct_until, cp.kind, cp.account_id, i.id AS identity,
                    i.version_no AS identity_version, i.full_name, i.identity_kind, i.identity_number
               FROM case_ownership_share sh
               JOIN case_person cp ON cp.id = sh.person_id
               CROSS JOIN LATERAL case_identity_latest(cp.id) i
              WHERE sh.statement_id = d.ownership_statement_id
              ORDER BY cp.ref, sh.interest LOOP
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, account_id, created_by, source_identity_id,
                                    source_identity_version)
        VALUES (a.office_id, s.kind, s.full_name, s.identity_kind, s.identity_number, s.account_id, actor, s.identity, s.identity_version)
        RETURNING id INTO party;
        INSERT INTO inscription_holder (inscription_id, party_id, interest, share_num, share_den, usufruct_term, usufruct_until)
        VALUES (new_id, party, s.interest, s.share_num, s.share_den, s.usufruct_term, s.usufruct_until);
    END LOOP;
    IF register_actor_interested(actor, NULL, new_id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN new_id;
END;
$$;

CREATE OR REPLACE FUNCTION register_case_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, parcel_reference text, decided_at timestamptz,
               problems text[], may_propose boolean, inscription_id uuid, inscription_status text, owners text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.id, a.reference::text, f.code, a.type_code, a.parcel_reference::text, d.at,
           register_case_problems(a.id, NULL),
           cardinality(register_case_problems(a.id, NULL)) = 0 AND NOT register_case_handler(a.id, current_actor()),
           i.id, i.status,
           (SELECT string_agg(format('%s (%s/%s)', (case_identity_latest(cp.id)).full_name, sh.share_num, sh.share_den), '; '
                              ORDER BY cp.ref)
              FROM case_ownership_share sh JOIN case_person cp ON cp.id = sh.person_id
             WHERE sh.statement_id = d.ownership_statement_id)
      FROM application a
      JOIN office f ON f.id = a.office_id
      JOIN decision d ON d.application_id = a.id AND d.round = a.review_round AND d.outcome = 'APPROVED'
      LEFT JOIN inscription i ON i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
     WHERE a.state = 'APPROVED'
       AND application_procedure(a.id) ->> 'registers' = 'CREATION'
       AND holds_at(current_actor(), 'register.propose', a.office_id)
       AND NOT EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id)
     ORDER BY d.at, a.id
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;

CREATE OR REPLACE FUNCTION register_case_evidence(p_application uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_build_object(
        'reference', a.reference,
        'capacity', a.declaration ->> 'capacity',
        'decision', jsonb_build_object('id', d.id, 'at', d.at, 'statement', d.ownership_statement_id),
        'statement', (SELECT jsonb_build_object('id', s.id, 'version', s.version_no, 'enteredBy', u.display_name, 'enteredAt', s.entered_at,
                             'verification', s.verification, 'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                                                         FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                                                        WHERE c.target_kind = 'OWNERSHIP' AND c.target_id = s.id))
                        FROM case_ownership_statement s JOIN app_user u ON u.id = s.entered_by WHERE s.id = d.ownership_statement_id),
        'owners', (SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                        'ref', cp.ref, 'kind', cp.kind, 'fullName', i.full_name, 'identityKind', i.identity_kind, 'identityNumber', i.identity_number,
                        'validUntil', i.valid_until, 'expiredAtTranscription', i.expired_at_transcription,
                        'exception', (SELECT jsonb_build_object('policy', x.policy, 'reason', x.reason, 'by', xu.display_name, 'at', x.authorised_at)
                                        FROM case_party_exception x JOIN app_user xu ON xu.id = x.authorised_by
                                       WHERE x.target_kind = 'IDENTITY' AND x.target_id = i.id),
                        'interest', sh.interest, 'shareNum', sh.share_num, 'shareDen', sh.share_den, 'usufructTerm', sh.usufruct_term,
                        'usufructUntil', sh.usufruct_until, 'identityVersionId', i.id, 'identityVersion', i.version_no, 'verification', i.verification,
                        'transcribedBy', iu.display_name, 'transcribedAt', i.entered_at, 'sourceFile', i.source_file_id, 'sourceVersion', i.source_version_id,
                        'basisFile', sh.basis_file_id, 'basisVersion', sh.basis_version_id,
                        'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                    FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                   WHERE c.target_kind = 'IDENTITY' AND c.target_id = i.id))) ORDER BY cp.ref, sh.interest), '[]')
                     FROM case_ownership_share sh JOIN case_person cp ON cp.id = sh.person_id
                     CROSS JOIN LATERAL case_identity_latest(cp.id) i JOIN app_user iu ON iu.id = i.entered_by
                    WHERE sh.statement_id = d.ownership_statement_id),
        'mandates', (SELECT coalesce(jsonb_agg(jsonb_build_object('representative', (case_identity_latest(cp.id)).full_name, 'scope', m.scope,
                        'represents', (SELECT jsonb_agg((case_identity_latest(q.id)).full_name ORDER BY q.ref)
                                         FROM case_mandate_subject ms JOIN case_person q ON q.id = ms.person_id WHERE ms.mandate_id = m.id),
                        'documentFile', m.document_file_id, 'version', m.version_no,
                        'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                    FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                   WHERE c.target_kind = 'MANDATE' AND c.target_id = m.id)) ORDER BY cp.ref), '[]')
                       FROM case_person cp CROSS JOIN LATERAL case_mandate_latest(cp.id) m WHERE cp.application_id = a.id AND m.id IS NOT NULL),
        'files', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'purpose', f.purpose, 'name', f.original_name, 'sha256', f.sha256,
                        'mediaType', f.detected_type, 'sizeBytes', f.size_bytes) ORDER BY f.uploaded_at, f.id), '[]')
                    FROM case_party_file f WHERE f.application_id = a.id))
      FROM application a
      JOIN decision d ON d.application_id = a.id AND d.round = a.review_round AND d.outcome = 'APPROVED'
     WHERE a.id = p_application AND a.state = 'APPROVED' AND case_parties_required(a.id)
       AND (holds_at(current_actor(), 'register.propose', a.office_id) OR holds_at(current_actor(), 'register.inscribe', a.office_id)
            OR holds_at(current_actor(), 'register.confirm', a.office_id))
$$;

-- --- The reconsideration --------------------------------------------------------------------------------------------------

-- Its grounds (D41): every objection upheld after the current decision, oldest first.
CREATE FUNCTION case_reconsideration_grounds(p_application uuid) RETURNS uuid[]
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce(array_agg(o.id ORDER BY od.decided_at, o.id), '{}')
      FROM procedure_objection o
      JOIN procedure_objection_decision od ON od.objection_id = o.id AND od.outcome = 'UPHELD'
     WHERE o.application_id = p_application
       AND od.decided_at > (case_decision_current(p_application)).at
$$;

-- Why the actor may not open a reconsideration of this case now, or NULL when they may. Shared by the writer and the
-- screens. The case: approved, of a procedure, no title registered (D42: ADR-040's correction or cancellation instead), no
-- CREATION proposed (a conservator withdraws or rejects it first, with a reason; never closed automatically), and grounds.
-- The actor: holds procedure.case.reopen at the office and never applied, was assigned (reviewer or decision authority),
-- decided, recorded a stage, or decided one of the objections the reconsideration answers.
CREATE FUNCTION procedure_reconsider_problem(p_application uuid, p_actor uuid) RETURNS text
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a        application;
    v_grounds  uuid[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN 'CASE_NOT_FOUND';
    END IF;
    IF NOT holds_at(p_actor, 'procedure.case.reopen', a.office_id) THEN
        RETURN 'PROCEDURE_FORBIDDEN';
    END IF;
    IF application_procedure(a.id) IS NULL THEN
        RETURN 'RECONSIDERATION_NOT_APPLICABLE';
    END IF;
    IF a.state <> 'APPROVED' THEN
        RETURN 'CASE_NOT_APPROVED';
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id) THEN
        RETURN 'CASE_REGISTERED';
    END IF;
    IF EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')) THEN
        RETURN 'CREATION_IN_PROGRESS';
    END IF;
    v_grounds := case_reconsideration_grounds(a.id);
    IF cardinality(v_grounds) = 0 THEN
        RETURN 'GROUNDS_MISSING';
    END IF;
    IF p_actor = a.applicant_id OR EXISTS (SELECT 1 FROM case_person cp WHERE cp.application_id = a.id AND cp.account_id = p_actor) THEN
        RETURN 'APPLICANT_CANNOT_REOPEN';
    END IF;
    IF EXISTS (SELECT 1 FROM decision d WHERE d.application_id = a.id AND d.decided_by = p_actor) THEN
        RETURN 'DECISION_AUTHORITY_CANNOT_REOPEN';
    END IF;
    IF case_ever_assigned(a.id, p_actor) THEN
        RETURN 'ASSIGNEE_CANNOT_REOPEN';
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = a.id AND r.recorded_by = p_actor) THEN
        RETURN 'STAGE_RECORDER_CANNOT_REOPEN';
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision od WHERE od.objection_id = ANY (v_grounds) AND od.decided_by = p_actor) THEN
        RETURN 'OBJECTION_DECIDER_CANNOT_REOPEN';
    END IF;
    RETURN NULL;
END;
$$;

-- Opens round n+1: records the reconsideration and moves the case from APPROVED to UNDER_REVIEW, under the case's lock
-- (a CREATION's proposal and signatures take the same lock). The stages reopened are those the objections named, those
-- the supervisor adds (never fewer), and the publication when the procedure has one, in the procedure's order. Nothing
-- of the earlier rounds is changed.
CREATE FUNCTION procedure_reconsider(p_application uuid, p_expected_version bigint, p_added_stages text[], p_justification text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    proc      jsonb;
    problem   text;
    v_grounds   uuid[];
    added     text[] := coalesce(p_added_stages, '{}');
    named     text[];
    stages    text[];
    new_id    uuid;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL THEN
        PERFORM procedure_refuse('CASE_NOT_FOUND');
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    problem := procedure_reconsider_problem(a.id, actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF p_expected_version IS DISTINCT FROM a.version THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF NOT procedure_text_ok(p_justification, 20, 4000) THEN
        PERFORM procedure_refuse('JUSTIFICATION_REQUIRED');
    END IF;
    proc := application_procedure(a.id);
    IF cardinality(added) > 12 OR cardinality(added) <> (SELECT count(DISTINCT s) FROM unnest(added) s)
       OR EXISTS (SELECT 1 FROM unnest(added) s WHERE NOT (proc -> 'stages') ? s OR s IN ('TITLE_VERIFICATION', 'DUTIES_EVIDENCE')) THEN
        PERFORM procedure_refuse('REOPEN_STAGE_INVALID');
    END IF;
    v_grounds := case_reconsideration_grounds(a.id);
    SELECT coalesce(array_agg(DISTINCT s), '{}') INTO named
      FROM procedure_objection_decision od CROSS JOIN LATERAL unnest(od.review_stages) s
     WHERE od.objection_id = ANY (v_grounds);
    SELECT array_agg(ps.code ORDER BY ps.rank) INTO stages
      FROM procedure_stage ps
     WHERE (proc -> 'stages') ? ps.code
       AND (ps.code = ANY (named) OR ps.code = ANY (added) OR ps.code = 'PUBLICATION');
    INSERT INTO case_reconsideration (application_id, round, reconsiders_decision_id, objection_ids, stages, added_stages, justification,
                                      opened_by)
    VALUES (a.id, a.review_round + 1, (case_decision_current(a.id)).id, v_grounds, stages,
            (SELECT coalesce(array_agg(s ORDER BY s), '{}') FROM unnest(added) s WHERE NOT s = ANY (named) AND s <> 'PUBLICATION'),
            btrim(p_justification), actor)
    RETURNING id INTO new_id;
    UPDATE application SET state = 'UNDER_REVIEW', review_round = a.review_round + 1, version = a.version + 1,
                           state_changed_by = actor, state_reason = 'RECONSIDERATION'
     WHERE id = a.id;
    RETURN new_id;
END;
$$;

-- A case's reconsiderations for its readers: the round, the decision reconsidered, the objections it answers (their numbers),
-- the stages reopened, the justification, who opened it, and the outcome of that round's decision once made.
CREATE FUNCTION procedure_reconsiderations(p_application uuid)
RETURNS TABLE (id uuid, round int, reconsidered_decision uuid, reconsidered_outcome text, objection_numbers text[], stages text[],
               added_stages text[], justification text, opened_by uuid, opened_by_name text, opened_at timestamptz, round_outcome text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT r.id, r.round, r.reconsiders_decision_id, d.outcome,
           (SELECT array_agg(o.number ORDER BY o.received_at, o.id) FROM procedure_objection o WHERE o.id = ANY (r.objection_ids)),
           r.stages, r.added_stages, r.justification, r.opened_by, u.display_name, r.opened_at,
           (SELECT nd.outcome FROM decision nd WHERE nd.application_id = r.application_id AND nd.round = r.round)
      FROM case_reconsideration r
      JOIN decision d ON d.id = r.reconsiders_decision_id
      JOIN app_user u ON u.id = r.opened_by
     WHERE r.application_id = p_application AND procedure_readable(p_application, current_actor())
     ORDER BY r.round
$$;

-- What the applicant is told (D41): that the case is reconsidered, which stages are carried out again, and whether the new
-- decision is made. Never the justification, the objections, the objectors or who opened it.
CREATE FUNCTION application_reconsideration(p_application uuid)
RETURNS TABLE (round int, stages text[], opened_at timestamptz, decided boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT r.round, r.stages, r.opened_at,
           EXISTS (SELECT 1 FROM decision d WHERE d.application_id = r.application_id AND d.round = r.round)
      FROM case_reconsideration r
      JOIN application a ON a.id = r.application_id
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor()))
     ORDER BY r.round
$$;

-- --- Row-level security and grants ------------------------------------------------------------------------------------------

ALTER TABLE case_reconsideration ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_reconsideration_read ON case_reconsideration FOR SELECT TO snigf_app
    USING (procedure_readable(application_id, current_actor()));
REVOKE ALL ON case_reconsideration FROM PUBLIC;
GRANT SELECT ON case_reconsideration TO snigf_app;

REVOKE ALL ON FUNCTION case_decision_current(uuid), case_ever_assigned(uuid, uuid), case_round_guard(),
    case_reconsideration_grounds(uuid), procedure_reconsider_problem(uuid, uuid), procedure_reconsider(uuid, bigint, text[], text),
    procedure_reconsiderations(uuid), application_reconsideration(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION case_decision_current(uuid), case_ever_assigned(uuid, uuid), case_reconsideration_grounds(uuid),
    procedure_reconsider_problem(uuid, uuid), procedure_reconsider(uuid, bigint, text[], text), procedure_reconsiderations(uuid),
    application_reconsideration(uuid) TO snigf_app;
