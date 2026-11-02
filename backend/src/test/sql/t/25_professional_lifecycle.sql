-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The professional lifecycle (Slice 3, V24, ADR-041 step 2): a request; verified by one administrator
-- with offices; approved by another, never the verifier or the professional, and only with two keys;
-- suspended by one; reinstated, revoked and moved between offices by two; one open request; expiry;
-- evidence for verifiers and approvers only; nothing written directly; every step in the history; a
-- professional's lost keys reset by a supervisor of their office.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(47);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- a1 DEMO1, a2 DEMO2. b2 is an administrator; d1, d2 two more; r1 a reader-only custom role holder;
-- s1 supervisor at DEMO1. f1 notary.
INSERT INTO office (id, code, name_fr, name_en) VALUES ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau 2', 'Office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d3', 'adm3@demo.invalid', 'Admin Three', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c1', 'sup@demo.invalid', 'Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e1', 'clerk@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'notary@demo.invalid', 'Demo Notary', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d2', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d3', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');

CREATE FUNCTION pg_temp.q(p_actor text, p_sql text) RETURNS text
LANGUAGE plpgsql AS $f$
DECLARE v text;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    EXECUTE p_sql INTO v;
    RESET ROLE;
    RETURN v;
END;
$f$;
CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;
CREATE FUNCTION pg_temp.verify(p_actor text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, $s$SELECT snigf.professional_verify('00000000-0000-0000-0000-0000000000f1', 'DEMO Chamber of Notaries', 'DEMO-REF-1',
        current_date, 'Credential confirmed by the DEMO chamber by telephone', ARRAY['00000000-0000-0000-0000-0000000000a1']::uuid[])$s$)
$f$;
CREATE FUNCTION pg_temp.decide(p_actor text, p_request text, p_approve boolean, p_reason text DEFAULT NULL) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.professional_decide(%L, %L, %L)$s$, p_request, p_approve, p_reason))
$f$;
CREATE FUNCTION pg_temp.change(p_actor text, p_kind text, p_add text, p_remove text, p_reason text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.professional_request_change('00000000-0000-0000-0000-0000000000f1', %L, %L::uuid[], %L::uuid[], %L)$s$,
                                     p_kind, p_add, p_remove, p_reason))
$f$;
CREATE FUNCTION pg_temp.status() RETURNS text LANGUAGE sql AS $f$
    SELECT status FROM snigf.professional_profile WHERE user_id = '00000000-0000-0000-0000-0000000000f1'
$f$;
CREATE FUNCTION pg_temp.offices() RETURNS text LANGUAGE sql AS $f$
    SELECT string_agg(o.code, ',' ORDER BY o.code) FROM snigf.professional_office p JOIN snigf.office o ON o.id = p.office_id
     WHERE p.user_id = '00000000-0000-0000-0000-0000000000f1' AND p.removed_at IS NULL
$f$;

-- ---- 1. The request ---------------------------------------------------------------------------------
SELECT lives_ok($$ SELECT pg_temp.q(NULL, 'SELECT snigf.professional_profile_create(''00000000-0000-0000-0000-0000000000f1'', ''NOTARY'', ''DEMO Chamber'', ''DEMO-N-1'', ''DEMO Yaounde'')') $$,
    'the professional''s request creates the profile');
SELECT is(pg_temp.status(), 'REQUESTED', 'requested');
SELECT throws_ok($$ SELECT pg_temp.q(NULL, 'SELECT snigf.professional_profile_create(''00000000-0000-0000-0000-0000000000d1'', ''NOTARY'', ''DEMO C'', ''DEMO-N-2'', ''DEMO Y'')') $$,
    'PR001', 'PROFESSIONAL_NOT_FOUND', 'no profile for a staff account');
SELECT throws_ok($$ SELECT pg_temp.q(NULL, 'SELECT snigf.professional_profile_create(''00000000-0000-0000-0000-0000000000f1'', ''NOTARY'', ''DEMO C'', ''DEMO-N-3'', ''DEMO Y'')') $$,
    'PR001', 'PROFESSIONAL_NOT_FOUND', 'one profile per account');

-- ---- 2. Verification by one administrator --------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.verify('00000000-0000-0000-0000-0000000000e1') $$, 'PR001', 'PROFESSIONAL_FORBIDDEN',
    'an officer without admin.professional.verify cannot verify');
