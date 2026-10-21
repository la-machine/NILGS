-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Functions resolve names through a fixed search_path, never the caller's (V4).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(4);

SELECT is((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'snigf'
              AND NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, snigf, public, pg_temp'], false)),
    0::bigint, 'every function of the snigf schema pins search_path = pg_catalog, snigf, public, pg_temp');

-- A session whose path does not contain snigf, as an auditor or superuser with defaults
-- (tap stays on the path: pgTAP's own functions call each other unqualified).
SET search_path = public, tap;
INSERT INTO snigf.audit_event (id, actor_type, actor_label, action, outcome, correlation_id)
VALUES (gen_random_uuid(), 'SERVICE', 'test', 'PATH_TEST', 'SUCCESS', gen_random_uuid());
SELECT tap.pass('the chain trigger works without snigf on the caller''s search_path');
SELECT tap.is((SELECT verified FROM snigf.audit_verify_chain()), true,
    'chain verification works without snigf on the caller''s search_path');

-- A caller's object named like a table used inside the function cannot be substituted.
CREATE TEMP TABLE audit_event (chain_pos bigint, hash bytea);
SET search_path = pg_temp, public, tap;
SELECT tap.is((SELECT verified FROM snigf.audit_verify_chain()), true,
    'a look-alike table earlier on the caller''s path is ignored');

SELECT * FROM tap.finish();
ROLLBACK;
