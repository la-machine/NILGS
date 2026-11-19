-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The persons of a case and its ownership statement (Slice 4, V35, ADR-043 step 5a, D35 to D40, D43): the declaration's
-- rules (capacity, explicit roles, mandates, exact shares, never an equal split by default); the declaration turned into
-- DECLARED records at submission and never changed; transcription at intake from a cited document, append-only, each
-- correction naming what it replaces with a reason; the check by a second officer, never the recorder, MATCHES meaning
-- "corresponds to the document presented"; UNVERIFIED_DEMO on every version; acceptance and approval held until every
-- person, the statement and the mandate are recorded and checked; the decision naming its statement; the registered
-- holders exactly the statement's owners, never the representative; anything changed after the decision stops the
-- registration; two conservators sign; who reads.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(92);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; i1 and i2 intake officers (record and check); sp supervisor (checks only); i3 intake at DEMO2;
-- rv reviewer; da decision authority; k1 and k2 conservators (the two signers); b3 the applicant, a representative.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000e1', 'i1@demo.invalid', 'Intake One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e2', 'i2@demo.invalid', 'Intake Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e3', 'i3@demo.invalid', 'Intake Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e4', 'sp@demo.invalid', 'Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e5', 'rv@demo.invalid', 'Reviewer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000e6', 'da@demo.invalid', 'Decision', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'k1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f2', 'k2@demo.invalid', 'Conservator Two', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e2', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e3', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e4', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e5', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e6', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f2', 'CONSERVATOR', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000f1', 'One'), ('h2', '00000000-0000-0000-0000-0000000000f2', 'Two');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'), ('k2', 'h2', '\x0202', false, false, 'Key two');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_R', 1, true, 'Procédure A courte (DEMO)', 'Short procedure A (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW"], "registers": "CREATION", "delivery": true}}');

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
CREATE FUNCTION pg_temp.dp(p jsonb) RETURNS text LANGUAGE sql AS $f$ SELECT array_to_string(case_declaration_problems(p), ',') $f$;
CREATE FUNCTION pg_temp.person(p_ref text, p_roles text, p_name text, p_phone text DEFAULT NULL) RETURNS jsonb
LANGUAGE sql AS $f$
    SELECT jsonb_strip_nulls(jsonb_build_object('ref', p_ref, 'kind', 'NATURAL_PERSON', 'roles', string_to_array(p_roles, ','),
        'fullName', p_name, 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-' || upper(p_ref), 'phone', p_phone))
$f$;
CREATE FUNCTION pg_temp.share(p_ref text, p_num int, p_den int, p_interest text DEFAULT 'CO_OWNERSHIP') RETURNS jsonb
LANGUAGE sql AS $f$ SELECT jsonb_build_object('person', p_ref, 'interest', p_interest, 'shareNum', p_num, 'shareDen', p_den) $f$;
-- The applicant p1 represents two co-owners, p2 (two thirds) and p3 (one third); p1 owns nothing.
CREATE FUNCTION pg_temp.several() RETURNS jsonb
LANGUAGE sql AS $f$
    SELECT jsonb_build_object('capacity', 'ON_BEHALF_OF_SEVERAL',
        'persons', jsonb_build_array(pg_temp.person('p1', 'APPLICANT,REPRESENTATIVE', 'DEMO Representative Ateba', '+237 690000001'),
                                     pg_temp.person('p2', 'INTENDED_OWNER', 'DEMO Owner Two'), pg_temp.person('p3', 'INTENDED_OWNER', 'DEMO Owner Three')),
        'shares', jsonb_build_array(pg_temp.share('p2', 2, 3), pg_temp.share('p3', 1, 3)),
        'mandates', jsonb_build_array(jsonb_build_object('representative', 'p1', 'represents', jsonb_build_array('p2', 'p3'), 'scope', 'APPLY')))
$f$;
CREATE FUNCTION pg_temp.pid(p_ref text) RETURNS uuid LANGUAGE sql AS $f$
    SELECT id FROM case_person WHERE application_id = '00000000-0000-0000-0000-00000000d001' AND ref = p_ref
$f$;
CREATE FUNCTION pg_temp.latest(p_ref text) RETURNS uuid LANGUAGE sql AS $f$ SELECT (case_identity_latest(pg_temp.pid(p_ref))).id $f$;
CREATE FUNCTION pg_temp.statement() RETURNS uuid LANGUAGE sql AS $f$
    SELECT (case_statement_latest('00000000-0000-0000-0000-00000000d001')).id
$f$;
CREATE FUNCTION pg_temp.mandate() RETURNS uuid LANGUAGE sql AS $f$ SELECT (case_mandate_latest(pg_temp.pid('p1'))).id $f$;
CREATE FUNCTION pg_temp.problems() RETURNS text LANGUAGE sql AS $f$
    SELECT array_to_string(case_party_problems('00000000-0000-0000-0000-00000000d001'), ',')
$f$;
CREATE FUNCTION pg_temp.file(p_actor text, p_purpose text) RETURNS text LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_party_file_add('00000000-0000-0000-0000-00000000d001', %L, md5(gen_random_uuid()::text),
        repeat('c', 64), 10, 'application/pdf', 'scan.pdf')::text $q$, p_purpose))
