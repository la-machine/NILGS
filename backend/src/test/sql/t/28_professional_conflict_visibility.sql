-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- A professional sees their own deposit's conflict (Slice 3, V27, ADR-041 D17): the real status, the conflict
-- under review or resolved, on the holding and on the arriving side, the review date while open, no action
-- required; never the competing deposit, its parties, documents or number, nor the conservators' resolution
-- reason; a plain refusal keeps its reason; another professional still sees nothing; a withdrawal during a
-- conflict still shows it under review (what the application audits as withdrawnDuringConflict).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(29);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- c1, c2: conservators at DEMO1. f1, f3: notaries at DEMO1.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'c1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'c2@demo.invalid', 'Conservator Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'n1@demo.invalid', 'DEMO Notary One', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f3', 'n3@demo.invalid', 'DEMO Notary Three', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES ('h1', '00000000-0000-0000-0000-0000000000c1', 'One');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one');

-- Active notaries, as an approved activation leaves them.
DO $$
DECLARE
    who record;
    v   uuid;
    r   uuid;
BEGIN
    FOR who IN SELECT * FROM (VALUES ('00000000-0000-0000-0000-0000000000f1'::uuid, 'DEMO-N-1'),
                                     ('00000000-0000-0000-0000-0000000000f3'::uuid, 'DEMO-N-3')) t(id, reg) LOOP
        INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction, status)
        VALUES (who.id, 'NOTARY', 'DEMO Chamber', who.reg, 'DEMO Yaounde', 'ACTIVE');
        INSERT INTO snigf.professional_verification (user_id, verified_by, body, reference, checked_on, note)
        VALUES (who.id, '00000000-0000-0000-0000-0000000000d1', 'DEMO Chamber', 'DEMO-REF', current_date, 'Checked with the DEMO chamber')
        RETURNING id INTO v;
        INSERT INTO snigf.professional_request (user_id, kind, offices_add, reason, verification_id, requested_by, status, decided_by, decided_at)
        VALUES (who.id, 'ACTIVATION', ARRAY['00000000-0000-0000-0000-0000000000a1'::uuid], 'Checked with the DEMO chamber', v,
                '00000000-0000-0000-0000-0000000000d1', 'APPROVED', '00000000-0000-0000-0000-0000000000d2', now())
        RETURNING id INTO r;
        INSERT INTO snigf.professional_office (user_id, office_id, added_by_request) VALUES (who.id, '00000000-0000-0000-0000-0000000000a1', r);
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

-- A deposit by a notary on T1, with a deed of this hash; its id.
CREATE FUNCTION pg_temp.pdep(p_actor text, p_sha text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT transaction_id::text FROM snigf.transaction_deposit_professional(%L, 'SALE', %L::jsonb, %L::jsonb, interval '30 days')$s$,
        pg_temp.c('t1'),
        '{"kind":"NATURAL_PERSON","fullName":"DEMO Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER"}',
        jsonb_build_array(jsonb_build_object('category', 'DEED', 'storageKey', replace(gen_random_uuid()::text, '-', ''),
                                             'sha256', p_sha, 'sizeBytes', 1000, 'mediaType', 'application/pdf', 'name', 'deed.pdf'))::text))
$f$;

-- What a notary reads of one of their deposits: status, conflict, whether a review date is shown, action, closing reason.
CREATE FUNCTION pg_temp.seen(p_actor text, p_deposit text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT concat_ws('|', status, conflict, CASE WHEN review_due_at IS NULL THEN 'no date' ELSE 'date' END, action_required, coalesce(closed_reason, '-'))
                                          FROM snigf.professional_deposits(%L, 1, 0)$s$, p_deposit))
$f$;

-- Resolves the open conflict naming this arriving deposit, as conservator c2.
CREATE FUNCTION pg_temp.resolve(p_arriving text, p_outcome text, p_reason text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format(
        $s$SELECT snigf.conflict_resolve((SELECT id FROM snigf.transaction_conflict WHERE arriving_id = %L), %L, %L, ARRAY['DEMO-REVIEW'])::text$s$,
        p_arriving, p_outcome, p_reason))
$f$;

