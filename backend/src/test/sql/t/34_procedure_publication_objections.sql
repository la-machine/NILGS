-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Publication and objections (Slice 4, V33, ADR-042 step 4, D23, P2, P3): a publication only once the stages before
-- it hold, opening a notice whose period is the DEMO setting; the public list showing only what notification needs;
-- objections recorded at the counter by an officer for a named objector, late ones marked and kept; decided once, with
-- grounds, never by their recorder, an assignee or a stage officer; an open objection holding every recommendation and
-- decision; an upheld one sending the stages it names back for examination, never refusing the case; registration
-- blocked by an open or a late upheld objection; the objector's identity shown only to the objection officers, one
-- objection at a time. The owner's decisions of 13 Nov 2026: an upheld objection reopens only the stages it names (and
-- a report whose visit is made again), every earlier record and notice kept unchanged; once they are recorded again
-- the notice is published anew with a whole new period; the case is never refused by it; the applicant is told the
-- stages to carry out again and what they must do, never the objection.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(119);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer, also supervisor (a stage officer never decides an objection); c1 intake (publishes, receives
-- objections); c9 intake and reviewer (an objection's recorder never claims the case); c5 intake and supervisor (the
-- recorder never decides); c6 supervisor; c10 supervisor at DEMO2; c2 reviewer and supervisor (an assignee never
-- decides); c3 cadastral officer only (reads objections, never the objector's identity); c4 decision authority; b3 applicant.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c3', 'c3@demo.invalid', 'Cadastre Only', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'p1@demo.invalid', 'Intake Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c6', 'p2@demo.invalid', 'Supervisor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c9', 'i2@demo.invalid', 'Intake Reviewer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000ca', 'p3@demo.invalid', 'Supervisor Elsewhere', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c7', 's1@demo.invalid', 'Secretary One', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c9', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c9', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c6', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000ca', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'REVIEWER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c7', 'COMMISSION_SECRETARY', '00000000-0000-0000-0000-0000000000a1', 'seed');
-- A short procedure A type: a cadastral review, then the publication.
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_P', 1, true, 'Procédure A publiée (DEMO)', 'Procedure A published (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "PUBLICATION"], "registers": "CREATION", "delivery": true}}'),
    ('DEMO_PROC_F', 1, true, 'Procédure A complète (DEMO)', 'Procedure A in full (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT", "PUBLICATION"], "registers": "CREATION", "delivery": true}}');

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
CREATE FUNCTION pg_temp.rec(p_actor text, p_stage text, p_outcome text, p_finding text, p_details text DEFAULT '{}',
                            p_supersedes text DEFAULT NULL, p_reason text DEFAULT NULL, p_case text DEFAULT '00000000-0000-0000-0000-00000000d001',
                            p_files text DEFAULT '[]') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_stage_record_add(%L, %L, %L, %L,
        %L::jsonb, '{}', %L::uuid, %L, %L::jsonb) $q$, p_case, p_stage, p_outcome, p_finding, p_details, p_supersedes, p_reason, p_files))
$f$;
CREATE FUNCTION pg_temp.publish(p_actor text, p_period text DEFAULT 'PT5M', p_supersedes text DEFAULT NULL, p_reason text DEFAULT NULL,
                                p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.rec(p_actor, 'PUBLICATION', 'COMPLETED', 'Posted on the office board and at the chiefdom',
        format('{"objectionPeriod": "%s"}', p_period), p_supersedes, p_reason, p_case)
$f$;
-- Records an objection; returns "id:replayed".
CREATE FUNCTION pg_temp.object(p_actor text, p_key text, p_name text DEFAULT 'DEMO Neighbour Mbarga',
                               p_statement text DEFAULT 'The eastern boundary crosses my field (synthetic)',
                               p_interest text DEFAULT 'NEIGHBOUR', p_files text DEFAULT '[]',
                               p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT objection_id || ':' || replayed FROM snigf.procedure_objection_add(
        %L, %L::jsonb, %L, %L::jsonb, %L::uuid) $q$, p_case,
        jsonb_build_object('name', p_name, 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-0042', 'interest', p_interest),
        p_statement, p_files, p_key))
$f$;
CREATE FUNCTION pg_temp.decide_objection(p_actor text, p_objection text, p_outcome text, p_grounds text, p_stages text DEFAULT '{}',
                                         p_instructions text DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_objection_decide(%L, %L, %L, %L::text[], %L) $q$,
        p_objection, p_outcome, p_grounds, p_stages, p_instructions))
