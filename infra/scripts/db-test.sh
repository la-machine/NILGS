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

echo "== A title's registration racing an objection at the counter (20 approved cases, ADR-042 step 5)"
psql_super -d "$DB" -f "$TESTS/bench/register_case_setup.sql" >/dev/null
register_case() {
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba08', false);
               SELECT snigf.register_confirm(inscription, canonical, CAST(signature AS jsonb)) FROM snigf.bench_case_race WHERE round = $1"
}
object_case() {
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000bc06', false);
               SELECT snigf.procedure_objection_add(application, jsonb_build_object('name', 'DEMO Race Neighbour', 'identityKind', 'NATIONAL_ID',
                      'identityNumber', 'DEMO-ID-RACE', 'interest', 'NEIGHBOUR'), 'The boundary crosses my field (synthetic race)', '[]'::jsonb,
                      gen_random_uuid()) FROM snigf.bench_case_race WHERE round = $1"
}
# The control: alone, the CREATION registers (the fixture is valid), and an objection afterwards is refused.
register_case 21 >/dev/null
if object_case 21 >/dev/null 2>&1; then
  echo "FAIL: an objection was received on a registered case" >&2
  exit 1
fi
for ROUND in $(seq 1 20); do
  # The second conservator confirms the CREATION from the case (ADR-043 D40); meanwhile an intake officer records an objection to it. Both
  # take the case's lock: either may win, and the loser must be refused (CASE_REGISTERED or OBJECTION_OPEN). Who
  # starts first alternates.
  if (( ROUND % 2 )); then
    register_case "$ROUND" >/dev/null 2>&1 &
    object_case "$ROUND" >/dev/null 2>&1 &
  else
    object_case "$ROUND" >/dev/null 2>&1 &
    register_case "$ROUND" >/dev/null 2>&1 &
  fi
  wait
done
RESULT="$(psql_super -tA -d "$DB" -c \
  "SELECT count(*) FILTER (WHERE t.id IS NOT NULL),
          count(*) FILTER (WHERE o.n > 0),
          count(*) FILTER (WHERE t.id IS NOT NULL AND o.n > 0)
     FROM snigf.bench_case_race b
     LEFT JOIN snigf.land_title t ON t.application_id = b.application
     CROSS JOIN LATERAL (SELECT count(*) AS n FROM snigf.procedure_objection x WHERE x.application_id = b.application) o
    WHERE b.round <= 20")"
CONTROL="$(psql_super -tA -d "$DB" -c "SELECT count(*) FROM snigf.bench_case_race b JOIN snigf.land_title t ON t.application_id = b.application
                                       WHERE b.round = 21 AND t.origin = 'IMMATRICULATION'")"
IFS='|' read -r REGISTERED OBJECTED BOTH <<< "$RESULT"
echo "   registered=$REGISTERED objected=$OBJECTED both=$BOTH control_registered=$CONTROL"
if [[ "$CONTROL" != "1" || "$BOTH" != "0" || $((REGISTERED + OBJECTED)) != "20" ]]; then
  echo "FAIL: each round must end with the title registered or the objection received, never both" >&2
  exit 1
fi
echo "PASS: no title registered while an objection to its case was being received"

echo "== An owner's identity corrected while the CREATION's second signature is given (10 approved cases, ADR-043)"
correct_case() {
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000bc02', false);
               SELECT snigf.case_identity_record(person, jsonb_build_object('fullName', 'DEMO Race Owner Corrected', 'identityKind', 'NATIONAL_ID',
                      'identityNumber', 'DEMO-ID-SELF-0001', 'phone', '+237 600000001'), id_file, NULL, identity, 'A race correction of the name')
                 FROM snigf.bench_case_race WHERE round = $1"
}
for ROUND in $(seq 22 31); do
  # The correction makes the proposal stale; the signature registers it. Both take the case's lock: either may win, and
  # the loser must be refused (STALE_PROPOSAL or CASE_REGISTERED). Who starts first alternates.
  if (( ROUND % 2 )); then
    register_case "$ROUND" >/dev/null 2>&1 &
    correct_case "$ROUND" >/dev/null 2>&1 &
  else
    correct_case "$ROUND" >/dev/null 2>&1 &
    register_case "$ROUND" >/dev/null 2>&1 &
  fi
  wait
