-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Reconsideration rounds (Slice 4, V36, ADR-043 step 5b, D41, D42): an approved case is reopened only after an objection
-- upheld after its decision, by a supervisor independent of its handling, with a justification; the reopened stages are
-- those the objections named, any added, and the publication; the earlier round stays unchanged; the stages are recorded
-- again, the notice published anew, a new review and decision follow, by another decision authority; registration waits
-- for the latest round's approval; a refusal ends the case; a registered title is never reopened through a case.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(73);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer, then also a supervisor (a stage recorder); c1, c5 intake (persons, objections); c2 reviewer, also a
-- supervisor and a cadastral officer; c4 decision authority of round 0, also a supervisor; c6 supervisor (decides the
-- objections); c7 a second decision authority; c8 supervisor and reviewer (opens the reconsiderations); c9 supervisor;
-- f1, f4 conservators (the two signers); f2 conservator and intake officer (publishes); b3 applicant.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c2', 'r1@demo.invalid', 'Reviewer One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 'd1@demo.invalid', 'Decision One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'i2@demo.invalid', 'Counter', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c6', 'p1@demo.invalid', 'Supervisor Objections', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c7', 'd2@demo.invalid', 'Decision Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c8', 'p2@demo.invalid', 'Supervisor Reopens', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c9', 'p3@demo.invalid', 'Supervisor Three', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'k1@demo.invalid', 'Conservator One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f2', 'k2@demo.invalid', 'Conservator Publisher', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f4', 'k4@demo.invalid', 'Conservator Four', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
SELECT gen_random_uuid(), u::uuid, r, '00000000-0000-0000-0000-0000000000a1', 'seed'
  FROM (VALUES ('00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER'), ('00000000-0000-0000-0000-0000000000b1', 'SUPERVISOR'),
               ('00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER'), ('00000000-0000-0000-0000-0000000000c5', 'INTAKE_OFFICER'),
               ('00000000-0000-0000-0000-0000000000c2', 'REVIEWER'), ('00000000-0000-0000-0000-0000000000c2', 'SUPERVISOR'),
               ('00000000-0000-0000-0000-0000000000c2', 'CADASTRAL_OFFICER'),
               ('00000000-0000-0000-0000-0000000000c4', 'DECISION_AUTHORITY'), ('00000000-0000-0000-0000-0000000000c4', 'SUPERVISOR'),
               ('00000000-0000-0000-0000-0000000000c6', 'SUPERVISOR'), ('00000000-0000-0000-0000-0000000000c7', 'DECISION_AUTHORITY'),
               ('00000000-0000-0000-0000-0000000000c8', 'SUPERVISOR'), ('00000000-0000-0000-0000-0000000000c8', 'REVIEWER'),
               ('00000000-0000-0000-0000-0000000000c9', 'SUPERVISOR'),
               ('00000000-0000-0000-0000-0000000000f1', 'CONSERVATOR'), ('00000000-0000-0000-0000-0000000000f4', 'CONSERVATOR'),
               ('00000000-0000-0000-0000-0000000000f2', 'CONSERVATOR'), ('00000000-0000-0000-0000-0000000000f2', 'INTAKE_OFFICER')) AS x(u, r);
INSERT INTO user_entities (id, name, display_name) VALUES
    ('h1', '00000000-0000-0000-0000-0000000000f1', 'One'), ('h4', '00000000-0000-0000-0000-0000000000f4', 'Four');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('k1', 'h1', '\x0101', false, false, 'Key one'), ('k4', 'h4', '\x0404', false, false, 'Key four');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_PROC_P', 1, true, 'Procédure A publiée (DEMO)', 'Procedure A published (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
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
CREATE TEMP TABLE ctx (k text PRIMARY KEY, v text);
GRANT ALL ON ctx TO PUBLIC;
CREATE FUNCTION pg_temp.c(p_key text) RETURNS text LANGUAGE sql AS $f$ SELECT v FROM ctx WHERE k = p_key $f$;

-- A first record of a stage, or a correction of its latest one.
CREATE FUNCTION pg_temp.rec(p_actor text, p_case text, p_stage text, p_finding text, p_details text DEFAULT '{}') RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_stage_record_add(%L, %L, 'COMPLETED', %L, %L::jsonb, '{}',
        (snigf.procedure_stage_latest(%L, %L)).id,
        CASE WHEN (snigf.procedure_stage_latest(%L, %L)).id IS NULL THEN NULL ELSE 'Recorded again after the reconsideration' END,
        '[]'::jsonb) $q$, p_case, p_stage, p_finding, p_details, p_case, p_stage, p_case, p_stage))
