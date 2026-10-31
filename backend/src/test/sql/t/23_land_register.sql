-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The land register (Slice 2, V21, ADR-040): the application writes only through the functions;
-- proposed is not registered; numbers are gap-free; registered rows never change; nothing is
-- registered unsigned, or signed over other bytes, or with another officer's key; one HOLDING per
-- title, a second deposit recorded IN_CONFLICT; no TRANSFER through an open conflict, a blocking
-- restriction or a stale version; dual control and the holder rule; the state is derived, cancelled
-- and corrected entries are computed from the history; only synthetic data can exist.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(87);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- c1, c2: conservators at DEMO1. c3: conservator elsewhere (DEMO2). r1: an intake officer at DEMO1.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'c1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'c2@demo.invalid', 'Conservator Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c3', 'c3@demo.invalid', 'Conservator Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e1', 'r1@demo.invalid', 'Intake', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000c1', 'One'),
    ('h2', '00000000-0000-0000-0000-0000000000c2', 'Two');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'),
    ('k2', 'h2', '\x0202', false, false, 'Key two');

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

-- Evidence as the application stores it: the signature itself is the application's to verify.
CREATE FUNCTION pg_temp.sig(p_inscription uuid, p_credential text, p_key bytea, p_canonical bytea DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql AS $f$
DECLARE
    canon bytea := coalesce(p_canonical, snigf.inscription_canonical(p_inscription));
    nonce bytea := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
    client text := format('{"type":"webauthn.get","challenge":"%s"}',
                          rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '='));
BEGIN
    RETURN jsonb_build_object('credentialId', p_credential, 'publicKey', encode(p_key, 'base64'), 'nonce', encode(nonce, 'base64'),
                              'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
                              'clientData', encode(convert_to(client, 'UTF8'), 'base64'),
                              'officerSignature', encode('\x0a0b'::bytea, 'base64'),
                              'serverSignature', 'snigf:v1:AAAA');
END;
$f$;

-- Registers as conservator one (a kind under single control).
CREATE FUNCTION pg_temp.register(p_inscription text) RETURNS text
LANGUAGE plpgsql AS $f$
BEGIN
    RETURN pg_temp.q('00000000-0000-0000-0000-0000000000c1', format(
        $s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$, p_inscription,
        snigf.inscription_canonical(p_inscription::uuid), pg_temp.sig(p_inscription::uuid, 'k1', '\x0101')));
END;
$f$;

-- A proposal, by conservator p_actor.
CREATE FUNCTION pg_temp.propose(p_actor text, p_title text, p_kind text, p_expected int, p_transaction text, p_target text,
                                p_area numeric, p_parcel text, p_holders text, p_charge text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.register_propose('00000000-0000-0000-0000-0000000000a1', %L, %L, %s, 'SYNTHETIC_DEMO',
        'DEMO-AUTH-1', 'A synthetic reason of enough length', ARRAY['DEMO-EVIDENCE-1'], NULL, %L, %L, %L, %L, %L, %L)$s$,
        p_title, p_kind, p_expected, p_transaction, p_target, p_area, p_parcel, p_holders, p_charge))
$f$;

CREATE FUNCTION pg_temp.deposit(p_actor text, p_title text, p_depositor text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.transaction_deposit(%L, 'SALE', %L, NULL, ARRAY['DEMO-DEED-1'], interval '30 days')$s$,
                                     p_title, p_depositor))
$f$;

-- Synthetic parcel, at DEMO1.
DO $f$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[800000,400000],[800100,400000],[800100,400100],[800000,400100],[800000,400000]]]}', 32632);
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-000000005001', '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END;
$f$;

CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;

