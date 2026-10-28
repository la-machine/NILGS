-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V13: applications, slice 3 (MVP-02, E-12, ADR-032).
--
--   application_type        the DEMO service catalogue: one row per version of a type, insert
--                           only; requirements (fields, document categories) as JSON. The
--                           application writes a new version when its settings change.
--   application             the request: owner, type version, state, parcel, office, the
--                           form's data; a reference only once submitted.
--   application_transition  every state change (from, to, actor, reason, time), written by
--                           the database itself, append-only.
--
-- Zero trust, in the database:
--   * row-level security: the application sees an application only for the actor it names
--     in the transaction (snigf.actor_id): its owner, or staff holding application.read at
--     its office. No actor named, no row. A draft is never visible to staff (no office yet);
--   * the nine states and their only legal moves (409 INVALID_TRANSITION in the API) are
--     checked here; submitting and withdrawing are the owner's acts;
--   * once submitted, the form, parcel, office, type version and reference never change;
--     only a draft can be deleted; the owner never changes.

SET search_path = snigf, public;

-- --- The actor of the transaction --------------------------------------------------------

-- The account the application acts for, set by the application at the start of each
-- transaction (set_config(..., true)); NULL when none, so policies match nothing.
CREATE FUNCTION current_actor() RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT nullif(current_setting('snigf.actor_id', true), '')::uuid
$$;

-- --- Service catalogue -------------------------------------------------------------------

CREATE TABLE application_type (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    code            text NOT NULL CHECK (code ~ '^DEMO_[A-Z0-9_]{2,40}$'),
    version_no      int NOT NULL CHECK (version_no >= 1),
    effective_from  timestamptz NOT NULL DEFAULT clock_timestamp(),
    available       boolean NOT NULL,
    -- C4: a demonstration type says so in both languages.
    name_fr         text NOT NULL CHECK (length(name_fr) BETWEEN 3 AND 120 AND name_fr LIKE '%DEMO%'),
    name_en         text NOT NULL CHECK (length(name_en) BETWEEN 3 AND 120 AND name_en LIKE '%DEMO%'),
    requirements    jsonb NOT NULL CHECK (jsonb_typeof(requirements) = 'object'
                                          AND jsonb_typeof(requirements -> 'fields') = 'array'
                                          AND jsonb_typeof(requirements -> 'documents') = 'array'
                                          AND pg_column_size(requirements) <= 32768),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (code, version_no),
    UNIQUE (code, effective_from)
);
CREATE TRIGGER application_type_insert_only BEFORE UPDATE OR DELETE ON application_type
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER application_type_no_truncate BEFORE TRUNCATE ON application_type
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- A new version follows the last one of its code, never jumps or rewinds.
CREATE FUNCTION application_type_next_version() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('snigf.application-type:' || NEW.code));
    IF NEW.version_no <> coalesce((SELECT max(version_no) FROM application_type WHERE code = NEW.code), 0) + 1 THEN
        RAISE EXCEPTION 'version % of type % does not follow the last one', NEW.version_no, NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.effective_from := clock_timestamp();
    NEW.created_at := NEW.effective_from;
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_type_next_version BEFORE INSERT ON application_type
    FOR EACH ROW EXECUTE FUNCTION application_type_next_version();

-- --- Applications --------------------------------------------------------------------------

CREATE TABLE application (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    reference          varchar(32) UNIQUE
                       CHECK (reference ~ '^DEMO-APP-[0-9]{4}-[0-9]{6}-[0-9A-Z]$'),
    applicant_id       uuid NOT NULL REFERENCES app_user (id),
    type_code          text NOT NULL,
    type_version_id    uuid NOT NULL REFERENCES application_type (id),
    state              text NOT NULL DEFAULT 'DRAFT' CHECK (state IN (
                           'DRAFT', 'SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW',
                           'PENDING_DECISION', 'APPROVED', 'REFUSED', 'WITHDRAWN')),
    parcel_reference   varchar(40) CHECK (parcel_reference ~ '^DEMO-PCL-[A-Z0-9]{2,16}-[0-9]{4}-[0-9]{6}-[0-9A-Z]$'),
    parcel_id          uuid REFERENCES parcel (id),
    office_id          uuid REFERENCES office (id),
    data               jsonb NOT NULL DEFAULT '{}'
                       CHECK (jsonb_typeof(data) = 'object' AND pg_column_size(data) <= 16384),
    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    submitted_at       timestamptz,
    -- Who moves the state and why: read by the transition trigger, never by anyone else.
    state_changed_by   uuid REFERENCES app_user (id),
    state_reason       text CHECK (state_reason IS NULL OR length(state_reason) BETWEEN 3 AND 2000),
    version            bigint NOT NULL DEFAULT 0,   -- optimistic lock
    -- A draft has no reference; anything submitted has its reference, parcel, office and date.
    CONSTRAINT ck_application_submitted CHECK (
        (state = 'DRAFT' AND reference IS NULL AND submitted_at IS NULL AND parcel_id IS NULL AND office_id IS NULL)
        OR (state <> 'DRAFT' AND reference IS NOT NULL AND submitted_at IS NOT NULL
            AND parcel_id IS NOT NULL AND office_id IS NOT NULL AND parcel_reference IS NOT NULL))
);
CREATE INDEX ix_application_applicant ON application (applicant_id, updated_at DESC);
CREATE INDEX ix_application_office ON application (office_id, state) WHERE office_id IS NOT NULL;

