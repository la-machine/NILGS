-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Documents issued by SNIGF (Slice 1, V20, ADR-039): the applicant is issued their own
-- acknowledgment, now, valid, once per operation; what was issued never changes; its status
-- leaves VALID once, by a document officer who is not its holder, and a superseded document names
-- its replacement; the stored PDF matches its registered fingerprint; anyone may be recorded
-- verifying but only reviewers read the record; an incident is resolved once, with a note, by a
-- reviewer who is not the holder; the evidence function answers without exposing the table.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(32);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b3 applicant; e1 another applicant; c1 intake officer at DEMO1; d1 document officer; d2 a second
-- document officer who is also an applicant (holder of document 9).
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000e1', 'other@demo.invalid', 'Other Applicant', 'x', 'APPLICANT'),
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d1', 'd1@demo.invalid', 'Document Officer', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000d2', 'd2@demo.invalid', 'Document Officer Two', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', 'DOCUMENT_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d2', 'DOCUMENT_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');
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
-- Parcel n at DEMO1, a 100 m square x0 metres east (UTM 32N), recorded by b1.
CREATE FUNCTION pg_temp.parcel(p_no int, p_x0 float8) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    r    snigf.geometry_check_result := snigf.normalize_parcel_geometry(format(
             '{"type":"Polygon","coordinates":[[[%s,400000],[%s,400000],[%s,400100],[%s,400100],[%s,400000]]]}',
             p_x0, p_x0 + 100, p_x0 + 100, p_x0, p_x0), 32632);
    pid  uuid := ('00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'))::uuid;
BEGIN
    INSERT INTO snigf.parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (pid, format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), '00000000-0000-0000-0000-0000000000a1', 1,
            '00000000-0000-0000-0000-0000000000b1');
    INSERT INTO snigf.parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (gen_random_uuid(), pid, 1, r.geom, r.area_m2, 32632, repeat('a', 64), 'Synthetic fixture locality', '00000000-0000-0000-0000-0000000000b1');
END;
$f$;
-- Application n (d00n), submitted at DEMO1 by p_applicant.
CREATE FUNCTION pg_temp.application(p_no int, p_applicant text) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    app text := '00000000-0000-0000-0000-00000000d00' || p_no;
BEGIN
    PERFORM pg_temp.parcel(p_no, 900000 + p_no * 2000);
    PERFORM pg_temp.act(p_applicant, format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
        SELECT %L, %L, 'DEMO_TEST', id FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$, app, p_applicant));
    PERFORM pg_temp.act(p_applicant, format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
        parcel_reference = %L, parcel_id = %L, office_id = '00000000-0000-0000-0000-0000000000a1', reference = %L,
        state_changed_by = %L, version = version + 1 WHERE id = %L $q$,
        format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), '00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'),
        format('DEMO-APP-2026-%s-X', lpad(p_no::text, 6, '0')), p_applicant, app));
END;
$f$;
-- The INSERT of document n for application p_app, held by p_holder; p_extra overrides columns.
CREATE FUNCTION pg_temp.issue_sql(p_no int, p_app int, p_holder text, p_at text DEFAULT 'now()',
                                  p_supersedes text DEFAULT NULL, p_status text DEFAULT 'VALID') RETURNS text
LANGUAGE sql AS $f$
    SELECT format($q$ INSERT INTO snigf.issued_document (id, number, doc_type, template_version, operation_key, subject_type,
        subject_id, subject_ref, office_id, issued_to, issued_at, printed, content_sha256, manifest, signature, key_version,
        supersedes, status)
        VALUES (%L, %L, 'DEMO_SUBMISSION_ACKNOWLEDGMENT', 'ack-demo-1', %L, 'APPLICATION', %L, %L,
        '00000000-0000-0000-0000-0000000000a1', %L, %s, '{}', sha256(%L::bytea), 'manifest'::bytea, 'snigf:v1:AA==', 1, %L, %L) $q$,
        '00000000-0000-0000-0000-0000000090' || lpad(p_no::text, 2, '0'),
        'SNIGF-' || lpad(p_no::text, 26, '0'),
        'op-' || p_no, '00000000-0000-0000-0000-00000000d00' || p_app, format('DEMO-APP-2026-%s-X', lpad(p_app::text, 6, '0')),
        p_holder, p_at, 'pdf-' || p_no, p_supersedes, p_status)
$f$;

SELECT pg_temp.application(1, '00000000-0000-0000-0000-0000000000b3');
SELECT pg_temp.application(2, '00000000-0000-0000-0000-0000000000d2');