-- Parties.
INSERT INTO ctx SELECT 'party_a', pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Holder A', 'NATIONAL_ID', 'DEMO-ID-A', NULL, NULL, NULL, NULL)$s$);
INSERT INTO ctx SELECT 'party_b', pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Holder B', 'NATIONAL_ID', 'DEMO-ID-B', NULL, NULL, NULL, NULL)$s$);
-- The second conservator is, in the register, also a person: a party with their account.
INSERT INTO ctx SELECT 'party_c2', pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Conservator Person', 'NATIONAL_ID', 'DEMO-ID-C', NULL, NULL, NULL, '00000000-0000-0000-0000-0000000000c2')$s$);
INSERT INTO ctx SELECT 'holders_a', format('[{"party":"%s","interest":"OWNERSHIP","shareNum":1,"shareDen":1}]', pg_temp.c('party_a'));
INSERT INTO ctx SELECT 'holders_b', format('[{"party":"%s","interest":"OWNERSHIP","shareNum":1,"shareDen":1}]', pg_temp.c('party_b'));

-- ---- 1. Only synthetic data ---------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT snigf.register_party_create(''00000000-0000-0000-0000-0000000000a1'', ''NATURAL_PERSON'', ''Real Person'', ''NATIONAL_ID'', ''DEMO-ID-Z'', NULL, NULL, NULL, NULL)') $$,
    '23514', NULL, 'a party''s name must say DEMO');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT snigf.register_party_create(''00000000-0000-0000-0000-0000000000a1'', ''NATURAL_PERSON'', ''DEMO Person'', ''NATIONAL_ID'', ''CM12345'', NULL, NULL, NULL, NULL)') $$,
    '23514', NULL, 'a party''s identity number must say DEMO');
SELECT throws_ok($$ INSERT INTO register_migration_batch (origin, provenance) VALUES ('PAPER_REGISTER', 'scan') $$,
    '23514', NULL, 'no migration batch other than synthetic is possible');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.register_migration_batch (origin, provenance) VALUES (''SYNTHETIC_DEMO'', ''x'')') $$,
    '42501', NULL, 'the application cannot write a migration batch');

-- ---- 2. Proposed is not registered --------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000e1', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001', pg_temp.c('holders_a'), NULL) $$,
    'RG001', 'REGISTER_FORBIDDEN', 'an officer without register.propose cannot propose');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c3', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001', pg_temp.c('holders_a'), NULL) $$,
    'RG001', 'REGISTER_FORBIDDEN', 'a conservator of another conservation cannot propose here');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c1', NULL, 'CREATION', 0, NULL, NULL, 10000, NULL, pg_temp.c('holders_a'), NULL) $$,
    'RG001', 'PARCEL_REQUIRED', 'a creation rests on a parcel version');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c1', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001',
        format('[{"party":"%s","interest":"OWNERSHIP","shareNum":1,"shareDen":2}]', pg_temp.c('party_a')), NULL) $$,
    'RG001', 'SHARES_MUST_TOTAL_ONE', 'shares total exactly one');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c2', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001',
        format('[{"party":"%s","interest":"OWNERSHIP","shareNum":1,"shareDen":1}]', pg_temp.c('party_c2')), NULL) $$,
    'RG001', 'ACTOR_HAS_INTEREST', 'a conservator cannot propose a title they would hold');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT snigf.register_propose(''00000000-0000-0000-0000-0000000000a1'', NULL, ''AREA_REDUCTION'', 0, ''SYNTHETIC_DEMO'', ''DEMO-A'', ''Synthetic reason of length'', ARRAY[''E''], NULL, NULL, NULL, NULL, NULL, NULL, NULL)') $$,
    'RG001', 'KIND_NOT_AVAILABLE', 'area reduction waits for slice 4');
SELECT is(pg_temp.propose('00000000-0000-0000-0000-0000000000c1', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001',
          format('[{"party":"%s","interest":"CO_OWNERSHIP","shareNum":1,"shareDen":3},{"party":"%s","interest":"CO_OWNERSHIP","shareNum":2,"shareDen":3}]',
                 pg_temp.c('party_a'), pg_temp.c('party_b')), NULL) IS NOT NULL, true, 'exact thirds total one');

