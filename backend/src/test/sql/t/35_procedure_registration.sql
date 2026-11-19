-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Procedure A's registration (Slice 4, V34, ADR-042 step 5, §4, D27 to D29): a CREATION proposed from an approved case
-- only, by a conservator of its office who never handled it, once per case, on a parcel no active title rests on; never
-- from a procedure B case nor from the generic type; checked again when it is signed, under the case's lock; registered
-- by one conservator as an IMMATRICULATION title naming its case; the case stays APPROVED; the applicant reads the title's
-- number and nothing else; an objection is no longer received once the title is registered; no other origin can be
-- written.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(77);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; c1 intake (completeness); c2 reviewer; c4 decision authority; c5 intake (objections at the
-- counter, and the second officer on the case's persons); c6 supervisor; k1 and k4 conservators who never handled the case
-- (the two signers); k2 conservator and intake officer, who publishes (a handler); k3 conservator at DEMO2; b3 applicant.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'i2@demo.invalid', 'Counter', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c6', 'p1@demo.invalid', 'Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'k1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f2', 'k2@demo.invalid', 'Conservator Publisher', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f3', 'k3@demo.invalid', 'Conservator Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f4', 'k4@demo.invalid', 'Conservator Four', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c6', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f2', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f3', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f4', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000f1', 'One'),
    ('h2', '00000000-0000-0000-0000-0000000000f2', 'Two'),
    ('h4', '00000000-0000-0000-0000-0000000000f4', 'Four');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'),
    ('k2', 'h2', '\x0202', false, false, 'Key two'),
    ('k4', 'h4', '\x0404', false, false, 'Key four');
-- A short procedure A type, a procedure B type (naming its title, V37) and a type without a procedure.
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_P', 1, true, 'Procédure A publiée (DEMO)', 'Procedure A published (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "PUBLICATION"], "registers": "CREATION", "delivery": true}}'),
    ('DEMO_PROC_B', 1, true, 'Procédure B (DEMO)', 'Procedure B (DEMO)',
     '{"fields": [{"key": "titleNumber", "kind": "TEXT", "required": true, "maxLength": 40, "options": null,
                   "labelFr": "Titre", "labelEn": "Title", "hintFr": null, "hintEn": null}], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "B", "stages": ["TITLE_VERIFICATION", "DUTIES_EVIDENCE"], "registers": "TRANSFER", "delivery": true}}'),
    ('DEMO_GENERIC', 1, true, 'Générique (DEMO)', 'Generic (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}]}');

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
CREATE FUNCTION pg_temp.rec(p_actor text, p_stage text, p_finding text, p_details text DEFAULT '{}') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_stage_record_add('00000000-0000-0000-0000-00000000d001', %L, 'COMPLETED',
        %L, %L::jsonb, '{}', NULL, NULL, '[]'::jsonb) $q$, p_stage, p_finding, p_details))
$f$;
CREATE FUNCTION pg_temp.object(p_actor text, p_key text, p_name text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT objection_id::text FROM snigf.procedure_objection_add('00000000-0000-0000-0000-00000000d001',
        %L::jsonb, 'The northern boundary takes my well (synthetic)', '[]'::jsonb, %L::uuid) $q$,
        jsonb_build_object('name', p_name, 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-0042', 'interest', 'NEIGHBOUR'), p_key))
$f$;
CREATE FUNCTION pg_temp.dismiss(p_objection text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val('00000000-0000-0000-0000-0000000000c6', format($q$ SELECT snigf.procedure_objection_decide(%L, 'DISMISSED',
        'The well lies outside the parcel as surveyed (synthetic)', '{}'::text[], NULL) $q$, p_objection))
$f$;
CREATE FUNCTION pg_temp.claim(p_actor text, p_stage text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L) $q$, p_stage, p_actor, p_actor))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text, p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = %L $q$, p_state, p_actor, p_case))
$f$;
-- A proposal from the case, by p_actor; the holders come from the decided ownership statement (ADR-043).
CREATE FUNCTION pg_temp.propose(p_actor text, p_case text DEFAULT '00000000-0000-0000-0000-00000000d001',
                                p_reason text DEFAULT 'Approved immatriculation, procedure complete (synthetic)') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.register_propose_from_case(%L, %L)::text $q$, p_case, p_reason))