-- One registered title at DEMO1, held by a client.
DO $f$
DECLARE
    r       snigf.geometry_check_result;
    holder  uuid;
    created uuid;
    canon   bytea;
    nonce   bytea;
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
    INSERT INTO ctx SELECT 't1', i.new_title_id::text FROM snigf.inscription i WHERE i.id = created;
    INSERT INTO ctx SELECT 'n1', t.number FROM snigf.land_title t JOIN snigf.inscription i ON i.new_title_id = t.id WHERE i.id = created;
    PERFORM set_config('snigf.actor_id', '', true);
END;
$f$;

-- ---- 1. The answer's columns: an allow-list ------------------------------------------------------------------
SELECT is((SELECT array_agg(a ORDER BY n) FROM unnest((SELECT proargnames FROM pg_proc WHERE proname = 'professional_deposits')) WITH ORDINALITY x(a, n)),
    ARRAY['p_id', 'p_limit', 'p_offset', 'id', 'title_id', 'title_number', 'office_code', 'kind', 'status', 'conflict', 'review_due_at',
          'action_required', 'depositor_name', 'capacity', 'deposited_at', 'closed_at', 'closed_reason', 'documents'],
    'the answer names nothing of another deposit: no conflict id, no competing deposit, party, account or channel');
SELECT is((SELECT prosecdef FROM pg_proc WHERE proname = 'professional_deposits'), true, 'it reads for the professional, as its owner');

-- ---- 2. Alone on the title: holding, no conflict ---------------------------------------------------------------
INSERT INTO ctx SELECT 'a', pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', repeat('1', 64));
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('a')), 'HOLDING|NONE|date|NONE|-',
    'the first deposit: holding, no conflict, a review date, nothing required of the notary');

-- ---- 3. A second notary arrives: both sides see the review, neither sees the other ---------------------------------
INSERT INTO ctx SELECT 'b', pg_temp.pdep('00000000-0000-0000-0000-0000000000f3', repeat('2', 64));
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('b')), 'IN_CONFLICT|UNDER_REVIEW|date|NONE|-',
    'the arriving notary sees their deposit in conflict, under review');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('a')), 'HOLDING|UNDER_REVIEW|date|NONE|-',
    'the holding notary sees it still holds, with a conflict under review');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', 'SELECT string_agg(id::text, '','') FROM snigf.professional_deposits(NULL, 50, 0)'), pg_temp.c('b'),
    'each lists their own deposit only');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT count(*)::text FROM snigf.professional_deposits(%L, 1, 0)', pg_temp.c('a'))), '0',
    'the competing deposit does not exist for them');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT count(*)::text FROM snigf.professional_deposit_documents(%L)', pg_temp.c('a'))), '0',
    'nor do its documents');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', 'SELECT (SELECT count(*) FROM snigf.transaction_conflict) + (SELECT count(*) FROM snigf.pending_transaction)'), '0',
    'the conflicts and deposits tables stay closed to a professional');

-- ---- 4. A counter deposit too: the holding side stays under review until every conflict is decided ---------------
INSERT INTO ctx SELECT 'c', pg_temp.q('00000000-0000-0000-0000-0000000000c1', format(
    $s$SELECT snigf.transaction_deposit(%L, 'SALE', (SELECT id FROM snigf.register_party WHERE full_name = 'DEMO Seller'), NULL, ARRAY['DEMO-DEED-C'], interval '30 days')$s$,
    pg_temp.c('t1')));
SELECT is((SELECT count(*)::int FROM transaction_conflict WHERE holding_id = pg_temp.c('a')::uuid AND status = 'OPEN'), 2, 'two conflicts now name the holding deposit');
SELECT lives_ok($$ SELECT pg_temp.resolve(pg_temp.c('b'), 'KEEP_HOLDING', 'DEMO Buyer of the first deed keeps the title, the rival deed came later') $$,
    'a conservator keeps the holding deposit against the notary arriving second');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('b')), 'REFUSED|RESOLVED|no date|NONE|-',
    'the arriving notary reads refused and resolved, without the conservators'' reason, which may name the other party');
