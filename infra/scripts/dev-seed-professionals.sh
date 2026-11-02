#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Synthetic professional accounts for development (ADR-041, step 6; docs/guides/demo-accounts.md):
# one DEMO notary made active at YDE1, and one DEMO sworn surveyor verified at YDE1 and waiting for
# a second administrator.
#
#   bash infra/scripts/dev-seed-demo.sh            (first: YDE1, admin1 and admin2)
#   bash infra/scripts/dev-seed-professionals.sh   (the stack must be running)
#
# Each account goes through the real workflow, with no short cut:
#   1. it is requested on the public endpoint, like the form at #/professional-request (the
#      password is Argon2id with OpenBao's pepper; it cannot be written in SQL);
#   2. admin1 verifies the DEMO credential and proposes YDE1, through professional_verify;
#   3. admin2 approves the notary's activation, through professional_decide.
# Steps 2 and 3 call the database functions the application calls, as admin1 and admin2, so the
# database applies every rule itself: the four eyes, the reasons, the offices, and two security
# keys before an activation. This script holds no key, so the notary is approved only once they
# have signed in and registered two: run it again after that. The surveyor is left verified, for an
# administrator to approve or reject on #/professionals. Running it again adds nothing, and leaves
# any status changed by hand (suspended, rejected, revoked) as it is.
# Passwords are random and kept in infra/secrets/demo_accounts with the other demo accounts.
# Refused unless SNIGF_PROFILE=dev; never a migration, so these accounts can never exist outside
# development.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
setting() { sed -n "s/^$1=//p" .env | tr -d '\r'; }

[[ "$(setting SNIGF_PROFILE)" == "dev" ]] || { echo "refusing: SNIGF_PROFILE is not dev" >&2; exit 1; }
docker compose ps --status running --services | grep -qx web \
  || { echo "the stack is not running: docker compose up -d --wait" >&2; exit 1; }

BASE="https://$(setting SNIGF_PUBLIC_HOSTNAME):$(setting SNIGF_HTTPS_PORT)"
CSRF_COOKIE="$(setting SNIGF_CSRF_COOKIE_NAME)"
CSRF_HEADER="$(setting SNIGF_CSRF_HEADER_NAME)"
CA="infra/secrets/pki_root_ca_crt"
STORE="infra/secrets/demo_accounts"
JAR="$(mktemp)"
# As in dev-seed-demo.sh: the development CA publishes no revocation list.
TLS=(--cacert "$CA" --ssl-no-revoke --proto =https --tlsv1.3)
trap 'rm -f "$JAR" "$JAR.answer"' EXIT

# e-mail | display name | profession | chamber | registration number | jurisdiction
CAST="
notary.yde@demo.invalid|Demo Notary Yaounde|NOTARY|DEMO Chamber of Notaries|DEMO-N-YDE-SEED|DEMO Yaounde
sworn-surveyor.yde@demo.invalid|Demo Sworn Surveyor Yaounde|SWORN_SURVEYOR|DEMO Order of Sworn Surveyors|DEMO-GE-YDE-SEED|DEMO Yaounde
"

# Git Bash must not rewrite arguments for docker; curl, a Windows program, needs its paths rewritten.
psql_dev() { MSYS2_ARG_CONV_EXCL="*" docker compose exec -T -u postgres db psql -X -q -v ON_ERROR_STOP=1 -d snigf "$@"; }

