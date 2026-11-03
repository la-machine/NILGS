-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Professional deposits (Slice 3, V26, ADR-041 step 4): the lookup answers a title at a served office and
-- nothing else, the same for unknown and elsewhere; a refused deposit records nothing and locks nothing; a
-- valid one carries its deed, follows the V21 lock and conflict rules, and records the account and capacity,
-- never a receiving officer; duplicates are refused; a professional reads, adds to and withdraws only their
-- own deposits; the holder rule; suspension takes it all away; conservators still decide. Since V27 (D17) a
-- professional reads their deposit's real status: 28_professional_conflict_visibility tests what that shows.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(48);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- c1, c2: conservators at DEMO1. f1, f3: notaries at DEMO1. f2: a surveyor at DEMO1. f5: a notary at DEMO2 only.
INSERT INTO office (id, code, name_fr, name_en) VALUES ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau 2', 'Office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'c1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'c2@demo.invalid', 'Conservator Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'n1@demo.invalid', 'DEMO Notary One', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f2', 's1@demo.invalid', 'DEMO Surveyor', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f3', 'n3@demo.invalid', 'Notary Three', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f5', 'n5@demo.invalid', 'DEMO Notary Elsewhere', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES ('h1', '00000000-0000-0000-0000-0000000000c1', 'One');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one');

-- Active professionals, as an approved activation leaves them.
DO $$
DECLARE
    who record;
    v   uuid;
    r   uuid;
BEGIN
    FOR who IN SELECT * FROM (VALUES ('00000000-0000-0000-0000-0000000000f1'::uuid, 'NOTARY', 'DEMO-N-1', '00000000-0000-0000-0000-0000000000a1'::uuid),
                                     ('00000000-0000-0000-0000-0000000000f2'::uuid, 'SWORN_SURVEYOR', 'DEMO-S-1', '00000000-0000-0000-0000-0000000000a1'::uuid),
                                     ('00000000-0000-0000-0000-0000000000f3'::uuid, 'NOTARY', 'DEMO-N-3', '00000000-0000-0000-0000-0000000000a1'::uuid),
                                     ('00000000-0000-0000-0000-0000000000f5'::uuid, 'NOTARY', 'DEMO-N-5', '00000000-0000-0000-0000-0000000000a2'::uuid))
                              t(id, profession, reg, office) LOOP
        INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction, status)
        VALUES (who.id, who.profession, 'DEMO Chamber', who.reg, 'DEMO Yaounde', 'ACTIVE');
        INSERT INTO snigf.professional_verification (user_id, verified_by, body, reference, checked_on, note)
        VALUES (who.id, '00000000-0000-0000-0000-0000000000d1', 'DEMO Chamber', 'DEMO-REF', current_date, 'Checked with the DEMO chamber')
        RETURNING id INTO v;
        INSERT INTO snigf.professional_request (user_id, kind, offices_add, reason, verification_id, requested_by, status, decided_by, decided_at)
        VALUES (who.id, 'ACTIVATION', ARRAY[who.office], 'Checked with the DEMO chamber', v,
                '00000000-0000-0000-0000-0000000000d1', 'APPROVED', '00000000-0000-0000-0000-0000000000d2', now())
        RETURNING id INTO r;
        INSERT INTO snigf.professional_office (user_id, office_id, added_by_request) VALUES (who.id, who.office, r);
    END LOOP;
END;
$$;

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

-- Files as the application records them once kept: one DEED with this hash, or one of another category.
CREATE FUNCTION pg_temp.files(p_sha text, p_category text DEFAULT 'DEED') RETURNS text
LANGUAGE sql AS $f$
    SELECT jsonb_build_array(jsonb_build_object('category', p_category, 'storageKey', replace(gen_random_uuid()::text, '-', ''),
                                                'sha256', p_sha, 'sizeBytes', 1000, 'mediaType', 'application/pdf', 'name', 'deed.pdf'))::text
$f$;

CREATE FUNCTION pg_temp.pdep(p_actor text, p_title text, p_files text,
                             p_depositor text DEFAULT '{"kind":"NATURAL_PERSON","fullName":"DEMO Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER"}')
    RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT transaction_id::text FROM snigf.transaction_deposit_professional(%L, 'SALE', %L::jsonb, %L::jsonb, interval '30 days')$s$,
                                     p_title, p_depositor, p_files))
$f$;

-- Two registered titles at DEMO1: T1 held by a client, T2 held by a party linked to notary f1's account.
DO $f$
DECLARE
    geoms text[] := ARRAY['{"type":"Polygon","coordinates":[[[800000,400000],[800100,400000],[800100,400100],[800000,400100],[800000,400000]]]}',
                          '{"type":"Polygon","coordinates":[[[810000,400000],[810100,400000],[810100,400100],[810000,400100],[810000,400000]]]}'];
    r       snigf.geometry_check_result;
    holder  uuid;
    created uuid;
    canon   bytea;
    nonce   bytea;
