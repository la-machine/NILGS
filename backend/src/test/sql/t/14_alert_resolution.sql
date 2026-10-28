-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Resolving alerts (MVP-04, E-11, V12): a special grant in force for alert.resolve counts as
-- the permission, as it does for the application; a revoked, expired or other-office grant
-- does not; the author rule comes first; the note has an upper bound.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(11);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer (author of both parcels); b7, b8, b9, b10 staff without a role, each
-- with one special grant; b2 requests the grants, b4 decides them.
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000b4', 'admin2@demo.invalid', 'Demo Admin 2', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b7', 'granted@demo.invalid', 'Granted', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b8', 'revoked@demo.invalid', 'Revoked', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000b9', 'expired@demo.invalid', 'Expired', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-000000000b10', 'elsewhere@demo.invalid', 'Elsewhere', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');

-- The grants as they stand after their workflow (tested in 10); written directly here.
ALTER TABLE permission_grant DISABLE TRIGGER USER;
INSERT INTO permission_grant (id, user_id, permission, office_id, reason, valid_until, requested_by, requested_at,
                              status, decided_by, decided_at, revoked_by, revoked_at, revoke_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b7', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1',
     'Backlog of alerts at DEMO1', NULL, '00000000-0000-0000-0000-0000000000b2', now(),
     'APPROVED', '00000000-0000-0000-0000-0000000000b4', now(), NULL, NULL, NULL),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b8', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1',
     'Backlog of alerts at DEMO1', NULL, '00000000-0000-0000-0000-0000000000b2', now(),
     'REVOKED', '00000000-0000-0000-0000-0000000000b4', now(), '00000000-0000-0000-0000-0000000000b4', now(), 'No longer needed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b9', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1',
     'Backlog of alerts at DEMO1', now() - interval '1 day', '00000000-0000-0000-0000-0000000000b2', now() - interval '2 days',
     'APPROVED', '00000000-0000-0000-0000-0000000000b4', now() - interval '2 days', NULL, NULL, NULL),
    (gen_random_uuid(), '00000000-0000-0000-0000-000000000b10', 'alert.resolve', '00000000-0000-0000-0000-0000000000a2',
     'Backlog of alerts at DEMO2', NULL, '00000000-0000-0000-0000-0000000000b2', now(),
     'APPROVED', '00000000-0000-0000-0000-0000000000b4', now(), NULL, NULL, NULL);
ALTER TABLE permission_grant ENABLE TRIGGER USER;

-- Two overlapping parcels at DEMO1 (100 m x 100 m, 30 m apart in x), both by b1.
CREATE FUNCTION pg_temp.parcel(p_no int, p_x0 float8) RETURNS uuid
LANGUAGE plpgsql AS $f$
DECLARE
    r    snigf.geometry_check_result := snigf.normalize_parcel_geometry(format(
             '{"type":"Polygon","coordinates":[[[%s,400000],[%s,400000],[%s,400100],[%s,400100],[%s,400000]]]}',
             p_x0, p_x0 + 100, p_x0 + 100, p_x0, p_x0), 32632);
    pid  uuid := ('00000000-0000-0000-0000-0000000020' || lpad(p_no::text, 2, '0'))::uuid;
    vid  uuid := gen_random_uuid();
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (pid, format('DEMO-PCL-DEMO1-2026-%s-Y', lpad(p_no::text, 6, '0')),
            '00000000-0000-0000-0000-0000000000a1', 1, '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256,
                                      locality, created_by)
    VALUES (vid, pid, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality',
            '00000000-0000-0000-0000-0000000000b1');
    RETURN vid;
END;
$f$;
SELECT pg_temp.parcel(1, 800000);
SELECT pg_temp.parcel(2, 800030);
CREATE TEMP TABLE overlap AS SELECT id FROM conflict_alert WHERE status = 'OPEN' AND alert_type = 'OVERLAP';
GRANT SELECT ON overlap TO snigf_app;

-- A resolution by the given account, through the application's account.
CREATE FUNCTION pg_temp.resolve_as(p_user text, p_note text DEFAULT 'Checked on site with both owners') RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    PERFORM run_as('snigf_app', format($q$ UPDATE snigf.conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT',
        resolution_note = %L, resolved_by = %L, resolved_at = now(), version = version + 1
        WHERE id = (SELECT id FROM overlap) $q$, p_note, p_user));
END;
$f$;

-- --- Which grants count ----------------------------------------------------------------------
SELECT is((SELECT count(*)::int FROM overlap), 1, 'the two parcels raise one overlap alert');
SELECT ok(holds_grant_at('00000000-0000-0000-0000-0000000000b7', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1'),
    'an approved grant at the office counts');
SELECT ok(NOT holds_grant_at('00000000-0000-0000-0000-0000000000b8', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1'),
    'a revoked grant does not');
SELECT ok(NOT holds_grant_at('00000000-0000-0000-0000-0000000000b9', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1'),
    'an expired grant does not');
SELECT ok(NOT holds_grant_at('00000000-0000-0000-0000-000000000b10', 'alert.resolve', '00000000-0000-0000-0000-0000000000a1'),
    'a grant at another office does not');
SELECT ok(NOT holds_grant_at('00000000-0000-0000-0000-0000000000b7', 'alert.review', '00000000-0000-0000-0000-0000000000a1'),
    'a grant counts only for its own permission');

-- --- Resolution with a grant -----------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.resolve_as('00000000-0000-0000-0000-0000000000b8') $$,
    '42501', NULL, 'a revoked grant does not let anyone resolve');
SELECT throws_ok($$ SELECT pg_temp.resolve_as('00000000-0000-0000-0000-000000000b10') $$,
    '42501', NULL, 'nor does a grant at another office');
SELECT throws_ok($$ SELECT pg_temp.resolve_as('00000000-0000-0000-0000-0000000000b7', repeat('x', 2001)) $$,
    '23514', NULL, 'a note longer than 2000 characters is refused');
SELECT lives_ok($$ SELECT pg_temp.resolve_as('00000000-0000-0000-0000-0000000000b7') $$,
    'a special grant in force at the office lets its holder resolve, as the application allows');
SELECT is((SELECT resolved_by FROM conflict_alert WHERE id = (SELECT id FROM overlap)),
    '00000000-0000-0000-0000-0000000000b7'::uuid, '... and the resolver is recorded');

SELECT * FROM finish();
ROLLBACK;
