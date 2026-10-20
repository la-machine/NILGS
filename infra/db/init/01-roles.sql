-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- SNIGF/NILGS MVP — database roles and extensions.
-- Runs ONCE, as a PostgreSQL superuser, against the application database,
-- before any Flyway migration. Passwords are passed as psql variables and are
-- never stored in the repository:
--   psql -v migrator_pw=... -v app_pw=... -v auditor_pw=... -f 01-roles.sql
--
-- Role model (ADR-009):
--   snigf_migrator  owns the schema and runs Flyway; used only at deploy time
--   snigf_app       application runtime; least privilege, granted per table
--   snigf_auditor   read-only access to audit tables for chain verification

\set ON_ERROR_STOP on

CREATE ROLE snigf_migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE snigf_app      LOGIN PASSWORD :'app_pw';
CREATE ROLE snigf_auditor  LOGIN PASSWORD :'auditor_pw';

-- Nobody connects unless explicitly allowed.
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO snigf_migrator, snigf_app, snigf_auditor', current_database()) \gexec

-- PostGIS lives in the public schema; ordinary roles may use it but not create objects there.
CREATE EXTENSION IF NOT EXISTS postgis;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA snigf AUTHORIZATION snigf_migrator;
GRANT USAGE ON SCHEMA snigf TO snigf_app, snigf_auditor;

ALTER ROLE snigf_migrator SET search_path = snigf, public;
ALTER ROLE snigf_app      SET search_path = snigf, public;
ALTER ROLE snigf_auditor  SET search_path = snigf, public;

-- Audit inserts compute a hash chain under an advisory lock and must see the
-- latest committed row, which requires READ COMMITTED (see V3__audit.sql).
ALTER ROLE snigf_app SET default_transaction_isolation = 'read committed';
