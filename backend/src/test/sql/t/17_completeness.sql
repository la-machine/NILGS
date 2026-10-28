-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Completeness (MVP-03, E-14, V15): the automatic check finds what is missing; an intake officer
-- of the office, never the applicant, accepts only a complete application or requests
-- information; the applicant responds with files in the categories asked for; every move needs
-- its record, and the history keeps the whole round trip.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(26);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 intake officer at DEMO1; b6 cadastral officer at DEMO1; b5 intake officer at DEMO2; b3 applicant.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b5', 'intake2@demo.invalid', 'Intake 2', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b6', 'cadastre@demo.invalid', 'Cadastre', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b6', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b5', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed');
INSERT INTO parcel (id, reference, office_id, current_version_no, created_by) VALUES
    ('00000000-0000-0000-0000-0000000030a1', 'DEMO-PCL-DEMO1-2026-000001-X', '00000000-0000-0000-0000-0000000000a1', 1,
     '00000000-0000-0000-0000-0000000000b6');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)',
     '{"fields": [{"key": "statement", "required": true}, {"key": "note", "required": false}],
       "documents": [{"code": "DEMO_ID", "required": true}, {"code": "DEMO_PLAN", "required": false}]}');

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
CREATE FUNCTION pg_temp.record(p_actor text, p_kind text, p_requested text DEFAULT NULL, p_message text DEFAULT NULL) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.completeness_record (application_id, kind, requested, message, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L::text[], %L, %L) $q$,
        p_kind, p_requested, p_message, CASE WHEN p_kind = 'AUTOMATIC' THEN NULL ELSE p_actor END))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text, p_by text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = '00000000-0000-0000-0000-00000000d001' $q$, p_state, p_by))
$f$;
CREATE FUNCTION pg_temp.missing() RETURNS text[]
LANGUAGE sql AS $f$ SELECT snigf.application_missing_items('00000000-0000-0000-0000-00000000d001') $f$;

-- A submitted application: the statement is there, no document yet.
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id, data)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id, '{"statement": "Mine"}'
      FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-X', parcel_id = '00000000-0000-0000-0000-0000000030a1',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$);

-- --- The automatic check -------------------------------------------------------------------------
SELECT is(pg_temp.missing(), '{MISSING_DOCUMENT:DEMO_ID}'::text[], 'the automatic check finds the required document missing');
SELECT throws_ok($$ SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'COMPLETENESS_REVIEW', NULL) $$,
    '23001', NULL, 'completeness review starts only with the automatic check recorded');
SELECT is(pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'AUTOMATIC'), 1, 'the automatic check is recorded');
SELECT is((SELECT missing FROM completeness_record WHERE kind = 'AUTOMATIC'), '{MISSING_DOCUMENT:DEMO_ID}'::text[],
    '... with what the database found, not what anyone said');
SELECT is(pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'COMPLETENESS_REVIEW', NULL), 1,
    'the system moves it to completeness review, with no person named');
SELECT ok((SELECT actor_id IS NULL FROM application_transition WHERE to_state = 'COMPLETENESS_REVIEW'),
    '... and the history says so');

-- --- The officer -----------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'COMPLETE') $$,
    '23514', NULL, 'nothing is accepted while a required item is missing');
SELECT throws_ok($$ SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b1', 'UNDER_REVIEW', '00000000-0000-0000-0000-0000000000b1') $$,
    '23001', NULL, 'no move without its record');
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b6', 'INFO_REQUESTED', '{DEMO_ID}', 'Please send your identity document') $$,
    '42501', NULL, 'a cadastral officer does not handle completeness');
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'COMPLETE') $$,
    '42501', NULL, 'nobody reviews their own application');
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '{DEMO_SALARY}', 'Please send your salary slip') $$,
    '23514', NULL, 'only document categories of the type are requested');
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '{DEMO_ID}', 'Send it') $$,
    '23514', NULL, 'a request explains itself (10 characters at least)');
SELECT is(pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '{DEMO_ID}', 'Please send your identity document'), 1,
    'the intake officer requests the missing document, with a message');
SELECT is(pg_temp.move('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '00000000-0000-0000-0000-0000000000b1'), 1,
    '... and the application waits for the applicant');

-- --- The applicant's response ---------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (application_id, category)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO_PLAN') $q$) $$,
    '42501', NULL, 'during a request, only the categories asked for take files');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$), 1,
    'the requested category takes a file');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (document_id, version_no, status,
    storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000f1', 1, 'STORED', '0123456789abcdef0123456789abcdef', repeat('a', 64), 10,
            'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$), 1, '... a version of it');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application
    SET data = '{"statement": "Changed"}', version = version + 1 $q$) $$,
    '23001', NULL, 'the form itself stays as submitted');
SELECT throws_ok($$ SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'RESPONSE') $$,
    '42501', NULL, 'only the applicant responds');
SELECT is(pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'RESPONSE', NULL, 'Here it is') + pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'AUTOMATIC')
       + pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'COMPLETENESS_REVIEW', '00000000-0000-0000-0000-0000000000b3'), 3,
    'the applicant responds; the automatic check runs again; back to completeness review');
SELECT is(pg_temp.missing(), '{}'::text[], 'nothing is missing any more');

-- --- Acceptance and the history ---------------------------------------------------------------------
SELECT is(pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'COMPLETE')
       + pg_temp.move('00000000-0000-0000-0000-0000000000b1', 'UNDER_REVIEW', '00000000-0000-0000-0000-0000000000b1'), 2,
    'the officer accepts a complete application');
SELECT results_eq($$ SELECT from_state || '>' || to_state FROM application_transition ORDER BY at $$,
    $$ VALUES ('DRAFT>SUBMITTED'), ('SUBMITTED>COMPLETENESS_REVIEW'), ('COMPLETENESS_REVIEW>INFO_REQUESTED'),
              ('INFO_REQUESTED>COMPLETENESS_REVIEW'), ('COMPLETENESS_REVIEW>UNDER_REVIEW') $$,
    'the history keeps the whole round trip');
SELECT throws_ok($$ SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'PENDING_DECISION', '00000000-0000-0000-0000-0000000000b3') $$,
    '23001', NULL, 'a recommendation is recorded first, by the reviewer assigned (V16), never by the applicant');
SELECT throws_ok($$ UPDATE snigf.completeness_record SET message = 'rewritten' $$,
    '23001', NULL, 'the records are never rewritten');
SELECT is(snigf.application_office_code('00000000-0000-0000-0000-00000000d001'), 'DEMO1', 'the office is found for permission checks');

SELECT * FROM finish();
ROLLBACK;
