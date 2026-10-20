-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Geometry normalization: validation order, CRS handling, precision (C3, P1, P3, P10).
-- All coordinates are synthetic.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(26);

-- A 100 m x 80 m square in UTM zone 32N (EPSG:32632), inside the demo bounds.
CREATE TEMP TABLE fx AS SELECT
  '{"type":"Polygon","coordinates":[[[773000,428000],[773100,428000],[773100,428080],[773000,428080],[773000,428000]]]}'::text AS utm32_square,
  '{"type":"Polygon","coordinates":[[[773000,428000],[773100,428080],[773100,428000],[773000,428080],[773000,428000]]]}'::text AS utm32_bowtie,
  -- A square in zone 33N (EPSG:32633), also inside the demo bounds.
  '{"type":"Polygon","coordinates":[[[354000,506000],[354100,506000],[354100,506100],[354000,506100],[354000,506000]]]}'::text AS utm33_square,
  '{"type":"Polygon","coordinates":[[[11.5000,3.8700],[11.5009,3.8700],[11.5009,3.8707],[11.5000,3.8707],[11.5000,3.8700]]]}'::text AS wgs_square,
  '{"type":"Polygon","coordinates":[[[3.8700,11.5000],[3.8700,11.5009],[3.8707,11.5009],[3.8707,11.5000],[3.8700,11.5000]]]}'::text AS wgs_swapped;

-- Positive cases ------------------------------------------------------------
SELECT ok((normalize_parcel_geometry(utm32_square, 32632)).ok, 'UTM 32N square is accepted') FROM fx;
SELECT is(GeometryType((normalize_parcel_geometry(utm32_square, 32632)).geom), 'MULTIPOLYGON',
          'a Polygon is stored as a MultiPolygon') FROM fx;
SELECT is(ST_SRID((normalize_parcel_geometry(utm32_square, 32632)).geom), 4326,
          'stored geometry is in EPSG:4326') FROM fx;
SELECT ok((normalize_parcel_geometry(utm32_square, 32632)).area_m2 BETWEEN 7950 AND 8050,
          'area is computed on the spheroid in square metres (8000 m2 on the UTM grid)') FROM fx;
SELECT ok((normalize_parcel_geometry(utm33_square, 32633)).ok, 'UTM 33N square is accepted') FROM fx;
SELECT ok((normalize_parcel_geometry(wgs_square, 4326)).ok, 'EPSG:4326 square is accepted') FROM fx;

-- CRS twin: the same polygon sent in 4326 and in 32632 stores within 1 cm.
SELECT cmp_ok(
    ST_HausdorffDistance(
        ST_Transform((normalize_parcel_geometry(utm32_square, 32632)).geom, 32632),
        ST_Transform((normalize_parcel_geometry(
            ST_AsGeoJSON(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(utm32_square), 32632), 4326), 15), 4326)).geom, 32632)),
    '<', 0.01::float8, 'F9: the same parcel in 32632 and 4326 stores within 1 cm') FROM fx;

-- Coordinates are snapped to the 1e-7 degree grid (P3).
SELECT ok((SELECT bool_and(abs(ST_X(p.geom) * 1e7 - round(ST_X(p.geom) * 1e7)) < 1e-6
                       AND abs(ST_Y(p.geom) * 1e7 - round(ST_Y(p.geom) * 1e7)) < 1e-6)
             FROM fx, ST_DumpPoints((normalize_parcel_geometry(utm32_square, 32632)).geom) p),
          'P3: every stored vertex lies on the 1e-7 degree grid');

SELECT ok((normalize_parcel_geometry(
    '{"type":"Polygon","coordinates":[[[11.5,3.87,712],[11.5009,3.87,712],[11.5009,3.8707,713],[11.5,3.8707,713],[11.5,3.87,712]]]}', 4326)).ok,
    'coordinates with height are accepted');
SELECT is(ST_NDims((normalize_parcel_geometry(
    '{"type":"Polygon","coordinates":[[[11.5,3.87,712],[11.5009,3.87,712],[11.5009,3.8707,713],[11.5,3.8707,713],[11.5,3.87,712]]]}', 4326)).geom)::int,
    2, '... and heights are dropped');