INSERT INTO ctx SELECT 'creation', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', NULL, 'CREATION', 0, NULL, NULL, 10000, '00000000-0000-0000-0000-000000005001', pg_temp.c('holders_a'), NULL);
SELECT is((SELECT count(*)::int FROM land_title), 0, 'a proposal creates no title');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000e1', 'SELECT count(*) FROM snigf.inscription'), '0', 'row-level security: no register.read, no rows');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', 'SELECT count(*) FROM snigf.inscription'), '0', 'row-level security: another conservation, no rows');
SELECT cmp_ok(pg_temp.q('00000000-0000-0000-0000-0000000000c2', 'SELECT count(*) FROM snigf.inscription')::int, '>=', 1, 'a conservator of the office reads it');

-- ---- 3. No direct writes ------------------------------------------------------------------------
SELECT throws_ok($$ SELECT run_as('snigf_app', format('UPDATE snigf.inscription SET status = ''REGISTERED'' WHERE id = %L', pg_temp.c('creation'))) $$,
    '42501', NULL, 'the application cannot update an inscription');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.land_title (id, number, office_id, origin, creation_inscription_id) VALUES (gen_random_uuid(), ''DEMO-TF-DEMO1-000001-A'', ''00000000-0000-0000-0000-0000000000a1'', ''SYNTHETIC_DEMO'', gen_random_uuid())') $$,
    '42501', NULL, 'the application cannot write a title');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.title_version (title_id, version_no, inscription_id, status, holders, charges, restrictions) VALUES (gen_random_uuid(), 1, gen_random_uuid(), ''ACTIVE'', ''[]'', ''[]'', ''[]'')') $$,
    '42501', NULL, 'the application cannot write a derived version');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'SELECT snigf.register_apply(gen_random_uuid(), gen_random_uuid())') $$,
    '42501', NULL, 'the application cannot call the internal apply');

-- ---- 4. Signatures ------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('creation'), '\x00'::bytea, pg_temp.sig(pg_temp.c('creation')::uuid, 'k1', '\x0101', '\x00'))) $$,
    'RG001', 'CANONICAL_MISMATCH', 'a signature over other bytes is refused');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('creation'), snigf.inscription_canonical(pg_temp.c('creation')::uuid),
        pg_temp.sig(pg_temp.c('creation')::uuid, 'k1', '\x0101') || '{"nonce":"AAAA"}')) $$,
    'RG001', 'SIGNATURE_CHALLENGE_MISMATCH', 'a challenge that does not answer the record and nonce is refused');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('creation'), snigf.inscription_canonical(pg_temp.c('creation')::uuid), pg_temp.sig(pg_temp.c('creation')::uuid, 'k2', '\x0202'))) $$,
    'RG001', 'SIGNATURE_NOT_OWN_KEY', 'another officer''s key is refused');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000e1', format($s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('creation'), snigf.inscription_canonical(pg_temp.c('creation')::uuid), pg_temp.sig(pg_temp.c('creation')::uuid, 'k1', '\x0101'))) $$,
    'RG001', 'INSCRIPTION_NOT_FOUND', 'without register.read the inscription does not exist');
SELECT throws_ok($$ UPDATE inscription SET status = 'REGISTERED', number = 1, hash = sha256('x'), registered_by = '00000000-0000-0000-0000-0000000000c1',
                    registered_at = now(), title_id = new_title_id WHERE id = pg_temp.c('creation')::uuid $$,
    '23514', 'nothing is registered unsigned', 'not even the owner registers without a signature');

SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('creation')) $$, 'a signed creation is registered');
INSERT INTO ctx SELECT 'title', title_id::text FROM inscription WHERE id = pg_temp.c('creation')::uuid;
SELECT is((SELECT number FROM land_title WHERE id = pg_temp.c('title')::uuid) ~ '^DEMO-TF-DEMO1-[0-9]{6}-[A-Z]$', true, 'a DEMO title number');
SELECT is((SELECT number FROM inscription WHERE id = pg_temp.c('creation')::uuid), 1, 'the first inscription is number 1');
SELECT is((SELECT version_no FROM title_version WHERE inscription_id = pg_temp.c('creation')::uuid), 1, 'the first derived version');
SELECT is((SELECT holders->0->>'party' FROM title_version WHERE inscription_id = pg_temp.c('creation')::uuid), pg_temp.c('party_a'), 'the holder is derived');