$f$;
CREATE FUNCTION pg_temp.problems(p_case text) RETURNS text
LANGUAGE sql AS $f$
    SELECT array_to_string(register_case_problems(p_case::uuid, NULL), ',')
$f$;
-- Evidence as the application stores it: the signature itself is the application's to verify (as in 23).
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
-- One signing act (register_register, register_request_confirmation or register_confirm) by p_actor with their key.
CREATE FUNCTION pg_temp.sign(p_act text, p_actor text, p_credential text, p_key bytea, p_inscription text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($s$SELECT snigf.%I(%L, %L::bytea, %L::jsonb)::text$s$, p_act, p_inscription,
        snigf.inscription_canonical(p_inscription::uuid), pg_temp.sig(p_inscription::uuid, p_credential, p_key)))
$f$;
CREATE FUNCTION pg_temp.queue(p_actor text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, $q$ SELECT coalesce(string_agg(reference || ':' || array_to_string(problems, '|') || ':' || may_propose, ','), '')
        FROM snigf.register_case_queue(50, 0) $q$)
$f$;

CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;

-- A parcel at DEMO1; d001, a procedure A case under review with one stored document; d002 (procedure B, on a synthetic
-- title of a second parcel) and d003 (no procedure) under review; d004, a second procedure A case on the same parcel,
-- submitted.
DO $$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[910000,400000],[910100,400000],[910100,400100],[910000,400100],[910000,400000]]]}', 32632);
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-000000005001', '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
    r := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[911000,400000],[911100,400000],[911100,400100],[911000,400100],[911000,400000]]]}', 32632);
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004002', 'DEMO-PCL-DEMO1-2026-000002-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-000000005002', '00000000-0000-0000-0000-000000004002', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END $$;
-- The synthetic title d002 names, its holder the seller.
INSERT INTO ctx SELECT 'seller', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Seller', 'NATIONAL_ID', 'DEMO-ID-SELLER-01',
        NULL, NULL, NULL, NULL)::text $s$);
INSERT INTO ctx SELECT 'b_title', pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($s$ SELECT snigf.register_propose(
    '00000000-0000-0000-0000-0000000000a1', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH-B', 'A synthetic title to transfer', ARRAY['DEMO-E'], NULL,
    NULL, NULL, 10000, '00000000-0000-0000-0000-000000005002', %L::jsonb, NULL)::text $s$,
    jsonb_build_array(jsonb_build_object('party', pg_temp.c('seller'), 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1))));
DO $$ BEGIN
    PERFORM pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('b_title'));
END $$;
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT * FROM (VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO_PROC_P', '000001'),
                                   ('00000000-0000-0000-0000-00000000d002', 'DEMO_PROC_B', '000002'),
                                   ('00000000-0000-0000-0000-00000000d003', 'DEMO_GENERIC', '000003'),
                                   ('00000000-0000-0000-0000-00000000d004', 'DEMO_PROC_P', '000004')) AS x(id, type, n) LOOP
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
            SELECT %L, '00000000-0000-0000-0000-0000000000b3', %L, id FROM snigf.application_type WHERE code = %L $q$, c.id, c.type, c.type));
        IF c.n = '000001' THEN
            PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
                VALUES ('00000000-0000-0000-0000-0000000000e9', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
            PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
                status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
                VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000e9', 1, 'STORED',
                        '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
        END IF;
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'SUBMITTED', declaration = %L::jsonb,
            data = %L::jsonb, parcel_reference = %L, parcel_id = %L,
            office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-%s-X',
            state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = %L $q$,
            CASE WHEN c.type = 'DEMO_PROC_B' THEN tap.transfer_declaration() ELSE tap.self_declaration() END,
            CASE WHEN c.type = 'DEMO_PROC_B'
                 THEN jsonb_build_object('titleNumber', (SELECT number FROM snigf.land_title WHERE creation_inscription_id = pg_temp.c('b_title')::uuid))
                 ELSE '{}'::jsonb END,
            CASE WHEN c.type = 'DEMO_PROC_B' THEN 'DEMO-PCL-DEMO1-2026-000002-Z' ELSE 'DEMO-PCL-DEMO1-2026-000001-Z' END,
            CASE WHEN c.type = 'DEMO_PROC_B' THEN '00000000-0000-0000-0000-000000004002' ELSE '00000000-0000-0000-0000-000000004001' END,
            c.n, c.id));
    END LOOP;
