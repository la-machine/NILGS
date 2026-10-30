-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Permission catalogue, roles, four-eyes requests and special grants (ADR-025, V7).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(24);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- A third staff member, so that requester, approver and beneficiary can all differ.
INSERT INTO app_user (id, email, display_name, password_hash, account_type)
VALUES ('00000000-0000-0000-0000-0000000000b4', 'admin2@demo.invalid', 'Demo Admin 2', 'x', 'STAFF');
-- Administrators (bootstrap by the superuser, V8). b1 is one too, so that the tests below
-- reach the V7 constraint rather than V8's permission check.
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
SELECT gen_random_uuid(), u, 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'bootstrap'
  FROM unnest(ARRAY['00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000b2',
                    '00000000-0000-0000-0000-0000000000b4']::uuid[]) u;

-- Catalogue and seeds
SELECT is((SELECT count(*)::int FROM permission), 38, 'the catalogue holds the 38 permissions of the code (V7, V8, V10, V20; RoleSeedIT compares each)');
SELECT is((SELECT count(*)::int FROM role_definition WHERE system), 10, 'the ten baseline roles are seeded as system roles');
SELECT is((SELECT count(*)::int FROM permission WHERE family = 'ADMINISTRATION' AND delegable), 0,
    'no administration permission is delegable');
SELECT throws_ok($$ INSERT INTO permission VALUES ('admin.x.y', 'ADMINISTRATION', true, ARRAY['ANY'], 'Test', 'Test') $$,
    '23514', NULL, 'a delegable administration permission is refused');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.permission VALUES (''parcel.fly'', ''BUSINESS'', true, ARRAY[''ANY''], ''Fly'', ''Voler'')') $$,
    '42501', NULL, 'the application cannot add permissions');

-- System roles never change
SELECT throws_ok($$ UPDATE role_definition SET name_en = 'Boss' WHERE code = 'SYSTEM_ADMIN' $$,
    '23001', NULL, 'a system role cannot be renamed, even by the schema owner');
SELECT throws_ok($$ INSERT INTO role_permission VALUES ('AUDITOR', 'admin.role.assign', 'ANY') $$,
    '23001', NULL, 'a permission cannot be added to a system role');
SELECT throws_ok($$ DELETE FROM role_permission WHERE role_code = 'AUDITOR' AND permission = 'audit.chain.verify' $$,
    '23001', NULL, 'a permission cannot be removed from a system role');

-- Custom roles
SELECT lives_ok($$
    INSERT INTO role_definition (code, name_fr, name_en) VALUES ('LAND_CLERK', 'Commis foncier', 'Land clerk');
    INSERT INTO role_permission VALUES ('LAND_CLERK', 'parcel.read', 'ANY') $$,
    'the schema owner creates a custom role (the application only through an approved request, test 10)');
SELECT throws_ok($$ INSERT INTO role_permission VALUES ('LAND_CLERK', 'payment.reconcile', 'OFFICE') $$,
    '23514', NULL, 'a scope the catalogue does not allow is refused');
SELECT throws_ok($$ INSERT INTO role_permission VALUES ('LAND_CLERK', 'parcel.fly', 'ANY') $$,
    '23514', 'scope ANY is not allowed for permission parcel.fly', 'an unknown permission is refused');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.role_definition WHERE code = ''LAND_CLERK''') $$,
    '42501', NULL, 'the application cannot delete a role (retire it instead)');
UPDATE role_definition SET status = 'RETIRED' WHERE code = 'LAND_CLERK';
SELECT throws_ok($$ INSERT INTO role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'LAND_CLERK', '00000000-0000-0000-0000-0000000000a1',
            '00000000-0000-0000-0000-0000000000b2', 'retired') $$,
    '23514', NULL, 'a retired role cannot be assigned');
SELECT throws_ok($$ INSERT INTO role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'NOT_A_ROLE', '00000000-0000-0000-0000-0000000000a1',
            '00000000-0000-0000-0000-0000000000b2', 'unknown') $$,
    '23514', 'role NOT_A_ROLE is not defined or is retired', 'an undefined role cannot be assigned');

-- Role change requests
INSERT INTO role_change_request (id, kind, role_code, proposal, reason, requested_by)
VALUES ('00000000-0000-0000-0000-0000000000c1', 'CREATE', 'SURVEYOR',
        '{"nameFr": "Géomètre", "nameEn": "Surveyor", "permissions": {"parcel.read": "ANY"}}',
        'new job profile', '00000000-0000-0000-0000-0000000000b2');
SELECT throws_ok($$ UPDATE role_change_request SET status = 'APPROVED', decided_by = requested_by, decided_at = now()
    WHERE id = '00000000-0000-0000-0000-0000000000c1' $$,
    '23514', NULL, 'the requester cannot approve their own role request');
SELECT throws_ok($$ INSERT INTO role_change_request (id, kind, role_code, proposal, reason, requested_by)
    VALUES (gen_random_uuid(), 'CREATE', 'SURVEYOR', '{}', 'second attempt', '00000000-0000-0000-0000-0000000000b4') $$,
    '23505', NULL, 'one open request per role');
SELECT lives_ok($$ UPDATE role_change_request SET status = 'APPROVED', decided_by = '00000000-0000-0000-0000-0000000000b4',
    decided_at = now() WHERE id = '00000000-0000-0000-0000-0000000000c1' $$,
    'another administrator approves');

-- Special grants
SELECT throws_ok($$ INSERT INTO permission_grant (id, user_id, permission, reason, requested_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b2', 'parcel.read', 'for myself please', '00000000-0000-0000-0000-0000000000b2') $$,
    '23514', NULL, 'nobody requests a grant for themselves');
SELECT throws_ok($$ INSERT INTO permission_grant (id, user_id, permission, reason, requested_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'admin.role.assign', 'temporary admin', '00000000-0000-0000-0000-0000000000b2') $$,
    '23514', NULL, 'administration cannot be granted specially');
SELECT throws_ok($$ INSERT INTO permission_grant (id, user_id, permission, reason, requested_by, valid_until)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'parcel.read', 'already over', '00000000-0000-0000-0000-0000000000b2', now() - interval '1 day') $$,
    '23514', NULL, 'an end date must be in the future');
INSERT INTO permission_grant (id, user_id, permission, reason, requested_by, max_uses)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000b1', 'parcel.read',
        'audit of one file', '00000000-0000-0000-0000-0000000000b2', 2);
SELECT throws_ok($$ UPDATE permission_grant SET status = 'APPROVED', decided_by = user_id, decided_at = now()
    WHERE id = '00000000-0000-0000-0000-0000000000d1' $$,
    '23514', NULL, 'the beneficiary cannot approve their own grant');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000d1'';
     UPDATE snigf.permission_grant SET uses = uses + 1 WHERE id = ''00000000-0000-0000-0000-0000000000d1'';
     UPDATE snigf.permission_grant SET uses = uses + 1 WHERE id = ''00000000-0000-0000-0000-0000000000d1''') $$,
    'a third person approves; the application counts uses');
SELECT throws_ok($$ UPDATE permission_grant SET uses = 3 WHERE id = '00000000-0000-0000-0000-0000000000d1' $$,
    '23514', NULL, 'uses never exceed the maximum');
SELECT throws_ok($$ UPDATE permission_grant SET status = 'REVOKED', revoked_by = user_id, revoked_at = now()
    WHERE id = '00000000-0000-0000-0000-0000000000d1' $$,
    '23514', NULL, 'the beneficiary cannot revoke their own grant');

SELECT * FROM finish();
ROLLBACK;