-- ---- 5. Registered rows never change ------------------------------------------------------------
SELECT throws_ok($$ UPDATE inscription SET reason = 'Tampered by the owner of the table' WHERE id = pg_temp.c('creation')::uuid $$,
    '23001', NULL, 'not even the owner rewrites a registered inscription');
SELECT throws_ok($$ DELETE FROM inscription WHERE id = pg_temp.c('creation')::uuid $$,
    '23001', NULL, 'not even the owner deletes an inscription');
SELECT throws_ok($$ UPDATE title_version SET holders = '[]' WHERE inscription_id = pg_temp.c('creation')::uuid $$,
    '23001', NULL, 'a derived version never changes');
SELECT throws_ok($$ UPDATE land_title SET number = 'x' WHERE id = pg_temp.c('title')::uuid $$,
    '23001', NULL, 'a title never changes');
SELECT throws_ok($$ DELETE FROM inscription_signature WHERE inscription_id = pg_temp.c('creation')::uuid $$,
    '23001', NULL, 'a signature is never deleted');
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('creation')) $$,
    'RG001', 'INSCRIPTION_CLOSED', 'a registered inscription is not registered twice');

-- ---- 6. Deposits, the lock and conflicts --------------------------------------------------------
INSERT INTO ctx SELECT 't1', pg_temp.deposit('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), pg_temp.c('party_b'));
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('t1')::uuid), 'HOLDING', 'the first deposit holds the title');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000e1', format($s$SELECT snigf.transaction_deposit(%L, 'SALE', %L, NULL, ARRAY['D'], interval '1 day')$s$, pg_temp.c('title'), pg_temp.c('party_b'))) $$,
    'RG001', 'TITLE_NOT_FOUND', 'without register.read the title does not exist');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.transaction_deposit(%L, 'SALE', %L, NULL, ARRAY['D'], interval '0')$s$, pg_temp.c('title'), pg_temp.c('party_b'))) $$,
    'RG001', 'REVIEW_PERIOD_INVALID', 'a lock always has a review date');
INSERT INTO ctx SELECT 'cancelled_transfer', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'TRANSFER', 1, pg_temp.c('t1'), NULL, NULL, NULL, pg_temp.c('holders_b'), NULL);

INSERT INTO ctx SELECT 't2', pg_temp.deposit('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), pg_temp.c('party_a'));
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('t2')::uuid), 'IN_CONFLICT', 'a second deposit is recorded in conflict, never refused');
SELECT is((SELECT count(*)::int FROM transaction_conflict WHERE title_id = pg_temp.c('title')::uuid AND status = 'OPEN'), 1, 'a conflict opens');
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('cancelled_transfer')) $$,
    'RG001', 'OPEN_CONFLICT', 'no transfer through an open conflict');
SELECT throws_ok($$ INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, received_by, evidence_references, status, review_due_at)
                    VALUES (pg_temp.c('title')::uuid, '00000000-0000-0000-0000-0000000000a1', 'SALE', 'COUNTER', pg_temp.c('party_a')::uuid, '00000000-0000-0000-0000-0000000000c1', ARRAY['x'], 'HOLDING', now() + interval '1 day') $$,
    '23505', NULL, 'one holding transaction per title');
