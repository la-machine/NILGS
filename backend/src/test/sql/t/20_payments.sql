-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Simulated payment and reconciliation (MVP-08, E-17, V18): fee versions never overlap; what a
-- request owes is the database's finding; only the applicant opens one, one at a time; its status
-- changes only in the payment functions, never to PAID past its expiry; each provider transaction
-- is recorded once; mismatches and unknown references are exceptions; the gate holds acceptance;
-- the finance views and resolutions need payment.reconcile; only the simulator is approved.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(38);

DO $$ BEGIN PERFORM tap.seed_basic(); END $$;
-- b1 cadastral officer; b3 applicant; e1 another applicant; i1 intake at DEMO1; f1 finance officer.
INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES
    ('00000000-0000-0000-0000-0000000000e1', 'other@demo.invalid', 'Other Applicant', 'x', 'APPLICANT'),
    ('00000000-0000-0000-0000-0000000000c1', 'i1@demo.invalid', 'Intake', 'x', 'STAFF'),
    ('00000000-0000-0000-0000-0000000000f1', 'f1@demo.invalid', 'Finance', 'x', 'STAFF');
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', 'CADASTRAL_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', 'INTAKE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'FINANCE_OFFICER', '00000000-0000-0000-0000-0000000000a1', 'seed');
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
-- One value read as snigf_app for an actor.
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
-- Application n (d00n) on parcel n, submitted by b3 and in completeness review.
CREATE FUNCTION pg_temp.submitted(p_no int) RETURNS void
LANGUAGE plpgsql AS $f$
DECLARE
    app text := '00000000-0000-0000-0000-00000000d00' || p_no;
BEGIN
    PERFORM pg_temp.parcel(p_no, 900000 + p_no * 2000);
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.application (id, applicant_id, type_code, type_version_id)
        SELECT %L, '00000000-0000-0000-0000-0000000000b3', 'DEMO_TEST', id FROM snigf.application_type WHERE code = 'DEMO_TEST' $q$, app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'SUBMITTED',
        parcel_reference = %L, parcel_id = %L, office_id = '00000000-0000-0000-0000-0000000000a1',
        reference = %L, state_changed_by = '00000000-0000-0000-0000-0000000000b3', version = version + 1 WHERE id = %L $q$,
        format('DEMO-PCL-DEMO1-2026-%s-Z', lpad(p_no::text, 6, '0')), '00000000-0000-0000-0000-0000000040' || lpad(p_no::text, 2, '0'),
        format('DEMO-APP-2026-%s-X', lpad(p_no::text, 6, '0')), app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.completeness_record (application_id, kind)
        VALUES (%L, 'AUTOMATIC') $q$, app));
    PERFORM pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ UPDATE snigf.application SET state = 'COMPLETENESS_REVIEW',
        state_changed_by = NULL, version = version + 1 WHERE id = %L $q$, app));
END;
$f$;
-- Opens a request for application n as b3, with reference DEMO-PAY-2026-0000000n-X and a time to live.
CREATE FUNCTION pg_temp.open(p_no int, p_ttl interval) RETURNS int
LANGUAGE sql AS $f$
    SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', format($q$ INSERT INTO snigf.payment_request
        (application_id, reference, provider, scenario, requested_by, amount, currency, created_at, expires_at)
        VALUES (%L, %L, 'DEMO_SIMULATOR', 'SUCCEED', '00000000-0000-0000-0000-0000000000b3', 1, 'EUR', now(), now() + %L::interval) $q$,
        '00000000-0000-0000-0000-00000000d00' || p_no, format('DEMO-PAY-2026-%s-X', lpad(p_no::text, 8, '0')), p_ttl))
$f$;
-- A provider report, applied as snigf_app (no actor: the provider is no user); returns "outcome duplicate expired_now".
CREATE FUNCTION pg_temp.report(p_tx text, p_reference text, p_amount bigint, p_status text) RETURNS text
LANGUAGE sql AS $f$
    SELECT pg_temp.q(NULL, format($q$ SELECT outcome || ' ' || duplicate || ' ' || expired_now FROM snigf.payment_callback_apply(
        'DEMO_SIMULATOR', %L, %L, %s, 'XAF', %L, now(), 1) $q$, p_tx, p_reference, p_amount, p_status))
$f$;
CREATE FUNCTION pg_temp.status(p_no int) RETURNS text
LANGUAGE sql AS $f$
    SELECT status FROM snigf.payment_request WHERE reference = format('DEMO-PAY-2026-%s-X', lpad(p_no::text, 8, '0'))
$f$;
-- Time passing: moves a request's expiry into the past (triggers off, superuser only).
CREATE FUNCTION pg_temp.expire(p_no int) RETURNS void
LANGUAGE plpgsql AS $f$
BEGIN
    SET LOCAL session_replication_role = replica;
    UPDATE snigf.payment_request SET created_at = clock_timestamp() - interval '25 hours',
           expires_at = clock_timestamp() - interval '1 hour'
     WHERE reference = format('DEMO-PAY-2026-%s-X', lpad(p_no::text, 8, '0'));
    SET LOCAL session_replication_role = origin;