done
RESULT="$(psql_super -tA -d "$DB" -c \
  "SELECT count(*) FILTER (WHERE t.id IS NOT NULL),
          count(*) FILTER (WHERE c.n > 0),
          count(*) FILTER (WHERE t.id IS NOT NULL AND c.n > 0),
          count(*) FILTER (WHERE t.id IS NOT NULL AND EXISTS (SELECT 1 FROM snigf.inscription_signature s WHERE s.inscription_id = b.inscription
                                                                  AND s.canonical IS DISTINCT FROM snigf.inscription_canonical(b.inscription)))
     FROM snigf.bench_case_race b
     LEFT JOIN snigf.land_title t ON t.application_id = b.application
     CROSS JOIN LATERAL (SELECT count(*) AS n FROM snigf.case_person_identity x WHERE x.application_id = b.application AND x.version_no > 2) c
    WHERE b.round BETWEEN 22 AND 31")"
IFS='|' read -r REGISTERED CORRECTED BOTH STALE <<< "$RESULT"
echo "   registered=$REGISTERED corrected=$CORRECTED both=$BOTH signed_over_other_bytes=$STALE"
if [[ "$BOTH" != "0" || "$STALE" != "0" || $((REGISTERED + CORRECTED)) != "10" ]]; then
  echo "FAIL: each round must end with the title registered as approved or the identity corrected, never both" >&2
  exit 1
fi
echo "PASS: no title registered over an owner's identity corrected between its signatures"

echo "== Two supervisors open the same case's reconsideration at once (10 approved cases, ADR-043 step 5b)"
reopen_case() {
  app_psql -c "SELECT set_config('snigf.actor_id', '$2', false);
               SELECT snigf.procedure_reconsider(b.application, a.version, '{}'::text[], 'The upheld objection was not before the decision (race)')
                 FROM snigf.bench_case_race b JOIN snigf.application a ON a.id = b.application WHERE b.round = $1"
}
for ROUND in $(seq 32 41); do
  # Both read the same version and take the case's lock: one opens round 1, the other is refused (CASE_NOT_APPROVED or
  # STALE_VERSION). Who starts first alternates.
  if (( ROUND % 2 )); then
    reopen_case "$ROUND" 00000000-0000-0000-0000-00000000bc08 >/dev/null 2>&1 &
    reopen_case "$ROUND" 00000000-0000-0000-0000-00000000bc09 >/dev/null 2>&1 &
  else
    reopen_case "$ROUND" 00000000-0000-0000-0000-00000000bc09 >/dev/null 2>&1 &
    reopen_case "$ROUND" 00000000-0000-0000-0000-00000000bc08 >/dev/null 2>&1 &
  fi
  wait
done
RESULT="$(psql_super -tA -d "$DB" -c   "SELECT count(*) FILTER (WHERE r.n = 1 AND a.review_round = 1 AND a.state = 'UNDER_REVIEW' AND t.n = 1),
          count(*) FILTER (WHERE r.n > 1 OR a.review_round > 1 OR t.n > 1)
     FROM snigf.bench_case_race b
     JOIN snigf.application a ON a.id = b.application
     CROSS JOIN LATERAL (SELECT count(*) AS n FROM snigf.case_reconsideration x WHERE x.application_id = b.application) r
     CROSS JOIN LATERAL (SELECT count(*) AS n FROM snigf.application_transition x WHERE x.application_id = b.application
                           AND x.from_state = 'APPROVED' AND x.to_state = 'UNDER_REVIEW') t
    WHERE b.round BETWEEN 32 AND 41")"
IFS='|' read -r OPENED_ONCE TWICE <<< "$RESULT"
echo "   opened_once=$OPENED_ONCE opened_twice=$TWICE"
if [[ "$OPENED_ONCE" != "10" || "$TWICE" != "0" ]]; then
  echo "FAIL: each case must be reopened exactly once, in round 1" >&2
  exit 1
