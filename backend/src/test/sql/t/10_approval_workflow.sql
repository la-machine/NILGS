-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The approval workflow enforced by the database (ADR-026, V8): the application's account
-- cannot give anyone a right without a request approved by a different person who holds
-- the approval permission.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(30);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 officer, b2 admin, b3 applicant (seed); b4 and b5 two more administrators.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b4', 'admin2@demo.invalid', 'Demo Admin 2', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b5', 'admin3@demo.invalid', 'Demo Admin 3', 'x', 'STAFF');
-- Bootstrap by the superuser: the only path that needs no request.
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
SELECT gen_random_uuid(), u, 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'bootstrap'
  FROM unnest(ARRAY['00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000b4',
                    '00000000-0000-0000-0000-0000000000b5']::uuid[]) u;

-- Role assignments --------------------------------------------------------------------------
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''CADASTRAL_OFFICER'',
             ''00000000-0000-0000-0000-0000000000a1'', ''00000000-0000-0000-0000-0000000000b2'', ''direct'')') $$,
    '42501', 'a role assignment requires an approved request', 'the application cannot assign a role directly');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment_request (id, user_id, role, office_id, reason, requested_by, status, decided_by, decided_at)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''CADASTRAL_OFFICER'', ''00000000-0000-0000-0000-0000000000a1'',
             ''forged'', ''00000000-0000-0000-0000-0000000000b2'', ''APPROVED'', ''00000000-0000-0000-0000-0000000000b4'', now())') $$,
    '23514', 'a request is created pending', 'a request cannot be inserted already approved');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment_request (id, user_id, role, office_id, reason, requested_by)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b4'', ''AUDITOR'', ''00000000-0000-0000-0000-0000000000a1'',
             ''not an administrator'', ''00000000-0000-0000-0000-0000000000b1'')') $$,
    '42501', NULL, 'only a holder of admin.role.assign requests a role');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment_request (id, user_id, role, office_id, reason, requested_by)
     VALUES (''00000000-0000-0000-0000-0000000000e1'', ''00000000-0000-0000-0000-0000000000b1'', ''CADASTRAL_OFFICER'',
             ''00000000-0000-0000-0000-0000000000a1'', ''transferred'', ''00000000-0000-0000-0000-0000000000b2'')') $$,
    'an administrator requests a role');
SELECT is((SELECT count(*)::int FROM role_assignment WHERE user_id = '00000000-0000-0000-0000-0000000000b1'), 0,
    'nothing is assigned before approval');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b2'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '23514', NULL, 'the requester cannot approve');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b3'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '42501', NULL, 'someone without admin.assignment.approve cannot approve');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    'another administrator approves');
SELECT is((SELECT role || '@' || granted_by FROM role_assignment WHERE request_id = '00000000-0000-0000-0000-0000000000e1'),
    'CADASTRAL_OFFICER@00000000-0000-0000-0000-0000000000b2', 'the approval created the assignment, credited to the requester');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment_request SET status = ''REJECTED'' WHERE id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '23001', NULL, 'a decision is final');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment (id, user_id, role, office_id, granted_by, grant_reason, request_id)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''CADASTRAL_OFFICER'', ''00000000-0000-0000-0000-0000000000a1'',
             ''00000000-0000-0000-0000-0000000000b2'', ''again'', ''00000000-0000-0000-0000-0000000000e1'')') $$,
    '23505', NULL, 'an approval creates one assignment, never two');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment (id, user_id, role, grant_reason)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''APPLICANT'', ''registration'')') $$,
    '23514', NULL, 'the self-registration role goes to applicant accounts only');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_assignment (id, user_id, role, grant_reason)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b3'', ''APPLICANT'', ''registration'')') $$,
    'registration gives an applicant the self-registration role');
INSERT INTO role_assignment_request (id, user_id, role, office_id, reason, requested_by)
VALUES ('00000000-0000-0000-0000-0000000000e2', '00000000-0000-0000-0000-0000000000b2', 'AUDITOR',
        '00000000-0000-0000-0000-0000000000a1', 'administrator and auditor', '00000000-0000-0000-0000-0000000000b4');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b5'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000e2''') $$,
    '23514', 'separation of duties: administration and audit together', 'nobody becomes administrator and auditor');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_assignment SET revoked_by = ''00000000-0000-0000-0000-0000000000b3'', revoked_at = now(), revoke_reason = ''not mine''
      WHERE request_id = ''00000000-0000-0000-0000-0000000000e1''') $$,
    '42501', NULL, 'only a holder of admin.role.revoke revokes');

-- Role definitions -------------------------------------------------------------------------
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_definition (code, name_fr, name_en) VALUES (''ROGUE'', ''Voyou'', ''Rogue'')') $$,
    '42501', NULL, 'the application cannot create a role directly');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_definition SET name_en = ''x'' WHERE code = ''REVIEWER''') $$,
    '42501', NULL, 'the application cannot change a role directly');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_permission VALUES (''REVIEWER'', ''decision.record'', ''ASSIGNED'')') $$,
    '42501', NULL, 'the application cannot change a role''s permissions directly');