BEGIN
    PERFORM set_config('snigf.actor_id', '00000000-0000-0000-0000-0000000000c1', true);
    FOR i IN 1..2 LOOP
        r := snigf.normalize_parcel_geometry(geoms[i], 32632);
        INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
        VALUES (('00000000-0000-0000-0000-00000000400' || i)::uuid, 'DEMO-PCL-DEMO1-2026-00000' || i || '-Z', '00000000-0000-0000-0000-0000000000a1', 1,
                '00000000-0000-0000-0000-0000000000b1');
        INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
        VALUES (('00000000-0000-0000-0000-00000000500' || i)::uuid, ('00000000-0000-0000-0000-00000000400' || i)::uuid, 1, r.geom, r.area_m2, 32632,
                repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
        holder := snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', CASE i WHEN 1 THEN 'DEMO Seller' ELSE 'DEMO Notary Person' END,
                                              'NATIONAL_ID', 'DEMO-ID-H' || i, NULL, NULL, NULL,
                                              CASE i WHEN 2 THEN '00000000-0000-0000-0000-0000000000f1'::uuid END);
        created := snigf.register_propose('00000000-0000-0000-0000-0000000000a1', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'A synthetic reason of enough length',
                                          ARRAY['DEMO-EVIDENCE'], NULL, NULL, NULL, 10000, ('00000000-0000-0000-0000-00000000500' || i)::uuid,
                                          jsonb_build_array(jsonb_build_object('party', holder, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
        canon := snigf.inscription_canonical(created);
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        PERFORM snigf.register_register(created, canon, jsonb_build_object(
            'credentialId', 'k1', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
        INSERT INTO ctx SELECT 't' || i, i2.new_title_id::text FROM snigf.inscription i2 WHERE i2.id = created;
        INSERT INTO ctx SELECT 'n' || i, t.number FROM snigf.land_title t JOIN snigf.inscription i2 ON i2.new_title_id = t.id WHERE i2.id = created;
    END LOOP;
    PERFORM set_config('snigf.actor_id', '', true);
END;
$f$;
INSERT INTO ctx SELECT 'parties', count(*)::text FROM register_party;

-- ---- 1. The lookup: a served office, nothing else ------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format('SELECT row(number, office_code, accepts_deposit)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))),
    row(pg_temp.c('n1'), 'DEMO1', true)::text,
    'a notary at the office finds the number, its office and that it accepts a deposit');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format('SELECT count(*)::text FROM snigf.professional_title_lookup(%L)', lower(pg_temp.c('n1')))), '1',
    'the number is matched whatever its case');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', $s$SELECT count(*)::text FROM snigf.professional_title_lookup('DEMO-TF-DEMO1-999999-Z')$s$), '0',
    'an unknown number: nothing');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f5', format('SELECT count(*)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))), '0',
    'a title at an office not served: the same nothing');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f2', format('SELECT count(*)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))), '0',
    'a surveyor looks nothing up');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', format('SELECT count(*)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))), '0',
    'nor does a conservator: it is the professional''s function');
SELECT is((SELECT array_agg(a ORDER BY n) FROM unnest((SELECT proargnames FROM pg_proc WHERE proname = 'professional_title_lookup')) WITH ORDINALITY x(a, n)),
    ARRAY['p_number', 'id', 'number', 'office_code', 'accepts_deposit'], 'the answer has no holder, charge, restriction, history or deposit');

-- ---- 2. A refused deposit records nothing and locks nothing ----------------------------------------------
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64), 'IDENTITY')) $$, pg_temp.c('t1')),
    'RG001', 'DEED_REQUIRED', 'no deed, no deposit');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, '[]') $$, pg_temp.c('t1')),
    'RG001', 'DOCUMENTS_INVALID', 'an empty deposit is refused');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64), 'RANDOM')) $$, pg_temp.c('t1')),
    'RG001', 'CATEGORY_INVALID', 'an unknown category is refused');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files('nothex')) $$, pg_temp.c('t1')),
    '23514', NULL, 'a malformed file record is refused by the table');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64)),
        '{"kind":"NOTARY","fullName":"DEMO Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER"}') $$, pg_temp.c('t1')),
    'RG001', 'DEPOSITOR_INVALID', 'the depositor is never a notary');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64)),
        '{"kind":"NATURAL_PERSON","fullName":"Real Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER"}') $$, pg_temp.c('t1')),
    'RG001', 'DEPOSITOR_INVALID', 'only synthetic depositors');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f5', %L, pg_temp.files(repeat('1', 64))) $$, pg_temp.c('t1')),
    'RG001', 'TITLE_NOT_FOUND', 'an office not served: as if the title did not exist');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f2', %L, pg_temp.files(repeat('1', 64))) $$, pg_temp.c('t1')),
    'RG001', 'TITLE_NOT_FOUND', 'a surveyor deposits nothing');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000c1', %L, pg_temp.files(repeat('1', 64))) $$, pg_temp.c('t1')),
    'RG001', 'TITLE_NOT_FOUND', 'a conservator records counter deposits, not professional ones');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64))) $$, pg_temp.c('t2')),
    'RG001', 'ACTOR_HAS_INTEREST', 'the holder rule: never on a title the professional holds through a party linked to their account');
