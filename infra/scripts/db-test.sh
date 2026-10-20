#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Database-layer test run: builds a fresh database the way a deployment does
# (roles script as superuser, then every migration as snigf_migrator), then runs
# the pgTAP suite and the concurrent audit-chain test.
#
# Requirements: PostgreSQL with PostGIS and pgTAP, pg_prove, pgbench, and a
# superuser reachable as $PGSUPERUSER through local (peer) authentication.
# Usage: infra/scripts/db-test.sh            (from the repository root)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DB="${SNIGF_TEST_DB:-snigf_dbtest}"
PGSUPERUSER="${PGSUPERUSER:-postgres}"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
TESTS="$ROOT/backend/src/test/sql"

as_super() {
  if [[ "$(id -un)" == "$PGSUPERUSER" ]]; then "$@"; else runuser -u "$PGSUPERUSER" -- "$@"; fi
}
psql_super() { as_super psql -X -q -v ON_ERROR_STOP=1 "$@"; }

# Throwaway passwords for this run only.
APP_PW="$(openssl rand -hex 16)"
MIG_PW="$(openssl rand -hex 16)"
AUD_PW="$(openssl rand -hex 16)"

echo "== Recreating database $DB"
psql_super -d postgres -c "DROP DATABASE IF EXISTS $DB" >/dev/null
for r in snigf_app snigf_auditor snigf_migrator; do
  psql_super -d postgres -c "DROP ROLE IF EXISTS $r" >/dev/null
done
psql_super -d postgres -c "CREATE DATABASE $DB" >/dev/null

echo "== Roles and extensions (infra/db/init/01-roles.sql)"
psql_super -d "$DB" -v migrator_pw="$MIG_PW" -v app_pw="$APP_PW" -v auditor_pw="$AUD_PW" \
  -f "$ROOT/infra/db/init/01-roles.sql" >/dev/null

echo "== Migrations as snigf_migrator"
for f in $(ls "$MIGRATIONS"/V*__*.sql | sort -V); do
  echo "   $(basename "$f")"
  { echo "SET ROLE snigf_migrator; SET search_path = snigf, public;"; cat "$f"; } \
    | psql_super -d "$DB" -1 >/dev/null
done

echo "== pgTAP"
psql_super -d "$DB" -c "CREATE SCHEMA tap; CREATE EXTENSION pgtap SCHEMA tap; GRANT USAGE ON SCHEMA tap TO PUBLIC;" >/dev/null
psql_super -d "$DB" -f "$TESTS/helpers.sql" >/dev/null
as_super pg_prove -d "$DB" --ext .sql -r "$TESTS/t"

echo "== Concurrent audit chain (8 clients x 250 transactions, ~10% rolled back)"
PGPASSWORD="$APP_PW" pgbench -n -h 127.0.0.1 -U snigf_app -c 8 -j 4 -t 250 \
  -f "$TESTS/bench/audit_insert.pgbench" "$DB" > /dev/null
RESULT="$(PGPASSWORD="$AUD_PW" psql -X -tA -h 127.0.0.1 -U snigf_auditor -d "$DB" -c \
  "SELECT verified, rows_checked, (SELECT count(*) FROM audit_event), (SELECT max(chain_pos) FROM audit_event)
     FROM audit_verify_chain()")"
IFS='|' read -r VERIFIED CHECKED COUNT MAXPOS <<< "$RESULT"
echo "   verified=$VERIFIED rows_checked=$CHECKED committed_rows=$COUNT max_chain_pos=$MAXPOS"
if [[ "$VERIFIED" != "t" || "$CHECKED" != "$COUNT" || "$COUNT" != "$MAXPOS" || "$COUNT" -lt 1500 ]]; then
  echo "FAIL: chain is not contiguous and valid under concurrency" >&2
  exit 1
fi
echo "PASS: chain contiguous and valid under concurrency"
