-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Retention metadata and legal holds, no deletion (Slice 3, V30, ADR-041 D18): no period and no schedule; a class
-- cannot claim a schedule without its reference and date; every covered record has its class, set by the database;
-- a hold placed by one authorised person, released by two; record_disposal_allowed false without a schedule and,
-- separately, false under a hold; no delete path; the cleanup keeps pending replacement files; who reads what.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(50);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- c1: conservator at DEMO1. d1, d2: system administrators. au: auditor. f1: a notary.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'c1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000a9', 'audit@demo.invalid', 'Auditor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'n1@demo.invalid', 'DEMO Notary One', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d2', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a9', 'AUDITOR', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES ('h1', '00000000-0000-0000-0000-0000000000c1', 'One');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one');

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
CREATE FUNCTION pg_temp.place(p_actor text, p_kind text, p_subject text, p_reason text DEFAULT 'Litigation notice DEMO-LIT-1 received') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.legal_hold_place(%L, %L, %L)::text$s$, p_kind, p_subject, p_reason))
$f$;
CREATE FUNCTION pg_temp.ask(p_actor text, p_hold text, p_reason text DEFAULT 'The litigation was settled, DEMO-LIT-1 closed') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.legal_hold_release_request(%L, %L)::text$s$, p_hold, p_reason))
$f$;
CREATE FUNCTION pg_temp.decide(p_actor text, p_release text, p_outcome text, p_reason text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.legal_hold_release_decide(%L, %L, %L)::text$s$, p_release, p_outcome, p_reason))
$f$;
CREATE FUNCTION pg_temp.disposal(p_kind text, p_id text) RETURNS boolean
LANGUAGE sql AS $f$ SELECT snigf.record_disposal_allowed(p_kind, p_id::uuid) $f$;

-- Fixtures: a title with a counter deposit and its deed, a pending replacement, and a professional's evidence.
DO $f$
DECLARE
    r       snigf.geometry_check_result;
    holder  uuid;
    created uuid;
    canon   bytea;
    nonce   bytea;
    title   uuid;
    deposit uuid;
    deed    uuid;
    v       uuid;
