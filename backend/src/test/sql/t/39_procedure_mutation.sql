-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Procedure B, the mutation totale (Slice 4, V37, ADR-042 step 6, D45 to D47; ADR-043 D36, D37): the declaration of
-- transferors and transferees kept apart; the case naming its title at submission (not found, not on the parcel, already
-- named by an open case: refused) and never changing it; the deposit that names the case (another title, another kind, a
-- second open deposit: refused); TITLE_VERIFICATION completed only when the transferors are exactly the holders (D45), and
-- holding while the holders are those verified and nothing blocks (D47); DUTIES_EVIDENCE citing the deposit's receipt; the
-- TRANSFER from the approved case only, never through ADR-040's plain path, never by a handler of the case, signed twice
-- (D46), against the current version when only a charge came in between (D47); the title kept, its earlier holders in its
-- history; the applicant reading that the transfer is registered.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(77);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; c1 intake (records the persons); c5 intake (checks them); c2 reviewer; c4 decision authority;
-- k1 conservator who keeps the synthetic titles; k2 conservator who records the stages (a handler); k3 and k4 conservators
-- who never handled the case (the two signers); k5 conservator who receives a counter deposit (a handler of that case);
-- n1 a notary's account; d1 and d2 administrators (the notary's activation); b3 the applicant.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'i2@demo.invalid', 'Checker', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'adm1@demo.invalid', 'Admin One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'adm2@demo.invalid', 'Admin Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e1', 'n1@demo.invalid', 'DEMO Notary One', 'x', 'PROFESSIONAL'),
    ('00000000-0000-0000-0000-0000000000f1', 'k1@demo.invalid', 'Conservator Keeper', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f2', 'k2@demo.invalid', 'Conservator Stages', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f3', 'k3@demo.invalid', 'Conservator Three', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f4', 'k4@demo.invalid', 'Conservator Four', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f5', 'k5@demo.invalid', 'Conservator Counter', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f3', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f4', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f5', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000f1', 'One'), ('h2', '00000000-0000-0000-0000-0000000000f2', 'Two'),
    ('h3', '00000000-0000-0000-0000-0000000000f3', 'Three'),
    ('h4', '00000000-0000-0000-0000-0000000000f4', 'Four'), ('h5', '00000000-0000-0000-0000-0000000000f5', 'Five');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'), ('k2', 'h2', '\x0202', false, false, 'Key two'),
    ('k3', 'h3', '\x0303', false, false, 'Key three'),
    ('k4', 'h4', '\x0404', false, false, 'Key four'), ('k5', 'h5', '\x0505', false, false, 'Key five');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_MUT_T', 1, true, 'Mutation (DEMO)', 'Transfer (DEMO)',
     '{"fields": [{"key": "titleNumber", "kind": "TEXT", "required": true, "maxLength": 40, "options": null,
                   "labelFr": "Titre", "labelEn": "Title", "hintFr": null, "hintEn": null}],
       "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "B", "stages": ["TITLE_VERIFICATION", "DUTIES_EVIDENCE"], "registers": "TRANSFER", "delivery": true}}');

-- The notary, active at DEMO1 as an approved activation leaves them (as in 27).
DO $$
DECLARE
    v uuid;
    r uuid;
BEGIN
    INSERT INTO snigf.professional_profile (user_id, profession, chamber, registration_number, jurisdiction, status)
    VALUES ('00000000-0000-0000-0000-0000000000e1', 'NOTARY', 'DEMO Chamber', 'DEMO-N-1', 'DEMO Yaounde', 'ACTIVE');
    INSERT INTO snigf.professional_verification (user_id, verified_by, body, reference, checked_on, note)
    VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000d1', 'DEMO Chamber', 'DEMO-REF', current_date,
            'Checked with the DEMO chamber')
    RETURNING id INTO v;
    INSERT INTO snigf.professional_request (user_id, kind, offices_add, reason, verification_id, requested_by, status, decided_by, decided_at)
    VALUES ('00000000-0000-0000-0000-0000000000e1', 'ACTIVATION', ARRAY['00000000-0000-0000-0000-0000000000a1'::uuid],
            'Checked with the DEMO chamber', v, '00000000-0000-0000-0000-0000000000d1', 'APPROVED', '00000000-0000-0000-0000-0000000000d2', now())
    RETURNING id INTO r;
    INSERT INTO snigf.professional_office (user_id, office_id, added_by_request)
    VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a1', r);
END $$;

CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;
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
CREATE FUNCTION pg_temp.val(p_actor text, p_sql text) RETURNS text
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
CREATE FUNCTION pg_temp.sig(p_inscription uuid, p_credential text, p_key bytea) RETURNS jsonb
LANGUAGE plpgsql AS $f$
DECLARE
    canon bytea := snigf.inscription_canonical(p_inscription);
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
CREATE FUNCTION pg_temp.sign(p_act text, p_actor text, p_credential text, p_key bytea, p_inscription text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($s$SELECT snigf.%I(%L, %L::bytea, %L::jsonb)::text$s$, p_act, p_inscription,
        snigf.inscription_canonical(p_inscription::uuid), pg_temp.sig(p_inscription::uuid, p_credential, p_key)))
$f$;
-- ADR-040's proposal, by p_actor at DEMO1 (as in 23).
CREATE FUNCTION pg_temp.reg(p_actor text, p_title text, p_kind text, p_expected int, p_transaction text, p_target text,
                            p_area numeric, p_parcel text, p_holders text, p_charge text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($s$SELECT snigf.register_propose('00000000-0000-0000-0000-0000000000a1', %L, %L, %s, 'SYNTHETIC_DEMO',
        'DEMO-AUTH-1', 'A synthetic reason of enough length', ARRAY['DEMO-EVIDENCE-1'], NULL, %L, %L, %L, %L, %L, %L)::text$s$,
        p_title, p_kind, p_expected, p_transaction, p_target, p_area, p_parcel, p_holders, p_charge))
$f$;
CREATE FUNCTION pg_temp.holder(p_party text, p_interest text DEFAULT 'OWNERSHIP', p_num int DEFAULT 1, p_den int DEFAULT 1) RETURNS jsonb
LANGUAGE sql AS $f$ SELECT jsonb_build_object('party', p_party, 'interest', p_interest, 'shareNum', p_num, 'shareDen', p_den) $f$;
CREATE FUNCTION pg_temp.title(p_creation text) RETURNS text LANGUAGE sql AS $f$
    SELECT id::text FROM land_title WHERE creation_inscription_id = pg_temp.c(p_creation)::uuid
$f$;
CREATE FUNCTION pg_temp.number(p_creation text) RETURNS text LANGUAGE sql AS $f$
    SELECT number FROM land_title WHERE creation_inscription_id = pg_temp.c(p_creation)::uuid
$f$;
-- A draft of the procedure B type, by the applicant.
CREATE FUNCTION pg_temp.draft(p_case text) RETURNS int LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
        SELECT %L, '00000000-0000-0000-0000-0000000000b3', 'DEMO_MUT_T', id FROM snigf.application_type WHERE code = 'DEMO_MUT_T' $q$, p_case))
$f$;
CREATE FUNCTION pg_temp.submit(p_case text, p_n text, p_parcel text, p_title_number text, p_declaration jsonb) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
        declaration = %L::jsonb, data = %L::jsonb, parcel_reference = %L, parcel_id = %L, office_id = '00000000-0000-0000-0000-0000000000a1',
        reference = 'DEMO-APP-2026-%s-X', state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = %L $q$,
        p_declaration, jsonb_build_object('titleNumber', p_title_number), (SELECT reference FROM snigf.parcel WHERE id = p_parcel::uuid),
        p_parcel, p_n, p_case))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text, p_case text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = %L $q$, p_state, p_actor, p_case))
$f$;
-- Intake: completeness, then the persons transcribed by c1 and checked by c5, then under review.
CREATE FUNCTION pg_temp.intake(p_case text) RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.completeness_record (application_id, kind)
        VALUES (%L, 'AUTOMATIC') $q$, p_case));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW',
        state_changed_by = NULL, version = version + 1 WHERE id = %L $q$, p_case));
    PERFORM tap.parties_ready(p_case::uuid, '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c5');
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000c1', format($q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
        VALUES (%L, 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$, p_case));
    PERFORM pg_temp.move('00000000-0000-0000-0000-0000000000c1', 'UNDER_REVIEW', p_case);
END;
$f$;
CREATE FUNCTION pg_temp.rec(p_actor text, p_case text, p_stage text, p_details jsonb DEFAULT '{}', p_outcome text DEFAULT 'COMPLETED') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_stage_record_add(%L, %L, %L, 'What the register and the deposit show',
        %L::jsonb, '{}', NULL, NULL, '[]'::jsonb)::text $q$, p_case, p_stage, p_outcome, p_details))