$f$;
-- The notice's period, made to have ended.
CREATE FUNCTION pg_temp.age_notices() RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    ALTER TABLE snigf.procedure_notice DISABLE TRIGGER procedure_notice_append_only;
    UPDATE snigf.procedure_notice SET opens_at = opens_at - interval '1 day', closes_at = closes_at - interval '1 day' WHERE closes_at > now();
    ALTER TABLE snigf.procedure_notice ENABLE TRIGGER procedure_notice_append_only;
END;
$f$;
CREATE FUNCTION pg_temp.claim(p_actor text, p_case text, p_stage text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES (%L, %L, %L, %L) $q$, p_case, p_stage, p_actor, p_actor))
$f$;
CREATE FUNCTION pg_temp.move(p_actor text, p_case text, p_state text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ UPDATE snigf.application SET state = %L, state_changed_by = %L, version = version + 1
        WHERE id = %L $q$, p_state, p_actor, p_case))
$f$;
CREATE FUNCTION pg_temp.recommend(p_actor text, p_case text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES (%L, 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Boundaries and occupation are consistent', %L) $q$, p_case, p_actor))
    + pg_temp.move(p_actor, p_case, 'PENDING_DECISION')
$f$;
CREATE FUNCTION pg_temp.decide(p_actor text, p_case text, p_outcome text) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act(p_actor, format($q$ INSERT INTO snigf.decision (application_id, outcome, grounds, cited_versions, follows_step_id, decided_by)
        VALUES (%L, %L, 'The procedure''s record supports it', ARRAY[(SELECT dv.id FROM snigf.document_version dv JOIN snigf.document d
                ON d.id = dv.document_id WHERE d.application_id = %L LIMIT 1)],
                (SELECT id FROM snigf.review_step WHERE application_id = %L AND kind = 'RECOMMENDATION' ORDER BY at DESC LIMIT 1), %L) $q$,
        p_case, p_outcome, p_case, p_case, p_actor))
    + pg_temp.move(p_actor, p_case, p_outcome)
$f$;
-- An objection received at the counter by c5, and upheld by c6, naming the stages to examine again.
CREATE FUNCTION pg_temp.upheld(p_case text, p_key text, p_stages text[]) RETURNS text
LANGUAGE plpgsql AS $f$
DECLARE o text;
BEGIN
    o := pg_temp.val('00000000-0000-0000-0000-0000000000c5', format($q$ SELECT objection_id::text FROM snigf.procedure_objection_add(%L,
        '{"name": "DEMO Neighbour", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-0042", "interest": "NEIGHBOUR"}'::jsonb,
        'The northern boundary takes my well (synthetic)', '[]'::jsonb, %L::uuid) $q$, p_case, p_key));
    PERFORM pg_temp.val('00000000-0000-0000-0000-0000000000c6', format($q$ SELECT snigf.procedure_objection_decide(%L, 'UPHELD',
        'The well is inside the parcel as surveyed (synthetic)', %L::text[], NULL) $q$, o, p_stages));
    RETURN o;
END;
$f$;
CREATE FUNCTION pg_temp.reopen(p_actor text, p_case text, p_added text[] DEFAULT '{}',
                               p_justification text DEFAULT 'The upheld objection was not before the decision authority (synthetic)',
                               p_version bigint DEFAULT NULL) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($q$ SELECT snigf.procedure_reconsider(%L, %s, %L::text[], %L)::text $q$, p_case,
        coalesce(p_version::text, format('(SELECT version FROM snigf.application WHERE id = %L)', p_case)), p_added, p_justification))
$f$;
CREATE FUNCTION pg_temp.problem(p_actor text, p_case text) RETURNS text
LANGUAGE sql AS $f$ SELECT snigf.procedure_reconsider_problem(p_case::uuid, p_actor::uuid) $f$;
CREATE FUNCTION pg_temp.reg(p_case text) RETURNS text
LANGUAGE sql AS $f$ SELECT array_to_string(snigf.register_case_problems(p_case::uuid, NULL), ',') $f$;
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
                              'officerSignature', encode('\x0a0b'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA');
END;
$f$;
CREATE FUNCTION pg_temp.sign(p_act text, p_actor text, p_credential text, p_key bytea, p_inscription text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.val(p_actor, format($s$SELECT snigf.%I(%L, %L::bytea, %L::jsonb)::text$s$, p_act, p_inscription,
        snigf.inscription_canonical(p_inscription::uuid), pg_temp.sig(p_inscription::uuid, p_credential, p_key)))
$f$;

-- Two procedure A cases on one parcel, each with a stored document, taken through intake to an approval in round 0:
-- d001 and d002. b1 records the cadastral review, f2 publishes, c2 reviews, c4 decides.
DO $$
DECLARE
    r snigf.geometry_check_result := snigf.normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[910000,400000],[910100,400000],[910100,400100],[910000,400100],[910000,400000]]]}', 32632);
    c record;
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-000000004001', 'DEMO-PCL-DEMO1-2026-000001-Z', '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-000000005001', '00000000-0000-0000-0000-000000004001', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
    FOR c IN SELECT * FROM (VALUES ('00000000-0000-0000-0000-00000000d001', '000001', 'e9', 'e1'), ('00000000-0000-0000-0000-00000000d002', '000002', 'ea', 'e2'))
             AS x(id, n, doc, ver) LOOP
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
            SELECT %L, '00000000-0000-0000-0000-0000000000b3', 'DEMO_PROC_P', id FROM snigf.application_type WHERE code = 'DEMO_PROC_P' $q$, c.id));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.document (id, application_id, category)
            VALUES ('00000000-0000-0000-0000-0000000000%s', %L, 'DEMO_ID') $q$, c.doc, c.id));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.document_version (id, document_id, version_no,
            status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
            VALUES ('00000000-0000-0000-0000-0000000000%s', '00000000-0000-0000-0000-0000000000%s', 1, 'STORED', %L, repeat('a', 64), 10,
                    'application/pdf', 'id.pdf', '00000000-0000-0000-0000-0000000000b3') $q$, c.ver, c.doc, rpad(c.n, 32, '0')));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
            declaration = tap.self_declaration(), parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z',
            parcel_id = '00000000-0000-0000-0000-000000004001', office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-%s-X',
            state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = %L $q$, c.n, c.id));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.completeness_record (application_id, kind)
            VALUES (%L, 'AUTOMATIC') $q$, c.id));
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW',
            state_changed_by = NULL, version = version + 1 WHERE id = %L $q$, c.id));
        PERFORM tap.parties_ready(c.id::uuid, '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c5');
        PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000c1', format($q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
            VALUES (%L, 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$, c.id));
        PERFORM pg_temp.move('00000000-0000-0000-0000-0000000000c1', c.id, 'UNDER_REVIEW');
        PERFORM pg_temp.rec('00000000-0000-0000-0000-0000000000b1', c.id, 'CADASTRAL_REVIEW', 'The parcel matches the cadastre');
        PERFORM pg_temp.rec('00000000-0000-0000-0000-0000000000f2', c.id, 'PUBLICATION', 'Posted on the office board', '{"objectionPeriod": "PT5M"}');
        PERFORM pg_temp.age_notices();
        PERFORM pg_temp.claim('00000000-0000-0000-0000-0000000000c2', c.id, 'REVIEW');
        PERFORM pg_temp.recommend('00000000-0000-0000-0000-0000000000c2', c.id);
        PERFORM pg_temp.claim('00000000-0000-0000-0000-0000000000c4', c.id, 'DECISION');
        PERFORM pg_temp.decide('00000000-0000-0000-0000-0000000000c4', c.id, 'APPROVED');
    END LOOP;
