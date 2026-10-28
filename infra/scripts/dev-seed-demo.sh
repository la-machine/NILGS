#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Demonstration cast for development (docs/guides/demo-accounts.md): two demo offices and
# one account per baseline role, so every use case can be tried by hand.
#
#   bash infra/scripts/dev-seed-demo.sh      (the stack must be running)
#
# Each account is registered through the real API (passwords are Argon2id with a pepper held
# by OpenBao: they cannot be written in SQL), then made staff at its office through the
# superuser bootstrap path, as dev-make-staff.sh does. Passwords are random and kept in
# infra/secrets/demo_accounts (git-ignored, 0600). Running it again creates only what is
# missing and never changes a password. Refused unless SNIGF_PROFILE=dev; never a migration,
# so these accounts can never exist outside development.
# Staff still register two security keys at their first sign-in, in the browser.
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
# The certificate is verified against the development CA. That CA publishes no revocation
# list, which Windows' curl (Schannel) would otherwise insist on checking.
TLS=(--cacert "$CA" --ssl-no-revoke --proto =https --tlsv1.3)
trap 'rm -f "$JAR" "$JAR.answer"' EXIT

# e-mail | display name | role | office (none for the applicant)
CAST="
applicant@demo.invalid|Demo Applicant|APPLICANT|
intake.yde@demo.invalid|Demo Intake Yaounde|INTAKE_OFFICER|YDE1
cadastre.yde@demo.invalid|Demo Cadastre Yaounde|CADASTRAL_OFFICER|YDE1
cadastre.dla@demo.invalid|Demo Cadastre Douala|CADASTRAL_OFFICER|DLA1
surveyor.yde@demo.invalid|Demo Surveyor Yaounde|CADASTRAL_OFFICER|YDE1
reviewer.yde@demo.invalid|Demo Reviewer Yaounde|REVIEWER|YDE1
decision.yde@demo.invalid|Demo Decision Yaounde|DECISION_AUTHORITY|YDE1
supervisor.yde@demo.invalid|Demo Supervisor Yaounde|SUPERVISOR|YDE1
auditor@demo.invalid|Demo Auditor|AUDITOR|YDE1
finance.yde@demo.invalid|Demo Finance Yaounde|FINANCE_OFFICER|YDE1
admin1@demo.invalid|Demo Admin One|SYSTEM_ADMIN|YDE1
admin2@demo.invalid|Demo Admin Two|SYSTEM_ADMIN|YDE1
"

# Git Bash must not rewrite arguments for docker; curl, a Windows program, needs its paths rewritten.
psql_dev() { MSYS2_ARG_CONV_EXCL="*" docker compose exec -T -u postgres db psql -X -q -v ON_ERROR_STOP=1 -d snigf "$@"; }

echo "== demo offices"
psql_dev <<'SQL'
SET search_path = snigf, public;
INSERT INTO office (id, code, name_fr, name_en) VALUES
  (gen_random_uuid(), 'YDE1', 'Bureau de démonstration Yaoundé', 'Demo office Yaoundé'),
  (gen_random_uuid(), 'DLA1', 'Bureau de démonstration Douala', 'Demo office Douala')
ON CONFLICT (code) DO NOTHING;
SQL

touch "$STORE"; chmod 600 "$STORE"
stored_password() { awk -F'|' -v e="$1" '$1 == e { print $2 }' "$STORE"; }
exists() {
  [[ "$(psql_dev -tA -v email="$1" <<'SQL'
SELECT count(*) FROM snigf.app_user WHERE lower(email) = lower(:'email');
SQL
)" == "1" ]]
}

register() {  # email display password -> HTTP status
  local csrf status
  rm -f "$JAR"
  curl -sS "${TLS[@]}" -c "$JAR" -o /dev/null "$BASE/api/v1/auth/csrf"
  csrf="$(awk -v n="$CSRF_COOKIE" '$6 == n { print $7 }' "$JAR")"
  status="$(curl -sS "${TLS[@]}" -b "$JAR" -c "$JAR" -o "$JAR.answer" -w '%{http_code}' \
    -H 'Content-Type: application/json' -H "$CSRF_HEADER: $csrf" \
    -d "{\"email\":\"$1\",\"displayName\":\"$2\",\"password\":\"$3\",\"preferredLanguage\":\"fr\"}" \
    "$BASE/api/v1/auth/register")"
  echo "$status"
}

echo "== accounts"
while IFS='|' read -r email name role office; do
  [[ -n "$email" ]] || continue
  password="$(stored_password "$email")"
  if exists "$email"; then
    if [[ -z "$password" ]]; then
      echo "   $email exists with a password not in $STORE: left unchanged, not usable from this list" >&2
      continue
    fi
  else
    password="${password:-Pw-$(openssl rand -base64 18 | tr '+/' '-_' | tr -d '=\r\n')}"
    status="$(register "$email" "$name" "$password")"
    if [[ "$status" == "429" ]]; then
      # Registrations are limited per address and hour; the limiter is in memory (development).
      echo "   registration limit reached: restarting the application (development only)"
      docker compose restart app >/dev/null && docker compose up -d --wait >/dev/null
      status="$(register "$email" "$name" "$password")"
    fi
    [[ "$status" == "201" ]] || { echo "registration of $email answered $status: $(cat "$JAR.answer" 2>/dev/null)" >&2; exit 1; }
    [[ -n "$(stored_password "$email")" ]] || echo "$email|$password|$role|${office:--}" >> "$STORE"
    echo "   registered $email"
  fi
  if [[ -n "$office" ]]; then
    psql_dev -v email="$email" -v role="$role" -v office="$office" <<'SQL'
SET search_path = snigf, public;
SELECT id AS user_id FROM app_user WHERE lower(email) = lower(:'email') \gset
UPDATE app_user SET account_type = 'STAFF' WHERE id = :'user_id' AND account_type <> 'STAFF';
-- Always one row, so \gset never fails on a second run: NULL (variable unset) when the role was there.
WITH added AS (
    INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
    SELECT gen_random_uuid(), :'user_id', :'role', o.id, 'development seed (dev-seed-demo.sh)'
      FROM office o
     WHERE o.code = :'office'
       AND NOT EXISTS (SELECT 1 FROM role_assignment a
                        WHERE a.user_id = :'user_id' AND a.role = :'role' AND a.office_id = o.id
                          AND a.revoked_at IS NULL)
    RETURNING id)
SELECT (SELECT id FROM added) AS assignment_id \gset
\if :{?assignment_id}
INSERT INTO audit_event (id, actor_type, actor_label, action, entity_type, entity_id, outcome, reason, correlation_id, details)
VALUES (gen_random_uuid(), 'SERVICE', 'dev-seed-demo', 'ROLE_GRANTED', 'USER', :'user_id', 'SUCCESS',
        'development seed', gen_random_uuid(),
        jsonb_build_object('role', :'role', 'office', :'office', 'accountType', 'STAFF'));
\echo '   ' :'role' 'at' :'office' 'for' :'email'
\endif
SQL
  fi
done <<< "$CAST"

echo
echo "== demo accounts (also in $STORE; development only)"
printf '%-30s %-34s %-20s %s\n' "E-mail" "Password" "Role" "Office"
awk -F'|' '{ printf "%-30s %-34s %-20s %s\n", $1, $2, $3, $4 }' "$STORE"
echo
echo "Staff register two security keys at their first sign-in (docs/guides/demo-accounts.md)."