SELECT is(ST_NumGeometries((normalize_parcel_geometry(
    '{"type":"MultiPolygon","coordinates":[[[[11.5,3.87],[11.5009,3.87],[11.5009,3.8707],[11.5,3.8707],[11.5,3.87]]],[[[11.502,3.87],[11.5029,3.87],[11.5029,3.8707],[11.502,3.8707],[11.502,3.87]]]]}', 4326)).geom),
    2, 'a MultiPolygon with two parts keeps both parts');

-- CRS errors -----------------------------------------------------------------
SELECT is((normalize_parcel_geometry(utm32_square, NULL)).error_code, 'CRS_MISSING', 'missing SRID is rejected') FROM fx;
SELECT is((normalize_parcel_geometry(utm32_square, 3857)).error_code, 'CRS_UNSUPPORTED', 'EPSG:3857 is rejected') FROM fx;
SELECT is((normalize_parcel_geometry(utm33_square, 32632)).error_code, 'GEOMETRY_OUT_OF_BOUNDS',
          'zone 33N coordinates declared as zone 32N fall outside the accepted area') FROM fx;
SELECT is((normalize_parcel_geometry(wgs_swapped, 4326)).error_code, 'GEOMETRY_OUT_OF_BOUNDS',
          'swapped latitude/longitude is caught') FROM fx;

-- Structure errors -----------------------------------------------------------
SELECT is((normalize_parcel_geometry('not json', 4326)).error_code, 'GEOMETRY_MALFORMED', 'non-JSON input is rejected');
SELECT is((normalize_parcel_geometry('[1,2]', 4326)).error_code, 'GEOMETRY_MALFORMED', 'a JSON array is rejected');
SELECT is((normalize_parcel_geometry(
    '{"type":"Polygon","crs":{"type":"name","properties":{"name":"EPSG:32632"}},"coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}', 32632)).error_code,
    'GEOMETRY_MALFORMED', 'a GeoJSON crs member is rejected (RFC 7946)');
SELECT is((normalize_parcel_geometry('{"type":"Point","coordinates":[11.5,3.87]}', 4326)).error_code,
          'GEOMETRY_TYPE_UNSUPPORTED', 'a Point is not a parcel');
SELECT is((normalize_parcel_geometry('{"type":"LineString","coordinates":[[11.5,3.87],[11.6,3.88]]}', 4326)).error_code,
          'GEOMETRY_TYPE_UNSUPPORTED', 'a LineString is not a parcel');
SELECT is((normalize_parcel_geometry('{"type":"Polygon","coordinates":[]}', 4326)).error_code,
          'GEOMETRY_EMPTY', 'an empty polygon is rejected');
SELECT ok(NOT (normalize_parcel_geometry(
    '{"type":"Polygon","coordinates":[[[11.5,3.87],[11.5009,3.87],[11.5009,3.8707],[11.5,3.8707]]]}', 4326)).ok,
    'an unclosed ring is rejected');

-- Validity -------------------------------------------------------------------
SELECT is((normalize_parcel_geometry(utm32_bowtie, 32632)).error_code, 'GEOMETRY_INVALID',
          'F8: a self-intersecting (bow-tie) polygon is rejected') FROM fx;
SELECT matches((normalize_parcel_geometry(utm32_bowtie, 32632)).error_detail, '^Self-intersection',
          '... and the reason names the self-intersection') FROM fx;
SELECT is((normalize_parcel_geometry(utm32_square, 32632, 4)).error_code, 'GEOMETRY_TOO_COMPLEX',
          'P10: vertex limit is enforced') FROM fx;

-- Defence in depth: invalid geometry cannot be stored even if the normalizer is bypassed.
DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
SELECT throws_ok($$
    INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000d1', 'DEMO-PCL-DEMO1-2026-000001-7',
            '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 1,
            ST_Multi(ST_GeomFromText('POLYGON((11.5 3.87, 11.51 3.88, 11.51 3.87, 11.5 3.88, 11.5 3.87))', 4326)),
            1, 4326, repeat('a', 64), 'Synthetic locality', '00000000-0000-0000-0000-0000000000b1') $$,
    '23514', NULL, 'the table itself rejects an invalid geometry');

SELECT * FROM finish();
ROLLBACK;
