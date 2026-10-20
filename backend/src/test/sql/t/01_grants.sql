-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Least privilege: what each database role can and cannot do (ADR-009, C7).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(15);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
INSERT INTO audit_event (id, actor_type, actor_label, action, outcome, correlation_id, prev_hash, hash)
VALUES (gen_random_uuid(), 'SERVICE', 'test', 'TEST_EVENT', 'SUCCESS', gen_random_uuid(), '\x00', '\x00');

-- Application role: no DDL, no audit rewrites.
SELECT throws_ok($$ SELECT run_as('snigf_app', 'CREATE TABLE snigf.x (id int)') $$,
    '42501', NULL, 'app cannot create tables in the schema');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.audit_event SET reason = ''x''') $$,
    '42501', NULL, 'app cannot UPDATE audit_event');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.audit_event') $$,
    '42501', NULL, 'app cannot DELETE audit_event');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'TRUNCATE snigf.audit_event') $$,
    '42501', NULL, 'app cannot TRUNCATE audit_event');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'ALTER TABLE snigf.audit_event DISABLE TRIGGER audit_event_no_change') $$,
    '42501', NULL, 'app cannot disable audit triggers');

-- Column-level grants: immutable business columns stay immutable.
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.app_user SET identity_verified = true') $$,
    '42501', NULL, 'app cannot touch app_user.identity_verified');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.app_user SET email = ''a@b.c''') $$,
    '42501', NULL, 'app cannot change a user''s email (not granted in slice 1)');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.role_assignment SET role = ''SYSTEM_ADMIN''') $$,
    '42501', NULL, 'app cannot rewrite the role of an assignment');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.parcel_version SET locality = ''x''') $$,
    '42501', NULL, 'app cannot UPDATE parcel versions');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.app_user') $$,
    '42501', NULL, 'app cannot delete users');

-- Auditor is read-only.
SELECT throws_ok($$ SELECT run_as('snigf_auditor',
    'INSERT INTO snigf.audit_event (id, actor_type, actor_label, action, outcome, correlation_id, prev_hash, hash)
     VALUES (gen_random_uuid(), ''SERVICE'', ''x'', ''X_EVENT'', ''SUCCESS'', gen_random_uuid(), ''\x00'', ''\x00'')') $$,
    '42501', NULL, 'auditor cannot insert audit events');
SELECT lives_ok($$ SELECT run_as('snigf_auditor', 'SELECT count(*) FROM snigf.audit_event') $$,
    'auditor can read audit events');
SELECT throws_ok($$ SELECT run_as('snigf_auditor', 'SELECT count(*) FROM snigf.app_user') $$,
    '42501', NULL, 'auditor cannot read user accounts');

-- Even the schema owner is stopped by the append-only trigger (it could disable
-- the trigger, which is the documented residual risk).
SELECT throws_ok($$ SELECT run_as('snigf_migrator', 'UPDATE snigf.audit_event SET reason = ''x''') $$,
    '23001', NULL, 'owner UPDATE on audit_event is rejected by trigger');

-- Characterizes the documented residual risk (docs/audit.md): the owner CAN disable
-- the trigger. If this ever starts failing, the risk statement must be revisited.
SELECT lives_ok($$ SELECT run_as('snigf_migrator', 'ALTER TABLE snigf.audit_event DISABLE TRIGGER audit_event_no_change') $$,
    'RESIDUAL RISK: the schema owner can disable the append-only trigger');

SELECT * FROM finish();
ROLLBACK;