$f$;
CREATE FUNCTION pg_temp.identity(p_actor text, p_ref text, p_file text, p_name text, p_phone text DEFAULT NULL, p_reason text DEFAULT NULL,
                                 p_supersedes uuid DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_identity_record(%L, %L::jsonb, %L::uuid, NULL, %L::uuid, %L)::text $q$,
        pg_temp.pid(p_ref), jsonb_strip_nulls(jsonb_build_object('fullName', p_name, 'identityKind', 'NATIONAL_ID', 'identityNumber',
        'DEMO-ID-' || upper(p_ref), 'phone', p_phone)), p_file, coalesce(p_supersedes, pg_temp.latest(p_ref)), p_reason))
$f$;
CREATE FUNCTION pg_temp.check(p_actor text, p_kind text, p_target uuid, p_outcome text DEFAULT 'MATCHES', p_note text DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_party_check_record(%L, %L, %L, %L)::text $q$, p_kind, p_target, p_outcome, p_note))
$f$;
CREATE FUNCTION pg_temp.ownership(p_actor text, p_shares jsonb, p_reason text DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_ownership_record('00000000-0000-0000-0000-00000000d001', %L::jsonb, %L, %L)::text $q$,
        p_shares, pg_temp.statement(), p_reason))
$f$;
CREATE FUNCTION pg_temp.osh(p_ref text, p_num int, p_den int, p_file text) RETURNS jsonb LANGUAGE sql AS $f$
    SELECT jsonb_strip_nulls(jsonb_build_object('person', pg_temp.pid(p_ref), 'interest', 'CO_OWNERSHIP', 'shareNum', p_num, 'shareDen', p_den,
                                                'basisFile', p_file))
$f$;
CREATE FUNCTION pg_temp.mandate_rec(p_actor text, p_refs text, p_file text, p_reason text DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.case_mandate_record(%L, %L::uuid[], 'APPLY', %L::uuid, %L, %L)::text $q$,
        pg_temp.pid('p1'), ARRAY(SELECT pg_temp.pid(r) FROM unnest(string_to_array(p_refs, ',')) r), p_file, pg_temp.mandate(), p_reason))
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
CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;

-- --- 1. The declaration's rules -----------------------------------------------------------------------------------------

SELECT is(pg_temp.dp(NULL), 'DECLARATION_REQUIRED', 'a case that registers a title needs a declaration');
SELECT is(pg_temp.dp(tap.self_declaration()), '', 'the applicant alone, on their own behalf, in full ownership');
SELECT is(pg_temp.dp(pg_temp.several()), '', 'the applicant for two co-owners, two thirds and one third, under one mandate');
SELECT is(pg_temp.dp(jsonb_set(tap.self_declaration(), '{persons,0,roles}', '["APPLICANT"]')), 'OWNER_REQUIRED,CAPACITY_MISMATCH,SHARE_INVALID',
    'on their own behalf, the applicant must hold the owner''s role explicitly: it is never inferred');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{capacity}', '"SELF"')), 'CAPACITY_MISMATCH', 'the capacity says whom the applicant applies for');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{capacity}', '"ON_BEHALF_OF_ONE"')), 'CAPACITY_MISMATCH', '... one person, or several');