BEGIN
    PERFORM set_config('snigf.actor_id', '00000000-0000-0000-0000-0000000000c1', true);
    r := snigf.normalize_parcel_geometry('{"type":"Polygon","coordinates":[[[800000,400000],[800100,400000],[800100,400100],[800000,400100],[800000,400000]]]}', 32632);
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-000000005001', '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632,
            repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
    holder := snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Seller', 'NATIONAL_ID', 'DEMO-ID-H1', NULL, NULL, NULL, NULL);
    created := snigf.register_propose('00000000-0000-0000-0000-0000000000a1', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'A synthetic reason of enough length',
                                      ARRAY['DEMO-EVIDENCE'], NULL, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001',
                                      jsonb_build_array(jsonb_build_object('party', holder, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
    canon := snigf.inscription_canonical(created);
    nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
    PERFORM snigf.register_register(created, canon, jsonb_build_object(
        'credentialId', 'k1', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
        'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
        'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
        'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
    SELECT new_title_id INTO title FROM snigf.inscription WHERE id = created;
    deposit := snigf.transaction_deposit(title, 'SALE', holder, NULL, ARRAY['DEMO-DEED-1'], interval '30 days');
    deed := snigf.deposit_document_add(deposit, 'DEED', repeat('1', 32), repeat('d', 64), 100, 'application/pdf', 'deed.pdf', NULL, NULL);
    INSERT INTO ctx VALUES ('deposit', deposit::text), ('deed', deed::text);
    INSERT INTO ctx SELECT 'replacement', snigf.deposit_document_replacement_request(deed, repeat('2', 32), repeat('e', 64), 100, 'application/pdf',
                                                                                   'deed-v2.pdf', 'The scan is unreadable, a clearer copy')::text;
    PERFORM set_config('snigf.actor_id', '', true);

    INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction, status)
    VALUES ('00000000-0000-0000-0000-0000000000f1', 'NOTARY', 'DEMO Chamber', 'DEMO-N-1', 'DEMO Yaounde', 'VERIFIED');
    INSERT INTO snigf.professional_verification (user_id, verified_by, body, reference, checked_on, note)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000d1', 'DEMO Chamber', 'DEMO-REF', current_date, 'Checked with the DEMO chamber')
    RETURNING id INTO v;
    -- The caller names a wrong class: the database sets the right one.
    INSERT INTO snigf.professional_verification_document (verification_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by, retention_class)
    VALUES (v, repeat('3', 32), repeat('f', 64), 10, 'application/pdf', 'chamber-letter.pdf', '00000000-0000-0000-0000-0000000000d1', 'DEPOSIT_DOCUMENT');
    INSERT INTO ctx SELECT 'evidence', id::text FROM snigf.professional_verification_document WHERE verification_id = v;
END;
$f$;

-- ---- 1. The classes: no period, no schedule -----------------------------------------------------------------
SELECT results_eq($$ SELECT code, standing, schedule_reference IS NULL AND schedule_approved_on IS NULL FROM retention_class ORDER BY code $$,
    $$ VALUES ('DEPOSIT_DOCUMENT', 'NO_APPROVED_SCHEDULE_KEPT', true), ('DEPOSIT_DOCUMENT_REPLACEMENT', 'NO_APPROVED_SCHEDULE_KEPT', true),
              ('PROFESSIONAL_EVIDENCE', 'NO_APPROVED_SCHEDULE_KEPT', true) $$,
    'three classes, each read "no approved schedule: kept"');
SELECT is((SELECT count(*)::int FROM information_schema.columns WHERE table_schema = 'snigf' AND table_name = 'retention_class'
             AND column_name ~ '(period|year|month|day|until|expir|dispos)'), 0, 'no period column: a schedule needs its own ADR and migration');
SELECT throws_ok($$ INSERT INTO retention_class (code, description_en, description_fr, schedule_reference) VALUES ('TEST_CLASS', 'x', 'x', 'DEMO-SCHED-1') $$,
    '23514', NULL, 'a class cannot claim a schedule without its approval date');
SELECT throws_ok($$ INSERT INTO retention_class (code, description_en, description_fr, schedule_approved_on) VALUES ('TEST_CLASS', 'x', 'x', current_date) $$,
    '23514', NULL, '... nor with a date and no reference');
SELECT throws_ok($$ UPDATE retention_class SET description_en = 'Rewritten' $$, '23001', NULL, 'a class is never rewritten');
SELECT throws_ok($$ DELETE FROM retention_class $$, '23001', NULL, '... nor deleted');

-- ---- 2. Every covered record has its class, set by the database ------------------------------------------------
SELECT col_not_null('deposit_document', 'retention_class', 'every deposit document names its class');
SELECT col_not_null('deposit_document_replacement', 'retention_class', 'every replacement file names its class');
SELECT col_not_null('professional_verification_document', 'retention_class', 'every piece of evidence names its class');
SELECT is((SELECT retention_class FROM deposit_document WHERE id = pg_temp.c('deed')::uuid), 'DEPOSIT_DOCUMENT', 'a deposit document''s class');
SELECT is((SELECT retention_class FROM deposit_document_replacement WHERE id = pg_temp.c('replacement')::uuid), 'DEPOSIT_DOCUMENT_REPLACEMENT', 'a replacement file''s class');
SELECT is((SELECT retention_class FROM professional_verification_document WHERE id = pg_temp.c('evidence')::uuid), 'PROFESSIONAL_EVIDENCE',
    'the evidence''s class, whatever the caller named');
SELECT throws_ok($$ UPDATE deposit_document_replacement SET retention_class = 'DEPOSIT_DOCUMENT' $$, '23001', NULL, 'a class is set once');
SELECT is((SELECT count(*)::int FROM document_storage_keys_known(ARRAY[repeat('2', 32)])), 1, 'the orphan cleanup keeps a pending replacement''s file');

-- ---- 3. Who places a hold ----------------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000c1', 'DEPOSIT', pg_temp.c('deposit')) $$,
    'RT001', 'RECORDS_FORBIDDEN', 'a conservator does not place holds');
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000a9', 'DEPOSIT', pg_temp.c('deposit')) $$,
    'RT001', 'RECORDS_FORBIDDEN', 'an auditor reads, and does not place holds');
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000f1', 'DEPOSIT', pg_temp.c('deposit')) $$,
    'RT001', 'RECORDS_FORBIDDEN', 'nor does a professional');
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000d1', 'DEPOSIT', pg_temp.c('deposit'), 'Short') $$,
    'RT001', 'REASON_REQUIRED', 'a hold gives its reason');
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000d1', 'DEPOSIT', gen_random_uuid()::text) $$,
    'RT001', 'SUBJECT_NOT_FOUND', 'a hold names an existing subject');