$f$;
CREATE FUNCTION pg_temp.latest(p_stage text, p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT (procedure_stage_latest(p_case::uuid, p_stage)).id::text
$f$;
CREATE FUNCTION pg_temp.gate(p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT array_to_string(procedure_gate_problems(p_case::uuid), ',')
$f$;
CREATE FUNCTION pg_temp.status(p_stage text, p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS text
LANGUAGE sql AS $f$
    SELECT procedure_stage_status(p_case::uuid, p_stage)
$f$;
CREATE FUNCTION pg_temp.public_count() RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.val(NULL, 'SELECT count(*)::text FROM snigf.procedure_public_notices(50, 0)')::int
$f$;
-- Moves the objection period of every notice of the case into the past (the notice is append-only for everyone else).
CREATE FUNCTION pg_temp.period_over(p_case text DEFAULT '00000000-0000-0000-0000-00000000d001') RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    ALTER TABLE snigf.procedure_notice DISABLE TRIGGER procedure_notice_append_only;
    UPDATE snigf.procedure_notice SET opens_at = opens_at - interval '1 day', closes_at = closes_at - interval '1 day'
     WHERE application_id = p_case::uuid;
    ALTER TABLE snigf.procedure_notice ENABLE TRIGGER procedure_notice_append_only;
END;
$f$;
CREATE FUNCTION pg_temp.claim(p_actor text, p_stage text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES ('00000000-0000-0000-0000-00000000d001', %L, %L, %L) $q$, p_stage, p_actor, p_actor))
$f$;
CREATE FUNCTION pg_temp.step(p_actor text, p_recommendation text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d001', 'RECOMMENDATION', %L, 'Boundaries and occupation are consistent', %L) $q$,
        p_recommendation, p_actor))
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

-- A parcel at DEMO1, recorded by b1; d001, a case under review with one stored document.
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
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_P', id
      FROM snigf.application_type WHERE code = 'DEMO_PROC_P' $q$);
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
SELECT tap.parties_ready('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c6');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$);
SELECT pg_temp.move('00000000-0000-0000-0000-0000000000c1', 'UNDER_REVIEW');

-- --- Publication: only once the stages before it hold --------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c1', gen_random_uuid()::text) $$,
    'PC001', 'NOTICE_NOT_PUBLISHED', 'no objection before a notice is published');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000c1') $$,
    'PC001', 'EARLIER_STAGES_INCOMPLETE', 'no publication before the cadastral review');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre') IS NOT NULL,
    'the cadastral review is recorded');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000b1') $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'a cadastral officer does not publish');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c1', 'PUBLICATION', 'NOT_SATISFIED', 'The board was full today',
    '{"objectionPeriod": "PT5M"}') $$, 'PC001', 'OUTCOME_INVALID', 'a publication is published, or not recorded');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'P2Y') $$,
    'PC001', 'PERIOD_INVALID', 'the period is bounded by the database, whatever the setting says');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'soon') $$,
    'PC001', 'PERIOD_INVALID', '... and is a period');
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c1', 'PUBLICATION', 'COMPLETED', 'Posted on the office board',
    '{"objectionPeriod": "PT5M", "locality": "Elsewhere"}') $$, 'PC001', 'DETAILS_INVALID', 'the locality is the database''s, not the officer''s');
SELECT ok(pg_temp.publish('00000000-0000-0000-0000-0000000000c1') IS NOT NULL, 'the intake officer publishes');
SELECT ok((SELECT number ~ '^DEMO-PUB-[0-9]{4}-[0-9]{6}$' AND closes_at = opens_at + interval '5 minutes'
             AND parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z' AND locality = 'Synthetic fixture locality'
             AND record_id::text = pg_temp.latest('PUBLICATION') FROM procedure_notice),
    '... opening a notice: its number, the parcel and locality, the period from the setting');
SELECT is(pg_temp.status('PUBLICATION'), 'IN_PROGRESS', 'the publication is in progress while its period is open');
SELECT is(pg_temp.gate(), 'OBJECTION_PERIOD_OPEN', '... and the period holds the approval');
SELECT throws_ok($$ UPDATE procedure_notice SET closes_at = now() $$, '23001', NULL, 'a notice is never changed');

