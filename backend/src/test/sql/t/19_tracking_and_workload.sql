-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Tracking and workload report (MVP-06, E-16, V17): the report functions answer only for an
-- office where the actor holds report.workload; every time comes from the transition table;
-- the time in the current state, the overdue flag against the targets given, who holds a case,
-- and the durations of the stays that ended.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(20);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; b3 applicant; i1 intake at DEMO1; r1 reviewer at DEMO1; s1 supervisor at
-- DEMO1; s2 supervisor at DEMO2.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c3', 's1@demo.invalid', 'Supervisor One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 's2@demo.invalid', 'Supervisor Two', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a2', 'seed');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)', '{"fields": [], "documents": []}');

CREATE FUNCTION pg_temp.act(p_actor text, p_sql text) RETURNS int
LANGUAGE plpgsql AS $f$
DECLARE n int;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    EXECUTE p_sql;
    GET DIAGNOSTICS n = ROW_COUNT;
    RESET ROLE;
    RETURN n;
END;
$f$;
-- One value read as snigf_app for an actor.
CREATE FUNCTION pg_temp.q(p_actor text, p_sql text) RETURNS text
LANGUAGE plpgsql AS $f$
DECLARE v text;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    EXECUTE p_sql INTO v;
    RESET ROLE;
    RETURN v;
END;
$f$;
-- A parcel at DEMO1 of 100 m x 100 m, x0 metres east (UTM 32N), recorded by b1.
CREATE FUNCTION pg_temp.parcel(p_no int, p_x0 float8) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    r    snigf.geometry_check_result := snigf.normalize_parcel_geometry(format(
             '{"type":"Polygon","coordinates":[[[%s,400000],[%s,400000],[%s,400100],[%s,400100],[%s,400000]]]}',
             p_x0, p_x0 + 100, p_x0 + 100, p_x0, p_x0), 32632);
    pid  uuid := ('00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'))::uuid;
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (pid, format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), pid, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END;
$f$;
-- Application n (d00n) on parcel n, submitted and in completeness review, as its applicant.
CREATE FUNCTION pg_temp.submitted(p_no int) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    app text := '00000000-0000-0000-0000-00000000d00' || p_no;
BEGIN
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
        SELECT %L, '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$, app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
        parcel_reference = %L, parcel_id = %L, office_id = '00000000-0000-0000-0000-0000000000a1',
        reference = %L, state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = %L $q$,
        format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), '00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'),
        format('DEMO-APP-2026-%s-X', lpad(p_no::text, 6, '0')), app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.completeness_record (application_id, kind)
        VALUES (%L, 'AUTOMATIC') $q$, app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW',
        state_changed_by = NULL, version = version + 1 WHERE id = %L $q$, app));
END;
$f$;

SELECT pg_temp.parcel(1, 900000);
SELECT pg_temp.parcel(2, 902000);
SELECT pg_temp.submitted(1);
SELECT pg_temp.submitted(2);
-- Some time in completeness review, then d001 is accepted and claimed by r1; d002 waits.
SELECT pg_sleep(0.05);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ UPDATE snigf.application SET state = 'UNDER_REVIEW',
    state_changed_by = '00000000-0000-0000-0000-0000000000c1', version = version + 1
    WHERE id = '00000000-0000-0000-0000-00000000d001' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'REVIEW', '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000c2') $q$);
SELECT pg_sleep(0.02);

-- --- Who reads -----------------------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT string_agg(state || '=' || cases, ',' ORDER BY state)
    FROM snigf.workload_counts('DEMO1') $$), 'COMPLETENESS_REVIEW=1,UNDER_REVIEW=1', 'the supervisor of the office reads its counts');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c4', $$ SELECT count(*) FROM snigf.workload_counts('DEMO1') $$), '0',
    'a supervisor of another office reads nothing of it');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', $$ SELECT count(*) FROM snigf.workload_open_cases('DEMO1', '{}') $$), '0',
    'nor does an intake officer, who may read the applications but not the report');
SELECT is(pg_temp.q(NULL, $$ SELECT count(*) FROM snigf.workload_completed_stays('DEMO1', '{}', '-infinity') $$), '0',
    'nor anyone when no actor is named');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c3', 'SELECT count(*) FROM snigf.office_stays(''00000000-0000-0000-0000-0000000000a1'')') $$,
    '42501', NULL, 'the raw stays are not open to the application');