INSERT INTO ctx SELECT 'conflict', id::text FROM transaction_conflict WHERE title_id = pg_temp.c('title')::uuid;
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.conflict_resolve(%L, 'KEEP_HOLDING', 'The first deposit stands as filed', ARRAY['DEMO-DECISION-1'])$s$, pg_temp.c('conflict'))) $$,
    'RG001', 'RECEIVER_CANNOT_DECIDE', 'who received a deposit does not decide the conflict');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.conflict_resolve(%L, 'KEEP_HOLDING', 'short', ARRAY['DEMO-DECISION-1'])$s$, pg_temp.c('conflict'))) $$,
    'RG001', 'REASON_REQUIRED', 'a decision has a reason');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.conflict_resolve(%L, 'KEEP_HOLDING', 'The first deposit stands as filed', ARRAY['DEMO-DECISION-1'])$s$, pg_temp.c('conflict'))) $$,
    'a second conservator resolves the conflict');
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('t2')::uuid), 'REFUSED', 'the arriving deposit is refused by the decision');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.conflict_resolve(%L, 'KEEP_HOLDING', 'The first deposit stands as filed', ARRAY['DEMO-DECISION-1'])$s$, pg_temp.c('conflict'))) $$,
    'RG001', 'CONFLICT_CLOSED', 'a conflict is resolved once');

-- ---- 7. Restrictions and stale versions -----------------------------------------------------------
INSERT INTO ctx SELECT 'opposition', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'RESTRICTION', 1, NULL, NULL, NULL, NULL, NULL, '{"kind":"OPPOSITION","blocksTransfer":true,"note":"DEMO opposition"}');
SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('opposition')) $$, 'a restriction is registered');
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('cancelled_transfer')) $$,
    'RG001', 'TITLE_CHANGED', 'a proposal prepared against an earlier version is stale');
INSERT INTO ctx SELECT 'blocked', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'TRANSFER', 2, pg_temp.c('t1'), NULL, NULL, NULL, pg_temp.c('holders_b'), NULL);
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('blocked')) $$,
    'RG001', 'BLOCKING_RESTRICTION', 'no transfer through a blocking restriction');
INSERT INTO ctx SELECT 'lift', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'RESTRICTION_RELEASE', 2, NULL, pg_temp.c('opposition'), NULL, NULL, NULL, NULL);
SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('lift')) $$, 'the restriction is lifted');

-- ---- 8. The transfer -------------------------------------------------------------------------------
INSERT INTO ctx SELECT 'transfer', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'TRANSFER', 3, pg_temp.c('t1'), NULL, NULL, NULL, pg_temp.c('holders_b'), NULL);
SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('transfer')) $$, 'the transfer is registered through the lock');
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('t1')::uuid), 'REGISTERED', 'registration releases the lock');
SELECT is((SELECT holders->0->>'party' FROM title_version WHERE title_id = pg_temp.c('title')::uuid AND version_no = 4), pg_temp.c('party_b'), 'the holder changes');
SELECT is((SELECT holders->0->>'party' FROM title_version WHERE title_id = pg_temp.c('title')::uuid AND version_no = 3), pg_temp.c('party_a'), 'the previous holder stays in the history');
SELECT is((SELECT array_agg(number ORDER BY number) FROM inscription WHERE title_id = pg_temp.c('title')::uuid AND status = 'REGISTERED'), ARRAY[1, 2, 3, 4],
    'numbers are gap-free, whatever was proposed and never registered');

-- ---- 9. Dual control -------------------------------------------------------------------------------
INSERT INTO ctx SELECT 'correction', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'CORRECTION', 4, NULL, pg_temp.c('transfer'), NULL, NULL, pg_temp.c('holders_a'), NULL);
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('correction')) $$,
    'RG001', 'DUAL_CONTROL_REQUIRED', 'one conservator alone cannot register a correction');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_request_confirmation(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('correction'), snigf.inscription_canonical(pg_temp.c('correction')::uuid), pg_temp.sig(pg_temp.c('correction')::uuid, 'k1', '\x0101'))) $$,
    'the proposer signs and asks for confirmation');
SELECT throws_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_confirm(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('correction'), snigf.inscription_canonical(pg_temp.c('correction')::uuid), pg_temp.sig(pg_temp.c('correction')::uuid, 'k1', '\x0101'))) $$,
    'RG001', 'SELF_CONFIRMATION', 'nobody confirms their own proposal');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.register_confirm(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('correction'), snigf.inscription_canonical(pg_temp.c('correction')::uuid), pg_temp.sig(pg_temp.c('correction')::uuid, 'k2', '\x0202'))) $$,
    'a second conservator confirms and it is registered');
