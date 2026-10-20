-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Audit chain: server-assigned positions and hashes, verification, tamper detection (C7, S1-AC5).
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(13);

CREATE FUNCTION pg_temp.ev(p_action text) RETURNS void LANGUAGE sql AS $f$
    INSERT INTO snigf.audit_event (id, chain_pos, occurred_at, actor_type, actor_label, action, outcome,
                                   correlation_id, details, prev_hash, hash)
    -- The client tries to choose position, time and hashes; the trigger must ignore them.
    VALUES (gen_random_uuid(), 999, '2000-01-01', 'SERVICE', 'test', p_action, 'SUCCESS',
            gen_random_uuid(), jsonb_build_object('n', p_action), '\x00', '\x00');
$f$;

SELECT lives_ok($$ SELECT run_as('snigf_app', 'SELECT pg_temp.ev(''FIRST_EVENT'')') $$, 'app can append an event');
SELECT pg_temp.ev('SECOND_EVENT');
SELECT pg_temp.ev('THIRD_EVENT');

SELECT results_eq($$ SELECT chain_pos FROM audit_event ORDER BY chain_pos $$, $$ VALUES (1::bigint), (2), (3) $$,
    'positions are assigned by the database, ignoring the client value');
SELECT ok((SELECT bool_and(occurred_at > '2020-01-01') FROM audit_event), 'time comes from the database clock');
SELECT is((SELECT prev_hash FROM audit_event WHERE chain_pos = 1), decode(repeat('00', 32), 'hex'),
    'the first event links to the genesis hash');
SELECT ok((SELECT bool_and(a.prev_hash = b.hash) FROM audit_event a JOIN audit_event b ON b.chain_pos = a.chain_pos - 1),
    'each event links to the previous event''s hash');
SELECT is((SELECT verified FROM audit_verify_chain()), true, 'an untouched chain verifies');

-- Actor coherence.
SELECT throws_ok($$ INSERT INTO audit_event (id, actor_type, action, outcome, correlation_id, prev_hash, hash)
    VALUES (gen_random_uuid(), 'USER', 'PARCEL_CREATED', 'SUCCESS', gen_random_uuid(), '\x00', '\x00') $$,
    '23514', NULL, 'a USER event must name the user');

-- Tampering, as a superuser who disables the append-only trigger (the residual-risk actor).
SAVEPOINT clean;
ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change;
UPDATE audit_event SET details = '{"n":"REWRITTEN"}' WHERE chain_pos = 2;
SELECT results_eq($$ SELECT verified, first_mismatch_pos, mismatch_kind FROM audit_verify_chain() $$,
    $$ VALUES (false, 2::bigint, 'CONTENT_HASH'::text) $$,
    'an edited event is detected at its own position');

UPDATE audit_event SET hash = sha256(prev_hash || audit_canonical(audit_event.*)) WHERE chain_pos = 2;
SELECT results_eq($$ SELECT verified, first_mismatch_pos, mismatch_kind FROM audit_verify_chain() $$,
    $$ VALUES (false, 3::bigint, 'PREV_HASH'::text) $$,
    'an edited event with a recomputed hash breaks the next link');
ROLLBACK TO SAVEPOINT clean;

SAVEPOINT clean2;
ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change;
DELETE FROM audit_event WHERE chain_pos = 2;
SELECT results_eq($$ SELECT verified, first_mismatch_pos, mismatch_kind FROM audit_verify_chain() $$,
    $$ VALUES (false, 2::bigint, 'GAP'::text) $$,
    'a deleted event is detected as a gap');
ROLLBACK TO SAVEPOINT clean2;

SELECT is((SELECT verified FROM audit_verify_chain()), true, 'the chain verifies again after the tampering is rolled back');

-- Verification can start mid-chain and is available to the auditor role.
SELECT is((SELECT rows_checked FROM audit_verify_chain(2, 3)), 2::bigint, 'a range can be verified');
SELECT lives_ok($$ SELECT run_as('snigf_auditor', 'SELECT * FROM snigf.audit_verify_chain()') $$,
    'the auditor role can run verification');

SELECT * FROM finish();
ROLLBACK;
