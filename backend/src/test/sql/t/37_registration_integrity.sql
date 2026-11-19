-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The corrections of the owner's review of ADR-043 step 5a (14 Nov 2026), V35: the rights (sole ownership, co-ownership,
-- usufruct and bare ownership with the usufruct's term, partial dismemberment refused), for a case and for the register
-- alike; an identity document's validity told apart from the transcription's accuracy, and the DEMO exception policy for
-- an expired one; the conservators' read-only evidence; a retention class for every case document, legal holds on a
-- document and on a whole case, covering documents added later, disposal refused; a CREATION from a case bound to its
-- decision, its statement, its parcel and its holders' identities, any change between its signatures refused
-- (STALE_PROPOSAL), a first signature never reusable, and a party changed behind the signatures detectable.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(79);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; i1 intake (records); i2 intake (checks); sx supervisor and intake (records a correction, so never
-- authorises its exception); sp supervisor (authorises exceptions); rv reviewer; da decision authority; k1 and k2
-- conservators (the two signers); i3 intake at DEMO2; k3 conservator at DEMO2; ad system administrator (legal holds);
-- b3 the applicant.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000e1', 'i1@demo.invalid', 'Intake One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e2', 'i2@demo.invalid', 'Intake Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e3', 'i3@demo.invalid', 'Intake Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e4', 'sp@demo.invalid', 'Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e7', 'sx@demo.invalid', 'Supervisor Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e5', 'rv@demo.invalid', 'Reviewer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e6', 'da@demo.invalid', 'Decision', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e8', 'ad@demo.invalid', 'Administrator', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'k1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f2', 'k2@demo.invalid', 'Conservator Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f3', 'k3@demo.invalid', 'Conservator Elsewhere', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e2', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e3', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e4', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e7', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e7', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e5', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e6', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e8', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f3', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a2', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000f1', 'One'), ('h2', '00000000-0000-0000-0000-0000000000f2', 'Two');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'), ('k2', 'h2', '\x0202', false, false, 'Key two');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_R', 1, true, 'Procédure A courte (DEMO)', 'Short procedure A (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_IDENTITY", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW"], "registers": "CREATION", "delivery": true}}');

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
CREATE FUNCTION pg_temp.r(p jsonb) RETURNS text LANGUAGE sql AS $f$ SELECT coalesce(holder_rights_problem(p), 'OK') $f$;
CREATE FUNCTION pg_temp.h(p_interest text, p_num int, p_den int, p_extra jsonb DEFAULT '{}') RETURNS jsonb LANGUAGE sql AS $f$
    SELECT jsonb_build_object('interest', p_interest, 'shareNum', p_num, 'shareDen', p_den) || p_extra
$f$;
CREATE FUNCTION pg_temp.pid(p_ref text) RETURNS uuid LANGUAGE sql AS $f$
    SELECT id FROM case_person WHERE application_id = '00000000-0000-0000-0000-00000000d001' AND ref = p_ref
$f$;
CREATE FUNCTION pg_temp.latest(p_ref text) RETURNS uuid LANGUAGE sql AS $f$ SELECT (case_identity_latest(pg_temp.pid(p_ref))).id $f$;
CREATE FUNCTION pg_temp.problems() RETURNS text LANGUAGE sql AS $f$
    SELECT array_to_string(case_party_problems('00000000-0000-0000-0000-00000000d001'), ',')
$f$;
CREATE FUNCTION pg_temp.file(p_actor text, p_purpose text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_party_file_add('00000000-0000-0000-0000-00000000d001', %L, md5(gen_random_uuid()::text),
        repeat('c', 64), 10, 'application/pdf', 'scan.pdf')::text $q$, p_purpose))
$f$;
CREATE FUNCTION pg_temp.identity(p_actor text, p_ref text, p_valid date, p_reason text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_identity_record(%L, %L::jsonb, (SELECT id FROM snigf.case_party_file
        WHERE application_id = '00000000-0000-0000-0000-00000000d001' AND purpose = 'IDENTITY_DOCUMENT' LIMIT 1), NULL, %L::uuid, %L)::text $q$,
        pg_temp.pid(p_ref), jsonb_strip_nulls(jsonb_build_object('fullName', (case_identity_latest(pg_temp.pid(p_ref))).full_name,
        'identityKind', 'NATIONAL_ID', 'identityNumber', (case_identity_latest(pg_temp.pid(p_ref))).identity_number,
        'phone', (case_identity_latest(pg_temp.pid(p_ref))).phone, 'validUntil', p_valid)), pg_temp.latest(p_ref), p_reason))