SELECT is(pg_temp.dp(pg_temp.several() - 'mandates'), 'REPRESENTATIVE_WITHOUT_MANDATE:p1,MANDATE_MISSING:p2,MANDATE_MISSING:p3',
    'a representative needs a mandate covering every owner they apply for');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{mandates,0,represents}', '["p2"]')), 'MANDATE_MISSING:p3', '... every one of them');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{mandates,0,represents}', '["p1", "p2", "p3"]')),
    'MANDATE_INVALID,REPRESENTATIVE_WITHOUT_MANDATE:p1,MANDATE_MISSING:p2,MANDATE_MISSING:p3',
    'a mandate never covers its own representative');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{shares}', jsonb_build_array(pg_temp.share('p2', 1, 2)))),
    'CO_OWNERSHIP_INVALID,OWNER_WITHOUT_SHARE:p3', 'every owner has a share, from the documents: no equal split by default');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{shares}', jsonb_build_array(pg_temp.share('p2', 2, 3), pg_temp.share('p3', 2, 3)))),
    'SHARES_MUST_TOTAL_ONE', 'exact fractions totalling one');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{shares}', jsonb_build_array(pg_temp.share('p1', 1, 3), pg_temp.share('p2', 1, 3),
                                                                                 pg_temp.share('p3', 1, 3)))),
    'SHARE_INVALID', 'a representative holds no share unless an intended owner');
SELECT is(pg_temp.dp(jsonb_build_object('capacity', 'ON_BEHALF_OF_ONE',
        'persons', jsonb_build_array(pg_temp.person('p1', 'APPLICANT,REPRESENTATIVE,INTENDED_OWNER', 'DEMO Heir Applicant', '+237 690000002'),
                                     pg_temp.person('p2', 'INTENDED_OWNER', 'DEMO Heir Two')),
        'shares', jsonb_build_array(pg_temp.share('p1', 1, 2), pg_temp.share('p2', 1, 2)),
        'mandates', jsonb_build_array(jsonb_build_object('representative', 'p1', 'represents', jsonb_build_array('p2'), 'scope', 'APPLY_AND_COLLECT')))),
    '', 'a representative who is also a co-owner holds both roles and a share, explicitly');
SELECT is(pg_temp.dp(jsonb_set(tap.self_declaration(), '{shares}', jsonb_build_array(pg_temp.share('p1', 1, 1, 'USUFRUCT') || '{"usufructTerm": "LIFE"}',
                                                                                       pg_temp.share('p1', 1, 1, 'BARE_OWNERSHIP')))),
    '', 'usufruct and bare ownership each total one');
SELECT is(pg_temp.dp(jsonb_set(tap.self_declaration(), '{persons,0,fullName}', '"Applicant Real"')), 'PERSON_INVALID:p1,APPLICANT_REQUIRED,OWNER_REQUIRED,SHARE_INVALID',
    'synthetic data only: every name says DEMO');
SELECT is(pg_temp.dp(tap.self_declaration() #- '{persons,0,phone}'), 'PHONE_REQUIRED', 'the applicant gives a phone number (D43)');
SELECT is(pg_temp.dp(jsonb_set(pg_temp.several(), '{persons,2,ref}', '"p2"')),
    'PERSON_DUPLICATE:p2,CAPACITY_MISMATCH,MANDATE_INVALID,REPRESENTATIVE_WITHOUT_MANDATE:p1,MANDATE_MISSING:p2,SHARE_INVALID',
    'each person once');

-- --- 2. Submission: checked, then turned into DECLARED records ------------------------------------------------------------

DO $$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[920000,400000],[920100,400000],[920100,400100],[920000,400100],[920000,400000]]]}', 32632);
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality',
            '00000000-0000-0000-0000-0000000000b1');
END $$;
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_R', id
      FROM snigf.application_type WHERE code = 'DEMO_PROC_R' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000d9', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
    status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000d8', '00000000-0000-0000-0000-0000000000d9', 1, 'STORED',
            '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