SELECT ok((SELECT bool_and(prosecdef) FROM pg_proc WHERE proname IN ('workload_counts', 'workload_open_cases', 'workload_completed_stays')),
    'the report functions read as the owner of the tables, after their own check');

-- --- Cases under way ---------------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT count(*) FROM snigf.workload_open_cases('DEMO1', '{}') $$), '2',
    'both cases are under way');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT since::text FROM snigf.workload_open_cases('DEMO1', '{}')
    WHERE application_id = '00000000-0000-0000-0000-00000000d001' $$),
    (SELECT max(at)::text FROM snigf.application_transition WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'the time in the state starts with the last move recorded in the transition table');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT assignee_name FROM snigf.workload_open_cases('DEMO1', '{}')
    WHERE application_id = '00000000-0000-0000-0000-00000000d001' $$), 'Reviewer One', 'who holds a case under review');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT coalesce(assignee_name, '-') FROM snigf.workload_open_cases('DEMO1', '{}')
    WHERE application_id = '00000000-0000-0000-0000-00000000d002' $$), '-', 'nobody holds a case in completeness review');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT overdue::text || '/' || coalesce(target::text, '-')
    FROM snigf.workload_open_cases('DEMO1', '{}') WHERE application_id = '00000000-0000-0000-0000-00000000d002' $$), 'false/-',
    'a state without a target is never overdue');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT overdue::text FROM snigf.workload_open_cases('DEMO1',
    '{"COMPLETENESS_REVIEW": 86400}') WHERE application_id = '00000000-0000-0000-0000-00000000d002' $$), 'false',
    'within its target, not overdue');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT overdue::text || '/' || target::text FROM snigf.workload_open_cases('DEMO1',
    '{"COMPLETENESS_REVIEW": 0.001}') WHERE application_id = '00000000-0000-0000-0000-00000000d002' $$), 'true/00:00:00.001',
    'past its target, overdue');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT overdue::text FROM snigf.workload_open_cases('DEMO1',
    '{"COMPLETENESS_REVIEW": 0, "UNDER_REVIEW": "1"}') WHERE application_id = '00000000-0000-0000-0000-00000000d002' $$), 'false',
    'a target of zero or not a number is no target');

-- --- Stays that ended ------------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT string_agg(state || '=' || stays, ',' ORDER BY state)
    FROM snigf.workload_completed_stays('DEMO1', '{}', '-infinity') $$), 'COMPLETENESS_REVIEW=1,SUBMITTED=2',
    'one stay per departure: both left submitted, one left completeness review');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT average::text FROM snigf.workload_completed_stays('DEMO1', '{}', '-infinity')
    WHERE state = 'COMPLETENESS_REVIEW' $$),
    (SELECT (l.at - e.at)::text FROM snigf.application_transition e JOIN snigf.application_transition l
        ON l.application_id = e.application_id AND l.from_state = 'COMPLETENESS_REVIEW'
      WHERE e.application_id = '00000000-0000-0000-0000-00000000d001' AND e.to_state = 'COMPLETENESS_REVIEW'),
    'the duration is the time between the two moves of the transition table');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT over_target::text || '/' || (longest >= interval '50 milliseconds')::text
    FROM snigf.workload_completed_stays('DEMO1', '{"COMPLETENESS_REVIEW": 0.001}', '-infinity') WHERE state = 'COMPLETENESS_REVIEW' $$),
    '1/true', 'a stay longer than its target counts as over target');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT over_target::text
    FROM snigf.workload_completed_stays('DEMO1', '{"COMPLETENESS_REVIEW": 86400}', '-infinity') WHERE state = 'COMPLETENESS_REVIEW' $$),
    '0', 'a stay within its target does not');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', $$ SELECT count(*) FROM snigf.workload_completed_stays('DEMO1', '{}',
    clock_timestamp() + interval '1 day') $$), '0', 'only the stays that ended in the period');

-- Nothing a person writes moves the times: the transition table cannot be changed.
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c3', 'UPDATE snigf.application_transition SET at = at - interval ''10 days''') $$,
    '42501', NULL, 'the application cannot rewrite the transition table');

SELECT * FROM finish();
ROLLBACK;