$f$;
CREATE FUNCTION pg_temp.check(p_actor text, p_kind text, p_target uuid) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_party_check_record(%L, %L, 'MATCHES', NULL)::text $q$, p_kind, p_target))
$f$;
CREATE FUNCTION pg_temp.exception(p_actor text, p_target uuid, p_policy text, p_reason text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_party_exception_record(%L, %L, %L)::text $q$, p_target, p_policy, p_reason))
$f$;
CREATE FUNCTION pg_temp.sig(p_inscription uuid, p_credential text, p_key bytea) RETURNS jsonb
LANGUAGE plpgsql AS $f$
DECLARE
    canon bytea := snigf.inscription_canonical(p_inscription);
    nonce bytea := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
BEGIN
    RETURN jsonb_build_object('credentialId', p_credential, 'publicKey', encode(p_key, 'base64'), 'nonce', encode(nonce, 'base64'),
        'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
        'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
        'officerSignature', encode('\x0a0b'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA');
END;
$f$;
CREATE FUNCTION pg_temp.sign(p_act text, p_actor text, p_credential text, p_key bytea, p_inscription text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($s$SELECT snigf.%I(%L, %L::bytea, %L::jsonb)::text$s$, p_act, p_inscription,
        snigf.inscription_canonical(p_inscription::uuid), pg_temp.sig(p_inscription::uuid, p_credential, p_key)))
$f$;
CREATE FUNCTION pg_temp.confirm() RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f2', 'k2', '\x0202', pg_temp.c('creation'))
$f$;

-- --- 1. The rights ---------------------------------------------------------------------------------------------------

SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('OWNERSHIP', 1, 1))), 'OK', 'sole ownership: one holder of the whole');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('OWNERSHIP', 1, 2))), 'SOLE_OWNERSHIP_INVALID', '... never a fraction');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('OWNERSHIP', 1, 1), pg_temp.h('OWNERSHIP', 1, 1))), 'SOLE_OWNERSHIP_INVALID', '... never two sole owners');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('CO_OWNERSHIP', 2, 3), pg_temp.h('CO_OWNERSHIP', 1, 3))), 'OK', 'co-ownership: fractions totalling one');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('CO_OWNERSHIP', 1, 1))), 'CO_OWNERSHIP_INVALID', '... of two holders or more');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('CO_OWNERSHIP', 1, 1), pg_temp.h('CO_OWNERSHIP', 1, 2))), 'CO_OWNERSHIP_INVALID',
    '... each below the whole');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('CO_OWNERSHIP', 1, 2), pg_temp.h('CO_OWNERSHIP', 1, 3))), 'SHARES_MUST_TOTAL_ONE', '... exactly one');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('OWNERSHIP', 1, 2), pg_temp.h('CO_OWNERSHIP', 1, 2))), 'RIGHTS_INCONSISTENT',
    'sole ownership and co-ownership are never mixed: they are not interchangeable');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "LIFE"}'), pg_temp.h('BARE_OWNERSHIP', 1, 2),
    pg_temp.h('BARE_OWNERSHIP', 1, 2))), 'OK', 'usufruct for life and bare ownership in halves: each group totals one');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "UNTIL", "usufructUntil": "2040-12-31"}'),
    pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'OK', '... or until a date');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 2, '{"usufructTerm": "LIFE"}'), pg_temp.h('USUFRUCT', 1, 3, '{"usufructTerm": "LIFE"}'),
    pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'SHARES_MUST_TOTAL_ONE', 'the usufruct totals one on its own');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1), pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'USUFRUCT_TERM_INVALID',
    'a usufruct has its term');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "UNTIL"}'), pg_temp.h('BARE_OWNERSHIP', 1, 1))),
    'USUFRUCT_TERM_INVALID', '... UNTIL with its date');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "UNTIL", "usufructUntil": "2040-02-30"}'),
    pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'USUFRUCT_TERM_INVALID', '... a real date');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "LIFE"}'), pg_temp.h('BARE_OWNERSHIP', 1, 1, '{"usufructTerm": "LIFE"}'))),
    'USUFRUCT_TERM_INVALID', 'no other right has a term');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "LIFE"}'))), 'PARTIAL_DISMEMBERMENT_NOT_SUPPORTED',
    'a usufruct without bare ownership is not supported');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'PARTIAL_DISMEMBERMENT_NOT_SUPPORTED', '... nor bare ownership alone');