CREATE FUNCTION pg_temp.submit() RETURNS int LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
        parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
        office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
        state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$)
$f$;

SELECT throws_ok($$ SELECT pg_temp.submit() $$, 'PC001', 'DECLARATION_INVALID', 'no submission without a valid declaration');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT array_to_string(snigf.application_declaration_problems(
    '00000000-0000-0000-0000-00000000d001'), ',') $q$), 'DECLARATION_REQUIRED', 'the applicant reads why');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000e1', $q$ SELECT (snigf.application_declaration_problems(
    '00000000-0000-0000-0000-00000000d001') IS NULL)::text $q$), 'true', '... nobody else does');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET declaration = %L::jsonb, version = version + 1 $q$,
    pg_temp.several())), 1, 'the applicant declares, while a draft');
SELECT is(pg_temp.submit(), 1, 'and submits');
SELECT is((SELECT string_agg(cp.ref || ':' || (SELECT string_agg(r.role, '+' ORDER BY r.role) FROM case_role r WHERE r.person_id = cp.id)
                             || ':' || coalesce(cp.account_id::text, '-'), ',' ORDER BY cp.ref) FROM case_person cp
            WHERE cp.application_id = '00000000-0000-0000-0000-00000000d001'),
    'p1:APPLICANT+REPRESENTATIVE:00000000-0000-0000-0000-0000000000b3,p2:INTENDED_OWNER:-,p3:INTENDED_OWNER:-',
    'three persons, each with their explicit roles; only the applicant''s account is linked');
SELECT is((SELECT string_agg(source || ':' || version_no || ':' || verification || ':' || entered_by::text, ',') FROM case_person_identity
            WHERE application_id = '00000000-0000-0000-0000-00000000d001' GROUP BY source),
    'DECLARED:1:UNVERIFIED_DEMO:00000000-0000-0000-0000-0000000000b3,DECLARED:1:UNVERIFIED_DEMO:00000000-0000-0000-0000-0000000000b3,DECLARED:1:UNVERIFIED_DEMO:00000000-0000-0000-0000-0000000000b3',
    '... each identity DECLARED by the applicant, unverified');
SELECT is((SELECT string_agg(cp.ref || '=' || s.share_num || '/' || s.share_den, ',' ORDER BY cp.ref) FROM case_ownership_share s
             JOIN case_person cp ON cp.id = s.person_id WHERE s.statement_id = pg_temp.statement()), 'p2=2/3,p3=1/3',
    'the declared statement, as declared');
SELECT is((SELECT count(*)::int FROM case_mandate_subject WHERE mandate_id = pg_temp.mandate()), 2, 'the declared mandate covers both owners');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET declaration = NULL, version = version + 1 $q$) $$,
    '23001', NULL, 'a submitted declaration never changes');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.completeness_record (application_id, kind)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'AUTOMATIC') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL,
    version = version + 1 $q$);

-- --- 3. Intake: transcription from a cited document, and its check --------------------------------------------------------

SELECT is(pg_temp.problems(), 'IDENTITY_NOT_RECORDED:p1,IDENTITY_NOT_RECORDED:p2,IDENTITY_NOT_RECORDED:p3,OWNERSHIP_NOT_RECORDED,MANDATE_NOT_RECORDED:p1',
    'nothing declared is taken as recorded');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000e1') $q$) $$,
    'PC001', 'PARTIES_INCOMPLETE', 'the case is not accepted before its persons are transcribed and checked (D38)');
SELECT throws_ok($$ SELECT pg_temp.file('00000000-0000-0000-0000-0000000000e4', 'IDENTITY_DOCUMENT') $$, 'PC001', 'PARTY_FORBIDDEN',
    'a supervisor checks, never records');
SELECT throws_ok($$ SELECT pg_temp.file('00000000-0000-0000-0000-0000000000e3', 'IDENTITY_DOCUMENT') $$, 'PC001', 'PARTY_FORBIDDEN',
    '... nor does an officer of another office');
