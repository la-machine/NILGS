-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Stage records and the procedure's gate (Slice 4, V32, ADR-042 step 3, D23 and D26): each stage recorded by the
-- permission it names, at the case's office, while the case is under review; never the applicant, never an assignee;
-- the commission's report never by the officer of the visit it reports on; corrections as new records naming the latest
-- one, with a reason, every earlier record kept; the gate on an approval recommendation and an approved decision, a
-- refusal never gated; stage officers never claim the case; the applicant sees statuses and dates only.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(65);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer, also commission secretary and reviewer (to test D26 and the claim); c6 a second cadastral
-- officer; c7 commission secretary; c8 commission secretary at DEMO2; c1 intake; c2 reviewer, also cadastral officer
-- (to test the assignee); c4 decision authority; b3 applicant.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c6', 'c2@demo.invalid', 'Cadastre Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c7', 's1@demo.invalid', 'Secretary One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c8', 's2@demo.invalid', 'Secretary Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c9', 'i9@demo.invalid', 'Intake Checker', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'COMMISSION_SECRETARY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c6', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c7', 'COMMISSION_SECRETARY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c8', 'COMMISSION_SECRETARY', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c9', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed');
-- A procedure A type without publication (V33), so its gate can be passed here; one with publication.
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_A', 1, true, 'Procédure A (DEMO)', 'Procedure A (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT"], "registers": "CREATION", "delivery": true}}'),
    ('DEMO_PROC_P', 1, true, 'Procédure A publiée (DEMO)', 'Procedure A published (DEMO)',
     '{"fields": [], "documents": [],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "PUBLICATION"], "registers": "CREATION", "delivery": true}}');

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
-- Records a stage of d001 as an actor; returns the new record's id.
CREATE FUNCTION pg_temp.rec(p_actor text, p_stage text, p_outcome text, p_finding text, p_details text DEFAULT '{}',
                            p_evidence text DEFAULT '{}', p_supersedes text DEFAULT NULL, p_reason text DEFAULT NULL,
                            p_files text DEFAULT '[]', p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_stage_record_add(%L, %L, %L, %L, %L::jsonb, %L::uuid[], %L::uuid, %L, %L::jsonb) $q$,
        p_case, p_stage, p_outcome, p_finding, p_details, p_evidence, p_supersedes, p_reason, p_files))
$f$;
CREATE FUNCTION pg_temp.latest(p_stage text) RETURNS text
LANGUAGE sql AS $f$
    SELECT (procedure_stage_latest('00000000-0000-0000-0000-00000000d001', p_stage)).id::text
$f$;
CREATE FUNCTION pg_temp.gate() RETURNS text
LANGUAGE sql AS $f$
    SELECT array_to_string(procedure_gate_problems('00000000-0000-0000-0000-00000000d001'), ',')
$f$;
CREATE FUNCTION pg_temp.step(p_actor text, p_recommendation text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', %L, 'Boundaries and occupation are consistent', %L) $q$,
        p_recommendation, p_actor))
$f$;
CREATE FUNCTION pg_temp.claim(p_actor text, p_stage text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L) $q$, p_stage, p_actor, p_actor))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_state text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = '00000000-0000-0000-0000-00000000d001' $q$, p_state, p_actor))
$f$;
CREATE FUNCTION pg_temp.decide(p_actor text, p_outcome text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions, follows_step_id, decided_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, 'The procedure''s record supports it', '{00000000-0000-0000-0000-0000000000e1}',
                (SELECT id FROM snigf.review_step WHERE kind = 'RECOMMENDATION' ORDER BY at DESC LIMIT 1), %L) $q$, p_outcome, p_actor))
$f$;

-- A parcel at DEMO1, recorded by b1.
DO $$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[900000,400000],[900100,400000],[900100,400100],[900000,400100],[900000,400000]]]}', 32632);
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END $$;

