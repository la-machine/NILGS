-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Audit subjects (E-18, V19, ADR-038, implementing ADR-027): the database finds what an event is
-- about from the record, with the record's office at that moment, whatever the caller sent; the
-- subjects are hashed (canonical v2) while an event without them keeps v1; altering them is
-- detected; who reads an event is decided by its subjects' offices.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(16);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer at DEMO1 and DEMO2; b3 applicant; s1 supervisor at DEMO1; s2 supervisor at
-- DEMO2; au auditor (everywhere); i1 intake officer at DEMO1 (no audit reading).
INSERT INTO office (id, code, name_fr, name_en) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'DEMO2', 'Bureau de démonstration 2', 'Demo office 2');
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000c3', 's1@demo.invalid', 'Supervisor One', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c4', 's2@demo.invalid', 'Supervisor Two', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c5', 'au@demo.invalid', 'Auditor', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', 'SUPERVISOR', '00000000-0000-0000-0000-0000000000a2', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c5', 'AUDITOR', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');
INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements) VALUES
    ('DEMO_TEST', 1, true, 'Demande (DEMO)', 'Application (DEMO)', '{"fields": [], "documents": []}');

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
CREATE FUNCTION pg_temp.q(p_actor text, p_sql text) RETURNS text
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
-- Parcel n at an office, a 100 m square x0 metres east (UTM 32N), recorded by b1.
CREATE FUNCTION pg_temp.parcel(p_no int, p_office text, p_x0 float8) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    r    snigf.geometry_check_result := snigf.normalize_parcel_geometry(format(
             '{"type":"Polygon","coordinates":[[[%s,400000],[%s,400000],[%s,400100],[%s,400100],[%s,400000]]]}',
             p_x0, p_x0 + 100, p_x0 + 100, p_x0, p_x0), 32632);
    pid  uuid := ('00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'))::uuid;
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (pid, format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), p_office::uuid, 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), pid, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END;
$f$;
-- An event about (type, id), appended as snigf_app with whatever subjects the caller claims;
-- returns its chain position.
CREATE FUNCTION pg_temp.event(p_type text, p_id text, p_claimed jsonb DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql AS $f$
DECLARE pos bigint;
BEGIN
    SET LOCAL ROLE snigf_app;
    INSERT INTO snigf.audit_event (id, actor_type, action, outcome, correlation_id, entity_type, entity_id, subjects)
    VALUES (gen_random_uuid(), 'ANONYMOUS', 'TEST_EVENT', 'SUCCESS', gen_random_uuid(), p_type, p_id::uuid, p_claimed)
    RETURNING chain_pos INTO pos;
    RESET ROLE;
    RETURN pos;
END;
$f$;
CREATE FUNCTION pg_temp.subjects(p_pos bigint) RETURNS text
LANGUAGE sql AS $f$ SELECT subjects::text FROM snigf.audit_event WHERE chain_pos = p_pos $f$;

SELECT pg_temp.parcel(1, '00000000-0000-0000-0000-0000000000a1', 900000);
SELECT pg_temp.parcel(2, '00000000-0000-0000-0000-0000000000a2', 900050);   -- overlaps parcel 1: an alert

-- --- What an event is about --------------------------------------------------------------------
SELECT is(pg_temp.subjects(pg_temp.event('PARCEL', '00000000-0000-0000-0000-000000004001')),
    '[{"id": "00000000-0000-0000-0000-000000004001", "type": "PARCEL", "office": "00000000-0000-0000-0000-0000000000a1"}]',
    'an event about a parcel names it, with its office');
SELECT is(pg_temp.subjects(pg_temp.event('CONFLICT_ALERT', (SELECT id::text FROM conflict_alert LIMIT 1))),
    '[{"id": "00000000-0000-0000-0000-000000004001", "type": "PARCEL", "office": "00000000-0000-0000-0000-0000000000a1"}, '
    || '{"id": "00000000-0000-0000-0000-000000004002", "type": "PARCEL", "office": "00000000-0000-0000-0000-0000000000a2"}]',
    'an alert names both parcels, each with its own office');
SELECT is(pg_temp.subjects(pg_temp.event('PARCEL', '00000000-0000-0000-0000-000000004001',
    '[{"type": "PARCEL", "id": "00000000-0000-0000-0000-000000004001", "office": "00000000-0000-0000-0000-0000000000a2"}]')),
    '[{"id": "00000000-0000-0000-0000-000000004001", "type": "PARCEL", "office": "00000000-0000-0000-0000-0000000000a1"}]',
    'the caller''s claimed subjects are replaced by the database''s finding');
SELECT is(pg_temp.subjects(pg_temp.event('USER', '00000000-0000-0000-0000-0000000000b3',
    '[{"type": "PARCEL", "id": "00000000-0000-0000-0000-000000004001"}]')), NULL,
    'an event about an account is about no case or parcel, whatever the caller claims');
SELECT is(pg_temp.subjects(pg_temp.event('PARCEL', '00000000-0000-0000-0000-00000000dead')), NULL,
    'an unknown record yields no subjects');

-- A draft has no office; once submitted, its office is in every later event, and earlier events keep none.
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
    SELECT '00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id
      FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$);