SELECT throws_ok($$ SELECT pg_temp.verify('00000000-0000-0000-0000-0000000000f1') $$, 'PR001', 'PROFESSIONAL_FORBIDDEN',
    'a professional cannot verify, themselves or anyone');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', $s$SELECT snigf.professional_verify('00000000-0000-0000-0000-0000000000f1', 'DEMO Chamber', 'DEMO-REF', current_date, 'Credential confirmed by phone', '{}'::uuid[])$s$) $$,
    'PR001', 'OFFICES_INVALID', 'a verification proposes at least one office');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', $s$SELECT snigf.professional_verify('00000000-0000-0000-0000-0000000000f1', 'DEMO Chamber', 'DEMO-REF', current_date, 'short', ARRAY['00000000-0000-0000-0000-0000000000a1']::uuid[])$s$) $$,
    'PR001', 'VERIFICATION_INCOMPLETE', 'a verification says how the credential was checked');
INSERT INTO ctx SELECT 'activation', pg_temp.verify('00000000-0000-0000-0000-0000000000d1');
SELECT is(pg_temp.status(), 'VERIFIED', 'verified');
SELECT is(pg_temp.offices(), NULL, 'no office before approval');
SELECT throws_ok($$ SELECT pg_temp.verify('00000000-0000-0000-0000-0000000000d2') $$, 'PR001', 'INVALID_STATUS', 'verified once');

-- Evidence: by the verifier, while the activation is open; read by verifiers and approvers only.
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', format($s$SELECT snigf.professional_evidence_add(%L, repeat('5', 32), repeat('a', 64), 10, 'application/pdf', 'letter.pdf')$s$,
                   (SELECT verification_id FROM professional_request WHERE id = pg_temp.c('activation')::uuid))) $$, 'the verifier adds evidence');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d2', format($s$SELECT snigf.professional_evidence_add(%L, repeat('6', 32), repeat('a', 64), 10, 'application/pdf', 'x.pdf')$s$,
                   (SELECT verification_id FROM professional_request WHERE id = pg_temp.c('activation')::uuid))) $$, 'PR001', 'NOT_THE_VERIFIER',
    'only the verifier adds evidence');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000d2', 'SELECT count(*) FROM snigf.professional_verification_document'), '1', 'an approver reads the evidence');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT count(*) FROM snigf.professional_verification_document'), '0', 'the professional does not');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT count(*) FROM snigf.professional_verification'), '0', 'nor the verification record');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000e1', 'SELECT count(*) FROM snigf.professional_profile'), '0', 'an officer sees no professional');
SELECT results_eq($$ SELECT * FROM document_storage_keys_known(ARRAY[repeat('5', 32)]) $$, $$ VALUES (repeat('5', 32)) $$,
    'the orphan cleanup knows the evidence');

-- ---- 3. Approval by another administrator ---------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d1', pg_temp.c('activation'), true) $$, 'PR001', 'FOUR_EYES',
    'the verifier never approves');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000e1', pg_temp.c('activation'), true) $$, 'PR001', 'REQUEST_NOT_FOUND',
    'without admin.professional.approve the request does not exist');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('activation'), true) $$, 'PR001', 'SECURITY_KEYS_REQUIRED',
    'no approval before two security keys');