SELECT is((SELECT count(*)::int FROM inscription_signature WHERE inscription_id = pg_temp.c('correction')::uuid), 2, 'both signatures are kept');
SELECT is((SELECT holders->0->>'party' FROM title_version WHERE title_id = pg_temp.c('title')::uuid AND version_no = 5), pg_temp.c('party_a'),
    'the state follows the corrected content');
SELECT is((SELECT reason FROM inscription WHERE id = pg_temp.c('transfer')::uuid), 'A synthetic reason of enough length', 'the corrected original is kept');
SELECT is((SELECT bool_and(matches) FROM (SELECT (title_versions_check(pg_temp.c('title')::uuid)).matches FROM (SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-0000000000c1', true)) s) x), true,
    'every version recomputes to what was written');

-- ---- 10. Cancellation: the original kept, the state computed as if it had no effect --------------------
INSERT INTO ctx SELECT 'mortgage', pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'CHARGE', 5, NULL, NULL, NULL, NULL, NULL,
    format('{"kind":"MORTGAGE","beneficiary":"%s","amount":1000000,"note":"DEMO mortgage"}', pg_temp.c('party_b')));
SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('mortgage')) $$, 'a charge is registered');
SELECT is((SELECT jsonb_array_length(charges) FROM title_version WHERE title_id = pg_temp.c('title')::uuid AND version_no = 6), 1, 'the charge is listed');
INSERT INTO ctx SELECT 'void', pg_temp.propose('00000000-0000-0000-0000-0000000000c2', pg_temp.c('title'), 'CANCELLATION', 6, NULL, pg_temp.c('mortgage'), NULL, NULL, NULL, NULL);
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.register_request_confirmation(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('void'), snigf.inscription_canonical(pg_temp.c('void')::uuid), pg_temp.sig(pg_temp.c('void')::uuid, 'k2', '\x0202'))) $$, 'a cancellation is signed by its proposer');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_confirm(%L, %L::bytea, %L::jsonb)$s$,
        pg_temp.c('void'), snigf.inscription_canonical(pg_temp.c('void')::uuid), pg_temp.sig(pg_temp.c('void')::uuid, 'k1', '\x0101'))) $$, 'and confirmed by another conservator');
SELECT is((SELECT jsonb_array_length(charges) FROM title_version WHERE title_id = pg_temp.c('title')::uuid AND version_no = 7), 0, 'the cancelled charge has no effect');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('mortgage')::uuid), 'REGISTERED', 'the original is kept');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c1', pg_temp.c('title'), 'CANCELLATION', 7, NULL, pg_temp.c('creation'), NULL, NULL, NULL, NULL) $$,
    'RG001', 'TARGET_NOT_CANCELLABLE', 'a creation cannot be cancelled away');

-- ---- 11. A lock is reviewed, never expired ------------------------------------------------------------
INSERT INTO ctx SELECT 't3', pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.transaction_deposit(%L, 'GIFT', %L, NULL, ARRAY['DEMO-DEED-3'], interval '1 millisecond')$s$, pg_temp.c('title'), pg_temp.c('party_a')));
SELECT pg_sleep(0.01);
SELECT is((SELECT count(*)::int FROM register_record_overdue_locks() WHERE transaction_id = pg_temp.c('t3')::uuid), 1, 'an overdue lock is recorded');

-- ---- 12. A deposit's documents (V22): kept, never changed, and they register nothing ---------------------
CREATE FUNCTION pg_temp.document(p_actor text, p_transaction text, p_key text, p_replaces text DEFAULT NULL, p_reason text DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.deposit_document_add(%L, 'DEED', %L, %L, 100, 'application/pdf', 'deed.pdf', %L, %L)$s$,
                                     p_transaction, p_key, repeat('a', 64), p_replaces, p_reason))