END $$;
DO $$
DECLARE
    c text;
BEGIN
    FOREACH c IN ARRAY ARRAY['00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000d002',
                             '00000000-0000-0000-0000-00000000d003'] LOOP
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.completeness_record (application_id, kind)
            VALUES (%L, 'AUTOMATIC') $q$, c));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW',
            state_changed_by = NULL, version = version + 1 WHERE id = %L $q$, c));
        IF c IN ('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000d002') THEN
            PERFORM tap.parties_ready(c::uuid, '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c5');
        END IF;
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000c1', format($q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
            VALUES (%L, 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$, c));
        PERFORM pg_temp.move('00000000-0000-0000-0000-0000000000c1', 'UNDER_REVIEW', c);
    END LOOP;
END $$;

-- --- Never from a case of another procedure, nor before the approval ---------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d002') $$,
    'RG001', 'PROCEDURE_NOT_CREATION', 'procedure B keeps its title: no CREATION from its case (D28)');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d003') $$,
    'RG001', 'PROCEDURE_NOT_CREATION', 'the generic type registers nothing (D29)');
SELECT is(pg_temp.problems('00000000-0000-0000-0000-00000000d002'), 'PROCEDURE_NOT_CREATION', '... whatever its state');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1') $$,
    'RG001', 'CASE_NOT_APPROVED', 'no CREATION from a case under review');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f1'), '', 'a case under review is not in the queue');

-- The procedure, then the approval.
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'The parcel matches the cadastre') IS NOT NULL,
    'the cadastral review is recorded');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000f2', 'PUBLICATION', 'Posted on the office board',
    '{"objectionPeriod": "PT5M"}') IS NOT NULL, 'k2 publishes the notice');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1') $$,
    'RG001', 'CASE_NOT_APPROVED', 'still not approved while the period runs');
DO $$ BEGIN
    ALTER TABLE snigf.procedure_notice DISABLE TRIGGER procedure_notice_append_only;
    UPDATE snigf.procedure_notice SET opens_at = opens_at - interval '1 day', closes_at = closes_at - interval '1 day';
    ALTER TABLE snigf.procedure_notice ENABLE TRIGGER procedure_notice_append_only;
END $$;
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c2', 'REVIEW')
          + pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Boundaries and occupation are consistent',
                      '00000000-0000-0000-0000-0000000000c2') $q$)
          + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION')
          + pg_temp.claim('00000000-0000-0000-0000-0000000000c4', 'DECISION')
          + pg_temp.act('00000000-0000-0000-0000-0000000000c4', $q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions,
              follows_step_id, decided_by)
              VALUES ('00000000-0000-0000-0000-00000000d001', 'APPROVED', 'The procedure''s record supports it', '{00000000-0000-0000-0000-0000000000e1}',
                      (SELECT id FROM snigf.review_step ORDER BY at DESC LIMIT 1), '00000000-0000-0000-0000-0000000000c4') $q$)
          + pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'APPROVED'), 6, 'the case is reviewed and approved');

-- --- "Approved, to register" ---------------------------------------------------------------------------------------

SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f1'), 'DEMO-APP-2026-000001-X::true',
    'the approved case is in the queue of a conservator of its office, who may propose');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f2'), 'DEMO-APP-2026-000001-X::false',
    '... and in the queue of the conservator who published it, who may not');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f3'), '', '... never in another office''s');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000c1'), '', '... nor in an intake officer''s');

-- --- Who proposes ------------------------------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f3') $$,
    'RG001', 'CASE_NOT_FOUND', 'a conservator of another office does not see the case');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000c1') $$,
    'RG001', 'CASE_NOT_FOUND', 'an intake officer has no register to propose in');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000b3') $$,
    'RG001', 'CASE_NOT_FOUND', '... nor the applicant');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f2') $$,
    'RG001', 'CASE_HANDLER_CANNOT_REGISTER', 'a conservator who recorded a stage of the case never proposes its title');
SELECT ok(register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c2')
          AND register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c4')
          AND register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c1')
          AND register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3')
          AND register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c5'),
    '... nor its reviewer, its decision authority, its intake officer, its applicant or the officer who checked its persons');
SELECT ok(NOT register_case_handler('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000f1'), 'k1 never handled it');

-- --- What a proposal carries -------------------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1', p_reason => 'short') $$,
    'RG001', 'REASON_REQUIRED', 'a proposal says why');

-- An objection received after the approval holds the proposal.
INSERT INTO ctx SELECT 'late', pg_temp.object('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-00000000aa01', 'DEMO Neighbour Essomba');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1') $$,
    'RG001', 'OBJECTION_OPEN', 'an open objection holds the proposal');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f1'), 'DEMO-APP-2026-000001-X:OBJECTION_OPEN:false', '... and the queue says so');
SELECT ok(pg_temp.dismiss(pg_temp.c('late')) IS NOT NULL, 'the objection is dismissed');

INSERT INTO ctx SELECT 'first', pg_temp.propose('00000000-0000-0000-0000-0000000000f1');
SELECT ok(pg_temp.c('first') IS NOT NULL, 'k1 proposes a CREATION from the case');
SELECT is((SELECT kind || ':' || status || ':' || authority_type || ':' || authority_reference || ':' || expected_version
             FROM inscription WHERE id = pg_temp.c('first')::uuid),
    'CREATION:PROPOSED:DECISION:DEMO-APP-2026-000001-X:0', '... proposed, its authority the case''s decision');
SELECT is((SELECT application_id::text FROM inscription WHERE id = pg_temp.c('first')::uuid), '00000000-0000-0000-0000-00000000d001',
    '... naming the case');
SELECT is((SELECT parcel_version_id::text || ':' || (area_m2 = (SELECT area_m2 FROM parcel_version WHERE id = '00000000-0000-0000-0000-000000005001'))
             FROM inscription WHERE id = pg_temp.c('first')::uuid), '00000000-0000-0000-0000-000000005001:true',
    '... on the parcel at its current version, with its area');
SELECT is((SELECT evidence_versions::text FROM inscription WHERE id = pg_temp.c('first')::uuid), '{00000000-0000-0000-0000-0000000000e1}',
    '... its evidence the versions the decision cited');
SELECT ok((SELECT evidence_references @> ARRAY['DEMO-APP-2026-000001-X', 'DECISION:' || d.id::text]
             FROM inscription i, decision d WHERE i.id = pg_temp.c('first')::uuid AND d.application_id = i.application_id),
    '... and its references the case and the decision');
SELECT is((SELECT p.full_name || ':' || p.identity_number || ':' || p.account_id::text || ':' || h.interest || ':' || h.share_num || '/' || h.share_den
                    || ':' || (p.source_identity_id = (case_identity_latest(cp.id)).id)
             FROM inscription_holder h JOIN register_party p ON p.id = h.party_id
             JOIN case_person cp ON cp.application_id = '00000000-0000-0000-0000-00000000d001' AND cp.ref = 'p1'
            WHERE h.inscription_id = pg_temp.c('first')::uuid),
    'DEMO Applicant Owner:DEMO-ID-SELF-0001:00000000-0000-0000-0000-0000000000b3:OWNERSHIP:1/1:true',
    '... the holder the decided statement''s owner, from their checked identity, linked to the applicant''s account as they are its applicant');
