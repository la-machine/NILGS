-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Fixture of the deposit races of a procedure B case (infra/scripts/db-test.sh, ADR-042 step 6, V37): thirty synthetic
-- titles at the bench office, each held by its own seller, each named by a submitted procedure B case whose applicant is
-- the buyer. Rounds 1 to 20 race the case's counter deposit against a notary's deposit naming no case on the same title;
-- rounds 21 to 30 race two deposits naming the same case. Runs after register_case_setup.sql, on the throwaway test
-- database, as its owner: every write goes through the functions and triggers the application uses, under the actor each
-- step names.
SET search_path = snigf, public;

INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_BENCH_B', 1, true, 'Procédure B de banc (DEMO)', 'Bench procedure B (DEMO)',
     '{"fields": [{"key": "titleNumber", "kind": "TEXT", "required": true, "maxLength": 40, "options": null,
                   "labelFr": "Titre", "labelEn": "Title", "hintFr": null, "hintEn": null}],
       "documents": [{"code": "DEMO_ID", "required": false}],
       "procedure": {"code": "B", "stages": ["TITLE_VERIFICATION", "DUTIES_EVIDENCE"], "registers": "TRANSFER", "delivery": true}}');

CREATE TABLE bench_mutation_race (round int PRIMARY KEY, application uuid, reference text, title uuid, buyer uuid);
GRANT SELECT ON bench_mutation_race TO snigf_app;

DO $$
DECLARE
    i        int;
    x        int;
    r        geometry_check_result;
    parcel   uuid;
    pv       uuid;
    seller   uuid;
    buyer    uuid;
    created  uuid;
    title    land_title;
    app      uuid;
    canon    bytea;
    nonce    bytea;
BEGIN
    FOR i IN 1..30 LOOP
        x := 900000 + i * 200;
        r := normalize_parcel_geometry(format('{"type":"Polygon","coordinates":[[[%s,600000],[%s,600000],[%s,600100],[%s,600100],[%s,600000]]]}',
                                              x, x + 100, x + 100, x, x), 32632);
        parcel := gen_random_uuid();
        pv := gen_random_uuid();
        app := gen_random_uuid();

        -- The conservator keeps the title: the parcel, the seller, the CREATION signed once (a synthetic title).
        PERFORM set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);
        INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
        VALUES (parcel, format('DEMO-PCL-BENCH-2026-%s-M', lpad((300 + i)::text, 6, '0')), '00000000-0000-0000-0000-00000000ba01', 1,
                '00000000-0000-0000-0000-00000000ba02');
        INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
        VALUES (pv, parcel, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-00000000ba02');
        seller := register_party_create('00000000-0000-0000-0000-00000000ba01', 'NATURAL_PERSON', 'DEMO Mutation Seller ' || i, 'NATIONAL_ID',
                                        'DEMO-ID-MS-' || i, NULL, NULL, NULL, NULL);
        buyer := register_party_create('00000000-0000-0000-0000-00000000ba01', 'NATURAL_PERSON', 'DEMO Mutation Buyer ' || i, 'NATIONAL_ID',
                                       'DEMO-ID-MB-' || i, NULL, NULL, NULL, NULL);
        created := register_propose('00000000-0000-0000-0000-00000000ba01', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'Synthetic bench fixture',
                                    ARRAY['DEMO-EVIDENCE'], NULL, NULL, NULL, 10000, pv,
                                    jsonb_build_array(jsonb_build_object('party', seller, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
        canon := inscription_canonical(created);
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        PERFORM register_register(created, canon, jsonb_build_object(
            'credentialId', 'kb', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
        SELECT * INTO title FROM land_title WHERE creation_inscription_id = created;

        -- The buyer applies, naming the title; the database resolves it at submission.
        PERFORM set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000bc01', false);
        INSERT INTO application (id, applicant_id, type_code, type_version_id)
        SELECT app, '00000000-0000-0000-0000-00000000bc01', 'DEMO_BENCH_B', id FROM application_type WHERE code = 'DEMO_BENCH_B';
        UPDATE application SET state = 'SUBMITTED',
               declaration = tap.transfer_declaration('DEMO Mutation Seller ' || i, 'DEMO-ID-MS-' || i),
               data = jsonb_build_object('titleNumber', title.number),
               parcel_reference = format('DEMO-PCL-BENCH-2026-%s-M', lpad((300 + i)::text, 6, '0')), parcel_id = parcel,
               office_id = '00000000-0000-0000-0000-00000000ba01', reference = format('DEMO-APP-2026-%s-M', lpad((800 + i)::text, 6, '0')),
               state_changed_by = '00000000-0000-0000-0000-00000000bc01', version = version + 1
         WHERE id = app;
        IF (SELECT a.title_id FROM application a WHERE a.id = app) IS DISTINCT FROM title.id THEN
            RAISE EXCEPTION 'bench fixture: case % did not resolve its title', i;
        END IF;
        INSERT INTO bench_mutation_race (round, application, reference, title, buyer)
        VALUES (i, app, format('DEMO-APP-2026-%s-M', lpad((800 + i)::text, 6, '0')), title.id, buyer);
    END LOOP;
END;
$$;
