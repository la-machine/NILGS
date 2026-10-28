-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Documents (MVP-02, E-13, V14): a file is added only by the owner, to their draft, in a
-- category of its type; versions are numbered by the database and never change; a document is
-- visible exactly when its application is; nothing is deleted except with a draft.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(20);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b4', 'applicant2@demo.invalid', 'Demo Applicant 2', 'x', 'APPLICANT');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": true}, {"code": "DEMO_PLAN", "required": false}]}');

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
CREATE FUNCTION pg_temp.count_as(p_actor text, p_table text) RETURNS int
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
-- A version of the given document, as the given actor.
CREATE FUNCTION pg_temp.version(p_actor text, p_document text, p_key text, p_type text DEFAULT 'application/pdf',
                                p_name text DEFAULT 'identity.pdf', p_by text DEFAULT NULL) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.document_version (document_id, version_no, status, storage_key,
        sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (%L, 99, 'STORED', %L, repeat('a', 64), 1234, %L, %L, %L) $q$,
        p_document, p_key, p_type, p_name, coalesce(p_by, p_actor)))
$f$;

SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id
      FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$);

-- --- Adding ------------------------------------------------------------------------------------
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$), 1,
    'the owner adds a document to their draft, in a category of its type');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (application_id, category)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO_SALARY') $q$) $$,
    '42501', NULL, 'not in a category its type does not ask for');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b4', $q$ INSERT INTO snigf.document (application_id, category)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO_PLAN') $q$) $$,
    '42501', NULL, 'nobody adds a document to someone else''s application');
SELECT is(pg_temp.version('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-0000000000f1',
    '0123456789abcdef0123456789abcdef'), 1, 'the owner adds a version');
SELECT is(pg_temp.version('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-0000000000f1',
    'fedcba9876543210fedcba9876543210'), 1, '... and another');
SELECT results_eq($$ SELECT version_no FROM document_version ORDER BY version_no $$, $$ VALUES (1), (2) $$,
    'versions are numbered by the database, whatever was asked');
SELECT throws_ok($$ SELECT pg_temp.version('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-0000000000f1',
    '11111111111111111111111111111111', 'application/pdf', 'x.pdf', '00000000-0000-0000-0000-0000000000b4') $$,
    '42501', NULL, 'a version is uploaded by the actor, never in someone else''s name');
SELECT throws_ok($$ SELECT pg_temp.version('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-0000000000f1',
    '22222222222222222222222222222222', 'text/html') $$,
    '23514', NULL, 'only PDF, JPEG and PNG are kept');
SELECT throws_ok($$ SELECT pg_temp.version('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-0000000000f1',
    '33333333333333333333333333333333', 'application/pdf', '../../etc/passwd') $$,
    '23514', NULL, 'a name is a name, never a path');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version
    (document_id, version_no, status, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000f1', 1, 'STORED', repeat('a', 64), 1, 'image/png', 'a.png',
            '00000000-0000-0000-0000-0000000000b3') $q$) $$,
    '23514', NULL, 'a stored version names its storage key');

-- --- Seeing ------------------------------------------------------------------------------------
SELECT is(pg_temp.count_as('00000000-0000-0000-0000-0000000000b3', 'document_version'), 2, 'the owner sees their versions');
SELECT is(pg_temp.count_as('00000000-0000-0000-0000-0000000000b4', 'document_version'), 0, 'another applicant sees none');
SELECT is(pg_temp.count_as(NULL, 'document'), 0, 'with no actor named, nothing is visible');

-- --- Never changed, never deleted alone -------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.document_version SET original_name = 'x.pdf' $q$) $$,
    '42501', NULL, 'the application cannot change a version');
SELECT throws_ok($$ UPDATE snigf.document_version SET original_name = 'x.pdf' $$,
    '23001', NULL, 'nor can the schema owner');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ DELETE FROM snigf.document_version $q$) $$,
    '42501', NULL, 'the application cannot delete a version');
SELECT throws_ok($$ DELETE FROM snigf.document_version $$,
    '23001', NULL, 'nor can the schema owner while the application exists');
SELECT results_eq($$ SELECT * FROM document_storage_keys_known(ARRAY['0123456789abcdef0123456789abcdef', 'ffffffffffffffffffffffffffffffff']) $$,
    $$ VALUES ('0123456789abcdef0123456789abcdef') $$, 'the cleanup learns which keys are still named, nothing else');

-- --- Deleting the draft deletes its documents --------------------------------------------------
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ DELETE FROM snigf.application WHERE id = '00000000-0000-0000-0000-00000000d001' $q$), 1,
    'the owner deletes the draft');
SELECT is((SELECT count(*)::int FROM document_version), 0, '... and its documents go with it (their files become orphans for the cleanup)');

SELECT * FROM finish();
ROLLBACK;