-- d001: a procedure A case under review, with one stored document. d002: one submitted, of the published type.
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_A', id
      FROM snigf.application_type WHERE code = 'DEMO_PROC_A' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document (id, application_id, category)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000d001', 'DEMO_ID') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.document_version (id, document_id, version_no,
    status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000f1', 1, 'STORED',
            '0123456789abcdef0123456789abcdef', repeat('a', 64), 10, 'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED', declaration = '{"capacity": "SELF", "persons": [{"ref": "p1", "kind": "NATURAL_PERSON", "roles": ["APPLICANT", "INTENDED_OWNER"], "fullName": "DEMO Applicant Owner", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-SELF-0001", "phone": "+237 600000001"}], "shares": [{"person": "p1", "interest": "OWNERSHIP", "shareNum": 1, "shareDen": 1}]}'::jsonb,
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.completeness_record (application_id, kind)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'AUTOMATIC') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL, version = version + 1 $q$);
SELECT tap.parties_ready('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c9');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$);
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000c1', 'UNDER_REVIEW');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_P', id
      FROM snigf.application_type WHERE code = 'DEMO_PROC_P' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED', declaration = '{"capacity": "SELF", "persons": [{"ref": "p1", "kind": "NATURAL_PERSON", "roles": ["APPLICANT", "INTENDED_OWNER"], "fullName": "DEMO Applicant Owner", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-SELF-0001", "phone": "+237 600000001"}], "shares": [{"person": "p1", "interest": "OWNERSHIP", "shareNum": 1, "shareDen": 1}]}'::jsonb,
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000002-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d002' $q$);

-- --- Before anything is recorded --------------------------------------------------------------------------------

SELECT is(pg_temp.gate(), 'STAGE_MISSING:CADASTRAL_REVIEW,STAGE_MISSING:FIELD_VISIT,STAGE_MISSING:COMMISSION_REPORT',
    'the gate lists every stage not yet recorded');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(stage || ':' || status, ',' ORDER BY rank)
    FROM snigf.application_stage_progress('00000000-0000-0000-0000-00000000d001') $q$),
    'CADASTRAL_REVIEW:NOT_STARTED,FIELD_VISIT:NOT_STARTED,COMMISSION_REPORT:NOT_STARTED', 'the applicant sees every stage, not started');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c7', $q$ SELECT string_agg(stage, ',') FROM snigf.procedure_queue(50, 0) $q$),
    'COMMISSION_REPORT', 'the secretary''s queue holds the case''s commission report');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b1', $q$ SELECT string_agg(stage, ',' ORDER BY stage) FROM snigf.procedure_queue(50, 0)
    WHERE application_id = '00000000-0000-0000-0000-00000000d001' $q$),
    'CADASTRAL_REVIEW,COMMISSION_REPORT,FIELD_VISIT', '... and a cadastral officer who is also secretary sees the three');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b1', 'INSERT INTO snigf.procedure_stage_record (application_id, office_id,
    stage, outcome, finding, recorded_by) VALUES (''00000000-0000-0000-0000-00000000d001'', ''00000000-0000-0000-0000-0000000000a1'',
    ''CADASTRAL_REVIEW'', ''COMPLETED'', ''Recorded directly'', ''00000000-0000-0000-0000-0000000000b1'')') $$,
    '42501', NULL, 'the application never writes a record directly');

-- --- Who records ----------------------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b3', 'CADASTRAL_REVIEW', 'COMPLETED', 'I checked it myself') $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'the applicant records nothing');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c1', 'CADASTRAL_REVIEW', 'COMPLETED', 'Not my stage at all') $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'an officer without the stage''s permission records nothing');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c8', 'COMMISSION_REPORT', 'COMPLETED', 'Another office''s report') $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'a secretary of another office records nothing');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'SURVEY', 'COMPLETED', 'A stage that does not exist') $$,
    'PC001', 'STAGE_UNKNOWN', 'an unknown stage is refused');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c1', 'PUBLICATION', 'COMPLETED', 'Not in this procedure') $$,
    'PC001', 'STAGE_NOT_IN_PROCEDURE', 'a stage outside the case''s procedure is refused');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c1', 'PUBLICATION', 'COMPLETED', 'Published on the board',
    p_case => '00000000-0000-0000-0000-00000000d002') $$,
    'PC001', 'CASE_NOT_UNDER_REVIEW', 'publication is available from V33, on a case under review only');