END $$;

SELECT is((SELECT string_agg(state || ':' || review_round, ',' ORDER BY id) FROM application), 'APPROVED:0,APPROVED:0',
    'both cases approved in round 0');
SELECT is((SELECT string_agg(DISTINCT round::text, ',') FROM decision), '0', 'their decisions, assignments and steps are of round 0');

-- --- No grounds, no reconsideration -------------------------------------------------------------------------------------

SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001'), 'GROUNDS_MISSING',
    'nothing reopens an approved case without an objection upheld after its decision (D41)');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001') $$,
    'PC001', 'GROUNDS_MISSING', '... the writer refuses it too');

-- d001: a CREATION is proposed, then an objection is received and upheld after the decision.
DO $$ BEGIN
    INSERT INTO ctx VALUES ('first', pg_temp.val('00000000-0000-0000-0000-0000000000f1',
        $q$ SELECT snigf.register_propose_from_case('00000000-0000-0000-0000-00000000d001', 'Approved immatriculation (synthetic)')::text $q$));
    INSERT INTO ctx VALUES ('objection', pg_temp.upheld('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000aa01',
                                                         ARRAY['CADASTRAL_REVIEW']));
END $$;
SELECT ok(pg_temp.reg('00000000-0000-0000-0000-00000000d001') LIKE '%OBJECTION_UPHELD_AFTER_DECISION%',
    'the objection upheld after the decision stops the registration');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001'), 'CREATION_IN_PROGRESS',
    'a CREATION in progress: a conservator withdraws or rejects it first, never closed automatically');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('first')::uuid), 'PROPOSED', '... and it is still proposed');