$f$;
-- The notary deposits a sale on a title, the deed and the duty receipt, naming a case by its reference.
CREATE FUNCTION pg_temp.pdep(p_title text, p_case_reference text, p_kind text DEFAULT 'SALE') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val('00000000-0000-0000-0000-0000000000e1', format($s$SELECT transaction_id::text FROM snigf.transaction_deposit_professional(
        %L, %L, '{"kind":"NATURAL_PERSON","fullName":"DEMO Buyer","identityKind":"NATIONAL_ID","identityNumber":"DEMO-ID-BUYER-01"}'::jsonb,
        %L::jsonb, interval '30 days', %L)$s$, p_title, p_kind,
        jsonb_build_array(jsonb_build_object('category', 'DEED', 'storageKey', md5(gen_random_uuid()::text), 'sha256', encode(sha256(gen_random_uuid()::text::bytea), 'hex'),
                                             'sizeBytes', 1000, 'mediaType', 'application/pdf', 'name', 'deed.pdf'),
                          jsonb_build_object('category', 'TAX_OR_DUTY', 'storageKey', md5(gen_random_uuid()::text), 'sha256', encode(sha256(gen_random_uuid()::text::bytea), 'hex'),
                                             'sizeBytes', 1000, 'mediaType', 'application/pdf', 'name', 'receipt.pdf')),
        p_case_reference))
$f$;
-- k5 receives a sale at the counter, naming a case by its reference (or none).
CREATE FUNCTION pg_temp.cdep(p_title text, p_case_reference text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f5', format($s$SELECT snigf.transaction_deposit(%L, 'SALE', %L, NULL, ARRAY['DEMO-DEED-C'],
        interval '30 days', %L)::text$s$, p_title, pg_temp.c('buyer'), p_case_reference))
$f$;
CREATE FUNCTION pg_temp.doc(p_deposit text, p_category text) RETURNS text LANGUAGE sql AS $f$
    SELECT id::text FROM deposit_document WHERE transaction_id = p_deposit::uuid AND category = p_category
$f$;
CREATE FUNCTION pg_temp.propose(p_actor text, p_reason text DEFAULT 'Approved sale, title verified and duties evidenced (synthetic)') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.register_propose_transfer_from_case('00000000-0000-0000-0000-00000000d001', %L)::text $q$, p_reason))
$f$;
CREATE FUNCTION pg_temp.queue(p_actor text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, $q$ SELECT coalesce(string_agg(reference || ':' || array_to_string(problems, '|') || ':' || may_propose || ':'
        || registers || ':' || coalesce(title_number, '-'), ','), '') FROM snigf.register_case_queue(50, 0) $q$)
$f$;

-- --- The register: three synthetic titles at DEMO1 -------------------------------------------------------------------
-- T1 on parcel 1, the seller alone; T2 on parcel 2, the seller and a co-owner in halves; T3 on parcel 3, a third seller.
-- Parcel 9 carries no title.
DO $$
DECLARE
    g text;
    n int := 0;
    r snigf.geometry_check_result;
BEGIN
    FOREACH g IN ARRAY ARRAY['950000', '951000', '952000', '959000'] LOOP
        n := n + 1;
        r := snigf.normalize_parcel_geometry(format('{"type":"Polygon","coordinates":[[[%1$s,400000],[%2$s,400000],[%2$s,400100],[%1$s,400100],[%1$s,400000]]]}',
                                                    g, (g::int + 100)::text), 32632);
        INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
        VALUES (('00000000-0000-0000-0000-00000000400' || CASE n WHEN 4 THEN 9 ELSE n END)::uuid,
                'DEMO-PCL-DEMO1-2026-00000' || CASE n WHEN 4 THEN 9 ELSE n END || '-Z', '00000000-0000-0000-0000-0000000000a1', 1,
                '00000000-0000-0000-0000-0000000000b1');
        INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
        VALUES (('00000000-0000-0000-0000-00000000500' || CASE n WHEN 4 THEN 9 ELSE n END)::uuid,
                ('00000000-0000-0000-0000-00000000400' || CASE n WHEN 4 THEN 9 ELSE n END)::uuid, 1, r.geom, r.area_m2, 32632, repeat('a', 64),
                'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
    END LOOP;
END $$;
INSERT INTO ctx SELECT x.k, pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($s$ SELECT snigf.register_party_create(
    '00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', %L, 'NATIONAL_ID', %L, NULL, NULL, NULL, NULL)::text $s$, x.name, x.number))
  FROM (VALUES ('seller', 'DEMO Seller', 'DEMO-ID-SELLER-01'), ('coowner', 'DEMO Co-owner', 'DEMO-ID-COOWNER-01'),
               ('seller3', 'DEMO Seller Three', 'DEMO-ID-SELLER-03'), ('buyer', 'DEMO Buyer', 'DEMO-ID-BUYER-01')) x(k, name, number);