SELECT throws_ok($$ SELECT pg_temp.place('00000000-0000-0000-0000-0000000000d1', 'APPLICATION', pg_temp.c('deposit')) $$,
    'RT001', 'SUBJECT_KIND_INVALID', 'application drafts are outside D18');
INSERT INTO ctx SELECT 'hold', pg_temp.place('00000000-0000-0000-0000-0000000000d1', 'DEPOSIT', pg_temp.c('deposit'));
SELECT is((SELECT count(*)::int FROM record_holds('DEPOSIT_DOCUMENT', pg_temp.c('deed')::uuid)), 1, 'a hold on a deposit covers its documents');
SELECT is((SELECT count(*)::int FROM record_holds('DEPOSIT_DOCUMENT_REPLACEMENT', pg_temp.c('replacement')::uuid)), 1, '... and its replacement files');

-- ---- 4. record_disposal_allowed: two reasons, checked separately ------------------------------------------------
SELECT is(pg_temp.disposal('PROFESSIONAL_EVIDENCE', pg_temp.c('evidence')), false, 'no approved schedule: disposal is not allowed, without any hold');
SELECT is((SELECT count(*)::int FROM record_holds('PROFESSIONAL_EVIDENCE', pg_temp.c('evidence')::uuid)), 0, '... indeed, no hold covers it');
-- For the test only, as if a schedule had been approved for evidence (the transaction is rolled back).
ALTER TABLE retention_class DISABLE TRIGGER retention_class_append_only;
UPDATE retention_class SET schedule_reference = 'DEMO-SCHEDULE-TEST', schedule_approved_on = current_date WHERE code = 'PROFESSIONAL_EVIDENCE';
ALTER TABLE retention_class ENABLE TRIGGER retention_class_append_only;
SELECT is(pg_temp.disposal('PROFESSIONAL_EVIDENCE', pg_temp.c('evidence')), true, 'with a schedule and no hold, it would be allowed');
INSERT INTO ctx SELECT 'pro_hold', pg_temp.place('00000000-0000-0000-0000-0000000000d1', 'PROFESSIONAL', '00000000-0000-0000-0000-0000000000f1');
SELECT is(pg_temp.disposal('PROFESSIONAL_EVIDENCE', pg_temp.c('evidence')), false, 'under a hold, even with a schedule, it is not');
SELECT is(pg_temp.disposal('DEPOSIT_DOCUMENT', pg_temp.c('deed')), false, 'a deposit document, without a schedule and under a hold, is not');

-- ---- 5. Released by two -----------------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000a9', pg_temp.c('pro_hold')) $$,
    'RT001', 'RECORDS_FORBIDDEN', 'an auditor does not release');
INSERT INTO ctx SELECT 'release1', pg_temp.ask('00000000-0000-0000-0000-0000000000d1', pg_temp.c('pro_hold'));
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000d2', pg_temp.c('pro_hold')) $$,
    'RT001', 'RELEASE_PENDING', 'one pending release per hold');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d1', pg_temp.c('release1'), 'APPROVED', NULL) $$,
    'RT001', 'REQUESTER_CANNOT_DECIDE', 'the requester never approves their own release');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('release1'), 'REJECTED', NULL) $$,
    'RT001', 'REASON_REQUIRED', 'a rejection gives its reason');
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('release1'), 'REJECTED', 'The litigation is still open, see DEMO-LIT-1') $$,
    'a second administrator rejects it');