-- --- What a record says ---------------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'DONE', 'The parcel matches the cadastre') $$,
    'PC001', 'OUTCOME_REQUIRED', 'the outcome is COMPLETED or NOT_SATISFIED');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'Fine') $$,
    'PC001', 'FINDING_REQUIRED', 'a finding says something');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre',
    '{"parcelVersionNo": 7}') $$,
    'PC001', 'DETAILS_INVALID', 'the parcel version is the database''s to record, not the officer''s');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre',
    '{}', '{00000000-0000-0000-0000-0000000000e9}') $$,
    'PC001', 'EVIDENCE_NOT_OF_CASE', 'evidence is a stored version of the case''s own documents');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre',
    '{}', '{00000000-0000-0000-0000-0000000000e1}') IS NOT NULL, 'the cadastral officer records the cadastral review');
SELECT is((SELECT details ->> 'parcelVersionNo' FROM procedure_stage_record WHERE stage = 'CADASTRAL_REVIEW'), '1',
    '... and the database records the parcel version reviewed');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches again') $$,
    'PC001', 'STAGE_ALREADY_RECORDED', 'a second first record is refused: a retry adds nothing');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'COMPLETED', 'Visited the parcel',
    '{"visitedOn": "2999-01-01", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}') $$,
    'PC001', 'VISIT_DATE_INVALID', 'a visit is not in the future');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'COMPLETED', 'Visited the parcel',
    format('{"visitedOn": "%s", "members": []}', current_date)) $$,
    'PC001', 'MEMBERS_INVALID', 'a visit names the commission''s members present');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    '{00000000-0000-0000-0000-0000000000e1}') $$,
    'PC001', 'FIELD_VISIT_REQUIRED', 'no report before a completed field visit');

-- --- Corrections: new records, never overwritten -------------------------------------------------------------------

SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'NOT_SATISFIED', 'The boundary markers were missing',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date)) IS NOT NULL,
    'a visit not satisfied is recorded, with why');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    '{00000000-0000-0000-0000-0000000000e1}') $$,
    'PC001', 'FIELD_VISIT_REQUIRED', '... and the report still waits for a completed one');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'COMPLETED', 'Markers placed and checked',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date), '{}', pg_temp.latest('FIELD_VISIT')) $$,
    'PC001', 'CORRECTION_REASON_REQUIRED', 'a correction says why');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'COMPLETED', 'Markers placed and checked',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date), '{}',
    pg_temp.latest('CADASTRAL_REVIEW'), 'The markers were placed again') $$,
    'PC001', 'STALE_RECORD', 'a correction names the latest record of its own stage');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'FIELD_VISIT', 'COMPLETED', 'Markers placed and checked',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date), '{}',
    pg_temp.latest('FIELD_VISIT'), 'The markers were placed again the same day') IS NOT NULL,
    'the officer corrects the visit with a new record');
SELECT is((SELECT count(*)::int FROM procedure_stage_record WHERE stage = 'FIELD_VISIT'), 2, '... and both records are kept');
SELECT is((SELECT outcome FROM procedure_stage_record WHERE id = (SELECT supersedes_id FROM procedure_stage_record WHERE stage = 'FIELD_VISIT'
    AND supersedes_id IS NOT NULL)), 'NOT_SATISFIED', '... the corrected one unchanged, named by its correction');
SELECT throws_ok($$ UPDATE procedure_stage_record SET outcome = 'COMPLETED' WHERE stage = 'FIELD_VISIT' $$, '23001', NULL,
    'a record is never updated');
SELECT throws_ok($$ DELETE FROM procedure_stage_record WHERE stage = 'FIELD_VISIT' $$, '23001', NULL, 'a record is never deleted');