INSERT INTO ctx SELECT 'c1', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', NULL, 'CREATION', 0, NULL, NULL, 10000,
    '00000000-0000-0000-0000-000000005001', jsonb_build_array(pg_temp.holder(pg_temp.c('seller')))::text, NULL);
INSERT INTO ctx SELECT 'c2', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', NULL, 'CREATION', 0, NULL, NULL, 10000,
    '00000000-0000-0000-0000-000000005002', jsonb_build_array(pg_temp.holder(pg_temp.c('seller'), 'CO_OWNERSHIP', 1, 2),
                                                              pg_temp.holder(pg_temp.c('coowner'), 'CO_OWNERSHIP', 1, 2))::text, NULL);
INSERT INTO ctx SELECT 'c3', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', NULL, 'CREATION', 0, NULL, NULL, 10000,
    '00000000-0000-0000-0000-000000005003', jsonb_build_array(pg_temp.holder(pg_temp.c('seller3')))::text, NULL);
DO $$ BEGIN
    PERFORM pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c(k)) FROM unnest(ARRAY['c1', 'c2', 'c3']) k;
END $$;

-- --- 1. The declaration of a procedure B case (D37) ------------------------------------------------------------------

SELECT is(case_declaration_problems(tap.transfer_declaration(), 'B'), '{}'::text[], 'a buyer applying for themselves, one transferor: valid');
SELECT ok('TRANSFEROR_AND_TRANSFEREE:p2' = ANY (case_declaration_problems(jsonb_set(tap.transfer_declaration(), '{persons,1,roles}',
    '["TRANSFEROR", "TRANSFEREE"]'), 'B')), 'a person is never both transferor and transferee');
SELECT ok('TRANSFEROR_REQUIRED' = ANY (case_declaration_problems(jsonb_set(tap.transfer_declaration(), '{persons,1,roles}', '["TRANSFEREE"]'), 'B')),
    'a mutation has a transferor');
SELECT ok('SHARE_INVALID' = ANY (case_declaration_problems(jsonb_set(tap.transfer_declaration(), '{shares}',
    '[{"person": "p2", "interest": "OWNERSHIP", "shareNum": 1, "shareDen": 1}]'), 'B')), 'the shares are the transferees'', never a transferor''s');
SELECT ok('PERSON_INVALID:p1' = ANY (case_declaration_problems(tap.self_declaration(), 'B')), 'procedure B has no intended owner');
SELECT is(case_declaration_problems(tap.self_declaration(), 'A'), '{}'::text[], 'procedure A''s rules are unchanged');

-- --- 2. Opening: the case names its title --------------------------------------------------------------------------------