SELECT ok((SELECT evidence_references @> ARRAY['OWNERSHIP_STATEMENT:' || d.ownership_statement_id::text]
             FROM inscription i, decision d WHERE i.id = pg_temp.c('first')::uuid AND d.application_id = i.application_id),
    '... and it names the ownership statement the decision approved');
SELECT ok(position(convert_to('00000000-0000-0000-0000-00000000d001', 'UTF8') IN inscription_canonical(pg_temp.c('first')::uuid)) > 0,
    'the signed bytes name the case');
SELECT ok(audit_subjects('INSCRIPTION', pg_temp.c('first')::uuid) @> '[{"type": "APPLICATION", "id": "00000000-0000-0000-0000-00000000d001"}]'
          AND audit_subjects('INSCRIPTION', pg_temp.c('first')::uuid) @> '[{"type": "LAND_TITLE"}]',
    'its audit events belong to the case and to the title');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1') $$,
    'RG001', 'CREATION_ALREADY_PROPOSED', 'one CREATION per case');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f1'), 'DEMO-APP-2026-000001-X:CREATION_ALREADY_PROPOSED:false',
    '... the queue shows the proposal in progress');
SELECT ok((SELECT inscription_id::text = pg_temp.c('first') FROM register_case_queue(50, 0)), '... naming it');
SELECT ok(pg_temp.problems('00000000-0000-0000-0000-00000000d004') LIKE '%PARCEL_CREATION_PENDING%',
    'another case on the same parcel waits for it');
SELECT throws_ok($$ UPDATE inscription SET application_id = NULL WHERE id = pg_temp.c('first')::uuid $$,
    '23001', 'the case of an inscription never changes', 'the case is never taken off, even by the schema owner');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000f1', $q$ INSERT INTO snigf.inscription (new_title_id, office_id, kind,
    expected_version, authority_type, authority_reference, reason, evidence_references, proposed_by, application_id)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', 'CREATION', 0, 'DECISION', 'DEMO-APP', 'A direct insert of a case',
            '{DEMO}', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001') $q$) $$,
    '42501', NULL, 'the application never writes an inscription itself');

-- Withdrawn, the slot frees; proposed again.
SELECT lives_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT snigf.register_close_proposal(%L, 'WITHDRAWN',
    'The holder''s identity number was mistyped') $q$, pg_temp.c('first'))) $$, 'the proposer withdraws it');
INSERT INTO ctx SELECT 'second', pg_temp.propose('00000000-0000-0000-0000-0000000000f1');
SELECT ok(pg_temp.c('second') IS NOT NULL AND pg_temp.c('second') <> pg_temp.c('first'), '... and a new proposal follows');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('first')::uuid), 'WITHDRAWN', '... the first kept as it was');

-- --- Registration: two signatures (ADR-043 D40), each checked against the case ---------------------------------------

INSERT INTO ctx SELECT 'late2', pg_temp.object('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-00000000aa02', 'DEMO Neighbour Owona');
SELECT throws_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('second')) $$,
    'RG001', 'OBJECTION_OPEN', 'an objection received after the proposal holds even the first signature');
SELECT ok(pg_temp.dismiss(pg_temp.c('late2')) IS NOT NULL, 'it is dismissed');
SELECT throws_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('second')) $$,
    'RG001', 'DUAL_CONTROL_REQUIRED', 'a CREATION from a case is never registered by one signature (D40)');
SELECT throws_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('second')) $$,
    'RG001', 'NOT_THE_PROPOSER', 'the first signature is the proposer''s');