CREATE TABLE application_transition (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    from_state      text NOT NULL,
    to_state        text NOT NULL,
    actor_id        uuid NOT NULL REFERENCES app_user (id),
    reason          text,
    at              timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_application_transition ON application_transition (application_id, at);
CREATE TRIGGER application_transition_append_only BEFORE UPDATE OR DELETE ON application_transition
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER application_transition_no_truncate BEFORE TRUNCATE ON application_transition
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- The only legal moves (Baseline, application lifecycle).
CREATE FUNCTION application_transition_legal(p_from text, p_to text) RETURNS boolean
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
        ('SUBMITTED', 'WITHDRAWN'),
        ('COMPLETENESS_REVIEW', 'WITHDRAWN'),
        ('INFO_REQUESTED', 'WITHDRAWN'),
        ('UNDER_REVIEW', 'WITHDRAWN'),
        ('PENDING_DECISION', 'WITHDRAWN'))
$$;

CREATE FUNCTION application_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
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

    IF NEW.state <> OLD.state THEN
        IF NOT application_transition_legal(OLD.state, NEW.state) THEN
            RAISE EXCEPTION 'application % cannot go from % to %', OLD.id, OLD.state, NEW.state
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.state_changed_by IS NULL THEN
            RAISE EXCEPTION 'a state change names its actor' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.state IN ('SUBMITTED', 'WITHDRAWN') AND NEW.state_changed_by <> OLD.applicant_id THEN
            RAISE EXCEPTION 'only the applicant submits or withdraws application %', OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.state = 'SUBMITTED' THEN
            -- The parcel must be active, and the office is the parcel's, whatever the request said.
            IF NOT EXISTS (SELECT 1 FROM parcel p WHERE p.id = NEW.parcel_id AND p.status = 'ACTIVE'
                             AND p.reference = NEW.parcel_reference AND p.office_id = NEW.office_id) THEN
                RAISE EXCEPTION 'application % names no active parcel of its office', OLD.id
                    USING ERRCODE = 'check_violation';
            END IF;
            IF NEW.type_version_id IS DISTINCT FROM (SELECT t.id FROM application_type t WHERE t.code = NEW.type_code
                                                      ORDER BY t.version_no DESC LIMIT 1)
               OR NOT (SELECT available FROM application_type WHERE id = NEW.type_version_id) THEN
                RAISE EXCEPTION 'application % is submitted under the current version of its type', OLD.id
                    USING ERRCODE = 'check_violation';
            END IF;
            NEW.submitted_at := clock_timestamp();
        END IF;
    ELSIF NEW.state_changed_by IS DISTINCT FROM OLD.state_changed_by
          OR NEW.state_reason IS DISTINCT FROM OLD.state_reason THEN
        RAISE EXCEPTION 'actor and reason belong to a state change' USING ERRCODE = 'check_violation';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_guard BEFORE INSERT OR UPDATE ON application
    FOR EACH ROW EXECUTE FUNCTION application_guard();

-- Every state change is written to the history by the database, as the owner of the table.
CREATE FUNCTION application_record_transition() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    INSERT INTO application_transition (application_id, from_state, to_state, actor_id, reason)
    VALUES (NEW.id, OLD.state, NEW.state, NEW.state_changed_by, NEW.state_reason);
    RETURN NULL;
END;
$$;
CREATE TRIGGER application_record_transition AFTER UPDATE OF state ON application
    FOR EACH ROW WHEN (NEW.state IS DISTINCT FROM OLD.state)
    EXECUTE FUNCTION application_record_transition();

-- Only a draft can be deleted (it has no reference, no history, nothing submitted).
CREATE FUNCTION application_delete_draft_only() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF OLD.state <> 'DRAFT' THEN
        RAISE EXCEPTION 'application % was submitted and is kept', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER application_delete_draft_only BEFORE DELETE ON application
    FOR EACH ROW EXECUTE FUNCTION application_delete_draft_only();
CREATE TRIGGER application_no_truncate BEFORE TRUNCATE ON application
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Row-level security ----------------------------------------------------------------------

ALTER TABLE application ENABLE ROW LEVEL SECURITY;
ALTER TABLE application_transition ENABLE ROW LEVEL SECURITY;

CREATE POLICY application_read ON application FOR SELECT TO snigf_app
    USING (applicant_id = current_actor()
           OR (office_id IS NOT NULL AND holds_permission_at(current_actor(), 'application.read', office_id)));
CREATE POLICY application_create ON application FOR INSERT TO snigf_app
    WITH CHECK (applicant_id = current_actor());
CREATE POLICY application_change ON application FOR UPDATE TO snigf_app
    USING (applicant_id = current_actor()) WITH CHECK (applicant_id = current_actor());
CREATE POLICY application_discard ON application FOR DELETE TO snigf_app
    USING (applicant_id = current_actor() AND state = 'DRAFT');
CREATE POLICY application_transition_read ON application_transition FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));

-- --- Grants ----------------------------------------------------------------------------------

REVOKE ALL ON application_type, application, application_transition FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION application_record_transition() FROM PUBLIC;
GRANT SELECT, INSERT ON application_type TO snigf_app;
GRANT SELECT, INSERT, DELETE ON application TO snigf_app;
GRANT UPDATE (state, parcel_reference, parcel_id, office_id, data, type_version_id, reference,
              state_changed_by, state_reason, version) ON application TO snigf_app;
GRANT SELECT ON application_transition TO snigf_app;