DO $$ BEGIN
    PERFORM pg_temp.draft(x) FROM unnest(ARRAY['00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000d003',
        '00000000-0000-0000-0000-00000000d004', '00000000-0000-0000-0000-00000000d010', '00000000-0000-0000-0000-00000000d011',
        '00000000-0000-0000-0000-00000000d012', '00000000-0000-0000-0000-00000000d013']) x;
    -- d001's identity document, which its decision cites.
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
        VALUES ('00000000-0000-0000-0000-0000000000e9', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
        status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES ('00000000-0000-0000-0000-0000000000a9', '00000000-0000-0000-0000-0000000000e9', 1, 'STORED',
                '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
END $$;

SELECT throws_ok($$ SELECT pg_temp.submit('00000000-0000-0000-0000-00000000d010', '000010', '00000000-0000-0000-0000-000000004001',
    'DEMO-TF-DEMO1-999999-Z', tap.transfer_declaration()) $$, 'PC001', 'TITLE_NOT_FOUND', 'a title unknown at the office is refused');
SELECT throws_ok($$ SELECT pg_temp.submit('00000000-0000-0000-0000-00000000d011', '000011', '00000000-0000-0000-0000-000000004009',
    pg_temp.number('c1'), tap.transfer_declaration()) $$, 'PC001', 'TITLE_NOT_ON_PARCEL', '... and a title that does not rest on the case''s parcel');
SELECT throws_ok($$ SELECT pg_temp.submit('00000000-0000-0000-0000-00000000d013', '000013', '00000000-0000-0000-0000-000000004001',
    pg_temp.number('c1'), tap.self_declaration()) $$, 'PC001', 'DECLARATION_INVALID', '... and a declaration of intended owners');
SELECT is(pg_temp.submit('00000000-0000-0000-0000-00000000d001', '000001', '00000000-0000-0000-0000-000000004001', pg_temp.number('c1'),
    tap.transfer_declaration()), 1, 'd001 is submitted on T1');
SELECT is((SELECT title_id::text FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), pg_temp.title('c1'),
    'the database resolved its title');
SELECT throws_ok($$ SELECT pg_temp.submit('00000000-0000-0000-0000-00000000d012', '000012', '00000000-0000-0000-0000-000000004001',
    pg_temp.number('c1'), tap.transfer_declaration()) $$, 'PC001', 'TITLE_CASE_OPEN', 'a second open case on the same title is refused');
SELECT is(pg_temp.submit('00000000-0000-0000-0000-00000000d003', '000003', '00000000-0000-0000-0000-000000004002', pg_temp.number('c2'),
              tap.transfer_declaration())
          + pg_temp.submit('00000000-0000-0000-0000-00000000d004', '000004', '00000000-0000-0000-0000-000000004003', pg_temp.number('c3'),
              tap.transfer_declaration('DEMO Seller Three', 'DEMO-ID-SELLER-03')), 2,
    'd003 on T2 (declaring one of its two holders) and d004 on T3 are submitted');
SELECT throws_ok($$ UPDATE application SET title_id = (SELECT id FROM land_title WHERE creation_inscription_id = pg_temp.c('c2')::uuid),
    version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d001' $$, '23001', NULL, 'the title of a case never changes');
SELECT is(case_owner_role('00000000-0000-0000-0000-00000000d001'), 'TRANSFEREE', 'the ownership statement of a B case is the transferees''');

-- --- 3. The deposit that names the case --------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.pdep(pg_temp.title('c1'), 'DEMO-APP-2026-999999-X') $$, 'RG001', 'CASE_NOT_FOUND',
    'a deposit naming an unknown case is refused');
SELECT throws_ok($$ SELECT pg_temp.pdep(pg_temp.title('c2'), 'DEMO-APP-2026-000001-X') $$, 'RG001', 'CASE_TITLE_MISMATCH',
    '... and one on another title than the case''s');
SELECT throws_ok($$ SELECT pg_temp.pdep(pg_temp.title('c1'), 'DEMO-APP-2026-000001-X', 'GIFT') $$, 'RG001', 'CASE_NOT_TRANSFER',
    '... and a gift naming a sale''s case');
INSERT INTO ctx SELECT 'dep1', pg_temp.pdep(pg_temp.title('c1'), 'DEMO-APP-2026-000001-X');
SELECT is((SELECT status || ':' || application_id FROM pending_transaction WHERE id = pg_temp.c('dep1')::uuid),
    'HOLDING:00000000-0000-0000-0000-00000000d001', 'the notary''s deposit holds T1 for d001');
SELECT throws_ok($$ SELECT pg_temp.cdep(pg_temp.title('c1'), 'DEMO-APP-2026-000001-X') $$, 'RG001', 'CASE_DEPOSIT_OPEN',
    'a second open deposit for the same case is refused');
SELECT ok(audit_subjects('PENDING_TRANSACTION', pg_temp.c('dep1')::uuid)::text LIKE '%00000000-0000-0000-0000-00000000d001%',
    'the deposit belongs to the case''s timeline too');
INSERT INTO ctx SELECT 'dep4', pg_temp.cdep(pg_temp.title('c3'), 'DEMO-APP-2026-000004-X');
SELECT ok(register_case_handler('00000000-0000-0000-0000-00000000d004', '00000000-0000-0000-0000-0000000000f5'),
    'the conservator who received a case''s counter deposit handled it (D46)');

-- --- 4. The stages -----------------------------------------------------------------------------------------------------

DO $$ BEGIN
    PERFORM pg_temp.intake(x) FROM unnest(ARRAY['00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000d003',
        '00000000-0000-0000-0000-00000000d004']) x;
END $$;
SELECT is(cardinality(case_party_problems('00000000-0000-0000-0000-00000000d001')), 0, 'the transferor and the transferee are transcribed and checked');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d001', 'TITLE_VERIFICATION') $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'a reviewer does not verify a title');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'TITLE_VERIFICATION',
    '{"versionNo": 1}') $$, 'PC001', 'DETAILS_INVALID', 'the title''s version is the database''s, never the officer''s');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d003', 'TITLE_VERIFICATION') $$,
    'PC001', 'TRANSFERORS_NOT_HOLDERS', 'one holder of two declared as transferor: refused (D45)');
SELECT is((SELECT (m ->> 'exact') || ':' || (SELECT count(*) FROM jsonb_array_elements(m -> 'holders') h WHERE h ->> 'transferor' IS NULL)
             FROM (SELECT case_transferor_match('00000000-0000-0000-0000-00000000d003', pg_temp.title('c2')::uuid, 1) m) x), 'false:1',
    '... the co-owner matches no transferor');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d003', 'TITLE_VERIFICATION', '{}', 'NOT_SATISFIED')
          IS NOT NULL, '... which the conservator records as not satisfied');
