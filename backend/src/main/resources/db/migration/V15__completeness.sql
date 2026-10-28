-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V15: completeness review and information requests (MVP-03, E-14, ADR-034).
--
--   application_missing_items()  the automatic check: required fields left empty and required
--                                document categories without a stored file, from the type
--                                version the application was submitted under. One function,
--                                used by the screens, the service and the guard alike.
--   completeness_record          every step, append-only: the automatic result, an officer's
--                                acceptance or information request (categories and message),
--                                the applicant's response. The database computes what was
--                                missing at that moment; nobody supplies it.
--
-- The lifecycle (V13) gains its rules: each move needs its record, written in the same
-- transaction by an entitled person; the automatic check is the system's (no actor); an officer
-- never handles their own application; acceptance needs nothing missing. The moves of review
-- and decision (E-15) already require their permission at the office. While information is
-- requested, the applicant may add files only in the categories asked for.

SET search_path = snigf, public;

-- A permission held at an office by a role or by a special grant in force (V10, V12).
CREATE FUNCTION holds_at(p_user uuid, p_permission text, p_office uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_user IS NOT NULL AND p_office IS NOT NULL
       AND (holds_permission_at(p_user, p_permission, p_office) OR holds_grant_at(p_user, p_permission, p_office))
$$;

-- The office of an application, for the endpoints' permission checks (never shown).
CREATE FUNCTION application_office_code(p_application uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT o.code FROM application a JOIN office o ON o.id = a.office_id WHERE a.id = p_application
$$;

-- --- The automatic check -----------------------------------------------------------------------

CREATE FUNCTION application_missing_items(p_application uuid) RETURNS text[]
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce(array_agg(item ORDER BY part, position), '{}')
      FROM (
        SELECT 1 AS part, f.position, 'MISSING:' || (f.field ->> 'key') AS item
          FROM application a
          JOIN application_type t ON t.id = a.type_version_id
          CROSS JOIN LATERAL jsonb_array_elements(t.requirements -> 'fields') WITH ORDINALITY AS f(field, position)
         WHERE a.id = p_application
           AND coalesce((f.field ->> 'required')::boolean, false)
           AND coalesce(btrim(a.data ->> (f.field ->> 'key')), '') = ''
        UNION ALL
        SELECT 2, d.position, 'MISSING_DOCUMENT:' || (d.doc ->> 'code')
          FROM application a
          JOIN application_type t ON t.id = a.type_version_id
          CROSS JOIN LATERAL jsonb_array_elements(t.requirements -> 'documents') WITH ORDINALITY AS d(doc, position)
         WHERE a.id = p_application
           AND coalesce((d.doc ->> 'required')::boolean, false)
           AND NOT EXISTS (SELECT 1 FROM document doc JOIN document_version v ON v.document_id = doc.id
                            WHERE doc.application_id = a.id AND doc.category = d.doc ->> 'code' AND v.status = 'STORED')
      ) AS items
$$;

-- --- The records -------------------------------------------------------------------------------

CREATE TABLE completeness_record (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    kind            text NOT NULL CHECK (kind IN ('AUTOMATIC', 'COMPLETE', 'INFO_REQUESTED', 'RESPONSE')),
    missing         text[] NOT NULL DEFAULT '{}',
    requested       text[],
    message         text CHECK (message IS NULL OR length(btrim(message)) BETWEEN 1 AND 2000),
    actor_id        uuid REFERENCES app_user (id),
    at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_completeness_actor CHECK ((kind = 'AUTOMATIC') = (actor_id IS NULL)),
    CONSTRAINT ck_completeness_request CHECK (
        (kind = 'INFO_REQUESTED') = (requested IS NOT NULL AND cardinality(requested) > 0)
        AND (kind <> 'INFO_REQUESTED' OR length(btrim(message)) >= 10)
        AND (kind NOT IN ('AUTOMATIC', 'COMPLETE') OR message IS NULL))
);
CREATE INDEX ix_completeness_record_application ON completeness_record (application_id, at);

CREATE FUNCTION completeness_record_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    SELECT * INTO a FROM application WHERE id = NEW.application_id;
    IF a.id IS NULL THEN
        RAISE EXCEPTION 'no such application' USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- What was missing is the database's finding, at this moment, never the caller's.
    NEW.missing := application_missing_items(a.id);
    NEW.at := clock_timestamp();
    IF NEW.kind = 'AUTOMATIC' THEN
        IF a.state NOT IN ('SUBMITTED', 'INFO_REQUESTED') THEN
            RAISE EXCEPTION 'the automatic check runs on submission or response' USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.actor_id IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'a record names its actor' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.kind = 'RESPONSE' THEN
        IF a.state <> 'INFO_REQUESTED' OR NEW.actor_id <> a.applicant_id THEN
            RAISE EXCEPTION 'only the applicant responds to an information request' USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;
    -- COMPLETE or INFO_REQUESTED: an officer of the office, never the applicant.
    IF a.state <> 'COMPLETENESS_REVIEW' THEN
        RAISE EXCEPTION 'application % is not in completeness review', a.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.actor_id = a.applicant_id THEN
        RAISE EXCEPTION 'nobody reviews their own application' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.kind = 'COMPLETE' THEN
        IF NOT holds_at(NEW.actor_id, 'completeness.review', a.office_id) THEN
            RAISE EXCEPTION 'accepting needs completeness.review at the office' USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF cardinality(NEW.missing) > 0 THEN
            RAISE EXCEPTION 'application % still misses %', a.id, NEW.missing USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NOT holds_at(NEW.actor_id, 'completeness.request-info', a.office_id) THEN
            RAISE EXCEPTION 'requesting information needs completeness.request-info at the office'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF EXISTS (SELECT 1 FROM unnest(NEW.requested) r
                    WHERE NOT (SELECT t.requirements -> 'documents' @> jsonb_build_array(jsonb_build_object('code', r))
                                 FROM application_type t WHERE t.id = a.type_version_id)) THEN
            RAISE EXCEPTION 'only document categories of the type can be requested' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER completeness_record_guard BEFORE INSERT ON completeness_record
    FOR EACH ROW EXECUTE FUNCTION completeness_record_guard();
CREATE TRIGGER completeness_record_append_only BEFORE UPDATE OR DELETE ON completeness_record
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER completeness_record_no_truncate BEFORE TRUNCATE ON completeness_record
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- The latest record of an application written since the given moment, by the given actor.
CREATE FUNCTION completeness_recorded(p_application uuid, p_kind text, p_actor uuid, p_since timestamptz) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM completeness_record r
                    WHERE r.application_id = p_application AND r.kind = p_kind AND r.at >= p_since
                      AND r.actor_id IS NOT DISTINCT FROM p_actor)
$$;

-- --- The lifecycle's rules ---------------------------------------------------------------------

-- The automatic check is the only move without a person.
ALTER TABLE application_transition ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE application_transition ADD CONSTRAINT ck_transition_actor CHECK (
    actor_id IS NOT NULL OR (from_state IN ('SUBMITTED', 'INFO_REQUESTED') AND to_state = 'COMPLETENESS_REVIEW'));

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
            IF actor = OLD.applicant_id OR NOT holds_at(actor, 'review.recommend', OLD.office_id) THEN
                RAISE EXCEPTION 'a recommendation needs review.recommend at the office' USING ERRCODE = 'insufficient_privilege';
            END IF;
        WHEN OLD.state = 'PENDING_DECISION' THEN
            IF actor = OLD.applicant_id OR NOT holds_at(actor, 'decision.record', OLD.office_id) THEN
                RAISE EXCEPTION 'a decision needs decision.record at the office' USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSE
            RAISE EXCEPTION 'application % cannot go from % to % here', OLD.id, OLD.state, NEW.state
                USING ERRCODE = 'restrict_violation';
    END CASE;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- --- Documents during an information request ----------------------------------------------------

-- A draft takes any category of its type; an information request only the categories asked for.
CREATE OR REPLACE FUNCTION document_accepted(p_application uuid, p_category text, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1
          FROM application a
          JOIN application_type t ON t.id = a.type_version_id
         WHERE a.id = p_application
           AND a.applicant_id = p_actor
           AND t.requirements -> 'documents' @> jsonb_build_array(jsonb_build_object('code', p_category))
           AND (a.state = 'DRAFT'
                OR (a.state = 'INFO_REQUESTED'
                    AND p_category = ANY (coalesce((SELECT r.requested FROM completeness_record r
                                                     WHERE r.application_id = a.id AND r.kind = 'INFO_REQUESTED'
                                                     ORDER BY r.at DESC LIMIT 1), '{}'::text[])))))
$$;

-- --- Row-level security ----------------------------------------------------------------------

ALTER TABLE completeness_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY completeness_record_read ON completeness_record FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY completeness_record_add ON completeness_record FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));

-- Staff move the state of their office's applications; the guard says which moves and who.
CREATE POLICY application_handle ON application FOR UPDATE TO snigf_app
    USING (office_id IS NOT NULL AND applicant_id <> current_actor()
           AND holds_permission_at(current_actor(), 'application.read', office_id))
    WITH CHECK (office_id IS NOT NULL AND applicant_id <> current_actor());

-- --- Grants ----------------------------------------------------------------------------------

REVOKE ALL ON completeness_record FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION application_office_code(uuid) FROM PUBLIC;
GRANT SELECT, INSERT ON completeness_record TO snigf_app;
GRANT EXECUTE ON FUNCTION application_office_code(uuid) TO snigf_app;