SELECT is(pg_temp.r(jsonb_build_array(pg_temp.h('CO_OWNERSHIP', 1, 2), pg_temp.h('USUFRUCT', 1, 1, '{"usufructTerm": "LIFE"}'),
    pg_temp.h('BARE_OWNERSHIP', 1, 1))), 'PARTIAL_DISMEMBERMENT_NOT_SUPPORTED', '... nor full ownership of a part beside a dismembered part');
SELECT is(holder_shares_total_one(jsonb_build_array(pg_temp.h('OWNERSHIP', 1, 2))), false, 'the register''s own check follows the same rules');

-- The register: a synthetic title in usufruct and bare ownership keeps the term, in its state and its signed bytes.
INSERT INTO ctx SELECT 'usu', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Widow Usufruct', 'NATIONAL_ID', 'DEMO-ID-U', NULL, NULL, NULL, NULL)::text $s$);
INSERT INTO ctx SELECT 'bare', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $s$
    SELECT snigf.register_party_create('00000000-0000-0000-0000-0000000000a1', 'NATURAL_PERSON', 'DEMO Child Bare', 'NATIONAL_ID', 'DEMO-ID-B', NULL, NULL, NULL, NULL)::text $s$);
DO $$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[930000,400000],[930100,400000],[930100,400100],[930000,400100],[930000,400000]]]}', 32632);
    r2 snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[931000,400000],[931100,400000],[931100,400100],[931000,400100],[931000,400000]]]}', 32632);
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by) VALUES
        ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1'),
        ('00000000-0000-0000-0000-000000004002', 'DEMO-PCL-DEMO1-2026-000002-Z', '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by) VALUES
        ('00000000-0000-0000-0000-000000005001', '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic', '00000000-0000-0000-0000-0000000000b1'),
        ('00000000-0000-0000-0000-000000005002', '00000000-0000-0000-0000-000000004002', 1, r2.geom, r2.area_m2, 32632, repeat('a', 64), 'Synthetic', '00000000-0000-0000-0000-0000000000b1');
END $$;
INSERT INTO ctx SELECT 'usu_creation', pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($s$ SELECT snigf.register_propose(
    '00000000-0000-0000-0000-0000000000a1', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH-U', 'A synthetic dismembered title', ARRAY['DEMO-E'], NULL,
    NULL, NULL, 10000, '00000000-0000-0000-0000-000000005002', %L::jsonb, NULL)::text $s$,
    jsonb_build_array(jsonb_build_object('party', pg_temp.c('usu'), 'interest', 'USUFRUCT', 'shareNum', 1, 'shareDen', 1, 'usufructTerm', 'LIFE'),
                      jsonb_build_object('party', pg_temp.c('bare'), 'interest', 'BARE_OWNERSHIP', 'shareNum', 1, 'shareDen', 1))));
SELECT ok(position(convert_to('usufruct', 'UTF8') IN inscription_canonical(pg_temp.c('usu_creation')::uuid)) > 0
          AND position(convert_to('LIFE', 'UTF8') IN inscription_canonical(pg_temp.c('usu_creation')::uuid)) > 0, 'the usufruct''s term is signed');
SELECT ok(position(convert_to('DEMO-ID-U', 'UTF8') IN inscription_canonical(pg_temp.c('usu_creation')::uuid)) > 0,
    'so are the holders'' identities, for every inscription proposed from V35 on');
SELECT lives_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('usu_creation')) $$,
    'a synthetic CREATION keeps one signature');
SELECT is((SELECT string_agg(x ->> 'interest' || ':' || coalesce(x ->> 'usufructTerm', '-'), ',' ORDER BY x ->> 'interest')
             FROM title_version v, jsonb_array_elements(v.holders) x
            WHERE v.inscription_id = pg_temp.c('usu_creation')::uuid), 'BARE_OWNERSHIP:-,USUFRUCT:LIFE', 'the title''s state keeps the term');
