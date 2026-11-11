-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Replacing a formally submitted document needs a second person (Slice 3, V29, ADR-041 D19):
--   deposit documents, both channels: a request first, the original current until another person decides; the
--   requester, a professional or a conservator elsewhere never decides; rejected and withdrawn files kept; no UPDATE
--   or DELETE; a transfer refused while a replacement is pending; an approval changes the signed bytes;
--   verification evidence has no replacement path;
--   application documents: after submission, a new version of a document that has one needs the latest information
--   request to name it, by its officer, with a reason; the version records the request; each request allows one.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(49);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- c1, c2: conservators at DEMO1; c3: conservator at DEMO2. f1, f3: notaries at DEMO1.
-- b1: intake officer at DEMO1; b5: intake officer at DEMO2; b3: applicant (seed).
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'c1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'c2@demo.invalid', 'Conservator Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c3', 'c3@demo.invalid', 'Conservator Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b5', 'intake2@demo.invalid', 'Intake 2', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'n1@demo.invalid', 'DEMO Notary One', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f3', 'n3@demo.invalid', 'DEMO Notary Three', 'x', 'PROFESSIONAL');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b5', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed');
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

CREATE FUNCTION pg_temp.act(p_actor text, p_sql text) RETURNS int
LANGUAGE plpgsql AS $f$
DECLARE n int;
BEGIN
    PERFORM set_config('snigf.actor_id', coalesce(p_actor, ''), true);
    SET LOCAL ROLE snigf_app;
    EXECUTE p_sql;
    GET DIAGNOSTICS n = ROW_COUNT;
    RESET ROLE;
    RETURN n;
END;
$f$;

CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;
CREATE FUNCTION pg_temp.key() RETURNS text LANGUAGE sql AS $f$ SELECT replace(gen_random_uuid()::text, '-', '') $f$;

-- Asks for document p_document to be replaced, as p_actor; the request's id.
CREATE FUNCTION pg_temp.ask(p_actor text, p_document text, p_reason text DEFAULT 'The scan is unreadable, a clearer copy') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.deposit_document_replacement_request(%L, %L, %L, 2000, 'application/pdf', 'deed-v2.pdf', %L)::text$s$,
        p_document, pg_temp.key(), repeat('e', 64), p_reason))
$f$;

CREATE FUNCTION pg_temp.decide(p_actor text, p_replacement text, p_outcome text, p_reason text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(p_actor, format($s$SELECT snigf.deposit_document_replacement_decide(%L, %L, %L)::text$s$, p_replacement, p_outcome, p_reason))
$f$;

CREATE FUNCTION pg_temp.sig(p_canonical bytea) RETURNS jsonb
LANGUAGE plpgsql AS $f$
DECLARE
    nonce bytea := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
BEGIN
    RETURN jsonb_build_object('credentialId', 'k1', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
        'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
        'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(p_canonical || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
        'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA');
END;
$f$;

-- Registers an inscription as c1, over the given bytes (the current ones by default).
CREATE FUNCTION pg_temp.register(p_inscription text, p_canonical bytea DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_register(%L, %L::bytea, %L::jsonb)$s$,
        p_inscription, coalesce(p_canonical, snigf.inscription_canonical(p_inscription::uuid)),
        pg_temp.sig(coalesce(p_canonical, snigf.inscription_canonical(p_inscription::uuid)))))
$f$;

-- One registered title at DEMO1, held by a client.
DO $f$
DECLARE
    r       snigf.geometry_check_result;
    holder  uuid;
    created uuid;
    canon   bytea;
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
    PERFORM snigf.register_register(created, canon, pg_temp.sig(canon));
    INSERT INTO ctx SELECT 't1', i.new_title_id::text FROM snigf.inscription i WHERE i.id = created;
    PERFORM set_config('snigf.actor_id', '', true);
END;
$f$;