-- Issuing.
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', pg_temp.issue_sql(1, 1, '00000000-0000-0000-0000-0000000000b3')), 1,
    'the applicant is issued the acknowledgment of their application');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000e1', %L) $$,
    pg_temp.issue_sql(2, 1, '00000000-0000-0000-0000-0000000000e1')), '42501', NULL,
    'nobody is issued a document about an application that is not theirs');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', %L) $$,
    pg_temp.issue_sql(3, 1, '00000000-0000-0000-0000-0000000000b3', $t$ now() - interval '2 hours' $t$)), '23514', NULL,
    'the signed issuance time is the time of issuance');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', %L) $$,
    pg_temp.issue_sql(4, 1, '00000000-0000-0000-0000-0000000000b3', 'now()', NULL, 'REVOKED')), '23514', NULL,
    'a document is issued valid');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', %L) $$,
    replace(pg_temp.issue_sql(5, 1, '00000000-0000-0000-0000-0000000000b3'), 'op-5', 'op-1')), '23505', NULL,
    'one operation issues one document (idempotency under concurrency)');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', %L) $$,
    replace(pg_temp.issue_sql(6, 1, '00000000-0000-0000-0000-0000000000b3'), 'SNIGF-', 'SNIGF-I')), '23514', NULL,
    'a number has the registry''s form');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000d2', pg_temp.issue_sql(9, 2, '00000000-0000-0000-0000-0000000000d2')), 1,
    'a document officer who applies is issued their own acknowledgment');

-- The stored PDF.
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.issued_document_artifact
    (document_id, media_type, bytes) VALUES ('00000000-0000-0000-0000-000000009001', 'application/pdf', 'other bytes'::bytea) $q$) $$,
    '23514', NULL, 'the stored PDF must match the registered fingerprint');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.issued_document_artifact
    (document_id, media_type, bytes) VALUES ('00000000-0000-0000-0000-000000009001', 'application/pdf', 'pdf-1'::bytea) $q$), 1,
    'the PDF with the registered fingerprint is stored');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ UPDATE snigf.issued_document_artifact SET bytes = 'x'::bytea $q$) $$,
    '42501', NULL, 'the stored PDF is never rewritten');

-- Reading.
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', $q$ SELECT count(*)::text FROM snigf.issued_document
    WHERE id = '00000000-0000-0000-0000-000000009001' $q$), '1', 'the office of the application sees what was issued for it');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000e1', $q$ SELECT count(*)::text FROM snigf.issued_document $q$), '0',
    'another applicant sees nothing');
SELECT is(pg_temp.q(NULL, $q$ SELECT status || ' ' || doc_type FROM snigf.issued_document_evidence('SNIGF-'
    || lpad('1', 26, '0'), NULL) $q$), 'VALID DEMO_SUBMISSION_ACKNOWLEDGMENT', 'the evidence function answers anyone, by number');
SELECT is(pg_temp.q(NULL, $q$ SELECT number FROM snigf.issued_document_evidence(NULL, sha256('pdf-1'::bytea)) $q$),
    'SNIGF-' || lpad('1', 26, '0'), 'and by fingerprint');
SELECT is(pg_temp.q(NULL, $q$ SELECT count(*)::text FROM snigf.issued_document $q$), '0',
    'the table itself is not readable anonymously');

-- Status changes.
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.issued_document SET status = 'REVOKED',
    status_reason = 'Revoking my own document', status_changed_by = '00000000-0000-0000-0000-0000000000b3',
    status_changed_at = now() WHERE id = '00000000-0000-0000-0000-000000009001' $q$), 0, 'the holder changes no status');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.issued_document
    SET subject_ref = 'DEMO-APP-2026-999999-X' WHERE id = '00000000-0000-0000-0000-000000009001' $q$) $$, '42501', NULL,
    'what was issued is never rewritten, even by the document officer');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.issued_document SET status = 'SUPERSEDED',
    status_reason = 'Superseded without any replacement', status_changed_by = '00000000-0000-0000-0000-0000000000d1',
    status_changed_at = now(), superseded_by = '00000000-0000-0000-0000-000000009009'
    WHERE id = '00000000-0000-0000-0000-000000009001' $q$) $$, '23514', NULL,
    'a superseded document names the document that replaces it');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d2', $q$ UPDATE snigf.issued_document SET status = 'REVOKED',
    status_reason = 'Revoking my own document', status_changed_by = '00000000-0000-0000-0000-0000000000d2',
    status_changed_at = now() WHERE id = '00000000-0000-0000-0000-000000009009' $q$) $$, '42501', NULL,
    'a document officer never changes the status of a document issued to themselves');
