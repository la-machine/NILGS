-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Server-side sessions (ADR-004, V5): only the application role can see or change them.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(4);

SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.spring_session VALUES (''p1'', ''s1'', 1, 1, 1800, 2, ''u1'');
     INSERT INTO snigf.spring_session_attributes VALUES (''p1'', ''a'', ''\x00'');
     DELETE FROM snigf.spring_session WHERE primary_id = ''p1''') $$,
    'the application creates and revokes sessions');
SELECT is((SELECT count(*) FROM spring_session_attributes WHERE session_primary_id = 'p1'), 0::bigint,
    'revoking a session deletes its attributes');
SELECT throws_ok($$ SELECT run_as('snigf_auditor', 'SELECT count(*) FROM snigf.spring_session') $$,
    '42501', NULL, 'the auditor cannot read session ids');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'TRUNCATE snigf.spring_session') $$,
    '42501', NULL, 'the application cannot truncate sessions');

SELECT * FROM finish();
ROLLBACK;