SELECT is((SELECT count(*)::int FROM pending_transaction), 0, 'after every refusal: no transaction, so no lock');
SELECT is((SELECT count(*)::int FROM deposit_document), 0, 'no document');
SELECT is((SELECT count(*)::text FROM register_party), pg_temp.c('parties'), 'no party');

-- ---- 3. A valid deposit: the V21 rules, the account and the capacity --------------------------------------
INSERT INTO ctx SELECT 'a', pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', pg_temp.c('t1'), pg_temp.files(repeat('1', 64)));
SELECT results_eq(format($$ SELECT status, channel, received_by IS NULL, submitted_by_account::text, submitted_capacity FROM pending_transaction WHERE id = %L $$, pg_temp.c('a')),
    $$ VALUES ('HOLDING'::text, 'PROFESSIONAL_ACCOUNT'::text, true, '00000000-0000-0000-0000-0000000000f1'::text, 'NOTARY'::text) $$,
    'the first deposit holds the title, recorded with the account and capacity, and no receiving officer');
SELECT results_eq(format($$ SELECT p.kind, p.account_id::text, p.registration_number FROM pending_transaction t JOIN register_party p ON p.id = t.submitted_by_party_id WHERE t.id = %L $$, pg_temp.c('a')),
    $$ VALUES ('NOTARY'::text, '00000000-0000-0000-0000-0000000000f1'::text, 'DEMO-N-1'::text) $$,
    'the professional is also recorded as a notary party linked to their account');
SELECT results_eq(format($$ SELECT category, uploaded_by::text FROM deposit_document WHERE transaction_id = %L $$, pg_temp.c('a')),
    $$ VALUES ('DEED'::text, '00000000-0000-0000-0000-0000000000f1'::text) $$, 'the deed is recorded with it, uploaded by the professional');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('1', 64))) $$, pg_temp.c('t1')),
    'RG001', 'DUPLICATE_DEPOSIT', 'the same deed again on the same title, while the first is open: refused');
INSERT INTO ctx SELECT 'b', pg_temp.pdep('00000000-0000-0000-0000-0000000000f3', pg_temp.c('t1'),
    pg_temp.files(repeat('1', 64)),
    '{"kind":"NATURAL_PERSON","fullName":"DEMO Rival","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-RIVAL"}');
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('b')::uuid), 'IN_CONFLICT',
    'another notary arriving second is recorded in conflict, never refused (the same deed hash is theirs to deposit)');
SELECT is((SELECT count(*)::int FROM transaction_conflict WHERE arriving_id = pg_temp.c('b')::uuid AND holding_id = pg_temp.c('a')::uuid), 1,
    'a conflict is opened for conservators');
SELECT is((SELECT p.full_name FROM pending_transaction t JOIN register_party p ON p.id = t.submitted_by_party_id WHERE t.id = pg_temp.c('b')::uuid),
    'DEMO Notary Three', 'a display name without DEMO is marked synthetic in the party');
INSERT INTO ctx SELECT 'c', pg_temp.q('00000000-0000-0000-0000-0000000000c1', format(
    $s$SELECT snigf.transaction_deposit(%L, 'SALE', (SELECT id FROM snigf.register_party WHERE full_name = 'DEMO Seller'), NULL, ARRAY['DEMO-DEED-1'], interval '30 days')$s$,
    pg_temp.c('t1')));
SELECT results_eq(format($$ SELECT status, channel, received_by::text, submitted_by_account IS NULL FROM pending_transaction WHERE id = %L $$, pg_temp.c('c')),
    $$ VALUES ('IN_CONFLICT'::text, 'COUNTER'::text, '00000000-0000-0000-0000-0000000000c1'::text, true) $$,
    'counter deposits remain beside the professional channel, unchanged');
SELECT throws_ok($$ INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, received_by, evidence_references, status, review_due_at)
                    SELECT title_id, office_id, kind, 'PROFESSIONAL_ACCOUNT', depositor_party_id, received_by, evidence_references, 'IN_CONFLICT', review_due_at
                      FROM pending_transaction WHERE channel = 'COUNTER' $$,
    '23514', NULL, 'a professional deposit is never received by an officer, nor without its account');