SELECT throws_ok($$ SELECT pg_temp.file('00000000-0000-0000-0000-0000000000e1', 'PORTRAIT') $$, 'PC001', 'PURPOSE_INVALID', 'a file says what it is');
INSERT INTO ctx VALUES ('id_file', pg_temp.file('00000000-0000-0000-0000-0000000000e1', 'IDENTITY_DOCUMENT')),
                       ('own_file', pg_temp.file('00000000-0000-0000-0000-0000000000e1', 'OWNERSHIP_EVIDENCE')),
                       ('man_file', pg_temp.file('00000000-0000-0000-0000-0000000000e1', 'MANDATE'));
SELECT is((SELECT string_agg(retention_class, ',' ORDER BY retention_class) FROM case_party_file WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'CASE_IDENTITY_DOCUMENT,CASE_MANDATE,CASE_OWNERSHIP_EVIDENCE', 'a counter file has the class of its purpose');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000e1', format($q$ SELECT snigf.case_identity_record(%L,
    '{"fullName": "DEMO Owner Two", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-P2"}', NULL, NULL, %L, NULL)::text $q$,
    pg_temp.pid('p2'), pg_temp.latest('p2'))) $$, 'PC001', 'SOURCE_REQUIRED', 'a transcription cites its document');
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('man_file'), 'DEMO Owner Two') $$,
    'PC001', 'SOURCE_REQUIRED', '... an identity document, not a mandate');
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('id_file'), 'DEMO Owner Two',
    p_supersedes => gen_random_uuid()) $$, 'PC001', 'STALE_VERSION', '... naming the version it replaces');
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('id_file'), 'Owner Two') $$,
    'PC001', 'IDENTITY_INVALID', 'a transcription is DEMO data too');
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p1', pg_temp.c('id_file'), 'DEMO Representative Ateba') $$,
    'PC001', 'PHONE_REQUIRED', 'the applicant''s phone is kept with their identity');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000b3', format($q$ SELECT snigf.case_party_file_add(%L, 'IDENTITY_DOCUMENT',
    md5('x'), repeat('c', 64), 10, 'application/pdf', 'self.pdf')::text $q$, '00000000-0000-0000-0000-00000000d001')) $$,
    'PC001', 'PARTY_FORBIDDEN', 'the applicant never records');
SELECT ok(pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p1', pg_temp.c('id_file'), 'DEMO Representative Ateba', '+237 690000001') IS NOT NULL
          AND pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('id_file'), 'DEMO Owner Two') IS NOT NULL
          AND pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p3', pg_temp.c('id_file'), 'DEMO Owner Three') IS NOT NULL,
    'the intake officer transcribes each identity from the document presented');
SELECT is((SELECT source || ':' || version_no || ':' || verification || ':' || entered_by::text || ':' || (source_file_id::text = pg_temp.c('id_file'))
             FROM case_person_identity WHERE id = pg_temp.latest('p2')),
    'RECORDED:2:UNVERIFIED_DEMO:00000000-0000-0000-0000-0000000000e1:true', '... RECORDED, by them, citing the file, still unverified (D39)');
SELECT throws_ok($$ INSERT INTO case_person_identity (person_id, application_id, version_no, source, full_name, identity_kind, identity_number,
    source_file_id, entered_by, supersedes_id, verification)
    SELECT pg_temp.pid('p2'), '00000000-0000-0000-0000-00000000d001', 9, 'RECORDED', 'DEMO X', 'NATIONAL_ID', 'DEMO-ID-X', pg_temp.c('id_file')::uuid,
           '00000000-0000-0000-0000-0000000000e1', pg_temp.latest('p2'), 'VERIFIED' $$,
    '23514', NULL, 'nothing can be written as verified, even by the schema owner');
SELECT throws_ok($$ UPDATE case_person_identity SET full_name = 'DEMO Rewritten' WHERE id = pg_temp.latest('p2') $$, '23001', NULL,
    'a transcription is never rewritten');

SELECT throws_ok($$ SELECT pg_temp.check('00000000-0000-0000-0000-0000000000e1', 'IDENTITY', pg_temp.latest('p2')) $$, 'PC001', 'RECORDER_CANNOT_CHECK',
    'the recorder never checks their own transcription (D38)');