SELECT ok((SELECT bool_and(matches) FROM title_versions_check((SELECT title_id FROM inscription WHERE id = pg_temp.c('usu_creation')::uuid))),
    '... and recomputes as written');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($s$ SELECT snigf.register_propose(
    '00000000-0000-0000-0000-0000000000a1', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH-U', 'A synthetic partial dismemberment', ARRAY['DEMO-E'], NULL,
    NULL, NULL, 10000, '00000000-0000-0000-0000-000000005002', %L::jsonb, NULL)::text $s$,
    jsonb_build_array(jsonb_build_object('party', pg_temp.c('usu'), 'interest', 'USUFRUCT', 'shareNum', 1, 'shareDen', 1, 'usufructTerm', 'LIFE')))) $$,
    'RG001', 'PARTIAL_DISMEMBERMENT_NOT_SUPPORTED', 'the register refuses partial dismemberment too');

-- --- 2. A case: two co-owners, one of them the applicant and the other's representative ---------------------------------

SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_R', id FROM snigf.application_type WHERE code = 'DEMO_PROC_R' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000d9', '00000000-0000-0000-0000-00000000d001', 'DEMO_IDENTITY') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
    status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000d8', '00000000-0000-0000-0000-0000000000d9', 1, 'STORED',
            '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
SELECT is((SELECT retention_class FROM document_version WHERE id = '00000000-0000-0000-0000-0000000000d8'), 'APPLICATION_IDENTITY_DOCUMENT',
    'an applicant''s document takes the class of its category');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
    declaration = '{"capacity": "ON_BEHALF_OF_ONE", "persons": [
        {"ref": "p1", "kind": "NATURAL_PERSON", "roles": ["APPLICANT", "REPRESENTATIVE", "INTENDED_OWNER"], "fullName": "DEMO Heir Applicant",
         "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-H1", "phone": "+237 690000009"},
        {"ref": "p2", "kind": "NATURAL_PERSON", "roles": ["INTENDED_OWNER"], "fullName": "DEMO Heir Two", "identityKind": "NATIONAL_ID",
         "identityNumber": "DEMO-ID-H2"}],
      "shares": [{"person": "p1", "interest": "CO_OWNERSHIP", "shareNum": 1, "shareDen": 2},
                 {"person": "p2", "interest": "CO_OWNERSHIP", "shareNum": 1, "shareDen": 2}],
      "mandates": [{"representative": "p1", "represents": ["p2"], "scope": "APPLY"}]}'::jsonb,
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.completeness_record (application_id, kind)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'AUTOMATIC') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL,
    version = version + 1 $q$);
SELECT tap.parties_ready('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000e2');
SELECT is(pg_temp.problems(), '', 'transcribed and checked');
SELECT is((SELECT string_agg(retention_class, ',' ORDER BY retention_class) FROM case_party_file WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'CASE_IDENTITY_DOCUMENT,CASE_MANDATE,CASE_OWNERSHIP_EVIDENCE', 'each counter document has the class of its purpose');

-- --- 3. A document's validity is not the transcription's accuracy ------------------------------------------------------

SELECT ok(pg_temp.identity('00000000-0000-0000-0000-0000000000e7', 'p2', '2020-01-31', 'The card''s expiry date was added') IS NOT NULL,
    'an identity is transcribed with its document''s expiry date');
SELECT is((SELECT valid_until::text || ':' || expired_at_transcription FROM case_person_identity WHERE id = pg_temp.latest('p2')), '2020-01-31:true',
    '... which had passed on the day it was transcribed');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p2')) IS NOT NULL,
    'the transcription can still be found accurate: validity is not accuracy');
SELECT is(pg_temp.problems(), 'IDENTITY_DOCUMENT_EXPIRED:p2', 'but an expired document never silently passes');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT status FROM snigf.application_parties('00000000-0000-0000-0000-00000000d001')
    WHERE ref = 'p2' $q$), 'DOCUMENT_EXPIRED', 'the applicant is told the document has expired');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e2', pg_temp.latest('p2'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
    'Renewal receipt seen; the holder is known to the office') $$, 'PC001', 'PARTY_FORBIDDEN', 'an intake officer authorises no exception');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e7', pg_temp.latest('p2'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
    'Renewal receipt seen; the holder is known to the office') $$, 'PC001', 'RECORDER_CANNOT_AUTHORISE', '... nor the supervisor who transcribed it');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e4', pg_temp.latest('p2'), 'ANY_REASON',
    'Renewal receipt seen; the holder is known to the office') $$, 'PC001', 'POLICY_INVALID', 'only under the defined DEMO policy');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e4', pg_temp.latest('p2'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT', 'Seen') $$,
    'PC001', 'REASON_REQUIRED', 'with a written reason');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e4', pg_temp.latest('p1'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
    'Renewal receipt seen; the holder is known to the office') $$, 'PC001', 'EXCEPTION_NOT_APPLICABLE', 'never for a valid document');