DO $$ BEGIN
    PERFORM pg_temp.val('00000000-0000-0000-0000-0000000000f1', format($q$ SELECT snigf.register_close_proposal(%L, 'WITHDRAWN',
        'Withdrawn: an objection was upheld after the decision') $q$, pg_temp.c('first')));
END $$;

-- --- Who opens it --------------------------------------------------------------------------------------------------------

SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000d001'), 'PROCEDURE_FORBIDDEN',
    'an intake officer holds no procedure.case.reopen');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-00000000d001'), 'PROCEDURE_FORBIDDEN',
    '... nor a decision authority');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-00000000d001'), 'DECISION_AUTHORITY_CANNOT_REOPEN',
    'a supervisor who decided the case never reopens it');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d001'), 'ASSIGNEE_CANNOT_REOPEN',
    '... nor one who reviewed it');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000d001'), 'STAGE_RECORDER_CANNOT_REOPEN',
    '... nor one who recorded a stage of it');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c6', '00000000-0000-0000-0000-00000000d001'), 'OBJECTION_DECIDER_CANNOT_REOPEN',
    '... nor the supervisor who upheld the objection: one upholds, another reopens');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001'), NULL,
    'an independent supervisor may');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c6', '00000000-0000-0000-0000-00000000d001') $$,
    'PC001', 'OBJECTION_DECIDER_CANNOT_REOPEN', 'the writer refuses whoever the screen would');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001', '{}',
    'Too short', NULL) $$, 'PC001', 'JUSTIFICATION_REQUIRED', 'a written justification of at least 20 characters');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001', '{}',
    'The upheld objection was not before the decision authority (synthetic)', 0) $$, 'PC001', 'STALE_VERSION',
    'the case as the supervisor saw it');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001',
    ARRAY['FIELD_VISIT']) $$, 'PC001', 'REOPEN_STAGE_INVALID', 'only stages of the case''s own procedure are added');
