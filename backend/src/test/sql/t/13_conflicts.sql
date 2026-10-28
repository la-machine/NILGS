-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Overlap and duplicate detection (MVP-04, E-10, V11): the Baseline's synthetic fixture set
-- yields exactly its expected alerts; alerts are raised only by the database, keep their
-- evidence, are superseded by a correction, and are resolved only by someone entitled who
-- authored neither compared version.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(35);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 officer (author of the fixtures), b2 administrator; b6 cadastral officer at DEMO1 (resolver).
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b6', 'cadastre@demo.invalid', 'Demo Cadastre', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b2', 'SYSTEM_ADMIN', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b6', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');

-- A rectangle in UTM 32N metres (exact sizes), as GeoJSON.
CREATE FUNCTION pg_temp.rect(x0 float8, y0 float8, x1 float8, y1 float8) RETURNS text
LANGUAGE sql AS $f$
    SELECT format('{"type":"Polygon","coordinates":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}',
                  x0, y0, x1, y0, x1, y1, x0, y1, x0, y0)
$f$;

-- A parcel and its version 1, through the same normalisation as the application; returns
-- the version id, or NULL when the geometry is refused (nothing stored).
CREATE FUNCTION pg_temp.parcel(p_no int, p_geojson text, p_srid int, p_ext text DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql AS $f$
DECLARE
    r       snigf.geometry_check_result;
    pid     uuid := ('00000000-0000-0000-0000-0000000010' || lpad(p_no::text, 2, '0'))::uuid;
    vid     uuid := gen_random_uuid();
BEGIN
    r := snigf.normalize_parcel_geometry(p_geojson, p_srid);
    IF NOT r.ok THEN
        RETURN NULL;
    END IF;
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (pid, format('DEMO-PCL-DEMO1-2026-%s-X', lpad(p_no::text, 6, '0')),
            '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256,
                                      locality, external_reference, created_by)
    VALUES (vid, pid, 1, r.geom, r.area_m2, p_srid, repeat('a', 64), 'Synthetic fixture locality', p_ext,
            '00000000-0000-0000-0000-0000000000b1');
    RETURN vid;
END;
$f$;

-- A correction: version n of parcel p_no with a new geometry.
CREATE FUNCTION pg_temp.correct(p_no int, p_version int, p_geojson text) RETURNS uuid
LANGUAGE plpgsql AS $f$
DECLARE
    r    snigf.geometry_check_result := snigf.normalize_parcel_geometry(p_geojson, 32632);
    pid  uuid := ('00000000-0000-0000-0000-0000000010' || lpad(p_no::text, 2, '0'))::uuid;
    vid  uuid := gen_random_uuid();
BEGIN
    UPDATE snigf.parcel SET current_version_no = p_version, version = version + 1 WHERE id = pid;
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256,
                                      locality, change_reason, created_by)
    VALUES (vid, pid, p_version, r.geom, r.area_m2, 32632, repeat('b', 64), 'Synthetic fixture locality',
            'Boundary re-surveyed on site', '00000000-0000-0000-0000-0000000000b1');
    RETURN vid;
END;
$f$;

CREATE FUNCTION pg_temp.pid(p_no int) RETURNS uuid LANGUAGE sql AS $f$
    SELECT ('00000000-0000-0000-0000-0000000010' || lpad(p_no::text, 2, '0'))::uuid
$f$;

-- Open alerts between two fixtures, by type (empty when none).
CREATE FUNCTION pg_temp.open_between(a int, b int) RETURNS text[] LANGUAGE sql AS $f$
    SELECT coalesce(array_agg(alert_type ORDER BY alert_type), '{}') FROM snigf.conflict_alert
     WHERE status = 'OPEN' AND parcel_a_id = least(pg_temp.pid(a), pg_temp.pid(b))
       AND parcel_b_id = greatest(pg_temp.pid(a), pg_temp.pid(b))
$f$;

