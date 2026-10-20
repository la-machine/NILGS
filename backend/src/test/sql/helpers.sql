-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Test helpers, installed by infra/scripts/db-test.sh after the migrations.

-- Runs one statement as another database role, then returns to the session role.
-- If the statement fails, pgTAP's exception block rolls back the subtransaction,
-- which also undoes SET LOCAL ROLE, so later assertions run as the superuser again.
CREATE FUNCTION tap.run_as(p_role text, p_sql text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('SET LOCAL ROLE %I', p_role);
    EXECUTE p_sql;
    RESET ROLE;
END;
$$;

-- Synthetic fixture: one demo office and users, created as the schema owner.
CREATE FUNCTION tap.seed_basic() RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO snigf.office (id, code, name_fr, name_en) VALUES
        ('00000000-0000-0000-0000-0000000000a1', 'DEMO1', 'Bureau de démonstration 1', 'Demo office 1');
    INSERT INTO snigf.app_user (id, email, display_name, password_hash, account_type) VALUES
        ('00000000-0000-0000-0000-0000000000b1', 'officer@demo.invalid',   'Demo Officer',   'x', 'STAFF'),
        ('00000000-0000-0000-0000-0000000000b2', 'admin@demo.invalid',     'Demo Admin',     'x', 'STAFF'),
        ('00000000-0000-0000-0000-0000000000b3', 'applicant@demo.invalid', 'Demo Applicant', 'x', 'APPLICANT');
END;
$$;
