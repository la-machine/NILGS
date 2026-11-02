-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Professional accounts (Slice 3, V23, ADR-041 step 1): the type exists; a profile belongs to a
-- professional account only, with a synthetic credential; identity, profession and a revocation are
-- final; nothing becomes professional later, nor stops being; the application cannot write a profile;
-- and every staff permission function stays false for a professional, even one given a staff role
-- behind the workflow's back.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(16);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000f1', 'notary@demo.invalid', 'Demo Notary', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f2', 'surveyor@demo.invalid', 'Demo Surveyor', 'x', 'PROFESSIONAL');

SELECT lives_ok($$ INSERT INTO professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
                   VALUES ('00000000-0000-0000-0000-0000000000f1', 'NOTARY', 'DEMO Chamber', 'DEMO-N-1', 'DEMO Yaounde') $$,
    'a professional account has a profile');
SELECT is((SELECT status FROM professional_profile WHERE user_id = '00000000-0000-0000-0000-0000000000f1'), 'REQUESTED',
    'a profile starts as requested');
SELECT throws_ok($$ INSERT INTO professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
                    VALUES ('00000000-0000-0000-0000-0000000000b1', 'NOTARY', 'DEMO Chamber', 'DEMO-N-2', 'DEMO Yaounde') $$,
    '23514', NULL, 'a staff account has no professional profile');
SELECT throws_ok($$ INSERT INTO professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
                    VALUES ('00000000-0000-0000-0000-0000000000f2', 'SWORN_SURVEYOR', 'DEMO Order', 'N-12345', 'DEMO Douala') $$,
    '23514', NULL, 'the registration number must be synthetic');
SELECT throws_ok($$ INSERT INTO professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
                    VALUES ('00000000-0000-0000-0000-0000000000f2', 'ARCHITECT', 'DEMO Order', 'DEMO-A-1', 'DEMO Douala') $$,
    '23514', NULL, 'only notaries and sworn surveyors');
SELECT throws_ok($$ UPDATE professional_profile SET profession = 'SWORN_SURVEYOR' WHERE user_id = '00000000-0000-0000-0000-0000000000f1' $$,
    '23001', NULL, 'the profession never changes');
SELECT throws_ok($$ DELETE FROM professional_profile WHERE user_id = '00000000-0000-0000-0000-0000000000f1' $$,
    '23001', NULL, 'a profile is never deleted');

SELECT throws_ok($$ UPDATE app_user SET account_type = 'STAFF' WHERE id = '00000000-0000-0000-0000-0000000000f1' $$,
    '23001', NULL, 'a professional never becomes staff');
SELECT throws_ok($$ UPDATE app_user SET account_type = 'PROFESSIONAL' WHERE id = '00000000-0000-0000-0000-0000000000b1' $$,
    '23001', NULL, 'staff never becomes professional');

SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction)
                    VALUES (''00000000-0000-0000-0000-0000000000f2'', ''NOTARY'', ''DEMO C'', ''DEMO-N-9'', ''DEMO J'')') $$,
    '42501', NULL, 'the application cannot write a profile');

-- The workflow refuses a staff role for a professional; the superuser can bypass it, and even then
-- the staff permission functions say no.
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.role_assignment (id, user_id, role, office_id, grant_reason)
                    VALUES (gen_random_uuid(), ''00000000-0000-0000-0000-0000000000f1'', ''CONSERVATOR'', ''00000000-0000-0000-0000-0000000000a1'', ''bypass'')') $$,
    NULL, NULL, 'the application cannot give a professional a staff role');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'superuser bypass');
SELECT is(holds_permission('00000000-0000-0000-0000-0000000000f1', 'register.read'), false, 'holds_permission: false for a professional');
SELECT is(holds_permission_at('00000000-0000-0000-0000-0000000000f1', 'register.inscribe', '00000000-0000-0000-0000-0000000000a1'), false,
    'holds_permission_at: false for a professional, even holding the role');
SELECT is(holds_at('00000000-0000-0000-0000-0000000000f1', 'register.propose', '00000000-0000-0000-0000-0000000000a1'), false,
    'holds_at: false for a professional');
SELECT is(holds_grant_at('00000000-0000-0000-0000-0000000000f1', 'register.read', '00000000-0000-0000-0000-0000000000a1'), false,
    'holds_grant_at: false for a professional');
SELECT is(holds_assignable('00000000-0000-0000-0000-0000000000f1', 'register.read', '00000000-0000-0000-0000-0000000000a1'), false,
    'holds_assignable: false for a professional');

SELECT * FROM finish();
ROLLBACK;
