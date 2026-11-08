-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- The directory's search folding (V28, ADR-041 D20): case and accents ignored, ligatures as two
-- letters, every other character kept, so '%' and '_' stay ordinary characters; immutable; callable
-- by the application, not by PUBLIC.
BEGIN;
SET search_path = snigf, public, tap;
SELECT plan(11);

SELECT is(search_fold('DEMO Hélène NGÔ'), 'demo helene ngo', 'case and accents folded');
SELECT is(search_fold('Œuvray Æther Straße'), 'oeuvray aether strasse', 'ligatures and sharp s as two letters');
SELECT is(search_fold('ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖØÙÚÛÜÝŸ'), 'aaaaaaceeeeiiiinoooooouuuuyy', 'the capitals of Latin-1');
SELECT is(search_fold('àáâãäåāăąçćčďèéêëēĕėęěìíîïĩīĭįıñńňòóôõöøōŏőŕřśšşťùúûüũūŭůűųýÿŷźżž'),
          'aaaaaaaaacccdeeeeeeeeeiiiiiiiiinnnooooooooorrssstuuuuuuuuuuyyyzzz', 'each accented letter to its own base letter');
SELECT is(search_fold('DEMO-N_1%x'), 'demo-n_1%x', 'wildcards and punctuation kept as they are');
SELECT is(search_fold('user-1@demo.invalid'), 'user-1@demo.invalid', 'an address unchanged but for case');
SELECT is(search_fold(NULL), NULL, 'NULL stays NULL');
SELECT ok(position(search_fold('HELENE') IN search_fold('DEMO Hélène')) > 0, 'a term without accents finds a name with them');
SELECT ok(position(search_fold('N%') IN search_fold('DEMO-N-1')) = 0, 'a percent sign matches only itself');

SELECT is((SELECT provolatile FROM pg_proc WHERE oid = 'snigf.search_fold(text)'::regprocedure), 'i', 'immutable');
SELECT ok(has_function_privilege('snigf_app', 'snigf.search_fold(text)', 'EXECUTE')
          AND NOT EXISTS (SELECT 1 FROM pg_proc p, aclexplode(p.proacl) a
                           WHERE p.oid = 'snigf.search_fold(text)'::regprocedure AND a.grantee = 0),
    'the application may call it; PUBLIC may not');

SELECT * FROM finish();
ROLLBACK;