SELECT lives_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('second')) $$,
    'the proposer signs first and asks for a confirmation');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('second')::uuid), 'CONFIRMATION_PENDING', '... which is pending');
SELECT throws_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('second')) $$,
    'RG001', 'SELF_CONFIRMATION', 'the proposer never confirms');
SELECT throws_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f2', 'k2', '\x0202', pg_temp.c('second')) $$,
    'RG001', 'CASE_HANDLER_CANNOT_REGISTER', 'a conservator who handled the case never confirms, even with their own key');
SELECT throws_ok($$ DO $d$ BEGIN
    INSERT INTO snigf.case_party_check (application_id, target_kind, target_id, outcome, checked_by)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'IDENTITY', gen_random_uuid(), 'MATCHES', '00000000-0000-0000-0000-0000000000f1');
    PERFORM pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('second'));
END $d$ $$, 'RG001', 'CASE_HANDLER_CANNOT_REGISTER', 'a proposer who came to handle the case since is caught at the second signature');
SELECT throws_ok($$ DO $d$ BEGIN
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, change_reason, created_by)
    SELECT gen_random_uuid(), parcel_id, 2, geom, area_m2, source_srid, repeat('b', 64), locality, 'The boundary was surveyed again',
           '00000000-0000-0000-0000-0000000000b1' FROM snigf.parcel_version WHERE id = '00000000-0000-0000-0000-000000005001';
    UPDATE snigf.parcel SET current_version_no = 2 WHERE id = '00000000-0000-0000-0000-000000004001';
    PERFORM pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('second'));
END $d$ $$, 'RG001', 'STALE_PROPOSAL', 'a parcel corrected after the proposal makes it stale (ADR-043)');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('second')::uuid), 'CONFIRMATION_PENDING', 'nothing refused registered anything');
SELECT is((SELECT count(*)::int FROM land_title WHERE application_id = '00000000-0000-0000-0000-00000000d001'), 0, '... and created no title');

SELECT lives_ok($$ SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('second')) $$,
    'a second conservator who never handled the case confirms it');
SELECT is((SELECT string_agg(act || ':' || signer_id::text, ',' ORDER BY act) FROM inscription_signature WHERE inscription_id = pg_temp.c('second')::uuid),
    'CONFIRMED:00000000-0000-0000-0000-0000000000f4,PROPOSED:00000000-0000-0000-0000-0000000000f1', 'two signatures, by two conservators');
SELECT is((SELECT origin || ':' || application_id::text || ':' || (number ~ '^DEMO-TF-DEMO1-[0-9]{6}-[A-Z]$')
             FROM land_title WHERE creation_inscription_id = pg_temp.c('second')::uuid),
    'IMMATRICULATION:00000000-0000-0000-0000-00000000d001:true', 'the title is an IMMATRICULATION naming its case');
SELECT is((SELECT status || ':' || number FROM inscription WHERE id = pg_temp.c('second')::uuid), 'REGISTERED:1', '... its first inscription');
SELECT is((SELECT v.status || ':' || jsonb_array_length(v.holders) || ':' || (v.holders -> 0 ->> 'interest')
             FROM title_version v JOIN land_title t ON t.id = v.title_id WHERE t.application_id = '00000000-0000-0000-0000-00000000d001'),
    'ACTIVE:1:OWNERSHIP', '... its first version, the applicant as holder');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), 'APPROVED', 'a case is not a title: it stays APPROVED');
SELECT is((SELECT count(*)::int FROM issued_document WHERE subject_id = '00000000-0000-0000-0000-00000000d001'), 0,
    'registration issues no document (D27, D29)');

-- --- Afterwards ---------------------------------------------------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT title_number FROM snigf.application_registration(
    '00000000-0000-0000-0000-00000000d001') $q$), (SELECT number FROM land_title WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'the applicant reads the title''s number');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f3', $q$ SELECT count(*)::text FROM snigf.application_registration(
    '00000000-0000-0000-0000-00000000d001') $q$), '0', '... another office does not');