SELECT is((SELECT closed_reason FROM pending_transaction WHERE id = pg_temp.c('b')::uuid), 'DEMO Buyer of the first deed keeps the title, the rival deed came later',
    'the reason is still recorded for conservators');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('a')), 'HOLDING|UNDER_REVIEW|date|NONE|-',
    'the holding notary: still under review, the counter deposit''s conflict is open');
SELECT lives_ok($$ SELECT pg_temp.resolve(pg_temp.c('c'), 'KEEP_HOLDING', 'The counter deposit came after the notary''s deed') $$,
    'the second conflict is decided');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('a')), 'HOLDING|RESOLVED|date|NONE|-',
    'every conflict decided: holding, resolved');

-- ---- 5. A plain refusal keeps the reason written for the depositor ---------------------------------------------------
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c2', format(
    $s$SELECT snigf.transaction_close(%L, 'REFUSED', 'The deed is not signed by the seller')::text$s$, pg_temp.c('a'))) $$,
    'a conservator refuses the holding deposit outside any conflict decision');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('a')), 'REFUSED|RESOLVED|no date|NONE|The deed is not signed by the seller',
    'the refusal''s own reason is shown; the earlier conflicts stay resolved');

-- ---- 6. Preferring the arriving deposit ------------------------------------------------------------------------------
INSERT INTO ctx SELECT 'd', pg_temp.pdep('00000000-0000-0000-0000-0000000000f3', repeat('3', 64));
INSERT INTO ctx SELECT 'e', pg_temp.pdep('00000000-0000-0000-0000-0000000000f1', repeat('4', 64));
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('d')), 'HOLDING|UNDER_REVIEW|date|NONE|-', 'the title free again, a new holder');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('e')), 'IN_CONFLICT|UNDER_REVIEW|date|NONE|-', 'and a deposit in conflict with it');
SELECT lives_ok($$ SELECT pg_temp.resolve(pg_temp.c('e'), 'PREFER_ARRIVING', 'DEMO Rival deed of the arriving deposit has priority') $$,
    'a conservator prefers the arriving deposit');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('e')), 'HOLDING|RESOLVED|date|NONE|-',
    'the arriving notary now holds, with a review date, the conflict resolved');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('d')), 'REFUSED|RESOLVED|no date|NONE|-',
    'the former holder reads refused, without the reason');

-- ---- 7. A withdrawal during a conflict ---------------------------------------------------------------------------------
INSERT INTO ctx SELECT 'g', pg_temp.pdep('00000000-0000-0000-0000-0000000000f3', repeat('5', 64));
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('g')), 'IN_CONFLICT|UNDER_REVIEW|date|NONE|-', 'in conflict again');
SELECT lives_ok($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f3', format(
    $s$SELECT snigf.transaction_close(%L, 'WITHDRAWN', 'The client changed their mind')::text$s$, pg_temp.c('g'))) $$,
    'the notary withdraws it while the conflict is open');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f3', pg_temp.c('g')), 'WITHDRAWN|UNDER_REVIEW|no date|NONE|The client changed their mind',
    'withdrawn, the conflict still under review: what the application audits as withdrawnDuringConflict');
SELECT is(pg_temp.seen('00000000-0000-0000-0000-0000000000f1', pg_temp.c('e')), 'HOLDING|UNDER_REVIEW|date|NONE|-',
    'the holder sees the review pending until a conservator decides it');

-- ---- 8. The lookup is unchanged: held or not, the same answer -----------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format('SELECT row(number, office_code, accepts_deposit)::text FROM snigf.professional_title_lookup(%L)', pg_temp.c('n1'))),
    row(pg_temp.c('n1'), 'DEMO1', true)::text,
    'the lookup still says only the number, the office and that it accepts a deposit');

-- ---- 9. Suspension: nothing to read ---------------------------------------------------------------------------------------
UPDATE professional_profile SET status = 'SUSPENDED' WHERE user_id = '00000000-0000-0000-0000-0000000000f1';
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT count(*)::text FROM snigf.professional_deposits(NULL, 50, 0)'), '0',
    'a suspended notary reads no deposit, and so no conflict');

SELECT * FROM finish();
ROLLBACK;