SELECT is(legal_hold_in_force(pg_temp.c('pro_hold')::uuid), true, 'a rejected release leaves the hold in force');
INSERT INTO ctx SELECT 'release2', pg_temp.ask('00000000-0000-0000-0000-0000000000d1', pg_temp.c('pro_hold'));
SELECT lives_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d2', pg_temp.c('release2'), 'APPROVED', NULL) $$,
    'a second administrator approves the next one');
SELECT is(legal_hold_in_force(pg_temp.c('pro_hold')::uuid), false, 'the hold is released');
SELECT is((SELECT count(*)::int FROM legal_hold WHERE id = pg_temp.c('pro_hold')::uuid), 1, '... and never deleted: its release is recorded beside it');
SELECT is(pg_temp.disposal('PROFESSIONAL_EVIDENCE', pg_temp.c('evidence')), true, 'once released, the schedule alone decides again');
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000d2', pg_temp.c('pro_hold')) $$,
    'RT001', 'HOLD_RELEASED', 'a released hold is not released twice');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000d1', pg_temp.c('release2'), 'REJECTED', 'Changing my mind afterwards') $$,
    'RT001', 'RELEASE_CLOSED', 'a release is decided once');

-- ---- 6. No delete path, no rewriting ---------------------------------------------------------------------------
SELECT throws_ok($$ DELETE FROM legal_hold $$, '23001', NULL, 'no hold is ever deleted, not even by the owner');
SELECT throws_ok($$ DELETE FROM legal_hold_release $$, '23001', NULL, 'no release is ever deleted');
SELECT throws_ok($$ UPDATE legal_hold_release SET reason = 'Rewritten afterwards, a long reason' WHERE id = pg_temp.c('release2')::uuid $$,
    '23001', NULL, 'a decided release is never rewritten');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000d1', format($s$INSERT INTO snigf.legal_hold (subject_kind, subject_id, reason, placed_by)
    VALUES ('DEPOSIT', %L, 'Written around the function', '00000000-0000-0000-0000-0000000000d1') RETURNING id::text$s$, pg_temp.c('deposit'))) $$,
    '42501', NULL, 'the application writes holds only through the functions');
SELECT is((SELECT count(*)::int FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'snigf'
             AND p.prosrc ~* 'DELETE\s+FROM\s+(snigf\.)?(deposit_document|deposit_document_replacement|professional_verification_document|legal_hold)\M'), 0,
    'no function deletes a covered record or a hold');

-- ---- 7. Who reads ---------------------------------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000a9', 'SELECT count(*)::text FROM snigf.legal_hold'), '2', 'the auditor reads the holds');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000a9', 'SELECT count(*)::text FROM snigf.legal_hold_release'), '2', '... and their release history');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT count(*)::text FROM snigf.legal_hold'), '0', 'a conservator reads none');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000a9', format($s$SELECT concat_ws('|', retention_class, standing, holds_in_force, disposal_allowed::text)
                                                                      FROM snigf.record_retention('DEPOSIT_DOCUMENT', %L)$s$, pg_temp.c('deed'))),
    'DEPOSIT_DOCUMENT|NO_APPROVED_SCHEDULE_KEPT|1|false', 'the auditor reads a document''s retention record, not the document');
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$SELECT standing FROM snigf.record_retention('DEPOSIT_DOCUMENT', %L)$s$) $$, pg_temp.c('deed')),
    'RT001', 'RECORDS_FORBIDDEN', 'a conservator does not read retention data');
SELECT results_eq($$ SELECT p.code, p.family, p.delegable, p.professional, array_agg(rp.role_code ORDER BY rp.role_code)
                      FROM permission p JOIN role_permission rp ON rp.permission = p.code WHERE p.code LIKE 'records.%' GROUP BY 1, 2, 3, 4 ORDER BY 1 $$,
    $$ VALUES ('records.hold.place', 'ADMINISTRATION', false, false, ARRAY['SYSTEM_ADMIN']),
              ('records.hold.release', 'ADMINISTRATION', false, false, ARRAY['SYSTEM_ADMIN']),
              ('records.retention.read', 'BUSINESS', false, false, ARRAY['AUDITOR', 'SYSTEM_ADMIN']) $$,
    'three permissions, never professional, never delegable: the auditor reads, the system administrator also places and releases');

SELECT * FROM finish();
ROLLBACK;