-- --- The public notice: only what notification needs ----------------------------------------------------------

SELECT is(pg_temp.public_count(), 1, 'anyone, signed in or not, lists the open notice');
SELECT is(pg_get_function_result('snigf.procedure_public_notices(int, int)'::regprocedure),
    'TABLE(number text, procedure text, parcel_reference text, locality text, office_code text, office_name_fr text, office_name_en text, opens_at timestamp with time zone, closes_at timestamp with time zone)',
    '... and it carries no applicant, case reference, document or objection');
SELECT is(pg_temp.val(NULL, 'SELECT count(*)::text FROM snigf.procedure_notice'), '0', 'the notice table itself is not public');

-- --- Objections at the counter -----------------------------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000b3', gen_random_uuid()::text) $$,
    'PC001', 'PROCEDURE_FORBIDDEN', 'nobody objects online: the applicant records nothing');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c1', NULL) $$,
    'PC001', 'IDEMPOTENCY_KEY_REQUIRED', 'a counter objection carries its retry key');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c1', gen_random_uuid()::text, 'Real Person') $$,
    'PC001', 'OBJECTOR_NAME_INVALID', 'the objector is synthetic (DEMO)');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c1', gen_random_uuid()::text, p_interest => 'CURIOUS') $$,
    'PC001', 'OBJECTOR_INTEREST_INVALID', 'the objector states their interest');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c1', gen_random_uuid()::text, p_statement => 'I object') $$,
    'PC001', 'STATEMENT_REQUIRED', 'an objection says why');
SELECT is(split_part(pg_temp.object('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000aa01'), ':', 2), 'false',
    'an intake officer records an objection');
SELECT is(pg_temp.object('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000aa01'),
    (SELECT id || ':true' FROM procedure_objection), '... a retry with the same key gives back the same objection');
SELECT throws_ok($$ SELECT pg_temp.object('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000aa01',
    p_statement => 'A different statement on another matter') $$,
    'PC001', 'IDEMPOTENCY_KEY_REUSED', '... and the same key with another objection is refused');
SELECT ok((SELECT number ~ '^DEMO-OBJ-[0-9]{4}-[0-9]{6}$' AND NOT late FROM procedure_objection), '... numbered, received in time');
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-00000000aa02', 'DEMO Heir Ngo',
    'My father''s heirs were never heard (synthetic)', 'HEIR',
    '[{"storageKey": "fedcba9876543210fedcba9876543210", "sha256": "' || repeat('b', 64) || '", "size": 20, "type": "application/pdf", "name": "claim.pdf"}]')
    IS NOT NULL, 'a second objection, with a file');
SELECT is((SELECT retention_class FROM procedure_objection_file), 'PROCEDURE_OBJECTION_EVIDENCE', 'the file has its retention class');
SELECT is((SELECT count(*)::int FROM document_storage_keys_known(ARRAY['fedcba9876543210fedcba9876543210'])), 1,
    '... and the orphan cleanup keeps it');
SELECT throws_ok($$ DELETE FROM procedure_objection $$, '23001', NULL, 'an objection is never deleted');
SELECT is(pg_temp.gate(), 'OBJECTION_PERIOD_OPEN,OBJECTION_OPEN', 'the open objections hold the approval');

-- --- Who sees the objector ---------------------------------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c1', $q$ SELECT identity_number FROM snigf.procedure_objector_identity(
    (SELECT id FROM snigf.procedure_objection ORDER BY received_at LIMIT 1)) $q$), 'DEMO-ID-0042',
    'an objection officer reads the objector''s identity, one objection at a time');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c1', $q$ SELECT string_agg(identity_visible::text, ',' ORDER BY received_at)
    FROM snigf.procedure_objections('00000000-0000-0000-0000-00000000d001') $q$), 'true,true', '... the list only says they may ask');