-- The notary's deposit on T1, with its deed; the deed's id.
INSERT INTO ctx SELECT 'dep', pg_temp.q('00000000-0000-0000-0000-0000000000f1', format(
    $s$SELECT transaction_id::text FROM snigf.transaction_deposit_professional(%L, 'SALE', %L::jsonb, %L::jsonb, interval '30 days')$s$,
    pg_temp.c('t1'), '{"kind":"NATURAL_PERSON","fullName":"DEMO Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER"}',
    jsonb_build_array(jsonb_build_object('category', 'DEED', 'storageKey', pg_temp.key(), 'sha256', repeat('d', 64), 'sizeBytes', 1000,
                                         'mediaType', 'application/pdf', 'name', 'deed.pdf'))::text));
INSERT INTO ctx SELECT 'deed', id::text FROM deposit_document WHERE transaction_id = pg_temp.c('dep')::uuid;

-- ---- 1. The old immediate replacement is gone ---------------------------------------------------------------
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', $s$SELECT snigf.deposit_document_add(%L, 'DEED', %L, %L, 10,
    'application/pdf', 'deed-v2.pdf', %L, 'The scan is unreadable, a clearer copy')::text$s$) $$, pg_temp.c('dep'), pg_temp.key(), repeat('f', 64), pg_temp.c('deed')),
    'RG001', 'REPLACEMENT_APPROVAL_REQUIRED', 'nobody replaces a submitted document at once any more');
SELECT lives_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', $s$SELECT snigf.deposit_document_add(%L, 'IDENTITY', %L, %L, 10,
    'application/pdf', 'id.pdf', NULL, NULL)::text$s$) $$, pg_temp.c('dep'), pg_temp.key(), repeat('9', 64)),
    'adding a document that replaces nothing is unchanged');

-- ---- 2. The notary asks ------------------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000f1', pg_temp.c('deed'), 'Too short') $$,
    'RG001', 'REASON_REQUIRED', 'a request gives its reason (10 to 2,000 characters)');
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000f3', pg_temp.c('deed')) $$,
    'RG001', 'DOCUMENT_NOT_FOUND', 'another notary does not see the document');
INSERT INTO ctx SELECT 'r1', pg_temp.ask('00000000-0000-0000-0000-0000000000f1', pg_temp.c('deed'));
SELECT is((SELECT status || '|' || requested_as FROM deposit_document_replacement WHERE id = pg_temp.c('r1')::uuid), 'PENDING|PROFESSIONAL',
    'the notary''s request waits, recorded as theirs');
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000c1', pg_temp.c('deed')) $$,
    'RG001', 'REPLACEMENT_PENDING', 'one pending request per document');
SELECT is((SELECT count(*)::int FROM deposit_document WHERE replaces_id = pg_temp.c('deed')::uuid), 0,
    'the original stays the current document while the request waits');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', format($s$SELECT string_agg(status || ':' || requested_by_self, ',') FROM snigf.professional_deposit_replacements(%L)$s$, pg_temp.c('dep'))),
    'PENDING:true', 'the notary reads their own request');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format($s$SELECT count(*)::text FROM snigf.professional_deposit_replacements(%L)$s$, pg_temp.c('dep'))),
    '0', 'another notary reads nothing of it');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f3', format($s$SELECT count(*)::text FROM snigf.professional_replacement_file(%L)$s$, pg_temp.c('r1'))),
    '0', '... nor its file');

-- ---- 3. Who never decides ------------------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000f1', pg_temp.c('r1'), 'APPROVED', NULL) $$,
    'RG001', 'REPLACEMENT_NOT_FOUND', 'a professional never decides, their own request least of all');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c3', pg_temp.c('r1'), 'APPROVED', NULL) $$,
    'RG001', 'REPLACEMENT_NOT_FOUND', 'a conservator elsewhere does not see it');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000b1', pg_temp.c('r1'), 'APPROVED', NULL) $$,
    'RG001', 'REPLACEMENT_NOT_FOUND', 'nor does an intake officer');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c2', pg_temp.c('r1'), 'MAYBE', NULL) $$,
    'RG001', 'OUTCOME_REQUIRED', 'approve or reject, nothing else');