SELECT throws_ok(format($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', %L) $$,
    pg_temp.issue_sql(10, 1, '00000000-0000-0000-0000-0000000000b3', 'now()', '00000000-0000-0000-0000-000000009001')), '42501', NULL,
    'the holder never re-issues');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000d1',
    pg_temp.issue_sql(10, 1, '00000000-0000-0000-0000-0000000000b3', 'now()', '00000000-0000-0000-0000-000000009001'))
    + pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.issued_document SET status = 'SUPERSEDED',
    status_reason = 'Office code printed was corrected', status_changed_by = '00000000-0000-0000-0000-0000000000d1',
    status_changed_at = now(), superseded_by = '00000000-0000-0000-0000-000000009010'
    WHERE id = '00000000-0000-0000-0000-000000009001' $q$), 2, 'the document officer re-issues; the old document names its replacement');
SELECT is(pg_temp.q(NULL, $q$ SELECT status || ' ' || replacement_number FROM snigf.issued_document_evidence('SNIGF-'
    || lpad('1', 26, '0'), NULL) $q$), 'SUPERSEDED SNIGF-' || lpad('10', 26, '0'), 'the evidence shows the replacement');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.issued_document SET status = 'REVOKED',
    status_reason = 'Revoking a superseded document', status_changed_by = '00000000-0000-0000-0000-0000000000d1',
    status_changed_at = now() WHERE id = '00000000-0000-0000-0000-000000009001' $q$) $$, '23001', NULL,
    'a status other than VALID is final');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ DELETE FROM snigf.issued_document $q$) $$,
    '42501', NULL, 'an issued document is never deleted');
SELECT is(audit_subjects('ISSUED_DOCUMENT', '00000000-0000-0000-0000-000000009010') -> 0 ->> 'id',
    '00000000-0000-0000-0000-00000000d001', 'audit events about a document are about its application');

-- Verifications and incidents.
SELECT is(pg_temp.act(NULL, $q$ INSERT INTO snigf.document_verification (id, channel, presented_sha256, issued_document_id, outcome, reason)
    VALUES ('00000000-0000-0000-0000-00000000a001', 'FILE', sha256('edited'::bytea), '00000000-0000-0000-0000-000000009010',
    'ALTERED', 'CONTENT_DIFFERS') $q$)
    + pg_temp.act(NULL, $q$ INSERT INTO snigf.verification_incident (id, verification_id, kind, issued_document_id)
    VALUES ('00000000-0000-0000-0000-00000000a101', '00000000-0000-0000-0000-00000000a001', 'ALTERED',
    '00000000-0000-0000-0000-000000009010') $q$), 2, 'anyone verifying is recorded, and an alteration opens an incident');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ INSERT INTO snigf.document_verification (channel, claimed_number, outcome, reason,
    actor_id) VALUES ('NUMBER', 'x', 'UNVERIFIED', 'NO_ISSUANCE_RECORD', '00000000-0000-0000-0000-0000000000b3') $q$) $$,
    '42501', NULL, 'a verification is never recorded in someone else''s name');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000c1', $q$ SELECT (SELECT count(*) FROM snigf.document_verification)
    + (SELECT count(*) FROM snigf.verification_incident) $q$), '0', 'only reviewers read verifications and incidents');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ UPDATE snigf.verification_incident SET status = 'RESOLVED',
    resolution = 'GENUINE_COPY', note = 'Looks fine to me, closing', resolved_by = '00000000-0000-0000-0000-0000000000c1',
    version = version + 1 $q$), 0, 'an intake officer resolves no incident');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.verification_incident
    SET status = 'RESOLVED', resolution = 'GENUINE_COPY', note = 'short', resolved_by = '00000000-0000-0000-0000-0000000000d1',
    version = version + 1 $q$) $$, '23514', NULL, 'a resolution carries a note');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.verification_incident SET status = 'RESOLVED',
    resolution = 'CONFIRMED_ALTERATION', note = 'Holder name edited after issuance', resolved_by = '00000000-0000-0000-0000-0000000000d1',
    version = version + 1 $q$), 1, 'the document officer resolves the incident');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000d1', $q$ UPDATE snigf.verification_incident
    SET status = 'RESOLVED', resolution = 'REFERRED', note = 'Resolving it a second time', resolved_by = '00000000-0000-0000-0000-0000000000d1',
    version = version + 1 $q$) $$, '23001', NULL, 'an incident is resolved once');

SELECT * FROM finish();
ROLLBACK;