-- --- The Baseline fixture set (Task D): eight synthetic parcels, known outcomes ----------------
-- Cluster 1, around A (100 m x 100 m at E 800000-800100, N 400000-400100, zone 32N).
SELECT pg_temp.parcel(1,  pg_temp.rect(800000, 400000, 800100, 400100), 32632);       -- A
SELECT pg_temp.parcel(2,  pg_temp.rect(800400, 400400, 800500, 400500), 32632);       -- clean, far away
SELECT pg_temp.parcel(3,  pg_temp.rect(799900, 400000, 800000, 400100), 32632);       -- shares A's west edge
SELECT pg_temp.parcel(4,  pg_temp.rect(800100, 399900, 800200, 400000), 32632);       -- touches A's corner only
SELECT pg_temp.parcel(5,  pg_temp.rect(800000, 400099.9, 800005, 400200), 32632);     -- 0.5 m2 sliver over A
SELECT pg_temp.parcel(6,  pg_temp.rect(800070, 400000, 800170, 400100), 32632);       -- 30 % overlap with A
-- Cluster 2: B and a 98 % copy of it.
SELECT pg_temp.parcel(7,  pg_temp.rect(801000, 400000, 801100, 400100), 32632);       -- B
SELECT pg_temp.parcel(8,  pg_temp.rect(801002, 400000, 801100, 400100), 32632);       -- 98 % of B
-- Self-intersecting (a bow tie): refused before anything is stored.
SELECT is(pg_temp.parcel(9, '{"type":"Polygon","coordinates":[[[802000,400000],[802100,400100],[802100,400000],[802000,400100],[802000,400000]]]}', 32632),
    NULL::uuid, 'a self-intersecting parcel is refused: nothing is stored, no alert can follow');
-- Cluster 3, east of 12 E: C in degrees, and the same parcel given in UTM zone 33N.
SELECT pg_temp.parcel(10, '{"type":"Polygon","coordinates":[[[12.5,4.0],[12.501,4.0],[12.501,4.001],[12.5,4.001],[12.5,4.0]]]}', 4326);
SELECT pg_temp.parcel(11, ST_AsGeoJSON(ST_Transform(ST_GeomFromText(
    'POLYGON((12.5 4.0,12.501 4.0,12.501 4.001,12.5 4.001,12.5 4.0))', 4326), 32633), 3, 0), 32633);

SELECT is(pg_temp.open_between(1, 2), '{}'::text[], 'a clean parcel raises nothing');
SELECT is(pg_temp.open_between(1, 3), '{}'::text[], 'a parcel sharing an edge raises nothing');
SELECT is(pg_temp.open_between(1, 4), '{}'::text[], 'a parcel touching a corner raises nothing');
SELECT is(pg_temp.open_between(1, 5), '{}'::text[], 'a 0.5 m2 sliver is below the 1 m2 threshold');
SELECT is(pg_temp.open_between(1, 6), '{OVERLAP}'::text[], 'a 30 % overlap raises OVERLAP');
SELECT is(pg_temp.open_between(7, 8), '{DUPLICATE_GEOMETRY}'::text[], 'a 98 % copy raises DUPLICATE_GEOMETRY, not OVERLAP');
SELECT is(pg_temp.open_between(10, 11), '{DUPLICATE_GEOMETRY}'::text[], 'the same parcel given in UTM 33N is a duplicate of its 4326 original');
SELECT is((SELECT count(*) FROM conflict_alert WHERE status = 'OPEN'), 3::bigint,
    'the fixture set yields exactly its three expected alerts');