SELECT is(pg_temp.queue('00000000-0000-0000-0000-0000000000f1'), '', 'the registered case leaves the queue');
SELECT throws_ok($$ SELECT pg_temp.propose('00000000-0000-0000-0000-0000000000f1') $$,
    'RG001', 'CASE_ALREADY_REGISTERED', 'no second CREATION once registered');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-00000000aa03', 'DEMO Neighbour Late') $$,
    'PC001', 'CASE_REGISTERED', 'no objection is received once the title is registered');
SELECT ok(pg_temp.problems('00000000-0000-0000-0000-00000000d004') LIKE '%PARCEL_ALREADY_TITLED%',
    'a parcel under an active title takes no second CREATION');
SELECT ok(audit_subjects('LAND_TITLE', (SELECT id FROM land_title WHERE application_id = '00000000-0000-0000-0000-00000000d001'))
          @> '[{"type": "APPLICATION", "id": "00000000-0000-0000-0000-00000000d001"}]', 'the title''s events belong to the case too');

-- --- No other origin can be written --------------------------------------------------------------------------------------

SELECT throws_ok($$ INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id)
    VALUES (gen_random_uuid(), 'DEMO-TF-DEMO1-999991-A', '00000000-0000-0000-0000-0000000000a1', 'IMMATRICULATION', gen_random_uuid()) $$,
    '23514', NULL, 'an IMMATRICULATION title names its case');
SELECT throws_ok($$ INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id, application_id)
    VALUES (gen_random_uuid(), 'DEMO-TF-DEMO1-999992-A', '00000000-0000-0000-0000-0000000000a1', 'SYNTHETIC_DEMO', gen_random_uuid(),
            '00000000-0000-0000-0000-00000000d004') $$,
    '23514', NULL, 'a synthetic title names none');
SELECT throws_ok($$ INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id, application_id)
    VALUES (gen_random_uuid(), 'DEMO-TF-DEMO1-999993-A', '00000000-0000-0000-0000-0000000000a1', 'MORCELLEMENT', gen_random_uuid(),
            '00000000-0000-0000-0000-00000000d004') $$,
    '23514', NULL, 'MORCELLEMENT stays refused');
SELECT throws_ok($$ INSERT INTO inscription (new_title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
    evidence_references, proposed_by, application_id, title_id)
    SELECT NULL, '00000000-0000-0000-0000-0000000000a1', 'TRANSFER', 1, 'DECISION', 'DEMO-APP', 'A transfer from a case', '{DEMO}',
           '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001', id FROM land_title LIMIT 1 $$,
    'RG001', 'CASE_DEPOSIT_MISMATCH', 'a TRANSFER names a case only completing that case''s deposit on its title (V37)');
SELECT is((SELECT count(*)::int FROM land_title WHERE origin = 'SYNTHETIC_DEMO' AND application_id IS NOT NULL), 0, 'no synthetic title names a case');

-- --- Privileges -----------------------------------------------------------------------------------------------------------

SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.register_case_problems(uuid, uuid)', 'EXECUTE'), 'the checks are not the application''s to call');
SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.register_case_handler(uuid, uuid)', 'EXECUTE'), '... nor the handler rule');
SELECT ok(has_function_privilege('snigf_app', 'snigf.register_propose_from_case(uuid, text)', 'EXECUTE')
          AND has_function_privilege('snigf_app', 'snigf.register_case_queue(int, int)', 'EXECUTE')
          AND has_function_privilege('snigf_app', 'snigf.application_registration(uuid)', 'EXECUTE'), 'the proposal, the queue and the reading are');
SELECT ok(NOT has_column_privilege('snigf_app', 'snigf.inscription', 'application_id', 'UPDATE'), 'the application never sets a case on an inscription');

SELECT * FROM finish();
ROLLBACK;
