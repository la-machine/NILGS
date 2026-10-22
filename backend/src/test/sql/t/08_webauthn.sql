-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- WebAuthn credentials (ADR-016, V6): one user entity per existing account, labelled credentials.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(7);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;

SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.user_entities (id, name, display_name) VALUES (''h1'', ''00000000-0000-0000-0000-0000000000b1'', ''Demo Officer'');
     INSERT INTO snigf.user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label)
     VALUES (''c1'', ''h1'', ''\x01'', false, false, ''Security key'')') $$,
    'the application registers a credential for an existing account');
SELECT throws_ok($$ INSERT INTO user_entities (id, name) VALUES ('h2', '00000000-0000-0000-0000-00000000ffff') $$,
    '23503', NULL, 'a user entity must belong to an existing account');
SELECT throws_ok($$ INSERT INTO user_entities (id, name) VALUES ('h3', '00000000-0000-0000-0000-0000000000b1') $$,
    '23505', NULL, 'an account has one user entity');
SELECT throws_ok($$ INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label)
    VALUES ('c2', 'h1', '\x01', false, false, '') $$, '23514', NULL, 'a credential has a label');
SELECT throws_ok($$ SELECT run_as('snigf_auditor', 'SELECT count(*) FROM snigf.user_credentials') $$,
    '42501', NULL, 'the auditor cannot read credentials');

SELECT throws_ok($$ UPDATE app_user SET mfa_enrolled_at = now() WHERE id = '00000000-0000-0000-0000-0000000000b2' $$,
    '23514', NULL, 'an account without any second factor cannot be marked enrolled');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.app_user SET mfa_enrolled_at = now() WHERE id = ''00000000-0000-0000-0000-0000000000b1''') $$,
    'an account with a WebAuthn credential can be marked enrolled');

SELECT * FROM finish();
ROLLBACK;