SELECT ok(pg_get_function_result('snigf.procedure_objections(uuid)'::regprocedure) !~ 'identity_(kind|number)',
    '... and never carries the identity itself');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000c3', 'SELECT identity_number FROM snigf.procedure_objector_identity(
    (SELECT id FROM snigf.procedure_objection ORDER BY received_at LIMIT 1))') $$, 'PC001', 'PROCEDURE_FORBIDDEN',
    'a cadastral officer of the case is refused the identity');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000c1', 'SELECT objector_identity_number FROM snigf.procedure_objection LIMIT 1') $$,
    '42501', NULL, '... never from the table directly');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c3', $q$ SELECT string_agg(identity_visible || '/' || objector_name, ',' ORDER BY received_at)
    FROM snigf.procedure_objections('00000000-0000-0000-0000-00000000d001') $q$), 'false/DEMO Neighbour Mbarga,false/DEMO Heir Ngo',
    '... who reads the objection, without the identity');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT count(*)::text FROM snigf.procedure_objections('00000000-0000-0000-0000-00000000d001') $q$),
    '0', 'the applicant reads no objection');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', 'SELECT count(*)::text FROM snigf.procedure_objection'), '0',
    '... directly neither');

-- --- The block: no recommendation, no decision while an objection is open -----------------------------------------

SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000c9', 'REVIEW') $$, 'PC001', 'OBJECTION_HANDLER_CANNOT_REVIEW',
    'whoever recorded an objection never reviews the case');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c2', 'REVIEW'), 1, 'a reviewer claims the case');
SELECT throws_ok($$ SELECT pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_APPROVAL') $$, 'PC001', 'OBJECTION_OPEN',
    'no approval recommended while an objection is open');
SELECT throws_ok($$ SELECT pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_REFUSAL') $$, 'PC001', 'OBJECTION_OPEN',
    '... nor a refusal (ADR-042 §3)');

-- --- Deciding an objection: someone else, with grounds ------------------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c1', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit') $$, 'PC001', 'PROCEDURE_FORBIDDEN', 'an intake officer decides nothing');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000ca', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit') $$, 'PC001', 'PROCEDURE_FORBIDDEN', 'a supervisor of another office decides nothing');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c5', (SELECT id::text FROM procedure_objection ORDER BY received_at DESC LIMIT 1),
    'DISMISSED', 'The heirs were heard at the visit') $$, 'PC001', 'OBJECTION_RECORDER_CANNOT_DECIDE', 'the recorder never decides the objection');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c2', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit') $$, 'PC001', 'ASSIGNEE_CANNOT_DECIDE', 'the case''s reviewer never decides an objection');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000b1', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit') $$, 'PC001', 'STAGE_RECORDER_CANNOT_DECIDE', '... nor the officer of one of its stages');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'Unfounded') $$, 'PC001', 'OBJECTION_GROUNDS_REQUIRED', 'a decision gives its grounds');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'REJECTED', 'The boundary was checked at the visit') $$, 'PC001', 'OUTCOME_REQUIRED', 'upheld or dismissed');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'UPHELD', 'The boundary must be checked again on site') $$, 'PC001', 'REVIEW_STAGES_REQUIRED',
    'an upheld objection names the stages to examine again');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'UPHELD', 'The boundary must be checked again on site', '{FIELD_VISIT}') $$, 'PC001', 'REVIEW_STAGE_INVALID',
    '... stages of the case''s own procedure');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit', '{CADASTRAL_REVIEW}') $$, 'PC001', 'REVIEW_STAGES_ONLY_IF_UPHELD',
    'a dismissed objection sends nothing back');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The boundary was checked at the visit', '{}', 'Come to the office next week') $$, 'PC001', 'INSTRUCTIONS_ONLY_IF_UPHELD',
    'a dismissal asks nothing of the applicant');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'DISMISSED', 'The eastern boundary was checked against the markers at the visit') IS NOT NULL, 'a supervisor dismisses the first objection');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection ORDER BY received_at LIMIT 1),
    'UPHELD', 'On second thoughts the boundary is wrong', '{CADASTRAL_REVIEW}') $$, 'PC001', 'OBJECTION_ALREADY_DECIDED', '... once');
SELECT throws_ok($$ UPDATE procedure_objection_decision SET outcome = 'UPHELD' $$, '23001', NULL, 'a decision is never changed');

-- --- A late objection, an upheld one: the review it sends back, never a refusal ---------------------------------------

SELECT pg_temp.period_over();
SELECT is(pg_temp.status('PUBLICATION'), 'COMPLETED', 'the period over, the publication is completed');
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000aa03', 'DEMO Chief Essomba',
    'The parcel is customary land of the village (synthetic)', 'CUSTOMARY_AUTHORITY') IS NOT NULL,
    'an objection after the period is still recorded');