-- Evidence of the overlap: both versions, the area on the spheroid, both shares, the geometry.
SELECT ok((SELECT intersection_m2 BETWEEN 2970 AND 3030 FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP'),
    'the intersection area is about 3000 m2, on the spheroid');
SELECT ok((SELECT share_a BETWEEN 0.29 AND 0.31 AND share_b BETWEEN 0.29 AND 0.31
             FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP'),
    'both shares are about 30 %');
SELECT ok((SELECT GeometryType(intersection_geom) = 'MULTIPOLYGON'
                  AND abs(ST_Area(intersection_geom::geography) - intersection_m2) < 1
             FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP'),
    'the intersection geometry is kept as evidence and matches the area');
SELECT ok((SELECT version_a_id IS NOT NULL AND version_b_id IS NOT NULL AND raised_by_version_id = version_b_id
             FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP'),
    'both compared versions are named; the later write raised it');
SELECT ok((SELECT least(share_a, share_b) >= 0.95 FROM conflict_alert WHERE status = 'OPEN'
            AND parcel_a_id = least(pg_temp.pid(7), pg_temp.pid(8))),
    'a duplicate covers at least 95 % of each parcel');

-- --- Duplicate reference ------------------------------------------------------------------
SELECT pg_temp.parcel(12, pg_temp.rect(805000, 400000, 805050, 400050), 32632, 'TF 123/Mfoundi');
SELECT pg_temp.parcel(13, pg_temp.rect(806000, 400000, 806050, 400050), 32632, 'tf-123 mfoundi');
SELECT is(pg_temp.open_between(12, 13), '{DUPLICATE_REFERENCE}'::text[],
    'the same external reference, whatever its spaces, punctuation or case, raises DUPLICATE_REFERENCE');
SELECT is((SELECT normalized_reference FROM conflict_alert WHERE alert_type = 'DUPLICATE_REFERENCE'), 'TF123MFOUNDI',
    'the alert keeps the normalised reference');

-- --- One open alert per pair and type -----------------------------------------------------
SELECT throws_ok($$ INSERT INTO conflict_alert (alert_type, parcel_a_id, parcel_b_id, version_a_id, version_b_id,
                        intersection_m2, share_a, share_b, intersection_geom, raised_by_version_id)
                    SELECT alert_type, parcel_a_id, parcel_b_id, version_a_id, version_b_id,
                        intersection_m2, share_a, share_b, intersection_geom, raised_by_version_id
                      FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP' $$,
    '23505', NULL, 'a second open alert for the same pair and type is impossible');

-- --- What the application's account cannot do ---------------------------------------------
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ INSERT INTO snigf.conflict_alert (alert_type, parcel_a_id, parcel_b_id,
        version_a_id, version_b_id, normalized_reference, raised_by_version_id)
        SELECT 'DUPLICATE_REFERENCE', parcel_a_id, parcel_b_id, version_a_id, version_b_id, 'X', raised_by_version_id
          FROM snigf.conflict_alert LIMIT 1 $q$) $$,
    '42501', NULL, 'the application cannot raise an alert itself');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'UPDATE snigf.conflict_alert SET intersection_m2 = 1') $$,
    '42501', NULL, 'the application cannot change an alert''s evidence');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'DELETE FROM snigf.conflict_alert') $$,
    '42501', NULL, 'the application cannot delete an alert');
SELECT throws_ok($$ SELECT run_as('snigf_migrator', 'DELETE FROM snigf.conflict_alert') $$,
    '23001', NULL, 'not even the schema owner deletes an alert');

-- --- Resolution rules (E-11 resolves through these) ----------------------------------------
CREATE TEMP TABLE overlap AS SELECT id, version FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP';
GRANT SELECT ON overlap TO snigf_app;
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT',
        resolution_note = 'Checked on site with both owners', resolved_by = '00000000-0000-0000-0000-0000000000b1',
        resolved_at = now(), version = version + 1 WHERE id = (SELECT id FROM overlap) $q$) $$,
    '42501', NULL, 'the author of a compared version cannot resolve the alert');
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT',
        resolution_note = 'Checked on site with both owners', resolved_by = '00000000-0000-0000-0000-0000000000b2',
        resolved_at = now(), version = version + 1 WHERE id = (SELECT id FROM overlap) $q$) $$,
    '42501', NULL, 'someone without alert.resolve at either parcel''s office cannot resolve it');
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT',
        resolution_note = 'too short', resolved_by = '00000000-0000-0000-0000-0000000000b6',
        resolved_at = now(), version = version + 1 WHERE id = (SELECT id FROM overlap) $q$) $$,
    '23514', NULL, 'a resolution needs a note of at least 10 characters');
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT',
        resolution_note = 'Checked on site with both owners', resolved_by = '00000000-0000-0000-0000-0000000000b6',
        resolved_at = now(), version = version WHERE id = (SELECT id FROM overlap) $q$) $$,
    '40001', NULL, 'a resolution based on a stale version is refused');