$f$;
INSERT INTO ctx SELECT 'd1', pg_temp.document('00000000-0000-0000-0000-0000000000c1', pg_temp.c('t3'), repeat('1', 32));
SELECT is((SELECT uploaded_by::text FROM deposit_document WHERE id = pg_temp.c('d1')::uuid), '00000000-0000-0000-0000-0000000000c1', 'the uploader is the actor');
SELECT is((SELECT count(*)::int FROM inscription WHERE title_id = pg_temp.c('title')::uuid AND status = 'REGISTERED'), 7, 'storing a document registers nothing');
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('t3')::uuid), 'HOLDING', 'storing a document moves no status');
SELECT throws_ok($$ SELECT pg_temp.document('00000000-0000-0000-0000-0000000000e1', pg_temp.c('t3'), repeat('2', 32)) $$,
    'RG001', 'TRANSACTION_NOT_FOUND', 'without register.read the deposit does not exist');
SELECT throws_ok($$ SELECT pg_temp.document('00000000-0000-0000-0000-0000000000c3', pg_temp.c('t3'), repeat('2', 32)) $$,
    'RG001', 'TRANSACTION_NOT_FOUND', 'a conservator elsewhere cannot add one');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000e1', 'SELECT count(*) FROM snigf.deposit_document'), '0', 'row-level security: no register.read, no rows');
SELECT throws_ok($$ SELECT run_as('snigf_app', format('INSERT INTO snigf.deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by) VALUES (%L, ''00000000-0000-0000-0000-0000000000a1'', ''DEED'', repeat(''3'', 32), repeat(''a'', 64), 1, ''application/pdf'', ''a.pdf'', ''00000000-0000-0000-0000-0000000000c1'')', pg_temp.c('t3'))) $$,
    '42501', NULL, 'the application cannot write a document directly');
SELECT throws_ok($$ UPDATE deposit_document SET sha256 = repeat('b', 64) WHERE id = pg_temp.c('d1')::uuid $$,
    '23001', NULL, 'not even the owner changes a document');
SELECT throws_ok($$ DELETE FROM deposit_document WHERE id = pg_temp.c('d1')::uuid $$,
    '23001', NULL, 'not even the owner deletes a document');
SELECT throws_ok($$ SELECT pg_temp.document('00000000-0000-0000-0000-0000000000c1', pg_temp.c('t3'), repeat('2', 32), pg_temp.c('d1'), 'short') $$,
    'RG001', 'REASON_REQUIRED', 'a replacement has a reason');
INSERT INTO ctx SELECT 'd2', pg_temp.document('00000000-0000-0000-0000-0000000000c1', pg_temp.c('t3'), repeat('2', 32), pg_temp.c('d1'), 'The first scan was the wrong page');
SELECT is((SELECT count(*)::int FROM deposit_document WHERE id IN (pg_temp.c('d1')::uuid, pg_temp.c('d2')::uuid)), 2, 'the original stays beside its replacement');
SELECT throws_ok($$ SELECT pg_temp.document('00000000-0000-0000-0000-0000000000c1', pg_temp.c('t3'), repeat('4', 32), pg_temp.c('d1'), 'Replacing the same file twice') $$,
    'RG001', 'DOCUMENT_ALREADY_REPLACED', 'an original is replaced once');
SELECT results_eq($$ SELECT * FROM document_storage_keys_known(ARRAY[repeat('1', 32)]) $$, $$ VALUES (repeat('1', 32)) $$, 'the orphan cleanup knows a deposit''s files');
SELECT is((SELECT (audit_subjects('DEPOSIT_DOCUMENT', pg_temp.c('d1')::uuid)->0->>'id')), pg_temp.c('title'), 'a document''s events belong to its title');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format($s$SELECT snigf.transaction_close(%L, 'REFUSED', 'Reviewed: the deposit was never completed')$s$, pg_temp.c('t3')));
                   SELECT 1 / (SELECT count(*)::int FROM deposit_document WHERE transaction_id = pg_temp.c('t3')::uuid) $$, 'a closed deposit keeps its documents');

SELECT * FROM finish();
ROLLBACK;
