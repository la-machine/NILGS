-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Applications (MVP-02, E-12, V13): the type catalogue is versioned and insert-only; the
-- database shows an application only to its owner or to staff reading at its office (row-level
-- security on the actor the application names); a draft is the owner's alone; submitting is
-- the owner's act, needs an active parcel of the office and freezes what was submitted; every
-- state change is in the history, which nobody edits.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(26);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer at DEMO1 (reads applications there); b5 at DEMO2; b3 and b4 applicants.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b4', 'applicant2@demo.invalid', 'Demo Applicant 2', 'x', 'APPLICANT'),
    ('00000000-0000-0000-0000-0000000000b5', 'officer2@demo.invalid', 'Demo Officer 2', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b5', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed');
INSERT INTO parcel (id, reference, office_id, current_version_no, created_by) VALUES
    ('00000000-0000-0000-0000-0000000030a1', 'DEMO-PCL-DEMO1-2026-000001-X', '00000000-0000-0000-0000-0000000000a1', 1,
     '00000000-0000-0000-0000-0000000000b1'),
    ('00000000-0000-0000-0000-0000000030a2', 'DEMO-PCL-DEMO2-2026-000001-X', '00000000-0000-0000-0000-0000000000a2', 1,
     '00000000-0000-0000-0000-0000000000b5');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)', '{"fields": [], "documents": []}');

-- A statement through the application's account, for an actor (NULL: none named).
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
CREATE FUNCTION pg_temp.visible(p_actor text) RETURNS int
LANGUAGE plpgsql AS $f$
DECLARE n int;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    SELECT count(*) INTO n FROM snigf.application;
    RESET ROLE;
    RETURN n;
END;
$f$;
CREATE FUNCTION pg_temp.draft(p_id text, p_owner text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_owner, format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
        SELECT %L, %L, 'DEMO_TEST', id FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$, p_id, p_owner))
$f$;
CREATE FUNCTION pg_temp.submit(p_id text, p_actor text, p_parcel text, p_office text, p_ref text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
        parcel_reference = (SELECT reference FROM snigf.parcel WHERE id = %L), parcel_id = %L, office_id = %L,
        reference = %L, state_changed_by = %L, version = version + 1 WHERE id = %L $q$,
        p_parcel, p_parcel, p_office, p_ref, p_actor, p_id))
$f$;

-- --- The type catalogue ----------------------------------------------------------------------
SELECT throws_ok($$ INSERT INTO snigf.application_type (code, version_no, available, name_fr, name_en, requirements)
    VALUES ('DEMO_TEST', 3, true, 'Demande (DEMO)', 'Application (DEMO)', '{"fields": [], "documents": []}') $$,
    '23514', NULL, 'a type version follows the last one');
SELECT throws_ok($$ UPDATE snigf.application_type SET available = false $$,
    '23001', NULL, 'a type version is never changed');
SELECT throws_ok($$ INSERT INTO snigf.application_type (code, version_no, available, name_fr, name_en, requirements)
    VALUES ('DEMO_OTHER', 1, true, 'Demande réelle', 'Real application', '{"fields": [], "documents": []}') $$,
    '23514', NULL, 'a demonstration type says DEMO in both languages (C4)');

-- --- Drafts: the owner's alone -------------------------------------------------------------------
SELECT is(pg_temp.draft('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3'), 1,
    'an applicant creates a draft for themselves');
SELECT throws_ok($$ SELECT pg_temp.draft('00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-0000000000b4')
                     WHERE false UNION ALL
                   SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application
                     (applicant_id, type_code, type_version_id) SELECT '00000000-0000-0000-0000-0000000000b4', 'DEMO_TEST', id
                     FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$) $$,
    '42501', NULL, 'nobody creates a draft in someone else''s name');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application
        (applicant_id, type_code, type_version_id, state) SELECT '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id, 'SUBMITTED'
        FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$) $$,
    '23514', NULL, 'an application starts as a draft');
SELECT is(pg_temp.visible('00000000-0000-0000-0000-0000000000b3'), 1, 'the owner sees their draft');
SELECT is(pg_temp.visible('00000000-0000-0000-0000-0000000000b4'), 0, 'another applicant does not');
SELECT is(pg_temp.visible('00000000-0000-0000-0000-0000000000b1'), 0, 'nor does staff: a draft has no office');
SELECT is(pg_temp.visible(NULL), 0, 'with no actor named, nothing is visible');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b4', $q$ UPDATE snigf.application SET data = '{"x": 1}',
        version = version + 1 $q$), 0, 'another applicant changes nothing');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b4', $q$ DELETE FROM snigf.application $q$), 0,
    '... and deletes nothing');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET data = '{"statement": "mine"}',
        version = version + 1 $q$), 1, 'the owner edits the draft');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application
        SET data = '{"statement": "again"}' $q$) $$, '40001', NULL, 'an edit based on a stale version is refused');

-- --- Submission --------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application
        SET state = 'SUBMITTED', state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$) $$,
    '23514', NULL, 'nothing is submitted without a reference, a parcel and an office');
SELECT throws_ok($$ SELECT pg_temp.submit('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3',
        '00000000-0000-0000-0000-0000000030a1', '00000000-0000-0000-0000-0000000000a2', 'DEMO-APP-2026-000001-X') $$,
    '23514', NULL, 'the office is the parcel''s, whatever the request says');
SELECT is(pg_temp.submit('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3',
        '00000000-0000-0000-0000-0000000030a1', '00000000-0000-0000-0000-0000000000a1', 'DEMO-APP-2026-000001-X'), 1,
    'the owner submits with an active parcel of its office');
SELECT results_eq($$ SELECT from_state || '>' || to_state || ':' || actor_id FROM application_transition $$,
    $$ VALUES ('DRAFT>SUBMITTED:00000000-0000-0000-0000-0000000000b3') $$,
    'the database wrote the transition, with its actor');
SELECT ok((SELECT submitted_at IS NOT NULL FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'),
    '... and the submission time');
SELECT is(pg_temp.visible('00000000-0000-0000-0000-0000000000b1'), 1, 'staff reading applications at its office sees it');
SELECT is(pg_temp.visible('00000000-0000-0000-0000-0000000000b5'), 0, 'staff of another office does not');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application
        SET data = '{"statement": "changed"}', version = version + 1 $q$) $$,
    '23001', NULL, 'what was submitted never changes');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application
        SET state = 'APPROVED', state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$) $$,
    '23001', NULL, 'only the drawn transitions are legal');
SELECT throws_ok($$ DELETE FROM snigf.application WHERE id = '00000000-0000-0000-0000-00000000d001' $$,
    '23001', NULL, 'a submitted application is kept, even by the schema owner');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ DELETE FROM snigf.application_transition $q$) $$,
    '42501', NULL, 'the application cannot touch the history');

-- Someone else submitting (the guard, below row-level security).
SELECT pg_temp.draft('00000000-0000-0000-0000-00000000d003', '00000000-0000-0000-0000-0000000000b3');
SELECT throws_ok($$ UPDATE snigf.application SET state = 'SUBMITTED', parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-X',
        parcel_id = '00000000-0000-0000-0000-0000000030a1', office_id = '00000000-0000-0000-0000-0000000000a1',
        reference = 'DEMO-APP-2026-000002-X', state_changed_by = '00000000-0000-0000-0000-0000000000b1', version = version + 1
        WHERE id = '00000000-0000-0000-0000-00000000d003' $$,
    '42501', NULL, 'only the applicant submits');

SELECT * FROM finish();
ROLLBACK;
