-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V4: every function of the snigf schema resolves names through a fixed search_path.
--
-- Found while verifying the audit chain (2026-10-21): the functions of V1..V3 resolved
-- table and function names through the CALLER's search_path. For a session without
-- "snigf" on its path (the auditor connecting with defaults, a superuser, a maintenance
-- script), audit_verify_chain failed with 'type "audit_event" does not exist'. A caller
-- could also have shadowed a name used inside a function, such as a table or sha256(),
-- with an object of their own earlier on their path.
--
-- pg_catalog comes first, so built-ins (sha256, length, ...) can never be shadowed; then
-- snigf; then public, where PostGIS lives (nobody but owners can create objects there,
-- 01-roles.sql revokes CREATE); pg_temp last, because PostgreSQL otherwise searches the
-- caller's temporary schema FIRST, where anyone can create a look-alike table. Every later
-- migration must give each new function
-- "SET search_path = pg_catalog, snigf, public, pg_temp"; pgTAP test 06 fails otherwise.

DO $$
DECLARE
    f regprocedure;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'snigf'
    LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path = pg_catalog, snigf, public, pg_temp', f);
    END LOOP;
END;
$$;