-- ---- 4. The transfer waits for every pending replacement ------------------------------------------------------------
INSERT INTO ctx SELECT 'transfer', pg_temp.q('00000000-0000-0000-0000-0000000000c1', format($s$SELECT snigf.register_propose('00000000-0000-0000-0000-0000000000a1', %L,
    'TRANSFER', 1, 'SYNTHETIC_DEMO', 'DEMO-AUTH-2', 'A synthetic reason of enough length', ARRAY['DEMO-EVIDENCE-2'], NULL, %L, NULL, NULL, NULL,
    %L::jsonb, NULL)::text$s$, pg_temp.c('t1'), pg_temp.c('dep'),
    jsonb_build_array(jsonb_build_object('party', (SELECT depositor_party_id FROM pending_transaction WHERE id = pg_temp.c('dep')::uuid),
                                         'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1))::text));
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('transfer')) $$,
    'RG001', 'REPLACEMENT_PENDING', 'a transfer is refused while a replacement of its deposit is pending');

-- ---- 5. Rejection and withdrawal keep the file -----------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c2', pg_temp.c('r1'), 'REJECTED', NULL) $$,
    'RG001', 'REASON_REQUIRED', 'a rejection gives its reason');
SELECT is(pg_temp.decide('00000000-0000-0000-0000-0000000000c2', pg_temp.c('r1'), 'REJECTED', 'The new copy is the same unreadable scan'), NULL,
    'another conservator rejects it');
SELECT is((SELECT status || '|' || (decided_by = '00000000-0000-0000-0000-0000000000c2')::text FROM deposit_document_replacement WHERE id = pg_temp.c('r1')::uuid),
    'REJECTED|true', '... recorded with who decided');
SELECT is((SELECT count(*)::int FROM document_storage_keys_known(ARRAY[(SELECT storage_key::text FROM deposit_document_replacement WHERE id = pg_temp.c('r1')::uuid)])),
    1, 'the rejected file is kept: the orphan cleanup knows it');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c1', pg_temp.c('r1'), 'APPROVED', NULL) $$,
    'RG001', 'REPLACEMENT_CLOSED', 'a request is decided once');
INSERT INTO ctx SELECT 'r2', pg_temp.ask('00000000-0000-0000-0000-0000000000f1', pg_temp.c('deed'));
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', $s$SELECT snigf.deposit_document_replacement_withdraw(%L, NULL)::text$s$) $$, pg_temp.c('r2')),
    'RG001', 'NOT_THE_REQUESTER', 'only the requester withdraws');
SELECT lives_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', $s$SELECT snigf.deposit_document_replacement_withdraw(%L, NULL)::text$s$) $$, pg_temp.c('r2')),
    'the notary withdraws their request');
SELECT is((SELECT status FROM deposit_document_replacement WHERE id = pg_temp.c('r2')::uuid), 'WITHDRAWN', '... which stays, withdrawn');

-- ---- 6. A counter request: never decided by its requester; approved by another --------------------------------------
INSERT INTO ctx SELECT 'r3', pg_temp.ask('00000000-0000-0000-0000-0000000000c1', pg_temp.c('deed'));
SELECT is((SELECT requested_as FROM deposit_document_replacement WHERE id = pg_temp.c('r3')::uuid), 'STAFF', 'a conservator asks too');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c1', pg_temp.c('r3'), 'APPROVED', NULL) $$,
    'RG001', 'REQUESTER_CANNOT_DECIDE', 'the requester never decides their own request');