SELECT throws_ok($$ SELECT pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p2'), 'DOES_NOT_MATCH') $$,
    'PC001', 'CHECK_NOTE_REQUIRED', 'a mismatch says why');
SELECT throws_ok($$ SELECT pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'OWNERSHIP', pg_temp.statement()) $$, 'PC001', 'NOT_YET_RECORDED',
    'what was only declared is not checked');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p1')) IS NOT NULL
          AND pg_temp.check('00000000-0000-0000-0000-0000000000e4', 'IDENTITY', pg_temp.latest('p2')) IS NOT NULL,
    'a second intake officer, or a supervisor, checks');
SELECT throws_ok($$ SELECT pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p2')) $$, 'PC001', 'ALREADY_CHECKED',
    'one check per version');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p3'), 'DOES_NOT_MATCH',
    'The number on the card ends in 7, not 3') IS NOT NULL, 'a transcription can be found not to match');
SELECT ok(pg_temp.problems() LIKE '%IDENTITY_DOES_NOT_MATCH:p3%', '... and holds the case');

INSERT INTO ctx SELECT 'p3_v2', pg_temp.latest('p3')::text;
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p3', pg_temp.c('id_file'), 'DEMO Owner Three') $$,
    'PC001', 'CORRECTION_REASON_REQUIRED', 'a correction says why');
SELECT ok(pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p3', pg_temp.c('id_file'), 'DEMO Owner Three', p_reason => 'The last digit was misread') IS NOT NULL,
    'a correction is a new version');
SELECT is((SELECT version_no || ':' || (supersedes_id::text = pg_temp.c('p3_v2')) FROM case_person_identity WHERE id = pg_temp.latest('p3')), '3:true',
    '... naming the one it replaces, which is kept');
SELECT is((SELECT outcome FROM case_party_check WHERE target_id = pg_temp.c('p3_v2')::uuid), 'DOES_NOT_MATCH', '... with its check');
SELECT throws_ok($$ SELECT pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.c('p3_v2')::uuid, 'MATCHES') $$,
    'PC001', 'STALE_VERSION', 'only the latest version is checked');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p3')) IS NOT NULL, 'the correction is checked');

-- The ownership statement, each share from its document.
SELECT throws_ok($$ SELECT pg_temp.ownership('00000000-0000-0000-0000-0000000000e1', jsonb_build_array(pg_temp.osh('p2', 2, 3, NULL),
    pg_temp.osh('p3', 1, 3, NULL))) $$, 'PC001', 'BASIS_REQUIRED', 'every share cites the legal document it comes from (D36)');
SELECT throws_ok($$ SELECT pg_temp.ownership('00000000-0000-0000-0000-0000000000e1', jsonb_build_array(pg_temp.osh('p2', 1, 2, pg_temp.c('own_file')),
    pg_temp.osh('p3', 1, 3, pg_temp.c('own_file')))) $$, 'PC001', 'SHARES_MUST_TOTAL_ONE', 'exact shares totalling one');
SELECT throws_ok($$ SELECT pg_temp.ownership('00000000-0000-0000-0000-0000000000e1', jsonb_build_array(pg_temp.osh('p2', 1, 1, pg_temp.c('own_file'))
    || '{"interest": "OWNERSHIP"}')) $$, 'PC001', 'OWNER_WITHOUT_SHARE', 'every intended owner has one');
SELECT throws_ok($$ SELECT pg_temp.ownership('00000000-0000-0000-0000-0000000000e1', jsonb_build_array(pg_temp.osh('p1', 1, 3, pg_temp.c('own_file')),
    pg_temp.osh('p2', 1, 3, pg_temp.c('own_file')), pg_temp.osh('p3', 1, 3, pg_temp.c('own_file')))) $$,
    'PC001', 'SHARE_INVALID', 'the representative holds none: they are not an owner (D35)');
SELECT ok(pg_temp.ownership('00000000-0000-0000-0000-0000000000e1', jsonb_build_array(pg_temp.osh('p2', 2, 3, pg_temp.c('own_file')),
    pg_temp.osh('p3', 1, 3, pg_temp.c('own_file')))) IS NOT NULL, 'the statement is recorded from the documents');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'OWNERSHIP', pg_temp.statement()) IS NOT NULL, '... and checked');