SELECT throws_ok($$ SELECT pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001',
    ARRAY['PUBLICATION', 'PUBLICATION']) $$, 'PC001', 'REOPEN_STAGE_INVALID', '... each once');

-- --- Opening round 1 ---------------------------------------------------------------------------------------------------------

DO $$ BEGIN
    INSERT INTO ctx VALUES ('stages_before', (SELECT count(*)::text FROM procedure_stage_record WHERE application_id = '00000000-0000-0000-0000-00000000d001'));
    INSERT INTO ctx VALUES ('reconsideration', pg_temp.reopen('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001'));
END $$;
SELECT is((SELECT state || ':' || review_round FROM application WHERE id = '00000000-0000-0000-0000-00000000d001'), 'UNDER_REVIEW:1',
    'the case is under review again, in round 1');
SELECT is((SELECT array_to_string(stages, ',') || '|' || array_to_string(added_stages, ',') || '|' || round || '|' || cardinality(objection_ids)
             FROM case_reconsideration WHERE id = pg_temp.c('reconsideration')::uuid), 'CADASTRAL_REVIEW,PUBLICATION||1|1',
    'the stages the objection named and the publication, in the procedure''s order; one objection answered');
SELECT is((SELECT reconsiders_decision_id FROM case_reconsideration WHERE id = pg_temp.c('reconsideration')::uuid),
          (SELECT id FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001' AND round = 0),
    'it names the decision it reconsiders');
SELECT is((SELECT from_state || '>' || to_state || ':' || actor_id || ':' || reason FROM application_transition
            WHERE application_id = '00000000-0000-0000-0000-00000000d001' ORDER BY at DESC LIMIT 1),
    'APPROVED>UNDER_REVIEW:00000000-0000-0000-0000-0000000000c8:RECONSIDERATION', 'the one new transition is in the history, by its supervisor');

-- What was before stays as it was.
SELECT is((SELECT outcome || ':' || round || ':' || decided_by FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'APPROVED:0:00000000-0000-0000-0000-0000000000c4', 'the original decision is kept, unchanged, the only one');
SELECT is((SELECT count(*)::text FROM procedure_stage_record WHERE application_id = '00000000-0000-0000-0000-00000000d001'), pg_temp.c('stages_before'),
    'no stage record is changed or added by opening');
SELECT is((SELECT outcome FROM procedure_objection_decision WHERE objection_id = pg_temp.c('objection')::uuid), 'UPHELD', 'the objection''s decision stays');
SELECT throws_ok($$ UPDATE snigf.case_reconsideration SET justification = 'Rewritten after the fact, quietly' $$, '23001', NULL,
    'a reconsideration is never changed');
SELECT throws_ok($$ DELETE FROM snigf.case_reconsideration $$, '23001', NULL, '... nor deleted');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c8', $q$ INSERT INTO snigf.case_reconsideration (application_id, round,
    reconsiders_decision_id, objection_ids, stages, justification, opened_by) SELECT application_id, 2, id, '{00000000-0000-0000-0000-000000000001}',
    '{PUBLICATION}', 'Written around the function, directly', '00000000-0000-0000-0000-0000000000c8' FROM snigf.decision LIMIT 1 $q$) $$,
    '42501', NULL, 'only the function writes one');

-- One open at a time; registration waits.
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000d001'), 'CASE_NOT_APPROVED',
    'one reconsideration open at a time: the case is no longer approved');
SELECT ok(pg_temp.reg('00000000-0000-0000-0000-00000000d001') LIKE 'CASE_NOT_APPROVED%', 'no registration while it is open');
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000f1',
    $q$ SELECT snigf.register_propose_from_case('00000000-0000-0000-0000-00000000d001', 'Proposed during the reconsideration')::text $q$) $$,
    'RG001', 'CASE_NOT_APPROVED', '... nor a proposal');