INSERT INTO ctx SELECT 'old_bytes', encode(inscription_canonical(pg_temp.c('transfer')::uuid), 'hex');
INSERT INTO ctx SELECT 'new_doc', pg_temp.decide('00000000-0000-0000-0000-0000000000c2', pg_temp.c('r3'), 'APPROVED', NULL);
SELECT is((SELECT concat_ws('|', (replaces_id::text = pg_temp.c('deed'))::text, (replacement_request_id::text = pg_temp.c('r3'))::text,
                            (approved_by = '00000000-0000-0000-0000-0000000000c2')::text, (uploaded_by = '00000000-0000-0000-0000-0000000000c1')::text,
                            (sha256 = repeat('e', 64))::text)
             FROM deposit_document WHERE id = pg_temp.c('new_doc')::uuid),
    'true|true|true|true|true', 'approval adds the document naming the original, the request, the uploader and the approver');
SELECT is((SELECT sha256::text FROM deposit_document WHERE id = pg_temp.c('deed')::uuid), repeat('d', 64), 'the original is unchanged');
SELECT is((SELECT result_document_id::text FROM deposit_document_replacement WHERE id = pg_temp.c('r3')::uuid), pg_temp.c('new_doc'),
    'the request names its result');
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000f1', pg_temp.c('deed')) $$,
    'RG001', 'DOCUMENT_ALREADY_REPLACED', 'a replaced document is not replaced twice; its replacement may be');
SELECT throws_ok($$ SELECT pg_temp.register(pg_temp.c('transfer'), decode(pg_temp.c('old_bytes'), 'hex')) $$,
    'RG001', 'CANONICAL_MISMATCH', 'a signature over the bytes before the approval no longer registers');
SELECT lives_ok($$ SELECT pg_temp.register(pg_temp.c('transfer')) $$, 'with nothing pending, the transfer registers over the new bytes');

-- ---- 7. Closed deposits, the tables, the evidence -----------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.ask('00000000-0000-0000-0000-0000000000c1', pg_temp.c('new_doc')) $$,
    'RG001', 'TRANSACTION_CLOSED', 'a registered deposit takes no request');
SELECT throws_ok($$ DELETE FROM deposit_document_replacement $$, '23001', NULL, 'no request is ever deleted');
SELECT throws_ok($$ UPDATE deposit_document_replacement SET reason = 'Rewritten afterwards, a long reason' WHERE id = pg_temp.c('r1')::uuid $$,
    '23001', NULL, 'a decided request is never rewritten');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ UPDATE snigf.deposit_document_replacement SET status = 'REJECTED' $q$) $$,
    '42501', NULL, 'the application never writes the table: only the functions do');
SELECT is((SELECT count(*)::int FROM pg_proc WHERE proname ~ '(evidence|verification).*replac|replac.*(evidence|verification)'), 0,
    'verification evidence has no replacement path');
SELECT hasnt_column('professional_verification_document', 'replaces_id', '... and no column for one');

-- ---- 8. Application documents: the information request names the document --------------------------------------
INSERT INTO parcel (id, reference, office_id, current_version_no, created_by) VALUES
    ('00000000-0000-0000-0000-0000000030a1', 'DEMO-PCL-DEMO1-2026-000002-X', '00000000-0000-0000-0000-0000000000a1', 1,
     '00000000-0000-0000-0000-0000000000b1');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)',
     '{"fields": [{"key": "statement", "required": true}],
       "documents": [{"code": "DEMO_ID", "required": true}, {"code": "DEMO_PLAN", "required": false}]}');

CREATE FUNCTION pg_temp.record(p_actor text, p_kind text, p_requested text DEFAULT NULL, p_message text DEFAULT NULL) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.completeness_record (application_id, kind, requested, message, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L::text[], %L, %L) $q$,
        p_kind, p_requested, p_message, CASE WHEN p_kind = 'AUTOMATIC' THEN NULL ELSE p_actor END))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text, p_by text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = '00000000-0000-0000-0000-00000000d001' $q$, p_state, p_by))
