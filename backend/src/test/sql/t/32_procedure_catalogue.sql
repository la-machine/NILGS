-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Procedures in the application type catalogue (Slice 4, V31, ADR-042 step 2, D23 and D26): the stage catalogue,
-- fixed and append-only; a type's procedure checked on insert (known stages of its own procedure, each once, in rank
-- order, with what they need, and its own register act); the seven permissions and their roles; the commission's
-- report owned by its secretary, delegable to a designated member, never the cadastral officer's.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(33);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b2 requests special grants, as a system administrator does.
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b2', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed');

CREATE FUNCTION pg_temp.req(p_procedure text) RETURNS jsonb
LANGUAGE sql AS $f$ SELECT ('{"fields":[],"documents":[],"procedure":' || p_procedure || '}')::jsonb $f$;

-- --- The stage catalogue --------------------------------------------------------------------------------

SELECT is((SELECT string_agg(code || ':' || procedure || rank, ',' ORDER BY procedure, rank) FROM procedure_stage),
    'CADASTRAL_REVIEW:A10,FIELD_VISIT:A20,COMMISSION_REPORT:A30,PUBLICATION:A40,TITLE_VERIFICATION:B10,DUTIES_EVIDENCE:B20',
    'six stages: four for procedure A, two for procedure B, in rank order');
SELECT is((SELECT requires FROM procedure_stage WHERE code = 'COMMISSION_REPORT'), 'FIELD_VISIT',
    'the commission''s report follows the field visit');
SELECT is((SELECT permission FROM procedure_stage WHERE code = 'COMMISSION_REPORT'), 'procedure.commission.report',
    'the commission''s report has its own permission (D26)');
SELECT isnt((SELECT permission FROM procedure_stage WHERE code = 'COMMISSION_REPORT'),
    (SELECT permission FROM procedure_stage WHERE code = 'FIELD_VISIT'),
    '... never the field visit''s');
SELECT throws_ok($$ UPDATE procedure_stage SET rank = 50 WHERE code = 'PUBLICATION' $$, '23001', NULL,
    'the catalogue is never updated');
SELECT throws_ok($$ DELETE FROM procedure_stage WHERE code = 'PUBLICATION' $$, '23001', NULL,
    'the catalogue is never deleted from');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.procedure_stage VALUES (''SURVEY'', ''A'', 50, NULL, ''parcel.read'', ''Survey'', ''Levé'')') $$,
    '42501', NULL, 'the application cannot add a stage');

-- --- A type's procedure ---------------------------------------------------------------------------------

SELECT is(application_procedure_problems('{"fields":[],"documents":[]}'), ARRAY[]::text[], 'a type without a procedure is valid');
SELECT is(application_procedure_problems('{"fields":[],"documents":[],"procedure":null}'), ARRAY[]::text[],
    '... and so is an explicit null');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW","FIELD_VISIT","COMMISSION_REPORT","PUBLICATION"],"registers":"CREATION","delivery":true}')),
    ARRAY[]::text[], 'procedure A as configured is valid');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"B","stages":["TITLE_VERIFICATION","DUTIES_EVIDENCE"],"registers":"TRANSFER","delivery":true}')),
    ARRAY[]::text[], 'procedure B as configured is valid');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW","SURVEY"],"registers":"CREATION","delivery":true}')),
    ARRAY['STAGE_UNKNOWN:SURVEY'], 'an unknown stage is refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW","TITLE_VERIFICATION"],"registers":"CREATION","delivery":true}')),
    ARRAY['STAGE_OF_ANOTHER_PROCEDURE:TITLE_VERIFICATION'], 'a stage of the other procedure is refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["FIELD_VISIT","FIELD_VISIT"],"registers":"CREATION","delivery":true}')),
    ARRAY['STAGE_TWICE:FIELD_VISIT'], 'a stage listed twice is refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["PUBLICATION","CADASTRAL_REVIEW"],"registers":"CREATION","delivery":true}')),
    ARRAY['STAGE_OUT_OF_ORDER:CADASTRAL_REVIEW'], 'stages out of rank order are refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW","COMMISSION_REPORT"],"registers":"CREATION","delivery":true}')),
    ARRAY['STAGE_NEEDS:COMMISSION_REPORT<FIELD_VISIT'], 'the commission''s report without the field visit before it is refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"B","stages":["TITLE_VERIFICATION"],"registers":"CREATION","delivery":true}')),
    ARRAY['PROCEDURE_REGISTERS_MISMATCH'], 'procedure B never creates a title (D28)');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW"],"registers":"TRANSFER","delivery":true}')),
    ARRAY['PROCEDURE_REGISTERS_MISMATCH'], 'procedure A never transfers');