SELECT ok((SELECT late FROM procedure_objection WHERE objector_name = 'DEMO Chief Essomba'), '... marked late, and nothing is lost');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Heir Ngo'),
    'UPHELD', 'The heirs must be heard: the parcel is examined again', '{CADASTRAL_REVIEW}') IS NOT NULL, 'a supervisor upholds the heirs'' objection');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), 'UNDER_REVIEW',
    'an upheld objection never refuses the case');
SELECT is(pg_temp.status('CADASTRAL_REVIEW'), 'OUTDATED', '... it sends the stage it names back');
SELECT is(pg_temp.gate(), 'OBJECTION_REVIEW_PENDING:CADASTRAL_REVIEW,OBJECTION_OPEN', '... which holds the approval, with the late objection open');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b1', $q$ SELECT string_agg(stage || ':' || stage_status, ',') FROM snigf.procedure_queue(50, 0) $q$),
    'CADASTRAL_REVIEW:OUTDATED', 'the cadastral officer''s queue has the stage to examine again');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c6', $q$ SELECT string_agg(number, ',') FROM snigf.procedure_objection_queue(50, 0) $q$),
    (SELECT number FROM procedure_objection WHERE objector_name = 'DEMO Chief Essomba'), 'the supervisor''s queue has the late objection');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Chief Essomba'),
    'DISMISSED', 'The parcel was registered as private land in the 1980s (synthetic)') IS NOT NULL, 'the late objection is dismissed');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_REVIEW', 'COMPLETED', 'The heirs were heard; the parcel stands',
    '{}', pg_temp.latest('CADASTRAL_REVIEW'), 'Examined again after the heirs'' objection was upheld') IS NOT NULL,
    'the cadastral officer examines the parcel again');
SELECT is(pg_temp.gate(), 'PUBLISHED_BEFORE_CHANGE', 'the stage holds again; the publication now precedes it');
SELECT is(pg_temp.status('PUBLICATION'), 'OUTDATED', '... and is to be published anew');
SELECT ok(pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'PT5M', pg_temp.latest('PUBLICATION'), 'Published anew after the parcel was examined again')
    IS NOT NULL, 'the intake officer publishes anew');
SELECT is((SELECT count(*)::int FROM procedure_notice WHERE replaces_id IS NOT NULL), 1, '... a new notice naming the one it replaces');
SELECT is(pg_temp.val(NULL, 'SELECT string_agg(number, '','') FROM snigf.procedure_public_notices(50, 0)'),
    (SELECT number FROM procedure_notice WHERE replaces_id IS NOT NULL), 'the public sees the new notice only');
SELECT is(pg_temp.gate(), 'OBJECTION_PERIOD_OPEN', 'a new period opens');

-- --- The decision, and an objection arriving while it is pending ----------------------------------------------------

SELECT pg_temp.period_over();
SELECT is(pg_temp.gate(), '', 'the new period over, no objection open: nothing holds the approval');
SELECT is(pg_temp.public_count(), 0, 'a notice whose period is over is no longer listed');
SELECT is(pg_temp.step('00000000-0000-0000-0000-0000000000c2', 'RECOMMEND_APPROVAL') + pg_temp.move('00000000-0000-0000-0000-0000000000c2', 'PENDING_DECISION'),
    2, 'approval is recommended');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c4', 'DECISION'), 1, 'the decision authority claims the case');
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000aa04', 'DEMO Buyer Fouda',
    'I bought part of this parcel in 2015 (synthetic)', 'CLAIMANT') IS NOT NULL, 'an objection arrives while the decision is pending');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED') $$, 'PC001', 'OBJECTION_OPEN',
    'no approval while it is open');
SELECT throws_ok($$ SELECT pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'REFUSED') $$, 'PC001', 'OBJECTION_OPEN',
    '... nor a refusal');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Buyer Fouda'),
    'DISMISSED', 'No sale is recorded on this parcel; the claimant is told the civil route (synthetic)') IS NOT NULL, 'it is dismissed');
SELECT is(pg_temp.decide('00000000-0000-0000-0000-0000000000c4', 'APPROVED') + pg_temp.move('00000000-0000-0000-0000-0000000000c4', 'APPROVED'), 2,
    'the case is approved');

-- --- After the approval: registration blocked by an open or upheld objection (V34 calls the check) --------------------

