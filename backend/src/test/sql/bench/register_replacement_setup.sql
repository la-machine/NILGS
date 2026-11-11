-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Fixture of the approval-against-signature race (infra/scripts/db-test.sh, ADR-041 D19): twenty registered DEMO titles
-- at the bench office, each with a holding counter deposit, its deed and a TRANSFER proposal completing it, and the
-- canonical bytes and signature evidence a conservator prepared before any replacement. A second conservator decides
-- replacements. Runs after register_deposit_setup.sql, on the throwaway test database.
SET search_path = snigf, public;

INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-00000000ba08', 'bench2@demo.invalid', 'Bench Conservator Two', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-00000000ba08', 'CONSERVATOR', '00000000-0000-0000-0000-00000000ba01', 'bench');

CREATE TABLE bench_race (round int PRIMARY KEY, inscription uuid, document uuid, canonical bytea, signature text);
GRANT SELECT ON bench_race TO snigf_app;

SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);

DO $$
DECLARE
    i         int;
    x         int;
    r         geometry_check_result;
    seller    uuid;
    buyer     uuid;
    parcel_id uuid;
    version   uuid;
    created   uuid;
    title     uuid;
    deposit   uuid;
    deed      uuid;
    transfer  uuid;
    canon     bytea;
    nonce     bytea;
BEGIN
    FOR i IN 1..20 LOOP
        x := 900000 + i * 200;
        r := normalize_parcel_geometry(format('{"type":"Polygon","coordinates":[[[%s,400000],[%s,400000],[%s,400100],[%s,400100],[%s,400000]]]}',
                                              x, x + 100, x + 100, x, x), 32632);
        parcel_id := gen_random_uuid();
        version := gen_random_uuid();
        INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
        VALUES (parcel_id, format('DEMO-PCL-BENCH-2026-%s-Z', lpad((100 + i)::text, 6, '0')), '00000000-0000-0000-0000-00000000ba01', 1,
                '00000000-0000-0000-0000-00000000ba02');
        INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
        VALUES (version, parcel_id, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-00000000ba02');
        seller := register_party_create('00000000-0000-0000-0000-00000000ba01', 'NATURAL_PERSON', 'DEMO Race Seller ' || i, 'NATIONAL_ID',
                                        'DEMO-ID-RS-' || i, NULL, NULL, NULL, NULL);
        buyer := register_party_create('00000000-0000-0000-0000-00000000ba01', 'NATURAL_PERSON', 'DEMO Race Buyer ' || i, 'NATIONAL_ID',
                                       'DEMO-ID-RB-' || i, NULL, NULL, NULL, NULL);
        created := register_propose('00000000-0000-0000-0000-00000000ba01', NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'Synthetic race fixture',
                                    ARRAY['DEMO-EVIDENCE'], NULL, NULL, NULL, 10000, version,
                                    jsonb_build_array(jsonb_build_object('party', seller, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
        canon := inscription_canonical(created);
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        PERFORM register_register(created, canon, jsonb_build_object(
            'credentialId', 'kb', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA'));
        SELECT new_title_id INTO title FROM inscription WHERE id = created;
        deposit := transaction_deposit(title, 'SALE', buyer, NULL, ARRAY['DEMO-DEED-RACE'], interval '30 days');
        deed := deposit_document_add(deposit, 'DEED', md5(gen_random_uuid()::text), encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
                                     1000, 'application/pdf', 'deed.pdf', NULL, NULL);
        transfer := register_propose('00000000-0000-0000-0000-00000000ba01', title, 'TRANSFER', 1, 'SYNTHETIC_DEMO', 'DEMO-AUTH', 'Synthetic race transfer',
                                     ARRAY['DEMO-EVIDENCE'], NULL, deposit, NULL, NULL, NULL,
                                     jsonb_build_array(jsonb_build_object('party', buyer, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
        -- What the conservator signs: the bytes as they are before any replacement.
        canon := inscription_canonical(transfer);
        nonce := sha256(convert_to(gen_random_uuid()::text, 'UTF8'));
        INSERT INTO bench_race VALUES (i, transfer, deed, canon, jsonb_build_object(
            'credentialId', 'kb', 'publicKey', encode('\x0101'::bytea, 'base64'), 'nonce', encode(nonce, 'base64'),
            'authenticatorData', encode(repeat('a', 37)::bytea, 'base64'),
            'clientData', encode(convert_to(format('{"challenge":"%s"}', rtrim(translate(encode(sha256(canon || nonce), 'base64'), '+/', '-_'), '=')), 'UTF8'), 'base64'),
            'officerSignature', encode('\x0a'::bytea, 'base64'), 'serverSignature', 'snigf:v1:AAAA')::text);
    END LOOP;
END;
$$;