SELECT ok(pg_temp.exception('00000000-0000-0000-0000-0000000000e4', pg_temp.latest('p2'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
    'Renewal receipt seen; the holder is known to the office') IS NOT NULL, 'another supervisor authorises the exception');
SELECT is(pg_temp.problems(), '', '... which the case then carries');
SELECT throws_ok($$ SELECT pg_temp.exception('00000000-0000-0000-0000-0000000000e4', pg_temp.latest('p2'), 'DEMO_EXPIRED_IDENTITY_DOCUMENT',
    'Renewal receipt seen; the holder is known to the office') $$, 'PC001', 'ALREADY_AUTHORISED', 'once');
SELECT throws_ok($$ UPDATE case_party_exception SET reason = 'Rewritten reason of enough length' $$, '23001', NULL, 'an exception is never rewritten');

-- Through to the approval.
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000e1') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ UPDATE snigf.application SET state = 'UNDER_REVIEW',
        state_changed_by = '00000000-0000-0000-0000-0000000000e1', version = version + 1 $q$), 2, 'accepted');
SELECT ok(pg_temp.val('00000000-0000-0000-0000-0000000000b1', $q$ SELECT snigf.procedure_stage_record_add('00000000-0000-0000-0000-00000000d001',
    'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre', '{}'::jsonb, '{}', NULL, NULL, '[]'::jsonb)::text $q$) IS NOT NULL, 'reviewed');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'REVIEW', '00000000-0000-0000-0000-0000000000e5', '00000000-0000-0000-0000-0000000000e5') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Both heirs'' halves are documented',
                '00000000-0000-0000-0000-0000000000e5') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ UPDATE snigf.application SET state = 'PENDING_DECISION',
        state_changed_by = '00000000-0000-0000-0000-0000000000e5', version = version + 1 $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'DECISION', '00000000-0000-0000-0000-0000000000e6', '00000000-0000-0000-0000-0000000000e6') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions,
        follows_step_id, decided_by) VALUES ('00000000-0000-0000-0000-00000000d001', 'APPROVED', 'Inheritance proven in halves',
        '{00000000-0000-0000-0000-0000000000d8}', (SELECT id FROM snigf.review_step ORDER BY at DESC LIMIT 1), '00000000-0000-0000-0000-0000000000e6') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ UPDATE snigf.application SET state = 'APPROVED',
        state_changed_by = '00000000-0000-0000-0000-0000000000e6', version = version + 1 $q$), 6, 'approved');

-- --- 4. The conservators' evidence -------------------------------------------------------------------------------------

INSERT INTO ctx SELECT 'evidence', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT snigf.register_case_evidence(
    '00000000-0000-0000-0000-00000000d001')::text $q$);
SELECT is((SELECT string_agg(x ->> 'fullName' || ':' || (x ->> 'interest') || ':' || (x ->> 'shareNum') || '/' || (x ->> 'shareDen'), ',' ORDER BY x ->> 'fullName')
             FROM jsonb_array_elements(pg_temp.c('evidence')::jsonb -> 'owners') x),
    'DEMO Heir Applicant:CO_OWNERSHIP:1/2,DEMO Heir Two:CO_OWNERSHIP:1/2', 'a conservator examines the approved owners, rights and shares');
SELECT ok((SELECT bool_and(x -> 'check' ->> 'outcome' = 'MATCHES' AND x ->> 'verification' = 'UNVERIFIED_DEMO' AND x ? 'transcribedBy' AND x ? 'identityVersion')
             FROM jsonb_array_elements(pg_temp.c('evidence')::jsonb -> 'owners') x),
    '... each identity''s version, who transcribed and who checked it, and that it is unverified');
