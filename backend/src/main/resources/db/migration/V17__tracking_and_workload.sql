-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V17: tracking and workload report (MVP-06, E-16, ADR-036).
--
--   office_stays()              every stay of an office's applications in a state, from the
--                               transition table only: entered, and left (NULL while still there)
--   workload_counts()           applications of an office per state
--   workload_open_cases()       the office's applications still under way: since when in their
--                               state, against the DEMO target of that state, and who holds them
--   workload_completed_stays()  the stays that ended in a period, with how long each lasted
--
-- Baseline acceptance: "stage durations derive from the transition table, not edited fields".
-- Nothing here reads updated_at or any column a person writes: only application_transition,
-- which the database itself appends to (V13) and nobody changes.
--
-- Zero trust, in the database: the report functions answer only for an office where the actor
-- of the transaction holds report.workload (a role or a special grant in force, holds_at);
-- for anyone else they return nothing. They read as the owner of the tables, so a holder of
-- report.workload needs no application.read: they get counts and durations, never a form, a
-- document, an applicant's name or a review. The targets come from the caller (settings,
-- in seconds per state); a state without a target is never overdue.

SET search_path = snigf, public;

-- --- Stays -------------------------------------------------------------------------------------

CREATE FUNCTION office_stays(p_office uuid)
RETURNS TABLE (application_id uuid, state text, entered_at timestamptz, left_at timestamptz)
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.application_id, t.to_state, t.at,
           lead(t.at) OVER (PARTITION BY t.application_id ORDER BY t.at, t.id)
      FROM application_transition t
      JOIN application a ON a.id = t.application_id
     WHERE a.office_id = p_office
$$;

-- The office of a report, when the actor of the transaction may read its workload; NULL otherwise.
CREATE FUNCTION workload_office(p_office_code text) RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT o.id FROM office o
     WHERE o.code = p_office_code AND holds_at(current_actor(), 'report.workload', o.id)
$$;

-- The target of a state, from {"COMPLETENESS_REVIEW": 432000, ...} (seconds); NULL when none.
CREATE FUNCTION workload_target(p_targets jsonb, p_state text) RETURNS interval
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN jsonb_typeof(p_targets -> p_state) = 'number' AND (p_targets ->> p_state)::numeric > 0
                THEN make_interval(secs => (p_targets ->> p_state)::double precision) END
$$;

-- --- Reports -----------------------------------------------------------------------------------

CREATE FUNCTION workload_counts(p_office_code text)
RETURNS TABLE (state text, cases bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.state, count(*)
      FROM application a
     WHERE a.office_id = workload_office(p_office_code) AND a.state <> 'DRAFT'
     GROUP BY a.state
$$;

CREATE FUNCTION workload_open_cases(p_office_code text, p_targets jsonb)
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
      LEFT JOIN case_assignment c ON c.application_id = a.id
                                 AND c.stage = CASE a.state WHEN 'UNDER_REVIEW' THEN 'REVIEW'
                                                            WHEN 'PENDING_DECISION' THEN 'DECISION' END
      LEFT JOIN app_user u ON u.id = c.assignee_id
     WHERE a.state IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION')
$$;

CREATE FUNCTION workload_completed_stays(p_office_code text, p_targets jsonb, p_from timestamptz)
RETURNS TABLE (state text, stays bigint, average interval, median interval, longest interval, over_target bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT s.state, count(*),
           avg(s.left_at - s.entered_at),
           make_interval(secs => percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM s.left_at - s.entered_at))),
           max(s.left_at - s.entered_at),
           count(*) FILTER (WHERE s.left_at - s.entered_at > workload_target(p_targets, s.state))
      FROM office_stays(workload_office(p_office_code)) s
     WHERE s.left_at IS NOT NULL AND s.left_at >= p_from
       AND s.state IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION')
     GROUP BY s.state
$$;

-- --- Grants ------------------------------------------------------------------------------------

REVOKE EXECUTE ON FUNCTION office_stays(uuid), workload_office(text), workload_target(jsonb, text),
    workload_counts(text), workload_open_cases(text, jsonb), workload_completed_stays(text, jsonb, timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION workload_counts(text), workload_open_cases(text, jsonb),
    workload_completed_stays(text, jsonb, timestamptz) TO snigf_app;