SELECT lives_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_change_request (id, kind, role_code, proposal, reason, requested_by)
     VALUES (''00000000-0000-0000-0000-0000000000f1'', ''CREATE'', ''LAND_CLERK'',
             ''{"nameFr": "Commis foncier", "nameEn": "Land clerk", "permissions": {"parcel.read": "ANY"}}'',
             ''new job profile'', ''00000000-0000-0000-0000-0000000000b2'');
     UPDATE snigf.role_change_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000f1''') $$,
    'a role is created by an approved request');
SELECT is((SELECT d.name_en || ':' || p.permission || ':' || p.scope FROM role_definition d
             JOIN role_permission p ON p.role_code = d.code WHERE d.code = 'LAND_CLERK'),
    'Land clerk:parcel.read:ANY', 'the approval applied the proposal');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.role_change_request (id, kind, role_code, proposal, reason, requested_by)
     VALUES (gen_random_uuid(), ''CREATE'', ''MIXED_ROLE'',
             ''{"nameFr": "Mixte", "nameEn": "Mixed", "permissions": {"admin.account.list": "ANY", "audit.key.read": "ANY"}}'',
             ''both families'', ''00000000-0000-0000-0000-0000000000b2'');
     UPDATE snigf.role_change_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE role_code = ''MIXED_ROLE''') $$,
    '23514', NULL, 'a role mixing administration and audit is never created');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b5', 'LAND_CLERK', '00000000-0000-0000-0000-0000000000a1', 'holder');
INSERT INTO role_change_request (id, kind, role_code, proposal, reason, requested_by)
VALUES ('00000000-0000-0000-0000-0000000000f2', 'RETIRE', 'LAND_CLERK', '{}', 'no longer needed', '00000000-0000-0000-0000-0000000000b2');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_change_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b5'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000f2''') $$,
    '42501', 'the approver holds role LAND_CLERK', 'a holder of the role cannot approve its change');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.role_change_request SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000f2''') $$,
    '23514', 'role LAND_CLERK is still assigned', 'a role still held cannot be retired');

-- Special grants ---------------------------------------------------------------------------
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.permission_grant (id, user_id, permission, reason, requested_by, status, decided_by, decided_at)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b1'', ''parcel.read'', ''forged approval'',
             ''00000000-0000-0000-0000-0000000000b2'', ''APPROVED'', ''00000000-0000-0000-0000-0000000000b4'', now())') $$,
    '23514', 'a special grant is created pending and unused', 'a grant cannot be inserted already approved');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'INSERT INTO snigf.permission_grant (id, user_id, permission, reason, requested_by)
     VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000b4'', ''parcel.read'', ''not an administrator'',
             ''00000000-0000-0000-0000-0000000000b1'')') $$,
    '42501', NULL, 'only a holder of admin.grant.request requests a grant');
INSERT INTO permission_grant (id, user_id, permission, reason, requested_by, max_uses)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000b1', 'parcel.read',
        'one file to check', '00000000-0000-0000-0000-0000000000b2', 1);
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b3'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000d1''') $$,
    '42501', NULL, 'someone without admin.grant.approve cannot approve');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET uses = 1 WHERE id = ''00000000-0000-0000-0000-0000000000d1''') $$,
    '23514', NULL, 'a pending grant cannot be used');
UPDATE permission_grant SET status = 'APPROVED', decided_by = '00000000-0000-0000-0000-0000000000b4', decided_at = now()
 WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET uses = uses + 2 WHERE id = ''00000000-0000-0000-0000-0000000000d1''') $$,
    '23514', 'uses move one at a time', 'uses move one at a time');
UPDATE permission_grant SET status = 'REVOKED', revoked_by = '00000000-0000-0000-0000-0000000000b4', revoked_at = now()
 WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET status = ''APPROVED'' WHERE id = ''00000000-0000-0000-0000-0000000000d1''') $$,
    '23001', NULL, 'a revoked grant never comes back');
INSERT INTO permission_grant (id, user_id, permission, reason, requested_by)
VALUES ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-0000000000b5', 'audit.key.read',
        'administrator reading keys', '00000000-0000-0000-0000-0000000000b2');
SELECT throws_ok($$ SELECT run_as('snigf_app',
    'UPDATE snigf.permission_grant SET status = ''APPROVED'', decided_by = ''00000000-0000-0000-0000-0000000000b4'', decided_at = now()
      WHERE id = ''00000000-0000-0000-0000-0000000000d2''') $$,
    '23514', 'separation of duties: administration and audit together', 'a grant never gives an administrator an audit permission');

SELECT * FROM finish();
ROLLBACK;
