-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V16: substantive review and simulated decision (MVP-05, E-15, ADR-035).
--
--   case_assignment  who works on a case at a stage: a reviewer (REVIEW) or a decision
--                    authority (DECISION) of the case's office claims it; one at a time per
--                    stage; reassignment by a supervisor comes with E-22
--   review_step      the reviewer's recommendation with its finding; the decision authority's
--                    return of the case to review with its reason; never changed
--   decision         the simulated outcome, APPROVED or REFUSED, one per application, with its
--                    grounds and the document versions it cites; simulated = true, always
--
-- Their permissions are held at scope ASSIGNED (V7): a reviewer sees and acts on the cases
-- assigned to them, and on the cases of their office waiting to be claimed at their stage.
-- The database enforces, whatever the application does:
--   * the decision authority never recommended on the case and is not the applicant;
--   * no decision while an overlap or duplicate alert on the parcel is open (MVP-04);
--   * a decision has grounds and cites stored versions of the application's documents;
--   * every move of the review and decision has its record, by the person assigned.

SET search_path = snigf, public;

-- A permission held at scope ASSIGNED through a role at this office (enabled staff, active role).
CREATE FUNCTION holds_assignable(p_user uuid, p_permission text, p_office uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_user IS NOT NULL AND p_office IS NOT NULL AND EXISTS (
        SELECT 1
          FROM app_user u
          JOIN role_assignment a ON a.user_id = u.id AND a.revoked_at IS NULL AND a.office_id = p_office
          JOIN role_definition d ON d.code = a.role AND d.status = 'ACTIVE'
          JOIN role_permission p ON p.role_code = d.code AND p.permission = p_permission AND p.scope = 'ASSIGNED'
         WHERE u.id = p_user AND u.enabled AND u.account_type = 'STAFF')
$$;

-- --- Assignment -------------------------------------------------------------------------------

CREATE TABLE case_assignment (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    stage           text NOT NULL CHECK (stage IN ('REVIEW', 'DECISION')),
    assignee_id     uuid NOT NULL REFERENCES app_user (id),
    assigned_by     uuid NOT NULL REFERENCES app_user (id),
    reason          text CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 3 AND 500),
    at              timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- One person per case and stage (reassignment, E-22, will end the current one first).
CREATE UNIQUE INDEX ux_case_assignment_stage ON case_assignment (application_id, stage);
CREATE INDEX ix_case_assignment_assignee ON case_assignment (assignee_id, stage);

-- Who works on a case at a stage (the application's state does not matter: it is kept for history).
CREATE FUNCTION case_assignee(p_application uuid, p_stage text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT assignee_id FROM case_assignment WHERE application_id = p_application AND stage = p_stage
$$;

-- Whether the actor may see a case through assignment: assigned to it at any stage, or able to
-- claim it now at their stage. Read by the row-level policy of application (no recursion: it
-- reads only case_assignment, as its owner).
CREATE FUNCTION case_visible_to_assignee(p_application uuid, p_office uuid, p_state text, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND (
        EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.assignee_id = p_actor)
        OR (p_state = 'UNDER_REVIEW' AND holds_assignable(p_actor, 'review.recommend', p_office)
            AND NOT EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.stage = 'REVIEW'))
        OR (p_state = 'PENDING_DECISION' AND holds_assignable(p_actor, 'decision.record', p_office)
            AND NOT EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.stage = 'DECISION')))
$$;

-- --- Review steps and decisions ------------------------------------------------------------------

CREATE TABLE review_step (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    kind            text NOT NULL CHECK (kind IN ('RECOMMENDATION', 'RETURN')),
    recommendation  text CHECK (recommendation IN ('RECOMMEND_APPROVAL', 'RECOMMEND_REFUSAL', 'RETURN_FOR_INFORMATION')),
    text            text NOT NULL CHECK (length(btrim(text)) BETWEEN 10 AND 4000),
    actor_id        uuid NOT NULL REFERENCES app_user (id),
    at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_review_step_recommendation CHECK ((kind = 'RECOMMENDATION') = (recommendation IS NOT NULL))
);
CREATE INDEX ix_review_step_application ON review_step (application_id, at);

CREATE TABLE decision (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL UNIQUE REFERENCES application (id),
    outcome         text NOT NULL CHECK (outcome IN ('APPROVED', 'REFUSED')),
    grounds         text NOT NULL CHECK (length(btrim(grounds)) BETWEEN 10 AND 4000),
    cited_versions  uuid[] NOT NULL CHECK (cardinality(cited_versions) BETWEEN 1 AND 50),
    -- Baseline: decisions carry simulated = true until an authorized mode exists (E-24).
    simulated       boolean NOT NULL DEFAULT true CHECK (simulated),
    follows_step_id uuid NOT NULL REFERENCES review_step (id),
    decided_by      uuid NOT NULL REFERENCES app_user (id),
    at              timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Whether an overlap or duplicate alert on the application's parcel is still open (MVP-04).
CREATE FUNCTION application_open_alerts(p_application uuid) RETURNS int
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT count(*)::int
      FROM application a
      JOIN conflict_alert c ON c.status = 'OPEN' AND a.parcel_id IN (c.parcel_a_id, c.parcel_b_id)
     WHERE a.id = p_application
$$;

CREATE FUNCTION case_assignment_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    SELECT * INTO a FROM application WHERE id = NEW.application_id;
    IF a.id IS NULL OR NEW.assignee_id IS DISTINCT FROM current_actor() OR NEW.assigned_by IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'a case is claimed by the actor, for themselves' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.assignee_id = a.applicant_id THEN
        RAISE EXCEPTION 'nobody reviews or decides their own application' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.stage = 'REVIEW' THEN
        IF a.state <> 'UNDER_REVIEW' OR NOT holds_assignable(NEW.assignee_id, 'review.recommend', a.office_id) THEN
            RAISE EXCEPTION 'only a reviewer of the office claims a case under review' USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF case_assignee(a.id, 'DECISION') = NEW.assignee_id THEN
            RAISE EXCEPTION 'the decision authority of a case does not review it' USING ERRCODE = 'insufficient_privilege';
        END IF;
    ELSE
        IF a.state <> 'PENDING_DECISION' OR NOT holds_assignable(NEW.assignee_id, 'decision.record', a.office_id) THEN
            RAISE EXCEPTION 'only a decision authority of the office claims a case pending decision'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = a.id AND s.kind = 'RECOMMENDATION'
                                                  AND s.actor_id = NEW.assignee_id) THEN
            RAISE EXCEPTION 'whoever recommended on a case does not decide it' USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    NEW.at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_assignment_guard BEFORE INSERT ON case_assignment
    FOR EACH ROW EXECUTE FUNCTION case_assignment_guard();

CREATE FUNCTION review_step_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    SELECT * INTO a FROM application WHERE id = NEW.application_id;
    IF a.id IS NULL OR NEW.actor_id IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'a review step names its actor' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.kind = 'RECOMMENDATION' THEN
        IF a.state <> 'UNDER_REVIEW' THEN
            RAISE EXCEPTION 'application % is not under review', a.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF case_assignee(a.id, 'REVIEW') IS DISTINCT FROM NEW.actor_id
           OR NOT holds_assignable(NEW.actor_id, 'review.recommend', a.office_id) THEN
            RAISE EXCEPTION 'only the reviewer assigned recommends' USING ERRCODE = 'insufficient_privilege';
        END IF;
    ELSE
        IF a.state <> 'PENDING_DECISION' THEN
            RAISE EXCEPTION 'application % is not pending decision', a.id USING ERRCODE = 'restrict_violation';
        END IF;
        IF case_assignee(a.id, 'DECISION') IS DISTINCT FROM NEW.actor_id
           OR NOT holds_assignable(NEW.actor_id, 'decision.record', a.office_id) THEN
            RAISE EXCEPTION 'only the decision authority assigned returns a case' USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    NEW.at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER review_step_guard BEFORE INSERT ON review_step
    FOR EACH ROW EXECUTE FUNCTION review_step_guard();

CREATE FUNCTION decision_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    SELECT * INTO a FROM application WHERE id = NEW.application_id;
    IF a.id IS NULL OR NEW.decided_by IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'a decision names its author' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF a.state <> 'PENDING_DECISION' THEN
        RAISE EXCEPTION 'application % is not pending decision', a.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF case_assignee(a.id, 'DECISION') IS DISTINCT FROM NEW.decided_by
       OR NOT holds_assignable(NEW.decided_by, 'decision.record', a.office_id) THEN
        RAISE EXCEPTION 'only the decision authority assigned decides' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.decided_by = a.applicant_id
       OR EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = a.id AND s.kind = 'RECOMMENDATION'
                                               AND s.actor_id = NEW.decided_by) THEN
        RAISE EXCEPTION 'the decision authority never recommended on the case nor applied' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF length(btrim(coalesce(NEW.grounds, ''))) < 10 THEN
        RAISE EXCEPTION 'a decision states its grounds' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.follows_step_id IS DISTINCT FROM (SELECT s.id FROM review_step s WHERE s.application_id = a.id
                                              AND s.kind = 'RECOMMENDATION' ORDER BY s.at DESC LIMIT 1) THEN
        RAISE EXCEPTION 'a decision follows the latest recommendation' USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(NEW.cited_versions) v
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv JOIN document d ON d.id = dv.document_id
                                   WHERE dv.id = v AND d.application_id = a.id AND dv.status = 'STORED')) THEN
        RAISE EXCEPTION 'a decision cites stored versions of the application''s own documents' USING ERRCODE = 'check_violation';
    END IF;
    -- Last: what the decision says is checked first, so the refusal names the real problem.
    IF application_open_alerts(a.id) > 0 THEN
        RAISE EXCEPTION 'an overlap or duplicate alert on the parcel of application % is open', a.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    NEW.simulated := true;
    NEW.at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_guard BEFORE INSERT ON decision
    FOR EACH ROW EXECUTE FUNCTION decision_guard();