INSERT INTO ctx SELECT 'tv1', pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'TITLE_VERIFICATION');
SELECT is((SELECT (details ->> 'versionNo') || ':' || (details -> 'holders' -> 0 ->> 'transferor') || ':' || (details ->> 'depositId')
             FROM procedure_stage_record WHERE id = pg_temp.c('tv1')::uuid), '1:p2:' || pg_temp.c('dep1'),
    'd001''s verification records the version read, the holder matched to the transferor, and the deposit');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f2', $q$ SELECT string_agg(status || ':' || title_number || ':' || receipt_name, ',')
    FROM snigf.procedure_case_deposit('00000000-0000-0000-0000-00000000d001') $q$), 'HOLDING:' || pg_temp.number('c1') || ':receipt.pdf',
    'the conservator who records the stages reads the case''s deposit and its duty receipt, never the deed');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c2', $q$ SELECT count(*)::text
    FROM snigf.procedure_case_deposit('00000000-0000-0000-0000-00000000d001') $q$), '0', '... and a reviewer reads none of it');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'DUTIES_EVIDENCE',
    jsonb_build_object('receiptDocument', pg_temp.doc(pg_temp.c('dep1'), 'DEED'), 'receiptReference', 'DEMO-Q-1')) $$,
    'PC001', 'DUTY_RECEIPT_REQUIRED', 'the deed is not a duty receipt (D24)');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'DUTIES_EVIDENCE',
    jsonb_build_object('receiptDocument', pg_temp.doc(pg_temp.c('dep1'), 'TAX_OR_DUTY'))) $$,
    'PC001', 'RECEIPT_REFERENCE_REQUIRED', '... and the receipt has its reference');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'DUTIES_EVIDENCE',
    jsonb_build_object('receiptDocument', pg_temp.doc(pg_temp.c('dep1'), 'TAX_OR_DUTY'), 'receiptReference', 'DEMO-Q-1')) IS NOT NULL,
    'the duty receipt of the case''s deposit is evidenced');
SELECT is(procedure_gate_problems('00000000-0000-0000-0000-00000000d001'), '{}'::text[], 'd001''s procedure is complete');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d004', 'TITLE_VERIFICATION') IS NOT NULL,
    'd004''s title is verified, its counter deposit holding');

-- --- 5. A title changed after its verification (D47), on d004's T3 ----------------------------------------------------------

INSERT INTO ctx SELECT 'm3', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', pg_temp.title('c3'), 'CHARGE', 1, NULL, NULL, NULL, NULL, NULL,
    format('{"kind":"MORTGAGE","beneficiary":"%s","amount":1000000,"note":"DEMO mortgage"}', pg_temp.c('coowner')));
SELECT lives_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('m3')) $$,
    'a mortgage is registered on T3 after its verification');
SELECT is(procedure_stage_problems('00000000-0000-0000-0000-00000000d004', 'TITLE_VERIFICATION'), '{}'::text[],
    '... the holders are those verified: the verification holds');
INSERT INTO ctx SELECT 'o3', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', pg_temp.title('c3'), 'RESTRICTION', 2, NULL, NULL, NULL, NULL, NULL,
    '{"kind":"OPPOSITION","blocksTransfer":true,"note":"DEMO opposition"}');
SELECT lives_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('o3')) $$,
    'an opposition is registered on T3');
SELECT is(procedure_stage_problems('00000000-0000-0000-0000-00000000d004', 'TITLE_VERIFICATION'), ARRAY['BLOCKING_RESTRICTION'],
    '... the verification no longer holds while it blocks');
INSERT INTO ctx SELECT 'x3', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', pg_temp.title('c3'), 'CORRECTION', 3, NULL, pg_temp.c('c3'), 10000,
    '00000000-0000-0000-0000-000000005003', jsonb_build_array(pg_temp.holder(pg_temp.c('coowner')))::text, NULL);
SELECT lives_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('x3')) $$,
    'a correction of T3''s holder is signed first');
SELECT lives_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f5', 'k5', '\x0505', pg_temp.c('x3')) $$,
    '... and confirmed: T3''s holder is corrected under dual control');
SELECT ok('TITLE_CHANGED_SINCE_VERIFICATION' = ANY (procedure_stage_problems('00000000-0000-0000-0000-00000000d004', 'TITLE_VERIFICATION')),
    '... the holders are no longer those verified: the case is held (D47)');

