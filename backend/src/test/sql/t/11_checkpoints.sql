-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Checkpoints are never replaced (decision D5, V3 and V9): append-only, one per position.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(5);

INSERT INTO audit_event (id, actor_type, actor_label, action, outcome, correlation_id, details)
VALUES (gen_random_uuid(), 'SERVICE', 'pgtap', 'AUDIT_VERIFIED', 'SUCCESS', gen_random_uuid(), '{}');
SELECT lives_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.audit_checkpoint (id, chain_pos, head_hash, signature, key_id)
    SELECT '00000000-0000-0000-0000-00000000c001', max(chain_pos), decode(repeat('aa', 32), 'hex'), '\x01', 'v1'
      FROM snigf.audit_event $q$) $$,
    'the application records a checkpoint');
SELECT throws_ok($$ SELECT run_as('snigf_app', $q$
    INSERT INTO snigf.audit_checkpoint (id, chain_pos, head_hash, signature, key_id)
    SELECT gen_random_uuid(), max(chain_pos), decode(repeat('bb', 32), 'hex'), '\x02', 'v1'
      FROM snigf.audit_event $q$) $$,
    '23505', NULL, 'a second checkpoint for the same position is refused');
SELECT throws_ok($$ UPDATE audit_checkpoint SET signature = '\x03' WHERE id = '00000000-0000-0000-0000-00000000c001' $$,
    '23001', NULL, 'a checkpoint is never changed, even by the schema owner');
SELECT throws_ok($$ DELETE FROM audit_checkpoint WHERE id = '00000000-0000-0000-0000-00000000c001' $$,
    '23001', NULL, 'a checkpoint is never deleted');
SELECT is((SELECT encode(signature, 'hex') FROM audit_checkpoint WHERE id = '00000000-0000-0000-0000-00000000c001'),
    '01', 'the first checkpoint is unchanged');

SELECT * FROM finish();
ROLLBACK;