SELECT is(array_to_string(procedure_registration_problems('00000000-0000-0000-0000-00000000d001'), ','), '', 'nothing blocks its registration');
SELECT is(pg_temp.public_count(), 0, 'an approved case has no public notice');
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000aa05', 'DEMO Neighbour Atangana',
    'The northern boundary takes my well (synthetic)') IS NOT NULL, 'a late objection is received after the approval');
SELECT is(array_to_string(procedure_registration_problems('00000000-0000-0000-0000-00000000d001'), ','), 'OBJECTION_OPEN',
    '... and blocks the registration while open');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Neighbour Atangana'),
    'UPHELD', 'The well lies inside the parcel as drawn: the boundary is examined again', '{CADASTRAL_REVIEW}') IS NOT NULL, 'it is upheld');
SELECT is(array_to_string(procedure_registration_problems('00000000-0000-0000-0000-00000000d001'), ','), 'OBJECTION_UPHELD_AFTER_DECISION',
    '... and still blocks the registration: the decision authority never saw it');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), 'APPROVED', '... without changing the case');

-- --- An upheld objection reopens only the stages it names, and a new publication follows (owner, 13 Nov 2026) -------

-- d002: a full procedure A case, every stage completed and published.
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_F', id
      FROM snigf.application_type WHERE code = 'DEMO_PROC_F' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED', declaration = '{"capacity": "SELF", "persons": [{"ref": "p1", "kind": "NATURAL_PERSON", "roles": ["APPLICANT", "INTENDED_OWNER"], "fullName": "DEMO Applicant Owner", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-SELF-0001", "phone": "+237 600000001"}], "shares": [{"person": "p1", "interest": "OWNERSHIP", "shareNum": 1, "shareDen": 1}]}'::jsonb,
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000002-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d002' $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.completeness_record (application_id, kind)
    VALUES ('00000000-0000-0000-0000-00000000d002', 'AUTOMATIC') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL,
    version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d002' $q$);
SELECT tap.parties_ready('00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c6');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d002', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$);
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ UPDATE snigf.application SET state = 'UNDER_REVIEW',
    state_changed_by = '00000000-0000-0000-0000-0000000000c1', version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d002' $q$);
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c3', 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre',
    p_case => '00000000-0000-0000-0000-00000000d002') IS NOT NULL
    AND pg_temp.rec('00000000-0000-0000-0000-0000000000c3', 'FIELD_VISIT', 'COMPLETED', 'Markers in place on every side',
        format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date), p_case => '00000000-0000-0000-0000-00000000d002') IS NOT NULL
    AND pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission agrees on the limits',
        format('{"reportReference": "PV-010", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
        p_case => '00000000-0000-0000-0000-00000000d002',
        p_files => '[{"storageKey": "abcdefabcdefabcdefabcdefabcdef01", "sha256": "' || repeat('d', 64) || '", "size": 30, "type": "application/pdf", "name": "pv10.pdf"}]') IS NOT NULL
    AND pg_temp.publish('00000000-0000-0000-0000-0000000000c1', p_case => '00000000-0000-0000-0000-00000000d002') IS NOT NULL,
    'a second case goes through every stage and is published');
CREATE TEMP TABLE before_upheld AS
    SELECT r.id, r.stage, r.outcome, r.finding, r.details, r.recorded_by, r.recorded_at,
           (SELECT array_agg(f.sha256 ORDER BY f.id) FROM snigf.procedure_stage_file f WHERE f.record_id = r.id) AS files
      FROM snigf.procedure_stage_record r WHERE r.application_id = '00000000-0000-0000-0000-00000000d002';
CREATE TEMP TABLE first_notice AS SELECT * FROM snigf.procedure_notice WHERE application_id = '00000000-0000-0000-0000-00000000d002';
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000bb01', 'DEMO Neighbour Owona',
    'The southern markers were moved onto my land (synthetic)', p_case => '00000000-0000-0000-0000-00000000d002') IS NOT NULL,
    'a neighbour objects to it at the counter');
SELECT throws_ok($$ SELECT pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Neighbour Owona'),
    'UPHELD', 'The southern boundary must be walked again with the neighbour', '{FIELD_VISIT}', 'Soon') $$, 'PC001', 'INSTRUCTIONS_INVALID',
    'instructions to the applicant say something');