-- --- 6. The TRANSFER from the approved case -------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f3') $$, 'RG001', 'CASE_NOT_APPROVED',
    'no TRANSFER from a case under review');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'REVIEW', '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000c2') $q$)
          + pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'The sale is in order and the title verified',
                      '00000000-0000-0000-0000-0000000000c2') $q$)
          + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION', '00000000-0000-0000-0000-00000000d001')
          + pg_temp.act('00000000-0000-0000-0000-0000000000c4', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'DECISION', '00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-0000000000c4') $q$)
          + pg_temp.act('00000000-0000-0000-0000-0000000000c4', $q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions,
              follows_step_id, decided_by)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'APPROVED', 'The procedure''s record supports the mutation', '{00000000-0000-0000-0000-0000000000a9}',
                      (SELECT id FROM snigf.review_step WHERE application_id = '00000000-0000-0000-0000-00000000d001' ORDER BY at DESC LIMIT 1),
                      '00000000-0000-0000-0000-0000000000c4') $q$)
          + pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'APPROVED', '00000000-0000-0000-0000-00000000d001'), 6,
    'd001 is reviewed and the mutation authorised');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f3'), 'DEMO-APP-2026-000001-X::true:TRANSFER:' || pg_temp.number('c1'),
    '"Approved, to register" lists the transfer and its title');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f2'), 'DEMO-APP-2026-000001-X::false:TRANSFER:' || pg_temp.number('c1'),
    '... for the conservator who recorded its stages, who may not propose it');
SELECT is((SELECT (e -> 'transferors' -> 0 ->> 'identityNumber') || ':' || (e -> 'owners' -> 0 ->> 'identityNumber') || ':' || (e -> 'title' ->> 'number')
                  || ':' || (e -> 'deposit' ->> 'id')
             FROM (SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f3',
                       $q$ SELECT snigf.register_case_evidence('00000000-0000-0000-0000-00000000d001')::text $q$)::jsonb e) x),
    'DEMO-ID-SELLER-01:DEMO-ID-BUYER-01:' || pg_temp.number('c1') || ':' || pg_temp.c('dep1'),
    'the signers examine the transferor, the transferee, the title and the deposit');
SELECT throws_ok($$ SELECT pg_temp.reg('00000000-0000-0000-0000-0000000000f3', pg_temp.title('c1'), 'TRANSFER', 1, pg_temp.c('dep1'), NULL, NULL, NULL,
    jsonb_build_array(pg_temp.holder(pg_temp.c('buyer')))::text, NULL) $$, 'RG001', 'DEPOSIT_OF_A_CASE',
    'ADR-040''s plain TRANSFER never completes a deposit that names a case');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f2') $$, 'RG001', 'CASE_HANDLER_CANNOT_REGISTER',
    'the conservator who recorded its stages never proposes its transfer');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c1') $$, 'RG001', 'CASE_NOT_FOUND',
    '... nor an intake officer, who has no register');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f3', 'short') $$, 'RG001', 'REASON_REQUIRED', 'a reason is required');
INSERT INTO ctx SELECT 'tr', pg_temp.propose('00000000-0000-0000-0000-0000000000f3');
SELECT is((SELECT kind || ':' || title_id || ':' || expected_version || ':' || completes_transaction_id || ':' || application_id
             FROM inscription WHERE id = pg_temp.c('tr')::uuid),
    'TRANSFER:' || pg_temp.title('c1') || ':1:' || pg_temp.c('dep1') || ':00000000-0000-0000-0000-00000000d001',
    'k3 proposes the TRANSFER onto T1 at its current version, completing the case''s deposit');
SELECT is((SELECT string_agg(rp.identity_number || ':' || h.interest || ':' || (rp.source_identity_id IS NOT NULL), ',')
             FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id WHERE h.inscription_id = pg_temp.c('tr')::uuid),
    'DEMO-ID-BUYER-01:OWNERSHIP:true', '... its holder the decided statement''s transferee, from their checked identity');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f4') $$, 'RG001', 'TRANSFER_ALREADY_PROPOSED',
    'one TRANSFER in progress per case');
SELECT throws_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f3', 'k3', '\x0303', pg_temp.c('tr')) $$,
    'RG001', 'DUAL_CONTROL_REQUIRED', 'a TRANSFER from a case takes two signatures (D46)');

-- D47: a mortgage registered on T1 between the proposal and its signature; the holders are unchanged.
INSERT INTO ctx SELECT 'm1', pg_temp.reg('00000000-0000-0000-0000-0000000000f1', pg_temp.title('c1'), 'CHARGE', 1, NULL, NULL, NULL, NULL, NULL,
    format('{"kind":"MORTGAGE","beneficiary":"%s","amount":500000,"note":"DEMO mortgage"}', pg_temp.c('coowner')));