END;
$f$;

-- --- Fees --------------------------------------------------------------------------------------
SELECT lives_ok($$ SELECT pg_temp.act(NULL, $q$ INSERT INTO snigf.fee_schedule (application_type_code, amount, currency, label_fr,
    label_en, valid_from) VALUES ('DEMO_TEST', 20000, 'XAF', 'Frais DÉMO', 'DEMO fee', now()) $q$) $$, 'a DEMO fee is configured');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ INSERT INTO snigf.fee_schedule (application_type_code, amount, currency, label_fr,
    label_en, valid_from) VALUES ('DEMO_TEST', 30000, 'XAF', 'Frais DÉMO', 'DEMO fee', now()) $q$) $$, '23P01', NULL,
    'two fees of one type are never in force together');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ UPDATE snigf.fee_schedule SET amount = 1 $q$) $$, '42501', NULL,
    'a fee version is never edited (no privilege on its amount)');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ INSERT INTO snigf.fee_schedule (application_type_code, amount, currency, label_fr,
    label_en, valid_from) VALUES ('DEMO_OTHER', 100, 'EUR', 'Frais DÉMO', 'DEMO fee', now()) $q$) $$, '23514', NULL,
    'fees are in XAF only');
SELECT lives_ok($$ SELECT pg_temp.act(NULL, $q$ UPDATE snigf.fee_schedule SET valid_to = now() WHERE valid_to IS NULL $q$);
    SELECT pg_temp.act(NULL, $q$ INSERT INTO snigf.fee_schedule (application_type_code, amount, currency, label_fr,
    label_en, valid_from) VALUES ('DEMO_TEST', 25000, 'XAF', 'Frais DÉMO', 'DEMO fee', now()) $q$) $$,
    'a changed fee closes the version in force and opens a new one');
SELECT throws_ok($$ SELECT pg_temp.act(NULL, $q$ UPDATE snigf.fee_schedule SET valid_to = now() WHERE amount = 20000 $q$) $$,
    '23001', NULL, 'a closed version is never closed again');
SELECT throws_ok($$ DELETE FROM snigf.fee_schedule $$, '23001', NULL, 'fee versions are never deleted, even by their owner');

SELECT pg_temp.submitted(1);
SELECT pg_temp.submitted(2);
SELECT pg_temp.submitted(3);

-- --- The gate ----------------------------------------------------------------------------------
SELECT ok(payment_blocks_acceptance('00000000-0000-0000-0000-00000000d001'), 'a fee applies and nothing is paid: acceptance waits');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record
    (application_id, kind, actor_id) VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$) $$,
    '55000', NULL, 'the intake officer cannot accept an unpaid application');

-- --- Requests ----------------------------------------------------------------------------------
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ INSERT INTO snigf.payment_request
    (application_id, reference, provider, scenario, requested_by, created_at, expires_at)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO-PAY-2026-00000099-X', 'DEMO_SIMULATOR', 'SUCCEED',
            '00000000-0000-0000-0000-0000000000e1', now(), now() + interval '1 day') $q$) $$,
    '42501', NULL, 'nobody pays for someone else''s application');
SELECT is(pg_temp.open(1, '24 hours'), 1, 'the applicant opens a payment request');
SELECT is((SELECT amount || ' ' || currency || ' ' || status FROM payment_request WHERE reference = 'DEMO-PAY-2026-00000001-X'),
    '25000 XAF PENDING', 'what it owes is the fee in force at submission, whatever the caller said');
SELECT throws_ok($$ SELECT pg_temp.open(1, '24 hours') $$, '23505', NULL, 'one payment at a time per application');
SELECT throws_ok($$ SELECT pg_temp.open(2, '8 days') $$, '23514', NULL, 'a payment never stays pending more than 7 days');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ UPDATE snigf.payment_request SET status = 'PAID' $q$) $$,
    '42501', NULL, 'the applicant cannot mark a payment paid');
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000e1', $q$ UPDATE snigf.payment_request SET provider_charge_id = 'SIMTX-stolen1' $q$),
    0, 'nor touch anyone else''s request');
SELECT throws_ok($$ SELECT pg_temp.act('00000000-0000-0000-0000-0000000000b3', $q$ INSERT INTO snigf.payment_request
    (application_id, reference, provider, scenario, requested_by, created_at, expires_at)
    VALUES ('00000000-0000-0000-0000-00000000d003', 'DEMO-PAY-2026-00000098-X', 'REAL_BANK', 'SUCCEED',
            '00000000-0000-0000-0000-0000000000b3', now(), now() + interval '1 day') $q$) $$,
    '23514', NULL, 'only the DEMO simulator is an approved provider');

-- --- Reports -----------------------------------------------------------------------------------
SELECT is(pg_temp.report('SIMTX-0000-0001', 'DEMO-PAY-2026-00000001-X', 25000, 'SUCCEEDED'), 'MATCHED false false',
    'a success of the amount asked pays the request');