-- The other move out of APPROVED, around the function, is refused.
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c4', $q$ UPDATE snigf.application SET state = 'UNDER_REVIEW',
    state_changed_by = '00000000-0000-0000-0000-0000000000c4', version = version + 1 WHERE id = '00000000-0000-0000-0000-00000000d002' $q$) $$,
    '23001', NULL, 'APPROVED -> UNDER_REVIEW only through a reconsideration');
SELECT throws_ok($$ UPDATE snigf.application SET review_round = review_round + 1, version = version + 1
    WHERE id = '00000000-0000-0000-0000-00000000d002' $$, '23001', NULL, 'the round moves only with a reconsideration');

-- --- The reopened stages --------------------------------------------------------------------------------------------------------

SELECT is(procedure_stage_status('00000000-0000-0000-0000-00000000d001', 'CADASTRAL_REVIEW'), 'OUTDATED', 'the cadastral review is to be recorded again');
SELECT ok('RECONSIDERATION_PENDING:PUBLICATION' = ANY (procedure_stage_problems('00000000-0000-0000-0000-00000000d001', 'PUBLICATION')),
    '... and the publication, reopened by the reconsideration');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(round || ':' || array_to_string(stages, '+') || ':' || decided, ',')
        FROM snigf.application_reconsideration('00000000-0000-0000-0000-00000000d001') $q$), '1:CADASTRAL_REVIEW+PUBLICATION:false',
    'the applicant reads the round, the stages carried out again, and that the new decision is not made yet');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT count(*)::text FROM snigf.procedure_reconsiderations('00000000-0000-0000-0000-00000000d001') $q$),
    '0', '... never the justification, the objections or who opened it');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c9', $q$ SELECT (round = 1 AND reconsidered_outcome = 'APPROVED'
        AND objection_numbers[1] LIKE 'DEMO-OBJ-%' AND cardinality(objection_numbers) = 1 AND opened_by_name = 'Supervisor Reopens'
        AND round_outcome IS NULL AND justification LIKE 'The upheld objection%')::text
        FROM snigf.procedure_reconsiderations('00000000-0000-0000-0000-00000000d001') $q$), 'true', 'staff of the office read the reconsideration: round, decision reconsidered, objections, who opened it');

-- Whoever handled the case in round 0 stays apart from round 1's stages.
SELECT throws_ok($$ SELECT pg_temp.rec('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d001', 'CADASTRAL_REVIEW',
    'Recorded by the reviewer of round 0') $$, 'PC001', 'ASSIGNEE_CANNOT_RECORD', 'the reviewer of an earlier round never records a stage of the case');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000c2', $q$ SELECT snigf.procedure_may_record('00000000-0000-0000-0000-00000000d001',
    'CADASTRAL_REVIEW')::text $q$), 'false', '... and the screens do not offer it');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000d001', 'CADASTRAL_REVIEW',
    'The boundary was surveyed again: the well is outside') IS NOT NULL, 'the cadastral review is recorded again, as a correction');
SELECT ok(pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d001', 'PUBLICATION', 'Posted again on the office board',
    '{"objectionPeriod": "PT5M"}') IS NOT NULL, 'the notice is published anew');
SELECT is((SELECT count(*)::int FROM procedure_notice WHERE application_id = '00000000-0000-0000-0000-00000000d001'), 2, '... a second notice');
SELECT ok((SELECT closes_at > now() AND replaces_id IS NOT NULL FROM procedure_notice WHERE application_id = '00000000-0000-0000-0000-00000000d001'
            ORDER BY opens_at DESC LIMIT 1), '... with a whole new period, replacing the first');
SELECT is((SELECT count(*)::int FROM procedure_public_notices(50, 0)), 1, 'the new notice is public while its period runs');
SELECT is(procedure_stage_status('00000000-0000-0000-0000-00000000d001', 'PUBLICATION'), 'IN_PROGRESS', 'the publication is in progress');
DO $$ BEGIN PERFORM pg_temp.age_notices(); END $$;
SELECT is(array_to_string(procedure_gate_problems('00000000-0000-0000-0000-00000000d001'), ','), '', 'every stage holds again');

