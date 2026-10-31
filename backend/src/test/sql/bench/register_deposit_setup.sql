-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Fixture of the concurrent-deposit test (infra/scripts/db-test.sh): one registered DEMO title at
-- a demo office, a conservator with a key, and a depositor. Runs on the throwaway test database.
SET search_path = snigf, public;

INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-00000000ba01', 'BENCH', 'Bureau de test', 'Bench office');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-00000000ba02', 'bench@demo.invalid', 'Bench Conservator', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000ba02', 'CONSERVATOR', '00000000-0000-0000-0000-00000000ba01', 'bench');
INSERT INTO user_entities (id, name, display_name) VALUES ('hb', '00000000-0000-0000-0000-00000000ba02', 'Bench');
INSERT INTO user_credentials (credential_id, user_entity_user_id, public_key, backup_eligible, backup_state, label) VALUES
    ('kb', 'hb', '\x0101', false, false, 'Bench key');

SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);

DO $$
DECLARE
    r       geometry_check_result := normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[800000,400000],[800100,400000],[800100,400100],[800000,400100],[800000,400000]]]}', 32632);
    depositor uuid;
    created uuid;
    canon   bytea;
    nonce   bytea := sha256('\x01'::bytea);
BEGIN
    INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-00000000ba03', 'DEMO-PCL-BENCH-2026-000001-Z', '00000000-0000-0000-0000-00000000ba01', 1,
            '00000000-0000-0000-0000-00000000ba02');
    INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES ('00000000-0000-0000-0000-00000000ba04', '00000000-0000-0000-0000-00000000ba03', 1, r.geom, r.area_m2, 32632, repeat('a', 64),
            'Synthetic fixture locality', '00000000-0000-0000-0000-00000000ba02');
    depositor := register_party_create('00000000-0000-0000-0000-00000000ba01', 'NATURAL_PERSON', 'DEMO Bench Depositor', 'NATIONAL_ID',
                                       'DEMO-ID-BENCH', NULL, NULL, NULL, NULL);
    created := register_propose('00000000-0000-0000-0000-00000000ba01', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'Synthetic bench fixture',
                                ARRAY['DEMO-EVIDENCE'], NULL, NULL, NULL, 10000, '00000000-0000-0000-0000-00000000ba04',
                                jsonb_build_array(jsonb_build_object('party', depositor, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
    canon := inscription_canonical(created);
    PERFORM register_register(created, canon, jsonb_build_object(
        'credentialId', 'kb', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
        'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
        'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
        'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
END;
$$;