SELECT ok('PROCEDURE_UNKNOWN' = ANY (application_procedure_problems(pg_temp.req('{"code":"C","stages":["CADASTRAL_REVIEW"],"registers":"CREATION","delivery":true}'))),
    'procedure C is not available');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":[],"registers":"CREATION","delivery":true}')),
    ARRAY['PROCEDURE_NO_STAGES'], 'a procedure without stages is refused');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW"],"registers":"CREATION","delivery":true,"skip":true}')),
    ARRAY['PROCEDURE_UNKNOWN_KEY'], 'an unknown key is refused, so a misspelt setting never passes');
SELECT is(application_procedure_problems(pg_temp.req('{"code":"A","stages":["CADASTRAL_REVIEW"],"registers":"CREATION","delivery":"yes"}')),
    ARRAY['PROCEDURE_DELIVERY_NOT_BOOLEAN'], 'delivery is a boolean');

SELECT throws_ok($$ INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements)
    VALUES ('DEMO_PROBE', 1, true, 'Sonde (DEMO)', 'Probe (DEMO)',
            pg_temp.req('{"code":"A","stages":["PUBLICATION","FIELD_VISIT"],"registers":"CREATION","delivery":true}')) $$,
    '23514', NULL, 'a type version with an invalid procedure is refused on insert');
SELECT lives_ok($$ INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements)
    VALUES ('DEMO_PROBE', 1, true, 'Sonde (DEMO)', 'Probe (DEMO)',
            pg_temp.req('{"code":"B","stages":["TITLE_VERIFICATION","DUTIES_EVIDENCE"],"registers":"TRANSFER","delivery":true}')) $$,
    '... and a valid one is stored');

-- --- Permissions and roles ------------------------------------------------------------------------------

SELECT is((SELECT count(*)::int FROM permission WHERE code LIKE 'procedure.%' AND family = 'BUSINESS' AND NOT professional), 8,
    'eight procedure permissions (V36: procedure.case.reopen), all BUSINESS, none professional');
SELECT is((SELECT string_agg(code, ',') FROM permission WHERE code LIKE 'procedure.%' AND delegable), 'procedure.commission.report',
    'only the commission''s report is delegable, so a designated member can receive it');
SELECT is((SELECT string_agg(permission || '@' || scope, ',' ORDER BY permission) FROM role_permission WHERE role_code = 'COMMISSION_SECRETARY'),
    'application.read@OFFICE,parcel.read@ANY,procedure.commission.report@OFFICE',
    'the commission secretary reads the office''s cases and records the commission''s report');
SELECT ok(EXISTS (SELECT 1 FROM role_permission WHERE role_code = 'CADASTRAL_OFFICER' AND permission = 'procedure.stage.cadastral')
          AND NOT EXISTS (SELECT 1 FROM role_permission WHERE role_code = 'CADASTRAL_OFFICER' AND permission = 'procedure.commission.report'),
    'the cadastral officer records the review and the visit, never the commission''s report (D26)');
SELECT is((SELECT string_agg(permission, ',' ORDER BY permission) FROM role_permission WHERE role_code = 'INTAKE_OFFICER' AND permission LIKE 'procedure.%'),
    'procedure.delivery.record,procedure.objection.record,procedure.stage.publish',
    'the intake officer publishes, receives objections and records the collection');
SELECT is((SELECT string_agg(role_code, ',') FROM role_permission WHERE permission = 'procedure.objection.decide'), 'SUPERVISOR',
    'only the supervisor decides objections');
SELECT is((SELECT string_agg(role_code, ',') FROM role_permission WHERE permission = 'procedure.stage.title'), 'CONSERVATOR',
    'only the conservator verifies the title and the duty receipt');

SELECT lives_ok($$ INSERT INTO permission_grant (id, user_id, permission, office_id, reason, requested_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'procedure.commission.report',
            '00000000-0000-0000-0000-0000000000a1', 'designated member of the commission', '00000000-0000-0000-0000-0000000000b2') $$,
    'a designated member can be asked a special grant of the commission''s report');
SELECT throws_ok($$ INSERT INTO permission_grant (id, user_id, permission, office_id, reason, requested_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'procedure.objection.decide',
            '00000000-0000-0000-0000-0000000000a1', 'decide objections for a week', '00000000-0000-0000-0000-0000000000b2') $$,
    '23514', NULL, '... but no other procedure permission can be granted specially');

SELECT * FROM finish();
ROLLBACK;
