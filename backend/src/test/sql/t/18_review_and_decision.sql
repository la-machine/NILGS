-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Review and simulated decision (MVP-05, E-15, V16): reviewers and decision authorities see the
-- cases of their office they may claim and those assigned to them; only the assigned reviewer
-- recommends; whoever recommended never decides; an open alert on the parcel blocks the
-- decision; a decision has grounds, cites the application's own stored versions, is simulated
-- and final; the applicant reads the decision, never the internal review.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(27);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer (author of the parcels); b3 applicant; i1 intake; r1 reviewer and decision
-- authority at DEMO1; r2 reviewer at DEMO1; d1 decision authority at DEMO1; d2 at DEMO2; c1 cadastral (resolves alerts).
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c3', 'r2@demo.invalid', 'Reviewer Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'd2@demo.invalid', 'Decision Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c6', 'c1@demo.invalid', 'Cadastre', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c6', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}]}');

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
CREATE FUNCTION pg_temp.seen(p_actor text, p_table text) RETURNS int
LANGUAGE plpgsql AS $f$
DECLARE n int;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    EXECUTE format('SELECT count(*) FROM snigf.%I', p_table) INTO n;
    RESET ROLE;
    RETURN n;
END;
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = '00000000-0000-0000-0000-00000000d001' $q$, p_state, p_actor))
$f$;
CREATE FUNCTION pg_temp.step(p_actor text, p_kind text, p_recommendation text, p_text text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L, %L) $q$, p_kind, p_recommendation, p_text, p_actor))
$f$;
CREATE FUNCTION pg_temp.claim(p_actor text, p_stage text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L) $q$, p_stage, p_actor, p_actor))
$f$;
CREATE FUNCTION pg_temp.decide(p_actor text, p_outcome text, p_grounds text, p_cited text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions,
        follows_step_id, decided_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L::uuid[],
                (SELECT id FROM snigf.review_step WHERE kind = 'RECOMMENDATION' ORDER BY at DESC LIMIT 1), %L) $q$,
        p_outcome, p_grounds, p_cited, p_actor))
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

-- The application's parcel, and another one overlapping it: an open alert.
SELECT pg_temp.parcel(1, 900000);
SELECT pg_temp.parcel(2, 900050);

-- An application under review, with one stored document (as its applicant, then the intake officer).
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id
      FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
    status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000f1', 1, 'STORED',
            '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.completeness_record (application_id, kind)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'AUTOMATIC') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL, version = version + 1 $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$);
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000c1', 'UNDER_REVIEW');

-- --- Review ------------------------------------------------------------------------------------
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000c3', 'application'), 1, 'a reviewer of the office sees a case to claim');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000c4', 'application'), 0, 'a decision authority does not, before it is pending decision');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c2', 'REVIEW'), 1, 'a reviewer claims it');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000c3', 'application'), 0, 'another reviewer no longer sees it');
SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000c3', 'REVIEW') $$,
    '42501', NULL, '... nor claims it');
SELECT throws_ok($$ SELECT pg_temp.step('00000000-0000-0000-0000-0000000000c3', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Everything is in order') $$,
    '42501', NULL, 'only the reviewer assigned recommends');
SELECT throws_ok($$ SELECT pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION') $$,
    '23001', NULL, 'no move without its recommendation');
SELECT throws_ok($$ SELECT pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Fine') $$,
    '23514', NULL, 'a finding says something (10 characters at least)');
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Boundaries and titles are consistent')
       + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION'), 2,
    'the reviewer recommends; the case is pending decision');

-- --- Decision -----------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000c2', 'DECISION') $$,
    '42501', NULL, 'whoever recommended never decides, even holding the role');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000c5', 'application'), 0, 'a decision authority of another office sees nothing');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c4', 'DECISION'), 1, 'a decision authority of the office claims it');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c2', 'APPROVED', 'Title proven and boundaries agreed',
    '{00000000-0000-0000-0000-0000000000e1}') $$, '42501', NULL, 'the reviewer cannot decide');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED', 'Fine', '{00000000-0000-0000-0000-0000000000e1}') $$,
    '23514', NULL, 'a decision has grounds');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED', 'Title proven and boundaries agreed',
    '{00000000-0000-0000-0000-0000000000ee}') $$, '23514', NULL, 'it cites the application''s own stored versions');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED', 'Title proven and boundaries agreed',
    '{00000000-0000-0000-0000-0000000000e1}') $$, '23001', NULL, 'an open overlap alert on the parcel blocks the decision');

-- Returned to review, recommended again: the decision follows the latest recommendation.
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c4', 'RETURN', NULL, 'Please check the overlap with the neighbour')
       + pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'UNDER_REVIEW'), 2, 'the decision authority returns the case');
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Overlap checked: a survey error')
       + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION'), 2, 'the reviewer recommends again');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c6', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED',
    resolution = 'DATA_ERROR_CORRECTED', resolution_note = 'Survey error, neighbour boundary to be corrected',
    resolved_by = '00000000-0000-0000-0000-0000000000c6', resolved_at = now(), version = version + 1 $q$);
SELECT col_is_unique('snigf', 'decision', 'application_id', 'one decision per application');
SELECT is(pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED', 'Title proven and boundaries agreed',
    '{00000000-0000-0000-0000-0000000000e1}') + pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'APPROVED'), 2,
    'once the alert is resolved, the decision authority decides');
SELECT ok((SELECT simulated FROM decision), 'the decision is simulated');
SELECT throws_ok($$ UPDATE snigf.decision SET grounds = 'Rewritten grounds afterwards' $$, '23001', NULL, 'a decision is final');
SELECT throws_ok($$ SELECT pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'UNDER_REVIEW') $$,
    '23001', NULL, 'approved is final');

-- --- What each sees --------------------------------------------------------------------------------
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000b3', 'decision'), 1, 'the applicant reads the decision');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000b3', 'review_step'), 0, '... never the internal review');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000c1', 'review_step'), 3, 'office staff read the review');
SELECT results_eq($$ SELECT to_state FROM application_transition ORDER BY at $$,
    $$ VALUES ('SUBMITTED'), ('COMPLETENESS_REVIEW'), ('UNDER_REVIEW'), ('PENDING_DECISION'), ('UNDER_REVIEW'),
              ('PENDING_DECISION'), ('APPROVED') $$, 'the history keeps every step');

SELECT * FROM finish();
ROLLBACK;