SELECT ok(pg_temp.decide_objection('00000000-0000-0000-0000-0000000000c6', (SELECT id::text FROM procedure_objection WHERE objector_name = 'DEMO Neighbour Owona'),
    'UPHELD', 'The southern boundary must be walked again with the neighbour', '{FIELD_VISIT}',
    'Be present at the new field visit and show the boundary markers') IS NOT NULL, 'the supervisor upholds it, naming the field visit');

-- Only the stage it names is reopened; the others keep their records and their status.
SELECT is(pg_temp.status('FIELD_VISIT', '00000000-0000-0000-0000-00000000d002'), 'OUTDATED', 'the field visit is reopened');
SELECT is(pg_temp.status('CADASTRAL_REVIEW', '00000000-0000-0000-0000-00000000d002') || ',' || pg_temp.status('COMMISSION_REPORT', '00000000-0000-0000-0000-00000000d002')
    || ',' || pg_temp.status('PUBLICATION', '00000000-0000-0000-0000-00000000d002'), 'COMPLETED,COMPLETED,IN_PROGRESS',
    '... the other stages are not');
SELECT is(pg_temp.gate('00000000-0000-0000-0000-00000000d002'), 'OBJECTION_REVIEW_PENDING:FIELD_VISIT,OBJECTION_PERIOD_OPEN',
    '... and the gate names it alone');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c3', $q$ SELECT string_agg(stage, ',') FROM snigf.procedure_queue(50, 0)
    WHERE application_id = '00000000-0000-0000-0000-00000000d002' $q$), 'FIELD_VISIT', 'the cadastral officer''s queue has the field visit only');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d002'), 'UNDER_REVIEW', 'the case is not refused');
SELECT is((SELECT count(*)::int FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d002')
    + (SELECT count(*)::int FROM review_step WHERE application_id = '00000000-0000-0000-0000-00000000d002'), 0,
    '... no decision and no recommendation is written for it');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'PT5M', pg_temp.latest('PUBLICATION', '00000000-0000-0000-0000-00000000d002'),
    'Republished before the visit was made again', '00000000-0000-0000-0000-00000000d002') $$, 'PC001', 'EARLIER_STAGES_INCOMPLETE',
    'no new publication while the reopened stage is not recorded again');

-- What the applicant is told: the stages to carry out again, the publication after them, and what they must do.
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT array_to_string(stages, ',') || ' | ' || action || ' | ' || pending
    FROM snigf.application_procedure_instructions('00000000-0000-0000-0000-00000000d002') $q$),
    'FIELD_VISIT,PUBLICATION | Be present at the new field visit and show the boundary markers | true',
    'the applicant reads the stages to carry out again and what they must do');
SELECT is(pg_get_function_result('snigf.application_procedure_instructions(uuid)'::regprocedure),
    'TABLE(stages text[], action text, at timestamp with time zone, pending boolean)',
    '... and nothing of the objection: no number, objector, statement or grounds');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000ca', $q$ SELECT count(*)::text FROM snigf.application_procedure_instructions('00000000-0000-0000-0000-00000000d002') $q$),
    '0', 'another office reads no instruction');

-- The visit made again: its report, which depends on it, is reopened in turn; nothing earlier is changed.
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c3', 'FIELD_VISIT', 'COMPLETED', 'Southern markers walked again with the neighbour',
    format('{"visitedOn": "%s", "members": [{"name": "DEMO Member", "capacity": "Chief"}]}', current_date),
    pg_temp.latest('FIELD_VISIT', '00000000-0000-0000-0000-00000000d002'), 'Made again after the neighbour''s objection was upheld',
    '00000000-0000-0000-0000-00000000d002') IS NOT NULL, 'the cadastral officer records the visit again');
SELECT is(pg_temp.status('COMMISSION_REPORT', '00000000-0000-0000-0000-00000000d002') || ',' || pg_temp.status('PUBLICATION', '00000000-0000-0000-0000-00000000d002')
    || ',' || pg_temp.status('CADASTRAL_REVIEW', '00000000-0000-0000-0000-00000000d002'), 'OUTDATED,OUTDATED,COMPLETED',
    '... its report and the publication follow it; the cadastral review stays completed');