CREATE TRIGGER case_assignment_append_only BEFORE UPDATE OR DELETE ON case_assignment
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER review_step_append_only BEFORE UPDATE OR DELETE ON review_step
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER decision_insert_only BEFORE UPDATE OR DELETE ON decision
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER case_assignment_no_truncate BEFORE TRUNCATE ON case_assignment
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
CREATE TRIGGER review_step_no_truncate BEFORE TRUNCATE ON review_step
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
CREATE TRIGGER decision_no_truncate BEFORE TRUNCATE ON decision
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- The lifecycle: review and decision moves need their records --------------------------------

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
                             AND d.decided_by = actor) THEN
                RAISE EXCEPTION 'the decision is recorded first' USING ERRCODE = 'restrict_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'application % cannot go from % to % here', OLD.id, OLD.state, NEW.state
                USING ERRCODE = 'restrict_violation';
    END CASE;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- --- Row-level security ----------------------------------------------------------------------

-- Reviewers and decision authorities see the cases assigned to them and those they may claim.
CREATE POLICY application_assigned_read ON application FOR SELECT TO snigf_app
    USING (office_id IS NOT NULL AND case_visible_to_assignee(id, office_id, state, current_actor()));
-- ... and move the state of the cases assigned to them (the guard says which moves).
CREATE POLICY application_assigned_handle ON application FOR UPDATE TO snigf_app
    USING (office_id IS NOT NULL AND applicant_id <> current_actor()
           AND EXISTS (SELECT 1 FROM unnest(ARRAY['REVIEW', 'DECISION']) st WHERE case_assignee(id, st) = current_actor()))
    WITH CHECK (office_id IS NOT NULL AND applicant_id <> current_actor());

ALTER TABLE case_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE decision ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_assignment_read ON case_assignment FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY case_assignment_add ON case_assignment FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
-- Recommendations and findings are internal: staff who see the case read them, never the applicant.
CREATE POLICY review_step_read ON review_step FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id AND a.applicant_id <> current_actor()));
CREATE POLICY review_step_add ON review_step FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY decision_read ON decision FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY decision_add ON decision FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));

-- --- Grants ----------------------------------------------------------------------------------

REVOKE ALL ON case_assignment, review_step, decision FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION case_assignee(uuid, text), case_visible_to_assignee(uuid, uuid, text, uuid) FROM PUBLIC;
GRANT SELECT, INSERT ON case_assignment, review_step, decision TO snigf_app;
GRANT EXECUTE ON FUNCTION case_assignee(uuid, text), case_visible_to_assignee(uuid, uuid, text, uuid) TO snigf_app;