SELECT lives_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED',
        resolution = 'REFERRED_FOR_ADJUDICATION', resolution_note = 'Both claims documented; sent to the land commission',
        resolved_by = '00000000-0000-0000-0000-0000000000b6', resolved_at = now(), version = version + 1
        WHERE id = (SELECT id FROM overlap) $q$) $$,
    'a cadastral officer of the office who authored neither version resolves it');
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$ UPDATE snigf.conflict_alert SET resolution = 'NO_CONFLICT', version = version + 1
        WHERE id = (SELECT id FROM overlap) $q$) $$,
    '23001', NULL, 'a resolved alert never changes again');

-- --- Corrections: alerts follow the current versions ----------------------------------------
-- B's copy (8) is corrected away from B: its duplicate alert is superseded, nothing new is raised.
SELECT pg_temp.correct(8, 2, pg_temp.rect(803000, 400000, 803100, 400100));
SELECT is((SELECT status FROM conflict_alert WHERE parcel_a_id = least(pg_temp.pid(7), pg_temp.pid(8))
             AND parcel_b_id = greatest(pg_temp.pid(7), pg_temp.pid(8))), 'SUPERSEDED',
    'a correction supersedes the parcel''s open alerts');
SELECT ok((SELECT superseded_by_version_id IS NOT NULL AND superseded_at IS NOT NULL FROM conflict_alert
            WHERE parcel_a_id = least(pg_temp.pid(7), pg_temp.pid(8)) AND parcel_b_id = greatest(pg_temp.pid(7), pg_temp.pid(8))),
    '... naming the version that superseded it');
SELECT is(pg_temp.open_between(7, 8), '{}'::text[], 'the corrected parcel no longer overlaps: no open alert');
-- Corrected back over B: a fresh alert names the new version.
SELECT pg_temp.correct(8, 3, pg_temp.rect(801001, 400000, 801100, 400100));
SELECT is(pg_temp.open_between(7, 8), '{DUPLICATE_GEOMETRY}'::text[], 'a correction is checked like a new parcel');
SELECT ok((SELECT raised_by_version_id = (SELECT id FROM parcel_version WHERE parcel_id = pg_temp.pid(8) AND version_no = 3)
             FROM conflict_alert WHERE status = 'OPEN' AND parcel_a_id = least(pg_temp.pid(7), pg_temp.pid(8))),
    '... and its alert names version 3');

-- --- The thresholds come from the policy ------------------------------------------------------
UPDATE conflict_policy SET min_overlap_m2 = 900;
SELECT pg_temp.parcel(14, pg_temp.rect(800095, 400000, 800195, 400100), 32632);   -- 5 m x 100 m = 500 m2 over A
SELECT is(pg_temp.open_between(1, 14), '{}'::text[], 'with a 900 m2 minimum, a 500 m2 overlap raises nothing');
SELECT throws_ok($$ UPDATE snigf.conflict_policy SET duplicate_share = 0.2 $$, '23514', NULL,
    'the duplicate share cannot be set below 50 %');
SELECT throws_ok($$ SELECT run_as('snigf_app', 'INSERT INTO snigf.conflict_policy (id, min_overlap_m2, duplicate_share) VALUES (false, 1, 0.9)') $$,
    '42501', NULL, 'the application can adjust the policy row, not add one');

SELECT * FROM finish();
ROLLBACK;