-- The mandate, with its document.
SELECT throws_ok($$ SELECT pg_temp.mandate_rec('00000000-0000-0000-0000-0000000000e1', 'p2,p3', NULL) $$, 'PC001', 'MANDATE_DOCUMENT_REQUIRED',
    'a mandate is recorded from its document');
SELECT throws_ok($$ SELECT pg_temp.mandate_rec('00000000-0000-0000-0000-0000000000e1', 'p1,p2', pg_temp.c('man_file')) $$, 'PC001', 'MANDATE_INVALID',
    '... and never covers its representative');
SELECT ok(pg_temp.mandate_rec('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('man_file')) IS NOT NULL, 'a mandate covering one owner only');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e4', 'MANDATE', pg_temp.mandate()) IS NOT NULL, '... checked');
SELECT is(pg_temp.problems(), 'MANDATE_MISSING:p3', '... leaves the other owner unrepresented: the case still waits');
SELECT ok(pg_temp.mandate_rec('00000000-0000-0000-0000-0000000000e1', 'p2,p3', pg_temp.c('man_file'), 'The mandate covers both heirs, page 2') IS NOT NULL,
    'the mandate is corrected');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'MANDATE', pg_temp.mandate()) IS NOT NULL, '... and checked');
SELECT is(pg_temp.problems(), '', 'every person, the statement and the mandate recorded and checked');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000e1') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ UPDATE snigf.application SET state = 'UNDER_REVIEW',
        state_changed_by = '00000000-0000-0000-0000-0000000000e1', version = version + 1 $q$), 2, 'the case is accepted');

-- --- 4. Who reads ---------------------------------------------------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(ref || ':' || status, ',' ORDER BY ref)
    FROM snigf.application_parties('00000000-0000-0000-0000-00000000d001') $q$), 'p1:MATCHES_DOCUMENT,p2:MATCHES_DOCUMENT,p3:MATCHES_DOCUMENT',
    'the applicant reads where each person stands, never the officer or the note');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT count(*)::text FROM snigf.case_person_identity $q$), '0',
    '... and never the tables');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b1', $q$ SELECT count(*)::text FROM snigf.case_person $q$), '3',
    'a stage officer of the case reads its persons');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000e3', $q$ SELECT count(*)::text FROM snigf.case_person_identity $q$), '0',
    'an officer of another office does not');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT count(*)::text FROM snigf.case_person $q$), '0',
    'nor a conservator: the register gets the owners from the statement, through the database');

-- --- 5. The approval names its statement -----------------------------------------------------------------------------------

SELECT ok(pg_temp.val('00000000-0000-0000-0000-0000000000b1', $q$ SELECT snigf.procedure_stage_record_add('00000000-0000-0000-0000-00000000d001',
    'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre', '{}'::jsonb, '{}', NULL, NULL, '[]'::jsonb)::text $q$) IS NOT NULL, 'the procedure');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'REVIEW', '00000000-0000-0000-0000-0000000000e5', '00000000-0000-0000-0000-0000000000e5') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'The two heirs'' shares are documented',
                '00000000-0000-0000-0000-0000000000e5') $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e5', $q$ UPDATE snigf.application SET state = 'PENDING_DECISION',
        state_changed_by = '00000000-0000-0000-0000-0000000000e5', version = version + 1 $q$)
    + pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'DECISION', '00000000-0000-0000-0000-0000000000e6', '00000000-0000-0000-0000-0000000000e6') $q$),
    4, 'reviewed, recommended, claimed for decision');
SELECT ok(pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('id_file'), 'DEMO Owner Two', p_reason => 'The birth date was copied wrong')
          IS NOT NULL, 'an identity corrected during the decision');
CREATE FUNCTION pg_temp.decide() RETURNS int LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions,
        follows_step_id, decided_by) VALUES ('00000000-0000-0000-0000-00000000d001', 'APPROVED', 'Occupation and inheritance proven',
        '{00000000-0000-0000-0000-0000000000d8}', (SELECT id FROM snigf.review_step ORDER BY at DESC LIMIT 1), '00000000-0000-0000-0000-0000000000e6') $q$)