[[ "$(psql_dev -tA <<'SQL'
SELECT count(*) FROM snigf.app_user u WHERE lower(u.email) IN ('admin1@demo.invalid', 'admin2@demo.invalid')
   AND EXISTS (SELECT 1 FROM snigf.office o WHERE o.code = 'YDE1');
SQL
)" == "2" ]] || { echo "run infra/scripts/dev-seed-demo.sh first: YDE1, admin1 and admin2 are needed" >&2; exit 1; }

touch "$STORE"; chmod 600 "$STORE"
stored_password() { awk -F'|' -v e="$1" '$1 == e { print $2 }' "$STORE"; }
exists() {
  [[ "$(psql_dev -tA -v email="$1" <<'SQL'
SELECT count(*) FROM snigf.app_user WHERE lower(email) = lower(:'email');
SQL
)" == "1" ]]
}

request() {  # email display password profession chamber registration jurisdiction -> HTTP status
  local csrf
  rm -f "$JAR"
  curl -sS "${TLS[@]}" -c "$JAR" -o /dev/null "$BASE/api/v1/auth/csrf"
  csrf="$(awk -v n="$CSRF_COOKIE" '$6 == n { print $7 }' "$JAR")"
  curl -sS "${TLS[@]}" -b "$JAR" -c "$JAR" -o "$JAR.answer" -w '%{http_code}' \
    -H 'Content-Type: application/json' -H "$CSRF_HEADER: $csrf" \
    -d "{\"email\":\"$1\",\"displayName\":\"$2\",\"password\":\"$3\",\"preferredLanguage\":\"fr\",\"profession\":\"$4\",\"chamber\":\"$5\",\"registrationNumber\":\"$6\",\"jurisdiction\":\"$7\"}" \
    "$BASE/api/v1/professional-requests"
}

echo "== requests"
while IFS='|' read -r email name profession chamber registration jurisdiction; do
  [[ -n "$email" ]] || continue
  password="$(stored_password "$email")"
  if exists "$email"; then
    [[ -n "$password" ]] || echo "   $email exists with a password not in $STORE: its workflow goes on, but it cannot sign in from this list" >&2
    continue
  fi
  password="${password:-Pw-$(openssl rand -base64 18 | tr '+/' '-_' | tr -d '=\r\n')}"
  status="$(request "$email" "$name" "$password" "$profession" "$chamber" "$registration" "$jurisdiction")"
  if [[ "$status" == "429" ]]; then
    # Requests are limited per address and hour; the limiter is in memory (development).
    echo "   request limit reached: restarting the application (development only)"
    docker compose restart app >/dev/null && docker compose up -d --wait >/dev/null
    status="$(request "$email" "$name" "$password" "$profession" "$chamber" "$registration" "$jurisdiction")"
  fi
  # The endpoint answers 201 whether or not the address was new: check that the account exists.
  [[ "$status" == "201" ]] && exists "$email" || { echo "the request for $email answered $status: $(cat "$JAR.answer" 2>/dev/null)" >&2; exit 1; }
  [[ -n "$(stored_password "$email")" ]] || echo "$email|$password|$profession|YDE1" >> "$STORE"
  echo "   requested $email"
done <<< "$CAST"

echo "== verification (admin1) and approval (admin2)"
psql_dev <<'SQL'
SET search_path = snigf, public;
DO $seed$
DECLARE
    verifier uuid := (SELECT id FROM app_user WHERE lower(email) = 'admin1@demo.invalid');
    approver uuid := (SELECT id FROM app_user WHERE lower(email) = 'admin2@demo.invalid');
    yde1     uuid := (SELECT id FROM office WHERE code = 'YDE1');
    who      record;
    request  uuid;
    keys     int;
BEGIN
    FOR who IN
        SELECT u.id, u.email, p.profession, p.status
          FROM app_user u JOIN professional_profile p ON p.user_id = u.id
         WHERE lower(u.email) IN ('notary.yde@demo.invalid', 'sworn-surveyor.yde@demo.invalid')
         ORDER BY u.email
    LOOP
        IF who.status = 'REQUESTED' THEN
            PERFORM set_config('snigf.actor_id', verifier::text, true);
            request := professional_verify(who.id, 'DEMO ' || CASE who.profession WHEN 'NOTARY' THEN 'Chamber of Notaries' ELSE 'Order of Sworn Surveyors' END,
                                           'DEMO-SEED-CHECK', current_date, 'Synthetic DEMO credential, development seed (dev-seed-professionals.sh)',
                                           ARRAY[yde1]);
            INSERT INTO audit_event (id, actor_type, actor_label, action, entity_type, entity_id, outcome, reason, correlation_id, details)
            VALUES (gen_random_uuid(), 'SERVICE', 'dev-seed-professionals', 'PROFESSIONAL_VERIFIED', 'PROFESSIONAL', who.id, 'SUCCESS',
                    'development seed', gen_random_uuid(),
                    jsonb_build_object('capacity', who.profession, 'request', request, 'offices', jsonb_build_array('YDE1'), 'asAdministrator', verifier));
            RAISE NOTICE '   % verified at YDE1 by admin1', who.email;
            who.status := 'VERIFIED';
        END IF;

        IF who.status = 'VERIFIED' AND who.profession = 'NOTARY' THEN
            request := (SELECT r.id FROM professional_request r WHERE r.user_id = who.id AND r.kind = 'ACTIVATION' AND r.status = 'PENDING');
            SELECT count(*) INTO keys FROM user_credentials c JOIN user_entities e ON e.id = c.user_entity_user_id WHERE e.app_user_id = who.id;
            IF request IS NULL THEN
                RAISE NOTICE '   % is verified with no activation waiting: left as it is', who.email;
            ELSIF keys < 2 THEN
                RAISE NOTICE '   % waits for two security keys: sign in as them, register two keys, then run this script again', who.email;
            ELSE
                PERFORM set_config('snigf.actor_id', approver::text, true);
                PERFORM professional_decide(request, true, NULL);
                INSERT INTO audit_event (id, actor_type, actor_label, action, entity_type, entity_id, outcome, reason, correlation_id, details)
                VALUES (gen_random_uuid(), 'SERVICE', 'dev-seed-professionals', 'PROFESSIONAL_APPROVED', 'PROFESSIONAL', who.id, 'SUCCESS',
                        'development seed', gen_random_uuid(),
                        jsonb_build_object('capacity', who.profession, 'request', request, 'kind', 'ACTIVATION', 'asAdministrator', approver));
                RAISE NOTICE '   % approved by admin2: active at YDE1', who.email;
            END IF;
        END IF;
    END LOOP;
END;
$seed$;
SQL

echo
echo "== professional accounts (passwords in $STORE; development only)"
psql_dev -P footer=off <<'SQL'
SELECT u.email AS "E-mail", p.profession AS "Profession", p.status AS "Status",
       coalesce((SELECT string_agg(o.code, ', ' ORDER BY o.code) FROM snigf.professional_office x JOIN snigf.office o ON o.id = x.office_id
                  WHERE x.user_id = u.id AND x.removed_at IS NULL),
                (SELECT 'proposed: ' || string_agg(o.code, ', ' ORDER BY o.code) FROM snigf.professional_request r
                   JOIN snigf.office o ON o.id = ANY (r.offices_add) WHERE r.user_id = u.id AND r.kind = 'ACTIVATION' AND r.status = 'PENDING'),
                '-') AS "Offices"
  FROM snigf.app_user u JOIN snigf.professional_profile p ON p.user_id = u.id
 WHERE lower(u.email) IN ('notary.yde@demo.invalid', 'sworn-surveyor.yde@demo.invalid')
 ORDER BY u.email;
SQL
echo "Both register two security keys at their first sign-in (docs/guides/demo-accounts.md)."
