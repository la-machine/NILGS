-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Professional permissions (Slice 3, V25, ADR-041 step 3): an ACTIVE notary holds the four deposit
-- permissions, submit at an approved office only, the others for their own records; a surveyor holds
-- none; suspension or a removed office takes them away at once; no staff role, custom role, assignment,
-- request or grant can carry them, and the staff functions keep refusing professionals.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(24);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
INSERT INTO office (id, code, name_fr, name_en) VALUES ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau 2', 'Office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c1', 'cons@demo.invalid', 'Conservator', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'notary@demo.invalid', 'Demo Notary', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f2', 'surveyor@demo.invalid', 'Demo Surveyor', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed');

-- Two active professionals at DEMO1, as an approved activation leaves them.
DO $$
DECLARE
    who record;
    v   uuid;
    r   uuid;
BEGIN
    FOR who IN SELECT * FROM (VALUES ('00000000-0000-0000-0000-0000000000f1'::uuid, 'NOTARY', 'DEMO-N-1'),
                                     ('00000000-0000-0000-0000-0000000000f2'::uuid, 'SWORN_SURVEYOR', 'DEMO-S-1')) t(id, profession, reg) LOOP
        INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction, status)
        VALUES (who.id, who.profession, 'DEMO Chamber', who.reg, 'DEMO Yaounde', 'ACTIVE');
        INSERT INTO snigf.professional_verification (user_id, verified_by, body, reference, checked_on, note)
        VALUES (who.id, '00000000-0000-0000-0000-0000000000d1', 'DEMO Chamber', 'DEMO-REF', current_date, 'Checked with the DEMO chamber')
        RETURNING id INTO v;
        INSERT INTO snigf.professional_request (user_id, kind, offices_add, reason, verification_id, requested_by, status, decided_by, decided_at)
        VALUES (who.id, 'ACTIVATION', ARRAY['00000000-0000-0000-0000-0000000000a1']::uuid[], 'Checked with the DEMO chamber', v,
                '00000000-0000-0000-0000-0000000000d1', 'APPROVED', '00000000-0000-0000-0000-0000000000d2', now())
        RETURNING id INTO r;
        INSERT INTO snigf.professional_office (user_id, office_id, added_by_request) VALUES (who.id, '00000000-0000-0000-0000-0000000000a1', r);
    END LOOP;
END;
$$;

-- ---- 1. What an active notary holds ----------------------------------------------------------------
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a1'), true,
    'an active notary deposits at an approved office');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a2'), false,
    'never at another office');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.submit', NULL), false,
    'an office-scoped permission needs its office');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.read', NULL), true, 'reads their own deposits');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.withdraw', NULL), true, 'withdraws their own deposits');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.read', '00000000-0000-0000-0000-0000000000a1'), false,
    'never a staff permission');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.inscribe', '00000000-0000-0000-0000-0000000000a1'), false,
    'never an inscription');
SELECT results_eq($$ SELECT role, office_code FROM professional_grants('00000000-0000-0000-0000-0000000000f1') $$,
    $$ VALUES ('NOTARY'::text, 'DEMO1'::text) $$, 'the session sees the profession at each approved office');

-- ---- 2. A surveyor holds nothing; staff never hold these ---------------------------------------------
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f2', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a1'), false,
    'a sworn surveyor has no operational permission');
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000c1', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a1'), false,
    'a conservator holds no professional permission');
SELECT is(holds_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a1'), false,
    'the staff function refuses an active notary');

-- ---- 3. Suspension and offices take effect at once ----------------------------------------------------
UPDATE professional_profile SET status = 'SUSPENDED' WHERE user_id = '00000000-0000-0000-0000-0000000000f1';
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.read', NULL), false, 'suspended: nothing');
SELECT is((SELECT count(*)::int FROM professional_grants('00000000-0000-0000-0000-0000000000f1')), 0, 'suspended: no role in the session');
UPDATE professional_profile SET status = 'ACTIVE' WHERE user_id = '00000000-0000-0000-0000-0000000000f1';
UPDATE professional_office SET removed_at = now(), removed_by_request = added_by_request WHERE user_id = '00000000-0000-0000-0000-0000000000f1';
SELECT is(holds_professional_at('00000000-0000-0000-0000-0000000000f1', 'register.deposit.submit', '00000000-0000-0000-0000-0000000000a1'), false,
    'a removed office: no deposit there');

-- ---- 4. Nothing else can carry a professional permission -----------------------------------------------
SELECT throws_ok($$ INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
                    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'NOTARY', '00000000-0000-0000-0000-0000000000a1', 'bypass') $$,
    '23514', NULL, 'not even the superuser assigns NOTARY to staff');
SELECT throws_ok($$ INSERT INTO role_assignment (id, user_id, role, grant_reason)
                    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b3', 'NOTARY', 'self-registration') $$,
    '23514', NULL, 'nor to an applicant through the self-registration path');
SELECT throws_ok($$ INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
                    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'NOTARY', '00000000-0000-0000-0000-0000000000a1', 'bypass') $$,
    '23514', NULL, 'nor to a professional: the role is computed');
SELECT throws_ok($$ INSERT INTO role_assignment_request (user_id, role, office_id, reason, requested_by)
                    VALUES ('00000000-0000-0000-0000-0000000000c1', 'NOTARY', '00000000-0000-0000-0000-0000000000a1', 'A request for the role', '00000000-0000-0000-0000-0000000000d1') $$,
    '23514', NULL, 'nor requested');
INSERT INTO role_definition (code, name_fr, name_en, system, assignable) VALUES ('CUSTOM_DEPOSITS', 'Rôle', 'Role', false, true);
SELECT throws_ok($$ INSERT INTO role_permission (role_code, permission, scope) VALUES ('CUSTOM_DEPOSITS', 'register.deposit.submit', 'OFFICE') $$,
    '23514', NULL, 'a custom role never holds a professional permission');
SELECT throws_ok($$ ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
                    INSERT INTO role_permission (role_code, permission, scope) VALUES ('NOTARY', 'register.read', 'OFFICE') $$,
    '23514', NULL, 'a professional role never holds a staff permission, even past the baseline guard');
SELECT is((SELECT count(*)::int FROM permission WHERE professional AND delegable), 0, 'no professional permission is delegable by grant');
SELECT throws_ok($$ UPDATE permission SET delegable = true WHERE code = 'register.deposit.submit' $$,
    '23514', NULL, 'and none can become delegable');
SELECT throws_ok($$ UPDATE role_definition SET assignable = true WHERE code = 'NOTARY' $$,
    NULL, NULL, 'a professional role never becomes assignable');
SELECT is((SELECT array_agg(code ORDER BY code) FROM permission WHERE professional),
    ARRAY['register.deposit.document', 'register.deposit.read', 'register.deposit.submit', 'register.deposit.withdraw'],
    'exactly the four deposit permissions are professional');

SELECT * FROM finish();
ROLLBACK;