-- --- A new review and a new decision, by another decision authority ---------------------------------------------------

SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d001', 'REVIEW') $$,
    'PC001', 'RECONSIDERATION_OPENER_CANNOT_REVIEW', 'whoever opened the reconsideration never reviews the case');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d001', 'REVIEW')
          + pg_temp.recommend('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d001'), 3,
    'round 1 is claimed at review again, and recommended');
SELECT is((SELECT string_agg(stage || ':' || round, ',' ORDER BY round, stage) FROM case_assignment WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'DECISION:0,REVIEW:0,REVIEW:1', 'one assignment per stage and round');
SELECT throws_ok($$ SELECT pg_temp.claim('00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-00000000d001', 'DECISION') $$,
    'PC001', 'EARLIER_DECIDER_CANNOT_DECIDE', 'the decision authority of round 0 never decides round 1');
SELECT is((SELECT count(*)::int FROM workload_open_cases('DEMO1', '{}'::jsonb) w WHERE w.application_id = '00000000-0000-0000-0000-00000000d001'), 1,
    'the workload shows the case once, with its current round''s assignee');
SELECT is(pg_temp.claim('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-00000000d001', 'DECISION')
          + pg_temp.decide('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-00000000d001', 'APPROVED'), 3,
    'another decision authority approves it in round 1');
SELECT is((SELECT string_agg(outcome || ':' || round, ',' ORDER BY round) FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d001'),
    'APPROVED:0,APPROVED:1', 'both decisions are kept');
SELECT is((case_decision_current('00000000-0000-0000-0000-00000000d001')).decided_by, '00000000-0000-0000-0000-0000000000c7'::uuid,
    'the current decision is the latest round''s');
SELECT is(pg_temp.val('00000000-0000-0000-0000-0000000000b3', $q$ SELECT string_agg(round || ':' || decided, ',')
        FROM snigf.application_reconsideration('00000000-0000-0000-0000-00000000d001') $q$), '1:true', 'the applicant reads that the new decision is made');

-- --- Registration from the latest round's approval ------------------------------------------------------------------------

SELECT is(pg_temp.reg('00000000-0000-0000-0000-00000000d001'), '', 'nothing stops the registration any more');
DO $$ BEGIN
    INSERT INTO ctx VALUES ('second', pg_temp.val('00000000-0000-0000-0000-0000000000f1',
        $q$ SELECT snigf.register_propose_from_case('00000000-0000-0000-0000-00000000d001', 'Approved again after reconsideration (synthetic)')::text $q$));
END $$;
SELECT is((SELECT decision_id FROM inscription WHERE id = pg_temp.c('second')::uuid), (case_decision_current('00000000-0000-0000-0000-00000000d001')).id,
    'the new proposal names round 1''s decision');
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('first')::uuid), 'WITHDRAWN', 'the earlier proposal stays withdrawn');
DO $$ BEGIN
    PERFORM pg_temp.sign('register_request_confirmation', '00000000-0000-0000-0000-0000000000f1', 'k1', '\x0101', pg_temp.c('second'));
    PERFORM pg_temp.sign('register_confirm', '00000000-0000-0000-0000-0000000000f4', 'k4', '\x0404', pg_temp.c('second'));
END $$;
SELECT is((SELECT status FROM inscription WHERE id = pg_temp.c('second')::uuid), 'REGISTERED', 'signed by two conservators, registered');

-- A registered title is never reopened through its case (D42).
DO $$ BEGIN
    PERFORM pg_temp.upheld('00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-00000000aa02', ARRAY['PUBLICATION']);