SELECT ok((SELECT x -> 'exception' ->> 'policy' = 'DEMO_EXPIRED_IDENTITY_DOCUMENT' AND (x ->> 'expiredAtTranscription')::boolean
             FROM jsonb_array_elements(pg_temp.c('evidence')::jsonb -> 'owners') x WHERE x ->> 'fullName' = 'DEMO Heir Two'),
    '... the expired document and its exception');
SELECT is(jsonb_array_length(pg_temp.c('evidence')::jsonb -> 'mandates'), 1, '... the mandate');
SELECT ok(pg_temp.c('evidence') NOT LIKE '%phone%' AND pg_temp.c('evidence') NOT LIKE '%+237%' AND pg_temp.c('evidence') NOT LIKE '%postal%',
    '... never a phone number or an address');
SELECT ok(pg_temp.val('00000000-0000-0000-0000-0000000000e1', $q$ SELECT snigf.register_case_evidence('00000000-0000-0000-0000-00000000d001')::text $q$) IS NULL
          AND pg_temp.val('00000000-0000-0000-0000-0000000000f3', $q$ SELECT snigf.register_case_evidence('00000000-0000-0000-0000-00000000d001')::text $q$) IS NULL,
    'nobody else reads it through this function: not intake, not another office''s conservator');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT count(*)::text FROM snigf.case_person_identity $q$), '0',
    '... and the conservator never reads the persons'' tables themselves');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT kind FROM snigf.register_case_evidence_file(
    '00000000-0000-0000-0000-00000000d001', %L) $q$, (SELECT id FROM case_party_file WHERE purpose = 'MANDATE' LIMIT 1))), 'CASE_PARTY_FILE',
    'a cited document can be downloaded');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT count(*)::text FROM snigf.register_case_evidence_file(
    '00000000-0000-0000-0000-00000000d001', gen_random_uuid()) $q$), '0', '... only one of this case');
SELECT throws_ok($$ SELECT pg_temp.file('00000000-0000-0000-0000-0000000000f1', 'IDENTITY_DOCUMENT') $$, 'PC001', 'PARTY_FORBIDDEN',
    'reading grants no writing');

-- --- 5. Retention and legal holds ---------------------------------------------------------------------------------------

INSERT INTO ctx SELECT 'case_hold', pg_temp.val('00000000-0000-0000-0000-0000000000e8', $q$ SELECT snigf.legal_hold_place('CASE',
    '00000000-0000-0000-0000-00000000d001', 'A boundary dispute is before the court (synthetic)')::text $q$);
SELECT ok(pg_temp.c('case_hold') IS NOT NULL, 'a legal hold is placed on a whole case');
SELECT ok((SELECT bool_and(EXISTS (SELECT 1 FROM record_holds('CASE_PARTY_FILE', f.id))) FROM case_party_file f
            WHERE f.application_id = '00000000-0000-0000-0000-00000000d001')
          AND EXISTS (SELECT 1 FROM record_holds('APPLICATION_DOCUMENT', '00000000-0000-0000-0000-0000000000d8')),
    '... it covers every document of the case');
INSERT INTO ctx SELECT 'late_file', pg_temp.file('00000000-0000-0000-0000-0000000000e1', 'OWNERSHIP_EVIDENCE');
SELECT ok(EXISTS (SELECT 1 FROM record_holds('CASE_PARTY_FILE', pg_temp.c('late_file')::uuid)), '... and every document added after it');
SELECT ok(pg_temp.val('00000000-0000-0000-0000-0000000000e8', format($q$ SELECT snigf.legal_hold_place('CASE_PARTY_FILE', %L,
    'The mandate is contested on its own (synthetic)')::text $q$, (SELECT id FROM case_party_file WHERE purpose = 'MANDATE' LIMIT 1))) IS NOT NULL,
    'a hold is placed on one document too');
SELECT ok(NOT record_disposal_allowed('CASE_PARTY_FILE', pg_temp.c('late_file')::uuid)
          AND NOT record_disposal_allowed('APPLICATION_DOCUMENT', '00000000-0000-0000-0000-0000000000d8')
          AND NOT record_disposal_allowed('PROCEDURE_STAGE_FILE', gen_random_uuid()),
    'disposal is refused by design: no approved schedule, and a hold in force');