SELECT throws_ok($$ SELECT pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'PT5M', pg_temp.latest('PUBLICATION', '00000000-0000-0000-0000-00000000d002'),
    'Republished before the report was made again', '00000000-0000-0000-0000-00000000d002') $$, 'PC001', 'EARLIER_STAGES_INCOMPLETE',
    '... and no new publication until the report is made again');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000c7', 'COMMISSION_REPORT', 'COMPLETED', 'The commission confirms the limits walked again',
    format('{"reportReference": "PV-011", "reportDate": "%s", "signatories": [{"name": "DEMO Chair", "capacity": "Chair"}]}', current_date),
    pg_temp.latest('COMMISSION_REPORT', '00000000-0000-0000-0000-00000000d002'), 'The report follows the visit made again',
    '00000000-0000-0000-0000-00000000d002',
    '[{"storageKey": "abcdefabcdefabcdefabcdefabcdef02", "sha256": "' || repeat('e', 64) || '", "size": 31, "type": "application/pdf", "name": "pv11.pdf"}]')
    IS NOT NULL, 'the secretary records the report again');
SELECT is((SELECT count(*)::int FROM before_upheld b JOIN procedure_stage_record r ON r.id = b.id
            WHERE (r.stage, r.outcome, r.finding, r.details, r.recorded_by, r.recorded_at)
                  IS NOT DISTINCT FROM (b.stage, b.outcome, b.finding, b.details, b.recorded_by, b.recorded_at)
              AND (SELECT array_agg(f.sha256 ORDER BY f.id) FROM procedure_stage_file f WHERE f.record_id = r.id) IS NOT DISTINCT FROM b.files), 4,
    'every earlier record and its evidence is kept exactly as it was');
SELECT is((SELECT count(*)::int FROM procedure_stage_record WHERE application_id = '00000000-0000-0000-0000-00000000d002'), 6,
    '... the two records made again stand beside them');

-- A new publication: a new notice, a whole new period; the first notice kept as history.
SELECT ok(pg_temp.publish('00000000-0000-0000-0000-0000000000c1', 'PT5M', pg_temp.latest('PUBLICATION', '00000000-0000-0000-0000-00000000d002'),
    'Published anew after the boundary was walked again', '00000000-0000-0000-0000-00000000d002') IS NOT NULL, 'the notice is published anew');
SELECT ok((SELECT n.id <> f.id AND n.number <> f.number AND n.replaces_id = f.id AND n.opens_at > f.opens_at
             AND n.opens_at >= (SELECT max(recorded_at) FROM procedure_stage_record WHERE application_id = '00000000-0000-0000-0000-00000000d002' AND stage <> 'PUBLICATION')
             AND n.closes_at = n.opens_at + interval '5 minutes'
             FROM procedure_notice n, first_notice f WHERE n.application_id = f.application_id AND n.id <> f.id),
    '... a new number, a new period opening now, naming the notice it replaces');
SELECT ok((SELECT row(n.*) IS NOT DISTINCT FROM row(f.*) FROM procedure_notice n JOIN first_notice f ON f.id = n.id),
    '... the first notice kept exactly as it was');
SELECT is((SELECT n.number FROM procedure_objection o JOIN procedure_notice n ON n.id = o.notice_id WHERE o.objector_name = 'DEMO Neighbour Owona'),
    (SELECT number FROM first_notice), '... and the objection stays on the notice it was lodged against');
SELECT is(pg_temp.gate('00000000-0000-0000-0000-00000000d002'), 'OBJECTION_PERIOD_OPEN', 'the new period holds the approval');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT pending::text FROM snigf.application_procedure_instructions('00000000-0000-0000-0000-00000000d002') $q$),
    'false', 'the applicant''s instruction is no longer pending');
SELECT ok(pg_temp.object('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000bb02', 'DEMO Neighbour Mvondo',
    'The western hedge is mine (synthetic)', p_case => '00000000-0000-0000-0000-00000000d002') IS NOT NULL, 'a new objection is received');
SELECT ok((SELECT o.notice_id <> f.id FROM procedure_objection o, first_notice f WHERE o.objector_name = 'DEMO Neighbour Mvondo'),
    '... against the new notice');

-- --- What the applicant sees --------------------------------------------------------------------------------------

SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(stage || ':' || status, ',' ORDER BY rank)
    FROM snigf.application_stage_progress('00000000-0000-0000-0000-00000000d001') $q$),
    'CADASTRAL_REVIEW:OUTDATED,PUBLICATION:COMPLETED', 'the applicant sees each stage''s status, never an objection');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', 'SELECT count(*)::text FROM snigf.procedure_notice'), '0',
    '... nor the notice''s record');

SELECT * FROM finish();
ROLLBACK;