CREATE TEMP TABLE draft_event AS SELECT pg_temp.event('APPLICATION', '00000000-0000-0000-0000-00000000d001') AS pos;
SELECT is(pg_temp.subjects((SELECT pos FROM draft_event)),
    '[{"id": "00000000-0000-0000-0000-00000000d001", "type": "APPLICATION"}]', 'a draft''s event names it, without an office');
SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.application SET state = 'SUBMITTED',
    parcel_reference = 'DEMO-PCL-DEMO1-2026-000001-Z', parcel_id = '00000000-0000-0000-0000-000000004001',
    office_id = '00000000-0000-0000-0000-0000000000a1', reference = 'DEMO-APP-2026-000001-X',
    state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1
    WHERE id = '00000000-0000-0000-0000-00000000d001' $q$);
CREATE TEMP TABLE submitted_event AS SELECT pg_temp.event('APPLICATION', '00000000-0000-0000-0000-00000000d001') AS pos;
SELECT is(pg_temp.subjects((SELECT pos FROM submitted_event)),
    '[{"id": "00000000-0000-0000-0000-00000000d001", "type": "APPLICATION", "office": "00000000-0000-0000-0000-0000000000a1"}]',
    'once submitted, its events carry the office it was submitted to');
SELECT is(pg_temp.subjects((SELECT pos FROM draft_event)),
    '[{"id": "00000000-0000-0000-0000-00000000d001", "type": "APPLICATION"}]',
    'the draft''s earlier event is never given the office afterwards');

-- --- The hash ----------------------------------------------------------------------------------
SELECT ok(convert_from(audit_canonical(e), 'UTF8') LIKE 'snigf-audit-v2|%' AND convert_from(audit_canonical(e), 'UTF8') LIKE '%' || e.subjects::text,
    'an event with subjects is encoded v2, its subjects last') FROM audit_event e WHERE chain_pos = (SELECT pos FROM submitted_event);
SELECT ok(convert_from(audit_canonical(e), 'UTF8') LIKE 'snigf-audit-v1|%', 'an event without subjects keeps v1')
    FROM audit_event e WHERE chain_pos = (SELECT max(chain_pos) FROM audit_event WHERE subjects IS NULL);
SELECT is((SELECT verified FROM audit_verify_chain()), true, 'the chain mixing v1 and v2 events verifies');

-- --- Who reads ---------------------------------------------------------------------------------
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c5', $$ SELECT audit_readable(NULL)::text $$), 'true',
    'the auditor reads an event without an office');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c3', format($$ SELECT string_agg(audit_readable(subjects)::text, ',' ORDER BY chain_pos)
    FROM snigf.audit_event WHERE chain_pos IN (%s, %s) $$, (SELECT pos FROM draft_event), (SELECT pos FROM submitted_event))), 'false,true',
    'the supervisor of the office reads the submitted case''s event, not the draft''s');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c4', $$ SELECT count(*) FILTER (WHERE audit_readable(subjects))
    || '/' || count(*) FILTER (WHERE entity_type = 'CONFLICT_ALERT' AND audit_readable(subjects)) FROM snigf.audit_event $$), '1/1',
    'a supervisor of the other office reads only the alert that touches their parcel');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', $$ SELECT count(*) FILTER (WHERE audit_readable(subjects))::text FROM snigf.audit_event $$)
    || '/' || pg_temp.q(NULL, $$ SELECT count(*) FILTER (WHERE audit_readable(subjects))::text FROM snigf.audit_event $$), '0/0',
    'nobody without audit.event.search, and nobody unnamed, reads anything');

-- --- Altering the subjects is detected ---------------------------------------------------------
SET LOCAL session_replication_role = replica;
UPDATE audit_event SET subjects = '[{"id": "00000000-0000-0000-0000-00000000d001", "type": "APPLICATION", "office": "00000000-0000-0000-0000-0000000000a2"}]'
 WHERE chain_pos = (SELECT pos FROM submitted_event);
SET LOCAL session_replication_role = origin;
SELECT is((SELECT mismatch_kind || '@' || (first_mismatch_pos = (SELECT pos FROM submitted_event))::text FROM audit_verify_chain()),
    'CONTENT_HASH@true', 'moving an event to another office breaks its hash');

SELECT * FROM finish();
ROLLBACK;