SELECT is((SELECT retention_class FROM case_party_file WHERE id = pg_temp.c('late_file')::uuid), 'CASE_OWNERSHIP_EVIDENCE', '... its class set by the database');
SELECT throws_ok($$ UPDATE document_version SET retention_class = 'CASE_MANDATE' WHERE id = '00000000-0000-0000-0000-0000000000d8' $$,
    '23001', NULL, 'a class is never changed');

-- --- 6. The CREATION's two signatures bind exactly what was approved ---------------------------------------------------

INSERT INTO ctx SELECT 'creation', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT snigf.register_propose_from_case(
    '00000000-0000-0000-0000-00000000d001', 'Approved immatriculation of two heirs (synthetic)')::text $q$);
SELECT is((SELECT (decision_id = (SELECT id FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001'))::text || ':'
                  || (ownership_statement_id = (case_statement_latest('00000000-0000-0000-0000-00000000d001')).id)::text || ':' || ownership_statement_version
             FROM inscription WHERE id = pg_temp.c('creation')::uuid), 'true:true:2', 'the proposal is bound to its decision and its statement');
SELECT ok(position(convert_to((SELECT id::text FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001'), 'UTF8')
                   IN inscription_canonical(pg_temp.c('creation')::uuid)) > 0
          AND position(convert_to((case_statement_latest('00000000-0000-0000-0000-00000000d001')).id::text, 'UTF8') IN inscription_canonical(pg_temp.c('creation')::uuid)) > 0,
    'the signed bytes name the decision and the statement');
SELECT ok(position(convert_to('DEMO-ID-H2', 'UTF8') IN inscription_canonical(pg_temp.c('creation')::uuid)) > 0
          AND position(convert_to(pg_temp.latest('p2')::text, 'UTF8') IN inscription_canonical(pg_temp.c('creation')::uuid)) > 0,
    '... and each holder''s identity and the version it came from');
SELECT lives_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('creation')) $$,
    'the first signature');
SELECT throws_ok($$ DO $d$ BEGIN
    PERFORM pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p1', NULL, 'A late correction of the name');
    PERFORM pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p1'));
    PERFORM pg_temp.confirm();
END $d$ $$, 'RG001', 'STALE_PROPOSAL', 'an owner''s identity changed after the first signature: the proposal is stale');
SELECT throws_ok($$ DO $d$ BEGIN
    PERFORM pg_temp.val('00000000-0000-0000-0000-0000000000e1', format($q$ SELECT snigf.case_ownership_record('00000000-0000-0000-0000-00000000d001',
        %L::jsonb, %L, 'The shares were copied the wrong way round')::text $q$,
        jsonb_build_array(jsonb_build_object('person', pg_temp.pid('p1'), 'interest', 'CO_OWNERSHIP', 'shareNum', 2, 'shareDen', 3, 'basisFile', pg_temp.c('late_file')),
                          jsonb_build_object('person', pg_temp.pid('p2'), 'interest', 'CO_OWNERSHIP', 'shareNum', 1, 'shareDen', 3, 'basisFile', pg_temp.c('late_file'))),
        (case_statement_latest('00000000-0000-0000-0000-00000000d001')).id));
    PERFORM pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'OWNERSHIP', (case_statement_latest('00000000-0000-0000-0000-00000000d001')).id);
    PERFORM pg_temp.confirm();
END $d$ $$, 'RG001', 'STALE_PROPOSAL', '... the shares changed');
SELECT throws_ok($$ DO $d$ BEGIN
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, change_reason, created_by)
    SELECT gen_random_uuid(), parcel_id, 2, geom, area_m2, source_srid, repeat('b', 64), locality, 'The boundary was surveyed again',
           '00000000-0000-0000-0000-0000000000b1' FROM snigf.parcel_version WHERE id = '00000000-0000-0000-0000-000000005001';
    UPDATE snigf.parcel SET current_version_no = 2 WHERE id = '00000000-0000-0000-0000-000000004001';
    PERFORM pg_temp.confirm();
END $d$ $$, 'RG001', 'STALE_PROPOSAL', '... the parcel changed');
SELECT throws_ok($$ DO $d$ BEGIN
    ALTER TABLE snigf.decision DISABLE TRIGGER USER;
    UPDATE snigf.decision SET ownership_statement_id = (SELECT id FROM snigf.case_ownership_statement
        WHERE application_id = '00000000-0000-0000-0000-00000000d001' AND version_no = 1) WHERE application_id = '00000000-0000-0000-0000-00000000d001';
    ALTER TABLE snigf.decision ENABLE TRIGGER USER;
    PERFORM pg_temp.confirm();
END $d$ $$, 'RG001', 'STALE_PROPOSAL', '... the decision no longer names the statement it was proposed under (a decision itself is never replaced: its foreign key keeps it)');
SELECT throws_ok($$ DO $d$
DECLARE
    old inscription_signature;
    fresh text;
BEGIN
    SELECT * INTO old FROM snigf.inscription_signature WHERE inscription_id = pg_temp.c('creation')::uuid AND act = 'PROPOSED';
    PERFORM pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT snigf.register_close_proposal(%L, 'WITHDRAWN',
        'Withdrawn to be proposed anew') $q$, pg_temp.c('creation')));
    fresh := pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT snigf.register_propose_from_case('00000000-0000-0000-0000-00000000d001',
        'Proposed anew after a withdrawal')::text $q$);
    -- The first signature of the withdrawn proposal, replayed on the new one.
    PERFORM pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT snigf.register_request_confirmation(%L, %L::bytea, %L::jsonb)::text $q$,
        fresh, old.canonical, jsonb_build_object('credentialId', old.credential_id, 'publicKey', encode(old.public_key_cose, 'base64'),
        'nonce', encode(old.nonce, 'base64'), 'authenticatorData', encode(old.authenticator_data, 'base64'),
        'clientData', encode(old.client_data, 'base64'), 'officerSignature', encode(old.officer_signature, 'base64'),
        'serverSignature', old.server_signature)));
