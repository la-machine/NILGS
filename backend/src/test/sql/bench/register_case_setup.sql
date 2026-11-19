-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Fixture of the registration-against-objection race (infra/scripts/db-test.sh, ADR-042 step 5): forty-one approved
-- procedure A cases at the bench office, each through its cadastral review, its publication (the period moved into the
-- past), its persons checked (ADR-043) and its approval, with a CREATION proposed from it and signed first by a conservator
-- who never handled it, and the second conservator's prepared signature (D40). An intake officer receives objections at the counter. Runs after
-- register_replacement_setup.sql, on the throwaway test database, as its owner: every write goes through the functions
-- and triggers the application uses, under the actor each step names.
SET search_path = snigf, public;

INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-00000000bc01', 'bench-applicant@demo.invalid', 'Bench Applicant', 'x', 'APPLICANT'),
    ('00000000-0000-0000-0000-00000000bc02', 'bench-intake@demo.invalid', 'Bench Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc03', 'bench-cadastre@demo.invalid', 'Bench Cadastre', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc04', 'bench-reviewer@demo.invalid', 'Bench Reviewer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc05', 'bench-authority@demo.invalid', 'Bench Authority', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc06', 'bench-counter@demo.invalid', 'Bench Counter', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc07', 'bench-objections@demo.invalid', 'Bench Objections', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc08', 'bench-reopen-a@demo.invalid', 'Bench Reopen A', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-00000000bc09', 'bench-reopen-b@demo.invalid', 'Bench Reopen B', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc02', 'INTAKE_OFFICER', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc03', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc04', 'REVIEWER', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc05', 'DECISION_AUTHORITY', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc06', 'INTAKE_OFFICER', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc07', 'SUPERVISOR', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc08', 'SUPERVISOR', '00000000-0000-0000-0000-00000000ba01', 'bench'),
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000bc09', 'SUPERVISOR', '00000000-0000-0000-0000-00000000ba01', 'bench');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_BENCH_A', 1, true, 'Procédure A de banc (DEMO)', 'Bench procedure A (DEMO)',
     '{"fields": [], "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "A", "stages": ["CADASTRAL_REVIEW", "PUBLICATION"], "registers": "CREATION", "delivery": true}}');

-- The second signer of each CREATION (ADR-043 D40), with their key.
INSERT INTO user_entities (id, name, display_name) VALUES ('hb8', '00000000-0000-0000-0000-00000000ba08', 'Bench Two');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('kb8', 'hb8', '\x0808', false, false, 'Bench key two');

CREATE TABLE bench_case_race (round int PRIMARY KEY, application uuid, inscription uuid, canonical bytea, signature text, person uuid,
                              identity uuid, id_file uuid);
GRANT SELECT ON bench_case_race TO snigf_app;

CREATE FUNCTION pg_temp.as_actor(p_actor text) RETURNS void LANGUAGE sql AS $f$
    SELECT set_config('snigf.actor_id', p_actor, false)
$f$;

DO $$
DECLARE
    i        int;
    x        int;
    r        geometry_check_result;
    parcel   uuid;
    app      uuid;
    doc      uuid;
    ver      uuid;
    step     uuid;
    proposal uuid;
    canon    bytea;
    nonce    bytea;