fi
echo "PASS: one reconsideration open at a time, under concurrency"

echo "== A procedure B case's deposit racing another deposit on its title (20 titles), and two deposits naming one case (10 cases), ADR-042 step 6"
psql_super -d "$DB" -f "$TESTS/bench/register_mutation_setup.sql" >/dev/null
# The conservator receives the sale at the counter, naming the case (the bench table is read as the conservator: the
# notary reads no register table).
case_deposit() {
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);
               SELECT snigf.transaction_deposit(b.title, 'SALE', b.buyer, NULL, ARRAY['DEMO-DEED-RACE'], interval '30 days', b.reference)
                 FROM snigf.bench_mutation_race b WHERE b.round = $1"
}
# The notary deposits a sale on the same title through their account, naming the case ($2 = case) or none.
notary_deposit() {
  app_psql -c "SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba02', false);
               SELECT set_config('bench.title', b.title::text, false), set_config('bench.case', CASE WHEN '$2' = 'case' THEN b.reference ELSE '' END, false)
                 FROM snigf.bench_mutation_race b WHERE b.round = $1;
               SELECT set_config('snigf.actor_id', '00000000-0000-0000-0000-00000000ba05', false);
               SELECT transaction_id FROM snigf.transaction_deposit_professional(current_setting('bench.title')::uuid, 'SALE',
                 jsonb_build_object('kind', 'NATURAL_PERSON', 'fullName', 'DEMO Race Buyer', 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-RACE-B'),
                 jsonb_build_array(jsonb_build_object('category', 'DEED', 'storageKey', md5(gen_random_uuid()::text),
                   'sha256', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'), 'sizeBytes', 1000,
                   'mediaType', 'application/pdf', 'name', 'deed.pdf')),
                 interval '30 days', nullif(current_setting('bench.case'), ''))"
}
for ROUND in $(seq 1 30); do
  # Rounds 1 to 20: the case's deposit and a deposit naming no case both take the title's lock; whichever comes second is
  # recorded in conflict (ADR-040 §4), never refused, never both holding. Rounds 21 to 30: both name the case; one is
  # accepted, the other refused (CASE_DEPOSIT_OPEN). Who starts first alternates.
  if (( ROUND <= 20 )); then OTHER=none; else OTHER=case; fi
  if (( ROUND % 2 )); then
    case_deposit "$ROUND" >/dev/null 2>&1 &
    notary_deposit "$ROUND" "$OTHER" >/dev/null 2>&1 &
  else
    notary_deposit "$ROUND" "$OTHER" >/dev/null 2>&1 &
    case_deposit "$ROUND" >/dev/null 2>&1 &
  fi
  wait
done
RESULT="$(psql_super -tA -d "$DB" -c \
  "SELECT count(*) FILTER (WHERE b.round <= 20 AND d.n = 2 AND d.holding = 1 AND d.of_case = 1),
          count(*) FILTER (WHERE b.round > 20 AND d.of_case = 1 AND d.holding = 1),
          count(*) FILTER (WHERE d.holding > 1 OR d.of_case > 1)
     FROM snigf.bench_mutation_race b
     CROSS JOIN LATERAL (SELECT count(*) AS n, count(*) FILTER (WHERE t.status = 'HOLDING') AS holding,
                                count(*) FILTER (WHERE t.application_id = b.application AND t.status IN ('HOLDING', 'IN_CONFLICT')) AS of_case
                           FROM snigf.pending_transaction t WHERE t.title_id = b.title) d")"
IFS='|' read -r RACED NAMED_ONCE BROKEN <<< "$RESULT"
echo "   case_and_other=$RACED named_once=$NAMED_ONCE broken=$BROKEN"
if [[ "$RACED" != "20" || "$NAMED_ONCE" != "10" || "$BROKEN" != "0" ]]; then
  echo "FAIL: one deposit holds each title, and a case is named by one open deposit at a time" >&2
  exit 1
fi
echo "PASS: a case's deposit follows ADR-040's lock and conflicts, and one open deposit names a case, under concurrency"
