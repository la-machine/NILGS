-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Parcel identity, append-only versions and reference numbering (MVP-01, C8).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(14);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;

-- A helper to insert one version from a WKT square, as the application would.
CREATE FUNCTION pg_temp.add_version(p_parcel uuid, p_no int, p_reason text) RETURNS void
LANGUAGE sql AS $f$
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid,
                                      source_geojson_sha256, locality, change_reason, created_by)
    VALUES (gen_random_uuid(), p_parcel, p_no,
            ST_Multi(ST_GeomFromText('POLYGON((11.5 3.87, 11.5009 3.87, 11.5009 3.8707, 11.5 3.8707, 11.5 3.87))', 4326)),
            7700.00, 4326, repeat('a', 64), 'Synthetic locality A', p_reason,
            '00000000-0000-0000-0000-0000000000b1');
$f$;

-- Creation: parcel row and version 1 in one transaction, as the application role.
SELECT lives_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000d1', 'DEMO-PCL-DEMO1-2026-000001-7',
            '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1') $q$) $$,
    'app can insert a parcel');
SELECT lives_ok($$ SELECT run_as('snigf_app', $q$ SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d1', 1, NULL) $q$) $$,
    'app can insert version 1 without a reason');
SELECT lives_ok($$ SET CONSTRAINTS ALL IMMEDIATE $$, 'the current-version pointer resolves at commit');
SET CONSTRAINTS ALL DEFERRED;

-- A parcel pointing at a version that does not exist fails at commit time.
INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
VALUES ('00000000-0000-0000-0000-0000000000d2', 'DEMO-PCL-DEMO1-2026-000002-8',
        '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
SELECT throws_ok($$ SET CONSTRAINTS ALL IMMEDIATE $$, '23503', NULL,
    'a parcel without its version cannot be committed');
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d2', 1, NULL);

-- Versions: sequential, reasoned, immutable (C8).
SELECT throws_ok($$ SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d1', 3, 'skipping version two') $$,
    '23514', NULL, 'versions cannot skip a number');
SELECT throws_ok($$ SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d1', 2, NULL) $$,
    '23514', NULL, 'C8: a correction without a reason is rejected');
SELECT throws_ok($$ SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d1', 2, 'too short') $$,
    '23514', NULL, 'C8: a reason under 10 characters is rejected');
SELECT lives_ok($$ SELECT pg_temp.add_version('00000000-0000-0000-0000-0000000000d1', 2, 'Boundary corrected after synthetic survey') $$,
    'C8: a reasoned version 2 is accepted');
SELECT throws_ok($$ SELECT run_as('snigf_migrator', 'DELETE FROM snigf.parcel_version') $$,
    '23001', NULL, 'versions cannot be deleted, even by the schema owner');

-- The current pointer only moves forward.
UPDATE parcel SET current_version_no = 2 WHERE id = '00000000-0000-0000-0000-0000000000d1';
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    UPDATE snigf.parcel SET current_version_no = 1 WHERE id = '00000000-0000-0000-0000-0000000000d1' $q$) $$,
    '23514', NULL, 'the current version cannot move backwards');

-- References: format and uniqueness are enforced by the table.
SELECT throws_ok($$ INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (gen_random_uuid(), 'PCL-123', '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1') $$,
    '23514', NULL, 'a reference outside the DEMO format is rejected');
SELECT throws_ok($$ INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (gen_random_uuid(), 'DEMO-PCL-DEMO1-2026-000001-7', '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1') $$,
    '23505', NULL, 'a reference can never be reused');

-- Reference numbers: per prefix, office and year, starting at 1.
SELECT results_eq(
    $$ SELECT next_reference_number('PCL', 'DEMO1', 2026) UNION ALL
       SELECT next_reference_number('PCL', 'DEMO1', 2026) UNION ALL
       SELECT next_reference_number('PCL', 'DEMO1', 2026) $$,
    $$ VALUES (1::bigint), (2), (3) $$,
    'numbers increase by one per call');
SELECT is(next_reference_number('PCL', 'DEMO1', 2027), 1::bigint, 'a new year starts again at 1');

SELECT * FROM finish();
ROLLBACK;