BEGIN
    -- Round 21 is the control: registered alone, it proves the fixture registers at all.
    -- Rounds 1 to 20 race an objection, 21 is the control, 22 to 31 race an owner's identity correction (ADR-043), 32 to 41
    -- race two supervisors opening the case's reconsideration (ADR-043 step 5b): no CREATION, an objection upheld after the decision.
    FOR i IN 1..41 LOOP
        x := 900000 + i * 200;
        r := normalize_parcel_geometry(format('{"type":"Polygon","coordinates":[[[%s,500000],[%s,500000],[%s,500100],[%s,500100],[%s,500000]]]}',
                                              x, x + 100, x + 100, x, x), 32632);
        parcel := gen_random_uuid();
        app := gen_random_uuid();
        doc := gen_random_uuid();
        ver := gen_random_uuid();
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc03');
        INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
        VALUES (parcel, format('DEMO-PCL-BENCH-2026-%s-C', lpad((200 + i)::text, 6, '0')), '00000000-0000-0000-0000-00000000ba01', 1,
                '00000000-0000-0000-0000-00000000bc03');
        INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
        VALUES (gen_random_uuid(), parcel, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality',
                '00000000-0000-0000-0000-00000000bc03');

        -- The applicant: a draft with its document, submitted.
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc01');
        INSERT INTO application (id, applicant_id, type_code, type_version_id)
        SELECT app, '00000000-0000-0000-0000-00000000bc01', 'DEMO_BENCH_A', id FROM application_type WHERE code = 'DEMO_BENCH_A';
        INSERT INTO document (id, application_id, category) VALUES (doc, app, 'DEMO_ID');
        INSERT INTO document_version (id, document_id, version_no, status, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (ver, doc, 1, 'STORED', md5(gen_random_uuid()::text), repeat('a', 64), 10, 'application/pdf', 'id.pdf',
                '00000000-0000-0000-0000-00000000bc01');
        UPDATE application SET state = 'SUBMITTED', declaration = tap.self_declaration('DEMO Race Owner ' || i), parcel_reference = format('DEMO-PCL-BENCH-2026-%s-C', lpad((200 + i)::text, 6, '0')),
               parcel_id = parcel, office_id = '00000000-0000-0000-0000-00000000ba01',
               reference = format('DEMO-APP-2026-%s-B', lpad((900 + i)::text, 6, '0')),
               state_changed_by = '00000000-0000-0000-0000-00000000bc01', version = version + 1
         WHERE id = app;
        INSERT INTO completeness_record (application_id, kind) VALUES (app, 'AUTOMATIC');
        UPDATE application SET state = 'COMPLETENESS_REVIEW', state_changed_by = NULL, version = version + 1 WHERE id = app;

        -- Intake, the cadastral review, the publication.
        PERFORM tap.parties_ready(app, '00000000-0000-0000-0000-00000000bc02', '00000000-0000-0000-0000-00000000bc06');
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc02');
        INSERT INTO completeness_record (application_id, kind, actor_id) VALUES (app, 'COMPLETE', '00000000-0000-0000-0000-00000000bc02');
        UPDATE application SET state = 'UNDER_REVIEW', state_changed_by = '00000000-0000-0000-0000-00000000bc02', version = version + 1 WHERE id = app;
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc03');
        PERFORM procedure_stage_record_add(app, 'CADASTRAL_REVIEW', 'COMPLETED', 'The parcel matches the cadastre', '{}'::jsonb, '{}', NULL, NULL, '[]'::jsonb);
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc02');
        PERFORM procedure_stage_record_add(app, 'PUBLICATION', 'COMPLETED', 'Posted on the office board', '{"objectionPeriod": "PT5M"}'::jsonb, '{}',
                                           NULL, NULL, '[]'::jsonb);
        ALTER TABLE procedure_notice DISABLE TRIGGER procedure_notice_append_only;
        UPDATE procedure_notice SET opens_at = opens_at - interval '1 day', closes_at = closes_at - interval '1 day' WHERE application_id = app;
        ALTER TABLE procedure_notice ENABLE TRIGGER procedure_notice_append_only;

        -- Review and decision.
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc04');
        INSERT INTO case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES (app, 'REVIEW', '00000000-0000-0000-0000-00000000bc04', '00000000-0000-0000-0000-00000000bc04');
        INSERT INTO review_step (application_id, kind, recommendation, text, actor_id)
        VALUES (app, 'RECOMMENDATION', 'RECOMMEND_APPROVAL', 'Boundaries and occupation are consistent', '00000000-0000-0000-0000-00000000bc04')
        RETURNING id INTO step;
        UPDATE application SET state = 'PENDING_DECISION', state_changed_by = '00000000-0000-0000-0000-00000000bc04', version = version + 1 WHERE id = app;
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc05');
        INSERT INTO case_assignment (application_id, stage, assignee_id, assigned_by)
        VALUES (app, 'DECISION', '00000000-0000-0000-0000-00000000bc05', '00000000-0000-0000-0000-00000000bc05');
        INSERT INTO decision (application_id, outcome, grounds, cited_versions, follows_step_id, decided_by)
        VALUES (app, 'APPROVED', 'The procedure''s record supports it', ARRAY[ver], step, '00000000-0000-0000-0000-00000000bc05');
        UPDATE application SET state = 'APPROVED', state_changed_by = '00000000-0000-0000-0000-00000000bc05', version = version + 1 WHERE id = app;

        IF i >= 32 THEN
            PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc06');
            PERFORM procedure_objection_add(app, jsonb_build_object('name', 'DEMO Race Neighbour', 'identityKind', 'NATIONAL_ID',
                    'identityNumber', 'DEMO-ID-RACE', 'interest', 'NEIGHBOUR'), 'The boundary crosses my field (synthetic race)', '[]'::jsonb,
                    gen_random_uuid());
            PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000bc07');
            PERFORM procedure_objection_decide((SELECT o.id FROM procedure_objection o WHERE o.application_id = app), 'UPHELD',
                    'The field boundary is to be surveyed again (synthetic race)', ARRAY['CADASTRAL_REVIEW'], NULL);
            INSERT INTO bench_case_race (round, application) VALUES (i, app);
            CONTINUE;
        END IF;
        -- The conservator's proposal and first signature; the second conservator's signature, prepared here, races (D40).
        PERFORM pg_temp.as_actor('00000000-0000-0000-0000-00000000ba02');
        proposal := register_propose_from_case(app, 'Approved immatriculation, race fixture');
        canon := inscription_canonical(proposal);
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        PERFORM register_request_confirmation(proposal, canon, jsonb_build_object(
            'credentialId', 'kb', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        INSERT INTO bench_case_race VALUES (i, app, proposal, canon, jsonb_build_object(
            'credentialId', 'kb8', 'publicKey', encode('\x0808'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA')::text,
            (SELECT cp.id FROM case_person cp WHERE cp.application_id = app), (SELECT (case_identity_latest(cp.id)).id FROM case_person cp WHERE cp.application_id = app),
            (SELECT f.id FROM case_party_file f WHERE f.application_id = app AND f.purpose = 'IDENTITY_DOCUMENT' LIMIT 1));
    END LOOP;
END;
$$;