END $$;
SELECT throws_ok($$ SELECT pg_temp.val('00000000-0000-0000-0000-0000000000c5', $q$ SELECT objection_id::text FROM snigf.procedure_objection_add(
    '00000000-0000-0000-0000-00000000d001', '{"name": "DEMO Late", "identityKind": "NATIONAL_ID", "identityNumber": "DEMO-ID-0099",
    "interest": "CLAIMANT"}'::jsonb, 'An objection after the registration (synthetic)', '[]'::jsonb, '00000000-0000-0000-0000-00000000aa03'::uuid) $q$) $$,
    'PC001', 'CASE_REGISTERED', 'no objection after the registration, so no grounds');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000d001'), 'CASE_REGISTERED',
    'and no reconsideration: ADR-040''s correction or cancellation, under dual control, instead');

-- --- d002: more stages, then a refusal that ends the case -------------------------------------------------------------------

DO $$ BEGIN
    INSERT INTO ctx VALUES ('r2', pg_temp.reopen('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-00000000d002', ARRAY['CADASTRAL_REVIEW'],
        'The neighbour''s objection also puts the cadastral review in doubt (synthetic)'));
END $$;
SELECT is((SELECT array_to_string(stages, ',') || '|' || array_to_string(added_stages, ',') FROM case_reconsideration WHERE id = pg_temp.c('r2')::uuid),
    'CADASTRAL_REVIEW,PUBLICATION|CADASTRAL_REVIEW', 'the supervisor may add a stage, with the justification; it is recorded as added');
SELECT is(procedure_stage_status('00000000-0000-0000-0000-00000000d002', 'CADASTRAL_REVIEW'), 'OUTDATED', '... and it is reopened');
DO $$ BEGIN
    PERFORM pg_temp.rec('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000d002', 'CADASTRAL_REVIEW', 'Surveyed again (synthetic)');
    PERFORM pg_temp.rec('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000d002', 'PUBLICATION', 'Posted again',
        '{"objectionPeriod": "PT5M"}');
    PERFORM pg_temp.age_notices();
    PERFORM pg_temp.claim('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d002', 'REVIEW');
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000c2', $q$ INSERT INTO snigf.review_step (application_id, kind, recommendation, text, actor_id)
        VALUES ('00000000-0000-0000-0000-00000000d002', 'RECOMMENDATION', 'RECOMMEND_REFUSAL', 'The well lies inside the parcel (synthetic)',
                '00000000-0000-0000-0000-0000000000c2') $q$);
    PERFORM pg_temp.move('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-00000000d002', 'PENDING_DECISION');
    PERFORM pg_temp.claim('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-00000000d002', 'DECISION');
END $$;
SELECT is(pg_temp.decide('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-00000000d002', 'REFUSED'), 2,
    'round 1 refuses the case');
SELECT is((SELECT state FROM application WHERE id = '00000000-0000-0000-0000-00000000d002'), 'REFUSED', 'the refusal ends the case');
SELECT is(pg_temp.problem('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-00000000d002'), 'CASE_NOT_APPROVED',
    'nothing reopens a refused case');
SELECT ok(pg_temp.reg('00000000-0000-0000-0000-00000000d002') LIKE 'CASE_NOT_APPROVED%', '... nor registers it, whatever round 0 said');
SELECT is((SELECT string_agg(outcome || ':' || round, ',' ORDER BY round) FROM decision WHERE application_id = '00000000-0000-0000-0000-00000000d002'),
    'APPROVED:0,REFUSED:1', 'round 0''s approval stays in the record, superseded');

-- --- Privileges ---------------------------------------------------------------------------------------------------------------

SELECT ok(NOT has_table_privilege('snigf_app', 'snigf.case_reconsideration', 'INSERT, UPDATE, DELETE'), 'the application writes no reconsideration');
SELECT ok(NOT has_function_privilege('snigf_app', 'snigf.case_round_guard()', 'EXECUTE'), 'nor calls the round guard');
SELECT ok(has_function_privilege('snigf_app', 'snigf.procedure_reconsider(uuid, bigint, text[], text)', 'EXECUTE'), 'it calls the writer');

SELECT * FROM finish();
ROLLBACK;
