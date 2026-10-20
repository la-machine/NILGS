-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Identity invariants enforced by the database (C6c, C9).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(13);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;

-- C6c: no self-grant, no self-revoke — even if the application had a bug.
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b2', 'SYSTEM_ADMIN',
            '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000b2', 'self grant attempt') $q$) $$,
    '23514', NULL, 'C6c: a user cannot grant a role to themselves');

SELECT lives_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER',
            '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000b2', 'assigned by admin') $q$) $$,
    'an admin can grant a role to another user');

SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    UPDATE snigf.role_assignment SET revoked_by = '00000000-0000-0000-0000-0000000000b1',
           revoked_at = now(), revoke_reason = 'self revoke attempt'
     WHERE id = '00000000-0000-0000-0000-0000000000c1' $q$) $$,
    '23514', NULL, 'C6c: a user cannot revoke their own role');

SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER',
            '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000b2', 'duplicate') $q$) $$,
    '23505', NULL, 'the same active role cannot be granted twice');

SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, office_id, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b3', 'APPLICANT',
            '00000000-0000-0000-0000-0000000000a1', 'applicant with office') $q$) $$,
    '23514', NULL, 'APPLICANT role carries no office');

SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'REVIEWER',
            '00000000-0000-0000-0000-0000000000b2', 'staff role without office') $q$) $$,
    '23514', NULL, 'staff roles are always office-scoped');

SELECT lives_ok($$ SELECT run_as('snigf_app', $q$
    UPDATE snigf.role_assignment SET revoked_by = '00000000-0000-0000-0000-0000000000b2',
           revoked_at = now(), revoke_reason = 'moved to another office'
     WHERE id = '00000000-0000-0000-0000-0000000000c1' $q$) $$,
    'another admin can revoke the role');

SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    UPDATE snigf.role_assignment SET revoke_reason = 'rewritten reason'
     WHERE id = '00000000-0000-0000-0000-0000000000c1' $q$) $$,
    '23001', NULL, 'a revoked assignment cannot be edited afterwards');

SELECT lives_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER',
            '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000b2', 're-granted') $q$) $$,
    'a revoked role can be granted again as a new assignment');

SELECT throws_ok($$ SELECT run_as('snigf_migrator', 'DELETE FROM snigf.role_assignment') $$,
    '23001', NULL, 'role history cannot be deleted, even by the schema owner');

-- C9: identity verification cannot be claimed.
SELECT throws_ok($$ INSERT INTO app_user (id, email, display_name, password_hash, account_type, identity_verified)
    VALUES (gen_random_uuid(), 'v@demo.invalid', 'V', 'x', 'APPLICANT', true) $$,
    '23514', NULL, 'C9: identity_verified cannot be true in this release');

SELECT throws_ok($$ INSERT INTO app_user (id, email, display_name, password_hash, account_type)
    VALUES (gen_random_uuid(), 'OFFICER@Demo.Invalid', 'Dup', 'x', 'STAFF') $$,
    '23505', NULL, 'email addresses are unique regardless of case');

SELECT throws_ok($$ UPDATE app_user SET mfa_enrolled_at = now() WHERE id = '00000000-0000-0000-0000-0000000000b1' $$,
    '23514', NULL, 'MFA cannot be marked enrolled without a secret');

SELECT * FROM finish();
ROLLBACK;