-- --- The commission's report (D26) ------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    '{00000000-0000-0000-0000-0000000000e1}') $$,
    'PC001', 'VISIT_RECORDER_CANNOT_REPORT', 'the officer of the visit never validates its report, even holding the permission');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date)) $$,
    'PC001', 'REPORT_EVIDENCE_REQUIRED', 'the report comes with its evidence');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date - 1),
    '{00000000-0000-0000-0000-0000000000e1}') $$,
    'PC001', 'REPORT_DATE_INVALID', 'a report is not dated before the visit');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees on the limits',
    format('{"reportReference": "PV-001", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    '{}', NULL, NULL,
    '[{"storageKey": "fedcba9876543210fedcba9876543210", "sha256": "' || repeat('b', 64) || '", "size": 20, "type": "application/pdf", "name": "pv.pdf"}]')
    IS NOT NULL, 'the secretary records the report with its file');
SELECT is((SELECT details ->> 'fieldVisitRecord' FROM procedure_stage_record WHERE stage = 'COMMISSION_REPORT'), pg_temp.latest('FIELD_VISIT'),
    '... naming the visit it reports on');
SELECT is((SELECT retention_class FROM procedure_stage_file), 'PROCEDURE_STAGE_EVIDENCE', 'the file has its retention class (D18)');
-- Step 3 verification (point 1): the recorded SHA-256 of a stage file is never changed, its record never removed.
SELECT throws_ok($$ UPDATE procedure_stage_file SET sha256 = repeat('c', 64) $$, '23001', NULL, 'a stage file''s recorded hash is never changed');
SELECT throws_ok($$ DELETE FROM procedure_stage_file $$, '23001', NULL, '... nor its record deleted');
SELECT is((SELECT count(*)::int FROM document_storage_keys_known(ARRAY['fedcba9876543210fedcba9876543210'])), 1,
    '... and the orphan cleanup keeps it');
SELECT is(pg_temp.gate(), '', 'every stage completed: nothing stops the approval');

-- --- The gate on the recommendation, and who may claim -----------------------------------------------------------

SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c6', 'FIELD_VISIT', 'COMPLETED', 'A second visit confirmed the markers',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date), '{}',
    pg_temp.latest('FIELD_VISIT'), 'A second visit was asked for by the commission') IS NOT NULL, 'another officer corrects the visit again');
SELECT is(pg_temp.gate(), 'REPORT_ON_SUPERSEDED_VISIT', '... so the report is on a superseded visit');
-- Step 3 verification (point 2): the corrected visit invalidates the report, which is kept, and says so everywhere.
SELECT is(procedure_stage_status('00000000-0000-0000-0000-00000000d001', 'COMMISSION_REPORT'), 'OUTDATED',
    'the report on the superseded visit no longer holds (V33)');
SELECT is((SELECT count(*)::int FROM procedure_stage_record WHERE stage = 'COMMISSION_REPORT'
             AND details ->> 'fieldVisitRecord' <> pg_temp.latest('FIELD_VISIT')), 1,
    '... it is kept unchanged, still naming the visit it reported on');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c7', $q$ SELECT string_agg(stage || ':' || stage_status, ',') FROM snigf.procedure_queue(50, 0) $q$),
    'COMMISSION_REPORT:OUTDATED', '... the secretary''s queue asks for a new report');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT status FROM snigf.application_stage_progress('00000000-0000-0000-0000-00000000d001')
    WHERE stage = 'COMMISSION_REPORT' $q$), 'OUTDATED', '... and the applicant no longer reads it as completed');
SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000b1', 'REVIEW') $$, 'PC001', 'STAGE_RECORDER_CANNOT_REVIEW',
    'whoever recorded a stage never reviews the case');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c2', 'REVIEW'), 1, 'a reviewer who recorded nothing claims it');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c2', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches still',
    '{}', '{}', pg_temp.latest('CADASTRAL_REVIEW'), 'Checking it as the reviewer') $$,
    'PC001', 'ASSIGNEE_CANNOT_RECORD', 'the case''s reviewer records no stage, even holding the permission');