SELECT is(pg_temp.status(1), 'PAID', 'it is paid');
SELECT is(pg_temp.report('SIMTX-0000-0001', 'DEMO-PAY-2026-00000001-X', 25000, 'FAILED'), 'MATCHED true false',
    'the same transaction again is a duplicate with its first outcome, whatever it now claims');
SELECT is(pg_temp.report('SIMTX-0000-0002', 'DEMO-PAY-2026-00000001-X', 25000, 'SUCCEEDED'), 'ALREADY_SETTLED false false',
    'a second success on a paid request is an exception');
SELECT is(pg_temp.report('SIMTX-0000-0003', 'DEMO-PAY-0000-00000000-0', 25000, 'SUCCEEDED'), 'UNMATCHED_REFERENCE false false',
    'money for a reference nobody issued is an exception');
SELECT is(pg_temp.report('SIMTX-0000-0004', 'DEMO-PAY-0000-00000000-0', 25000, 'FAILED'), 'NO_EFFECT false false',
    'a failure that changes nothing is recorded without effect');
SELECT is(pg_temp.open(2, '24 hours'), 1, 'a second application gets its own request');
SELECT is(pg_temp.report('SIMTX-0000-0005', 'DEMO-PAY-2026-00000002-X', 24000, 'SUCCEEDED') || ' ' || pg_temp.status(2),
    'AMOUNT_MISMATCH false false PENDING', 'another amount is an exception and the request stays unpaid');
SELECT pg_temp.expire(2);
SELECT is(pg_temp.report('SIMTX-0000-0006', 'DEMO-PAY-2026-00000002-X', 25000, 'SUCCEEDED') || ' ' || pg_temp.status(2),
    'AFTER_EXPIRY false true EXPIRED', 'a success past the expiry expires the request and is an exception, never a payment');
SELECT throws_ok($$ UPDATE snigf.payment_request SET status = 'PENDING' WHERE reference = 'DEMO-PAY-2026-00000002-X' $$,
    '23001', NULL, 'a final status never changes, even for the owner');
SELECT throws_ok($$ SELECT pg_temp.q(NULL, $q$ SELECT outcome FROM snigf.payment_callback_apply('REAL_BANK', 'TX-00000001', 'x', 1,
    'XAF', 'SUCCEEDED', now(), 1) $q$) $$, '42501', NULL, 'reports from an unapproved provider are refused');
SELECT throws_ok($$ UPDATE snigf.payment_record SET outcome = 'MATCHED' $$, '23001', NULL, 'provider reports are never changed');
SELECT is(pg_temp.open(3, '24 hours'), 1, 'a third request');
SELECT pg_temp.expire(3);
SELECT is(pg_temp.q(NULL, $$ SELECT count(*) FROM snigf.payment_expire_due() $$), '1', 'the sweep expires what is past its expiry');

-- --- Gate opened, finance ----------------------------------------------------------------------
SELECT is(pg_temp.act('00000000-0000-0000-0000-0000000000c1', $q$ INSERT INTO snigf.completeness_record (application_id, kind, actor_id)
    VALUES ('00000000-0000-0000-0000-00000000d001', 'COMPLETE', '00000000-0000-0000-0000-0000000000c1') $q$), 1,
    'paid: the intake officer accepts');
SELECT is(pg_temp.q('00000000-0000-0000-0000-0000000000f1', $$ SELECT count(*) FROM snigf.payment_exceptions_for_finance(NULL, 50, 0) $$)
    || ' ' || pg_temp.q('00000000-0000-0000-0000-0000000000b3', $$ SELECT count(*) FROM snigf.payment_exceptions_for_finance(NULL, 50, 0) $$),
    '4 0', 'the finance officer reads the four exceptions; an applicant reads none');
-- The record ids are read here as the owner: the application role reads no payment_record.
SELECT lives_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT snigf.payment_exception_resolve(''%s'',
    ''REFUND_SIMULATED'', ''Simulated refund'')') $$, (SELECT id FROM payment_record WHERE provider_transaction_id = 'SIMTX-0000-0003')),
    'the finance officer resolves an exception');
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT snigf.payment_exception_resolve(''%s'',
    ''NO_ACTION'', ''Already resolved once'')') $$, (SELECT id FROM payment_record WHERE provider_transaction_id = 'SIMTX-0000-0003')),
    '23505', NULL, 'an exception is resolved once');
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000f1', 'SELECT snigf.payment_exception_resolve(''%s'',
    ''NO_ACTION'', ''Not an exception at all'')') $$, (SELECT id FROM payment_record WHERE provider_transaction_id = 'SIMTX-0000-0001')),
    '23514', NULL, 'a matched payment is no exception to resolve');
SELECT throws_ok(format($$ SELECT pg_temp.q('00000000-0000-0000-0000-0000000000c1', 'SELECT snigf.payment_exception_resolve(''%s'',
    ''NO_ACTION'', ''Nothing to do here'')') $$, (SELECT id FROM payment_record WHERE provider_transaction_id = 'SIMTX-0000-0005')),
    '42501', NULL, 'nobody without payment.reconcile resolves one');
SELECT is(pg_temp.status(2), 'EXPIRED', 'resolving never pays a request');

SELECT * FROM finish();
ROLLBACK;