END $d$ $$, 'RG001', 'CANONICAL_MISMATCH', 'a first signature is never reusable on another proposal');
SELECT throws_ok($$ DO $d$ BEGIN
    ALTER TABLE snigf.register_party DISABLE TRIGGER register_party_append_only;
    UPDATE snigf.register_party SET full_name = 'DEMO Someone Else' WHERE id = (SELECT party_id FROM snigf.inscription_holder
        WHERE inscription_id = pg_temp.c('creation')::uuid LIMIT 1);
    ALTER TABLE snigf.register_party ENABLE TRIGGER register_party_append_only;
    IF snigf.inscription_canonical(pg_temp.c('creation')::uuid) IS DISTINCT FROM (SELECT canonical FROM snigf.inscription_signature
        WHERE inscription_id = pg_temp.c('creation')::uuid AND act = 'PROPOSED') THEN
        RAISE EXCEPTION 'PARTY_CHANGE_DETECTED';
    END IF;
END $d$ $$, 'P0001', 'PARTY_CHANGE_DETECTED', 'a party changed in the database no longer matches the signed bytes: the title''s verification sees it');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('creation')::uuid), 'CONFIRMATION_PENDING', 'nothing refused changed the proposal');
SELECT lives_ok($$ SELECT pg_temp.confirm() $$, 'unchanged, the second conservator confirms it');
SELECT is((SELECT count(*)::int FROM inscription_signature s WHERE s.inscription_id = pg_temp.c('creation')::uuid
             AND s.canonical = inscription_canonical(pg_temp.c('creation')::uuid)), 2, 'both signatures over exactly the same bytes');
SELECT is((SELECT jsonb_array_length(v.holders) FROM title_version v JOIN land_title t ON t.id = v.title_id
            WHERE t.application_id = '00000000-0000-0000-0000-00000000d001'), 2, 'the title holds the two heirs');

-- --- 7. Privileges ----------------------------------------------------------------------------------------------------------

SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.register_case_proposal_stale(uuid)', 'EXECUTE')
          AND NOT has_function_privilege('snigf_app', 'snigf.holder_rights_problem(jsonb)', 'EXECUTE'), 'the checks are not the application''s to call');
SELECT ok(has_function_privilege('snigf_app', 'snigf.register_case_evidence(uuid)', 'EXECUTE')
          AND has_function_privilege('snigf_app', 'snigf.case_party_exception_record(uuid, text, text)', 'EXECUTE'), 'the reading and the exception are');
SELECT ok(NOT has_table_privilege('snigf_app', 'snigf.case_party_exception', 'INSERT'), '... the exception only through its function');

SELECT * FROM finish();
ROLLBACK;