INSERT INTO user_entities (id, name, display_name) VALUES ('hf', '00000000-0000-0000-0000-0000000000f1', 'Notary');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('kf1', 'hf', '\x01', false, false, 'One'), ('kf2', 'hf', '\x02', false, false, 'Two');
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('activation'), true) $$, 'a second administrator approves');
SELECT is(pg_temp.status(), 'ACTIVE', 'active');
SELECT is(pg_temp.offices(), 'DEMO1', 'with the offices the verifier proposed');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d3', pg_temp.c('activation'), true) $$, 'PR001', 'REQUEST_CLOSED',
    'decided once');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.professional_profile SET status = ''SUSPENDED''') $$, '42501', NULL,
    'the application cannot change a status directly');
SELECT throws_ok($$ SELECT run_as('snigf_app', format('INSERT INTO snigf.professional_office (user_id, office_id, added_by_request) VALUES (''00000000-0000-0000-0000-0000000000f1'', ''00000000-0000-0000-0000-0000000000a2'', %L)', pg_temp.c('activation'))) $$,
    '42501', NULL, 'the application cannot add an office directly');

-- ---- 4. Office changes, by two ---------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.change('00000000-0000-0000-0000-0000000000d1', 'OFFICE_CHANGE', '{}', '{00000000-0000-0000-0000-0000000000a1}', 'Removing the only office') $$,
    'PR001', 'OFFICES_INVALID', 'a professional keeps at least one office');
INSERT INTO ctx SELECT 'move', pg_temp.change('00000000-0000-0000-0000-0000000000d1', 'OFFICE_CHANGE', '{00000000-0000-0000-0000-0000000000a2}', '{}', 'Serves the DEMO2 office too');
SELECT is(pg_temp.offices(), 'DEMO1', 'nothing changes before the second administrator');
SELECT throws_ok($$ SELECT pg_temp.change('00000000-0000-0000-0000-0000000000d3', 'REVOCATION', NULL, NULL, 'A second open request') $$,
    'PR001', 'REQUEST_ALREADY_OPEN', 'one open request per professional');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d1', pg_temp.c('move'), true) $$, 'PR001', 'FOUR_EYES', 'never by its requester');
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('move'), true) $$, 'approved by another');
SELECT is(pg_temp.offices(), 'DEMO1,DEMO2', 'the office is added');
-- The supervisor of an office confirms a professional's reset, as the application, without reading the table.
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT count(*) FROM snigf.professional_office'), '0',
    'a supervisor cannot read the offices of professionals');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT count(*) FROM snigf.professional_office_ids(''00000000-0000-0000-0000-0000000000f1'')'), '2',
    'but the reset finds them');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000d1', 'SELECT snigf.is_professional(''00000000-0000-0000-0000-0000000000f1'')::text'), 'true',
    'a reset may target a professional, whoever asks');

-- ---- 5. Suspension by one; reinstatement by two ----------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', 'SELECT snigf.professional_suspend(''00000000-0000-0000-0000-0000000000f1'', ''short'')') $$,
    'PR001', 'REASON_REQUIRED', 'a suspension has a reason');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', 'SELECT snigf.professional_suspend(''00000000-0000-0000-0000-0000000000f1'', ''Deposits reported as abusive'')') $$,
    'one administrator suspends');
SELECT is(pg_temp.status(), 'SUSPENDED', 'suspended at once');
INSERT INTO ctx SELECT 'back', pg_temp.change('00000000-0000-0000-0000-0000000000d1', 'REINSTATEMENT', NULL, NULL, 'The report was unfounded');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('back'), false, 'no') $$, 'PR001', 'REASON_REQUIRED',
    'a rejection has a reason');
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('back'), true) $$, 'reinstated by a second administrator');
SELECT is(pg_temp.status(), 'ACTIVE', 'active again');

-- ---- 6. Revocation by two; final ---------------------------------------------------------------------------
INSERT INTO ctx SELECT 'end', pg_temp.change('00000000-0000-0000-0000-0000000000d3', 'REVOCATION', NULL, NULL, 'The chamber struck the notary off');
SELECT is(pg_temp.status(), 'ACTIVE', 'a revocation request alone changes nothing');
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d1', pg_temp.c('end'), true) $$, 'revoked by a second administrator');
SELECT is(pg_temp.status() || ':' || coalesce(pg_temp.offices(), 'none'), 'REVOKED:none', 'revoked, every office removed');
SELECT throws_ok($$ SELECT pg_temp.change('00000000-0000-0000-0000-0000000000d1', 'REINSTATEMENT', NULL, NULL, 'Bringing a revoked account back') $$,
    'PR001', 'INVALID_STATUS', 'a revocation is final');
SELECT is((SELECT array_agg(event ORDER BY at, id) FROM professional_history WHERE user_id = '00000000-0000-0000-0000-0000000000f1'),
    ARRAY['REQUESTED', 'VERIFIED', 'EVIDENCE_ADDED', 'ACTIVATION_APPROVED', 'OFFICE_CHANGE_REQUESTED', 'OFFICE_CHANGE_APPROVED', 'SUSPENDED',
          'REINSTATEMENT_REQUESTED', 'REINSTATEMENT_APPROVED', 'REVOCATION_REQUESTED', 'REVOCATION_APPROVED'],
    'every step is in the history');

SELECT * FROM finish();
ROLLBACK;