$f$;
SELECT throws_ok($$ SELECT pg_temp.decide() $$, 'PC001', 'PARTIES_INCOMPLETE', '... holds the approval until it is checked again');
SELECT ok(pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p2')) IS NOT NULL, 'checked');
SELECT is(pg_temp.decide() + pg_temp.act('00000000-0000-0000-0000-0000000000e6', $q$ UPDATE snigf.application SET state = 'APPROVED',
    state_changed_by = '00000000-0000-0000-0000-0000000000e6', version = version + 1 $q$), 2, 'approved');
SELECT is((SELECT ownership_statement_id FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001'), pg_temp.statement(),
    'the decision names the statement it approved');

-- --- 6. The register: the statement's owners, two signatures ---------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT owners FROM snigf.register_case_queue(50, 0) $q$),
    'DEMO Owner Two (2/3); DEMO Owner Three (1/3)', '"Approved, to register" names the owners and their shares');
SELECT throws_ok($$ DO $d$ BEGIN
    PERFORM pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p3', pg_temp.c('id_file'), 'DEMO Owner Three', p_reason => 'A late correction');
    PERFORM pg_temp.check('00000000-0000-0000-0000-0000000000e2', 'IDENTITY', pg_temp.latest('p3'));
    RAISE EXCEPTION '%', array_to_string(register_case_problems('00000000-0000-0000-0000-00000000d001', NULL), ',');
END $d$ $$, 'P0001', 'PARTIES_CHANGED_SINCE_DECISION', 'what is registered is what was approved: a change since the decision stops it');
INSERT INTO ctx SELECT 'creation', pg_temp.val('00000000-0000-0000-0000-0000000000f1', $q$ SELECT snigf.register_propose_from_case(
    '00000000-0000-0000-0000-00000000d001', 'Approved immatriculation of two heirs (synthetic)')::text $q$);
SELECT is((SELECT string_agg(p.full_name || ':' || h.interest || ':' || h.share_num || '/' || h.share_den || ':' || coalesce(p.account_id::text, '-')
                             || ':' || (p.source_identity_id IS NOT NULL), ',' ORDER BY p.full_name)
             FROM inscription_holder h JOIN register_party p ON p.id = h.party_id WHERE h.inscription_id = pg_temp.c('creation')::uuid),
    'DEMO Owner Three:CO_OWNERSHIP:1/3:-:true,DEMO Owner Two:CO_OWNERSHIP:2/3:-:true',
    'the holders are the statement''s owners and shares, from their checked identities: never the representative who applied');
SELECT throws_ok($$ SELECT pg_temp.sign('register_register', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('creation')) $$,
    'RG001', 'DUAL_CONTROL_REQUIRED', 'one signature is not enough (D40)');
SELECT lives_ok($$ SELECT pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('creation'));
                   SELECT pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f2', 'k2', '\x0202', pg_temp.c('creation')) $$,
    'two conservators sign it');
SELECT is((SELECT jsonb_array_length(v.holders) || ':' || t.origin FROM land_title t JOIN title_version v ON v.title_id = t.id
            WHERE t.application_id = '00000000-0000-0000-0000-00000000d001'), '2:IMMATRICULATION', 'the title names the two owners');
SELECT throws_ok($$ SELECT pg_temp.identity('00000000-0000-0000-0000-0000000000e1', 'p2', pg_temp.c('id_file'), 'DEMO Owner Two',
    p_reason => 'After the registration') $$, 'PC001', 'CASE_REGISTERED', 'once registered, a person is corrected through the register (D42)');

-- --- 7. Privileges ---------------------------------------------------------------------------------------------------------

SELECT ok(NOT has_table_privilege('snigf_app', 'snigf.case_person', 'INSERT') AND NOT has_table_privilege('snigf_app', 'snigf.case_person_identity', 'INSERT')
          AND NOT has_table_privilege('snigf_app', 'snigf.case_party_check', 'INSERT'), 'the application writes persons only through the functions');
SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.case_party_problems(uuid)', 'EXECUTE'), 'the checks are not the application''s to call');

SELECT * FROM finish();
ROLLBACK;
