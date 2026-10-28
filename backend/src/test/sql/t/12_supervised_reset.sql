-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Supervised second-factor reset and key removal (ADR-028, V10), as the application's account.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(14);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 officer, b2 admin (seed); b4 supervisor at DEMO1, b5 supervisor at DEMO2.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b4', 'supervisor@demo.invalid', 'Demo Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b5', 'supervisor2@demo.invalid', 'Demo Supervisor 2', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b2', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b4', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b5', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a2', 'seed');
-- b1 enrolled with two keys and two recovery codes.
INSERT INTO user_entities (id, name, display_name) VALUES ('h1', '00000000-0000-0000-0000-0000000000b1', 'Demo Officer');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x01', false, false, 'Office key'), ('k2', 'h1', '\x02', false, false, 'Backup key');
INSERT INTO mfa_recovery_code (id, user_id, code_hash) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'h-one'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'h-two');
UPDATE app_user SET mfa_enrolled_at = now() WHERE id = '00000000-0000-0000-0000-0000000000b1';

SELECT is((SELECT scopes FROM permission WHERE code = 'identity.reset.confirm'), ARRAY['OFFICE'],
    'identity.reset.confirm exists, office-scoped');

-- Key removal
SELECT lives_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.user_credentials WHERE credential_id = ''k1''') $$,
    'the owner''s lost key is removed');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.user_credentials WHERE credential_id = ''k2''') $$,
    '23514', 'the last key of an enrolled account cannot be removed', 'the last key of an enrolled account stays');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.app_user SET mfa_enrolled_at = NULL WHERE id = ''00000000-0000-0000-0000-0000000000b1''') $$,
    '42501', 'a second factor is reset only through a confirmed reset request',
    'the application cannot clear an enrolment');

-- Reset requests
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.mfa_reset_request (id, user_id, reason, requested_by)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''lost every key'', ''00000000-0000-0000-0000-0000000000b4'')') $$,
    '42501', NULL, 'only a holder of admin.mfa.reset requests a reset');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.mfa_reset_request (id, user_id, reason, requested_by, status, decided_by, decided_at, identity_check)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''forged confirm'', ''00000000-0000-0000-0000-0000000000b2'',
             ''CONFIRMED'', ''00000000-0000-0000-0000-0000000000b4'', now(), ''none'')') $$,
    '23514', 'a request is created pending', 'a reset cannot be inserted already confirmed');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.mfa_reset_request (id, user_id, reason, requested_by)
     VALUES (''00000000-0000-0000-0000-0000000000e1'', ''00000000-0000-0000-0000-0000000000b1'', ''lost every key and code'',
             ''00000000-0000-0000-0000-0000000000b2'')') $$,
    'an administrator requests a reset');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.mfa_reset_request SET status = ''CONFIRMED'', decided_by = ''00000000-0000-0000-0000-0000000000b2'',
            decided_at = now(), identity_check = ''myself'' WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '42501', NULL, 'the requesting administrator cannot confirm');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.mfa_reset_request SET status = ''CONFIRMED'', decided_by = ''00000000-0000-0000-0000-0000000000b5'',
            decided_at = now(), identity_check = ''by phone'' WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '42501', NULL, 'a supervisor of another office cannot confirm');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.mfa_reset_request SET status = ''CONFIRMED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'',
            decided_at = now() WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '23514', NULL, 'a confirmation says how the identity was checked');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.mfa_reset_request SET status = ''CONFIRMED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'',
            decided_at = now(), identity_check = ''in person, ID card'' WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    'the person''s supervisor confirms');
SELECT is((SELECT count(*)::int FROM user_credentials WHERE user_entity_user_id = 'h1'), 0, 'every key is removed');
SELECT is((SELECT count(*)::int FROM mfa_recovery_code WHERE user_id = '00000000-0000-0000-0000-0000000000b1'
             AND used_at IS NULL AND invalidated_at IS NULL), 0, 'every unused recovery code is invalidated');
SELECT ok((SELECT mfa_enrolled_at IS NULL FROM app_user WHERE id = '00000000-0000-0000-0000-0000000000b1'),
    'the enrolment is cleared: the next sign-in enrols again');

SELECT * FROM finish();
ROLLBACK;