SELECT throws_ok(format($$ UPDATE pending_transaction SET submitted_by_account = '00000000-0000-0000-0000-0000000000f3' WHERE id = %L $$, pg_temp.c('a')),
    '23001', NULL, 'who deposited never changes');

-- ---- 4. Their own deposits, and only theirs ------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT string_agg(status, '','') FROM snigf.professional_deposits(NULL, 50, 0)'), 'HOLDING',
    'a holding deposit reads HOLDING to its notary (D17, V27; the details are in 28)');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', 'SELECT string_agg(id::text || status, '','') FROM snigf.professional_deposits(NULL, 50, 0)'),
    pg_temp.c('b') || 'IN_CONFLICT', 'a deposit in conflict reads IN_CONFLICT to its notary, and only their own is listed');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT count(*)::text FROM snigf.professional_deposits(%L, 1, 0)', pg_temp.c('a'))), '0',
    'another notary''s deposit does not exist for them');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT count(*)::text FROM snigf.professional_deposit_documents(%L)', pg_temp.c('a'))), '0',
    'nor do its documents, though the deposits conflict');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format('SELECT count(*)::text FROM snigf.professional_deposit_documents(%L)', pg_temp.c('a'))), '1',
    'their own documents they read');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT (SELECT count(*) FROM snigf.pending_transaction) + (SELECT count(*) FROM snigf.deposit_document) + (SELECT count(*) FROM snigf.land_title)'),
    '0', 'the register''s tables stay closed to a professional: only the functions answer');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$SELECT count(*)::text FROM snigf.pending_transaction WHERE channel = 'PROFESSIONAL_ACCOUNT'$s$), '2',
    'conservators of the office see the professional deposits');

-- ---- 5. Documents and withdrawal on their own open deposit ------------------------------------------------
SELECT isnt(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT snigf.deposit_document_add(%L, 'IDENTITY', replace(gen_random_uuid()::text, '-', ''), repeat('2', 64), 10, 'application/pdf', 'id.pdf', NULL, NULL)$s$,
    pg_temp.c('a'))), NULL::text, 'a notary adds a document to their own open deposit');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT snigf.deposit_document_add(%L, 'OTHER', replace(gen_random_uuid()::text, '-', ''), repeat('3', 64), 10, 'application/pdf', 'x.pdf', NULL, NULL)$s$,
    pg_temp.c('b'))) $$,
    'RG001', 'TRANSACTION_NOT_FOUND', 'never to another''s');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT snigf.transaction_close(%L, 'WITHDRAWN', 'Withdrawing a deposit that is not mine')$s$, pg_temp.c('b'))) $$,
    'RG001', 'TRANSACTION_NOT_FOUND', 'nor withdraws another''s');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT snigf.transaction_close(%L, 'REFUSED', 'Refusing my own deposit instead')$s$, pg_temp.c('a'))) $$,
    'RG001', 'REGISTER_FORBIDDEN', 'a professional refuses nothing: that is the conservators'' decision');

-- Conservators decide: keeping the notary's deposit refuses the other one.
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format(
    $s$SELECT snigf.conflict_resolve((SELECT id FROM snigf.transaction_conflict WHERE arriving_id = %L), 'KEEP_HOLDING', 'The first deed is in order', ARRAY['DEMO-REVIEW-1'])$s$,
    pg_temp.c('b'))) $$,
    'a conservator resolves a conflict between professional deposits');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT status FROM snigf.professional_deposits(%L, 1, 0)', pg_temp.c('b'))), 'REFUSED',
    'the notary arriving second reads the decision on their deposit');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT snigf.transaction_close(%L, 'WITHDRAWN', 'The client cancelled the sale')$s$, pg_temp.c('a'))) $$,
    'a notary withdraws their own deposit, with a reason');
SELECT results_eq(format($$ SELECT status, closed_by::text FROM pending_transaction WHERE id = %L $$, pg_temp.c('a')),
    $$ VALUES ('WITHDRAWN'::text, '00000000-0000-0000-0000-0000000000f1'::text) $$, 'withdrawn, by them');

-- ---- 6. Suspension takes it all away at once ----------------------------------------------------------------
UPDATE professional_profile SET status = 'SUSPENDED' WHERE user_id = '00000000-0000-0000-0000-0000000000f1';
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format('SELECT count(*)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))), '0',
    'suspended: no lookup');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT count(*)::text FROM snigf.professional_deposits(NULL, 50, 0)'), '0',
    'suspended: no deposits to read');
SELECT throws_ok(format($$ SELECT pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', %L, pg_temp.files(repeat('4', 64))) $$, pg_temp.c('t1')),
    'RG001', 'TITLE_NOT_FOUND', 'suspended: no deposit');

SELECT * FROM finish();
ROLLBACK;