SELECT throws_ok($$ SELECT pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_APPROVAL') $$, 'PC001',
    'PROCEDURE_INCOMPLETE: REPORT_ON_SUPERSEDED_VISIT', 'no approval recommended while the report is on a superseded visit');
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_REFUSAL'), 1, '... but a refusal may be recommended');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees after the second visit',
    format('{"reportReference": "PV-002", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    '{00000000-0000-0000-0000-0000000000e1}', pg_temp.latest('COMMISSION_REPORT'), 'The report is made again on the second visit') IS NOT NULL,
    'the secretary corrects the report on the latest visit');
SELECT is(pg_temp.gate(), '', '... a new valid report clears the gate');
SELECT is((SELECT count(*)::int FROM procedure_stage_record WHERE stage = 'COMMISSION_REPORT'), 2, '... both reports are kept');
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_APPROVAL') + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION'),
    2, 'with the gate clear, approval is recommended; the case is pending decision');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c6', 'CADASTRAL_REVIEW', 'NOT_SATISFIED', 'The parcel must be checked again',
    '{}', '{}', pg_temp.latest('CADASTRAL_REVIEW'), 'A late doubt about the boundary') $$,
    'PC001', 'CASE_NOT_UNDER_REVIEW', 'nothing is recorded once the case is pending decision');

-- --- The gate on the decision ----------------------------------------------------------------------------------

SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c4', 'DECISION'), 1, 'the decision authority claims the case');
-- Step 3 verification (point 2): the decision guard checks the report against the latest visit on its own, even if a
-- visit were corrected after the recommendation (the writer refuses that now; written here as the schema owner).
SELECT throws_ok($$ DO $d$ BEGIN
    INSERT INTO snigf.procedure_stage_record (application_id, office_id, stage, outcome, finding, details, supersedes_id,
                                              correction_reason, recorded_by)
    VALUES ('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000a1', 'FIELD_VISIT', 'COMPLETED',
            'A visit corrected after the recommendation', jsonb_build_object('visitedOn', current_date::text,
            'members', '[{"name": "DEMO Member", "capacity": "Chief"}]'::jsonb), pg_temp.latest('FIELD_VISIT')::uuid,
            'Simulates a correction behind the recommendation', '00000000-0000-0000-0000-0000000000c6');
    PERFORM pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED');
END $d$ $$, 'PC001', 'PROCEDURE_INCOMPLETE: REPORT_ON_SUPERSEDED_VISIT', 'the decision guard refuses an approval on a report of a superseded visit');
-- The parcel is corrected after its cadastral review (as the cadastre's own correction would do).
UPDATE parcel SET current_version_no = 2 WHERE id = '00000000-0000-0000-0000-000000004001';
SELECT is(pg_temp.gate(), 'PARCEL_CHANGED_SINCE_REVIEW', 'a parcel corrected since its review stops the approval');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED') $$, 'PC001', NULL,
    '... the decision guard re-checks the gate');
SELECT is(pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'REFUSED'), 1, 'a refusal is never gated');

-- --- What each reader sees --------------------------------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT count(*)::text FROM snigf.procedure_stage_record $q$), '0',
    'the applicant reads no stage record');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(stage || ':' || status, ',' ORDER BY rank)
    FROM snigf.application_stage_progress('00000000-0000-0000-0000-00000000d001') $q$),
    'CADASTRAL_REVIEW:OUTDATED,FIELD_VISIT:COMPLETED,COMMISSION_REPORT:COMPLETED',
    '... only each stage''s status and date; the review of a parcel corrected since reads outdated (V33)');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c1', $q$ SELECT count(*)::text FROM snigf.procedure_stage_record $q$), '6',
    'the office''s readers read every record, corrections included');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c8', $q$ SELECT count(*)::text FROM snigf.procedure_stage_record $q$), '0',
    'another office''s secretary reads none');

SELECT * FROM finish();
ROLLBACK;
