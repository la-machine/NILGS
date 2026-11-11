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

echo "== Concurrent deposits on one title, conservators and a notary's account (8 clients x 25 deposits)"
psql_super -d "$DB" -f "$TESTS/bench/register_deposit_setup.sql" >/dev/null
PGPASSWORD="$APP_PW" pgbench -n -h 127.0.0.1 -U snigf_app -c 8 -j 4 -t 25 \
  -f "$TESTS/bench/register_deposit.pgbench@1" -f "$TESTS/bench/register_deposit_professional.pgbench@1" "$DB" > /dev/null
RESULT="$(psql_super -tA -d "$DB" -c \
  "SELECT count(*) FILTER (WHERE status = 'HOLDING'), count(*) FILTER (WHERE status = 'IN_CONFLICT'),
          (SELECT count(*) FROM snigf.transaction_conflict),
          count(*) FILTER (WHERE channel = 'PROFESSIONAL_ACCOUNT'),
          count(*) FILTER (WHERE channel = 'PROFESSIONAL_ACCOUNT' AND NOT EXISTS (
              SELECT 1 FROM snigf.deposit_document d WHERE d.transaction_id = pending_transaction.id AND d.category = 'DEED'))
     FROM snigf.pending_transaction")"
IFS='|' read -r HOLDING IN_CONFLICT CONFLICTS PROFESSIONAL NO_DEED <<< "$RESULT"
echo "   holding=$HOLDING in_conflict=$IN_CONFLICT conflicts=$CONFLICTS professional=$PROFESSIONAL without_deed=$NO_DEED"
if [[ "$HOLDING" != "1" || "$IN_CONFLICT" != "199" || "$CONFLICTS" != "199" || "$PROFESSIONAL" -lt 1 || "$NO_DEED" != "0" ]]; then
  echo "FAIL: racing deposits must leave one holding and every other in conflict, each professional one with its deed" >&2
  exit 1
fi
echo "PASS: one holding transaction, every other deposit recorded in conflict, each professional one with its deed"

echo "== A replacement's approval racing a transfer's signature (20 titles, ADR-041 D19)"
psql_super -d "$DB" -f "$TESTS/bench/register_replacement_setup.sql" >/dev/null
app_psql() { PGPASSWORD="$APP_PW" psql -X -q -h 127.0.0.1 -U snigf_app -d "$DB" "$@"; }
for ROUND in $(seq 1 20); do
  # The conservator registers over the bytes prepared before; meanwhile one asks for the deed's replacement and
  # another approves it. Either may win the title's lock; a refusal is expected of the loser.
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);
               SELECT snigf.register_register(inscription, canonical, CAST(signature AS jsonb)) FROM snigf.bench_race WHERE round = $ROUND"     >/dev/null 2>&1 &
  app_psql -c "BEGIN;
               SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', true);
               SELECT set_config('bench.replacement', CAST(snigf.deposit_document_replacement_request(document, md5(CAST(gen_random_uuid() AS text)),
                      repeat('e', 64), 100, 'application/pdf', 'deed-v2.pdf', 'A clearer copy of the deed') AS text), true)
                 FROM snigf.bench_race WHERE round = $ROUND;
               SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba08', true);
               SELECT snigf.deposit_document_replacement_decide(CAST(current_setting('bench.replacement') AS uuid), 'APPROVED', NULL);
               COMMIT;" >/dev/null 2>&1 &
  wait
done
RESULT="$(psql_super -tA -d "$DB" -c   "SELECT count(*) FILTER (WHERE i.status = 'REGISTERED'),
          count(*) FILTER (WHERE EXISTS (SELECT 1 FROM snigf.deposit_document_replacement x WHERE x.document_id = b.document AND x.status = 'APPROVED')),
          count(*) FILTER (WHERE i.status = 'REGISTERED' AND EXISTS (SELECT 1 FROM snigf.deposit_document_replacement x WHERE x.document_id = b.document AND x.status = 'APPROVED')),
          count(*) FILTER (WHERE i.status = 'REGISTERED' AND EXISTS (SELECT 1 FROM snigf.inscription_signature s WHERE s.inscription_id = i.id
                                                                        AND s.canonical IS DISTINCT FROM snigf.inscription_canonical(i.id)))
     FROM snigf.bench_race b JOIN snigf.inscription i ON i.id = b.inscription")"
IFS='|' read -r REGISTERED APPROVED BOTH STALE <<< "$RESULT"
echo "   registered=$REGISTERED approved=$APPROVED both=$BOTH signed_over_stale_bytes=$STALE"
if [[ "$BOTH" != "0" || "$STALE" != "0" || $((REGISTERED + APPROVED)) != "20" ]]; then
  echo "FAIL: each round must end with the transfer registered over the current documents or the replacement approved, never both" >&2
  exit 1
fi
echo "PASS: no transfer registered over documents that changed underneath its signature"