SELECT lives_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('m1')) $$,
    'a mortgage is registered on T1 after the proposal');
SELECT throws_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f3', 'k3', '\x0303', pg_temp.c('tr')) $$,
    'RG001', 'TITLE_CHANGED', 'the proposal is stale');
SELECT is(register_transfer_case_problems('00000000-0000-0000-0000-00000000d001', pg_temp.c('tr')::uuid), '{}'::text[],
    '... but the case is not held: the holders are still those verified');
SELECT lives_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f3', format($q$ SELECT snigf.register_close_proposal(%L, 'WITHDRAWN',
    'Stale after a mortgage, proposed again at the current version')::text $q$, pg_temp.c('tr'))) $$, 'k3 withdraws it');
INSERT INTO ctx SELECT 'tr2', pg_temp.propose('00000000-0000-0000-0000-0000000000f3');
SELECT is((SELECT expected_version FROM inscription WHERE id = pg_temp.c('tr2')::uuid), 2, '... and proposes it again against the current version (D47)');

-- The two signatures.
SELECT lives_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f3', 'k3', '\x0303', pg_temp.c('tr2')) $$,
    'k3 signs first');
SELECT throws_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f3', 'k3', '\x0303', pg_temp.c('tr2')) $$,
    'RG001', 'SELF_CONFIRMATION', 'never twice by the same conservator');
SELECT throws_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f2', 'k2', '\x0202', pg_temp.c('tr2')) $$,
    'RG001', 'CASE_HANDLER_CANNOT_REGISTER', 'the conservator who recorded the case''s stages never signs its transfer (D46)');
SELECT lives_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('tr2')) $$,
    'k4, who never handled the case, signs second: the transfer is registered');

-- --- 7. After the registration -----------------------------------------------------------------------------------------------

SELECT is((SELECT status || ':' || number || ':' || confirmed_by FROM inscription WHERE id = pg_temp.c('tr2')::uuid),
    'REGISTERED:3:00000000-0000-0000-0000-0000000000f4', 'the TRANSFER is T1''s third inscription, confirmed by k4');
SELECT is((SELECT string_agg(rp.identity_number, ',') FROM title_version v, jsonb_array_elements(v.holders) h
             JOIN register_party rp ON rp.id = (h ->> 'party')::uuid
            WHERE v.title_id = pg_temp.title('c1')::uuid AND v.version_no = 3), 'DEMO-ID-BUYER-01', 'T1 is now held by the buyer');
SELECT is((SELECT string_agg(rp.identity_number, ',') FROM title_version v, jsonb_array_elements(v.holders) h
             JOIN register_party rp ON rp.id = (h ->> 'party')::uuid
            WHERE v.title_id = pg_temp.title('c1')::uuid AND v.version_no = 2), 'DEMO-ID-SELLER-01', '... the seller stays in its history');
SELECT is((SELECT count(*)::int FROM land_title WHERE application_id = '00000000-0000-0000-0000-00000000d001'), 0,
    'the title keeps its identity and number: no title is created (D28)');
SELECT is((SELECT status FROM pending_transaction WHERE id = pg_temp.c('dep1')::uuid), 'REGISTERED', 'the case''s deposit is completed');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), 'APPROVED', 'the case stays APPROVED');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT kind || ':' || title_number
    FROM snigf.application_registration('00000000-0000-0000-0000-00000000d001') $q$), 'TRANSFER:' || pg_temp.number('c1'),
    'the applicant reads that the transfer is registered on their title');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f4') $$, 'RG001', 'CASE_ALREADY_REGISTERED', 'never a second TRANSFER');
SELECT ok(NOT case_transfer_open('00000000-0000-0000-0000-00000000d001'), 'the case no longer holds its title for another case');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT bool_and(matches)::text FROM snigf.title_versions_check(%L) $q$,
    pg_temp.title('c1'))), 'true', 'T1''s versions recompute as written');

-- --- 8. Privileges ---------------------------------------------------------------------------------------------------------------

SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.register_transfer_case_problems(uuid, uuid)', 'EXECUTE')
          AND NOT has_function_privilege('snigf_app', 'snigf.case_transferor_match(uuid, uuid, int)', 'EXECUTE')
          AND NOT has_function_privilege('snigf_app', 'snigf.deposit_case_resolve(snigf.land_title, text)', 'EXECUTE'),
    'the application role never calls the inner checks');
SELECT ok(has_function_privilege('snigf_app', 'snigf.register_propose_transfer_from_case(uuid, text)', 'EXECUTE'),
    '... only the act itself');

SELECT * FROM finish();
ROLLBACK;