$f$;
CREATE FUNCTION pg_temp.upload(p_name text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.document_version (document_id, version_no, status,
        storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES ('00000000-0000-0000-0000-0000000000e1', 1, 'STORED', %L, repeat('a', 64), 10, 'application/pdf', %L,
                '00000000-0000-0000-0000-0000000000b3') $q$, pg_temp.key(), p_name))
$f$;
CREATE FUNCTION pg_temp.name(p_actor text, p_reason text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.completeness_replacement (record_id, document_id, reason)
        SELECT (SELECT id FROM snigf.completeness_record WHERE kind = 'INFO_REQUESTED' ORDER BY at DESC, id DESC LIMIT 1),
               '00000000-0000-0000-0000-0000000000e1', %L $q$, p_reason))
$f$;

-- A draft: the applicant corrects their file freely before submission.
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id, data)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id, '{"statement": "Mine"}'
      FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
SELECT is(pg_temp.upload('id.pdf') + pg_temp.upload('id-corrected.pdf'), 2, 'a draft takes corrections from its owner, with no request');
SELECT is((SELECT count(*)::int FROM document_version WHERE replacement_id IS NOT NULL), 0, '... and they replace nothing formally');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000002-X', parcel_id = '00000000-0000-0000-0000-0000000030a1',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$);
SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'AUTOMATIC');
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'COMPLETENESS_REVIEW', NULL);

-- A request that names nothing: the category is open, but the document is not replaced.
SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '{DEMO_ID}', 'Please send a clearer identity document');
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '00000000-0000-0000-0000-0000000000b1');
SELECT throws_like($$ SELECT pg_temp.upload('id-v3.pdf') $$, 'REPLACEMENT_NOT_REQUESTED%',
    'after submission, a document is replaced only as the request names it');
SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'RESPONSE', NULL, 'Nothing to add');
SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b3', 'AUTOMATIC');
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b3', 'COMPLETENESS_REVIEW', '00000000-0000-0000-0000-0000000000b3');

-- A request that names the document, by its officer, with the reason.
SELECT pg_temp.record('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '{DEMO_ID}', 'Please send a clearer identity document');
SELECT throws_ok($$ SELECT pg_temp.name('00000000-0000-0000-0000-0000000000b5', 'The identity document is unreadable') $$,
    '42501', NULL, 'only the request''s own officer names a document for it');
SELECT throws_ok($$ SELECT pg_temp.name('00000000-0000-0000-0000-0000000000b1', 'Unclear') $$,
    '23514', NULL, 'the reason is 10 characters at least');
SELECT is(pg_temp.name('00000000-0000-0000-0000-0000000000b1', 'The identity document is unreadable'), 1, 'the officer names the document and the reason');
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000b1', 'INFO_REQUESTED', '00000000-0000-0000-0000-0000000000b1');
SELECT is(pg_temp.upload('id-v3.pdf'), 1, 'the applicant replaces it');
SELECT is((SELECT cr.reason FROM document_version v JOIN completeness_replacement cr ON cr.id = v.replacement_id WHERE v.version_no = 3),
    'The identity document is unreadable', 'the new version records the request that allowed it');
SELECT throws_like($$ SELECT pg_temp.upload('id-v4.pdf') $$, 'REPLACEMENT_NOT_REQUESTED%', 'one request allows one replacement');
SELECT is((SELECT string_agg(original_name, ',' ORDER BY version_no) FROM document_version WHERE document_id = '00000000-0000-0000-0000-0000000000e1'),
    'id.pdf,id-corrected.pdf,id-v3.pdf', 'every earlier version is kept');
SELECT throws_ok($$ UPDATE completeness_replacement SET reason = 'Rewritten afterwards' $$, '23001', NULL, 'a named replacement is never rewritten');
SELECT throws_ok($$ DELETE FROM completeness_replacement $$, '23001', NULL, '... nor deleted');

SELECT * FROM finish();
ROLLBACK;
