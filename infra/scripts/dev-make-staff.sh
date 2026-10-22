#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
#
# DEVELOPMENT ONLY: turns an account registered through the interface into a staff
# account with one role at a demo office, so the second-factor screens can be tried
# before role administration exists (security step 4c). Refuses to run unless
# SNIGF_PROFILE=dev. The change is made by the PostgreSQL superuser through the local
# socket (the application role cannot change an account type) and is recorded in the
# audit trail as ROLE_GRANTED by the service "dev-make-staff".
#
#   bash infra/scripts/dev-make-staff.sh <email> [ROLE]     (ROLE defaults to CADASTRAL_OFFICER)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EMAIL="${1:?usage: dev-make-staff.sh <email> [ROLE]}"
ROLE="${2:-CADASTRAL_OFFICER}"

profile="$(sed -n 's/^SNIGF_PROFILE=//p' "$ROOT/.env" | tr -d '\r')"
if [[ "$profile" != "dev" ]]; then
  echo "refusing: SNIGF_PROFILE is '$profile', this tool runs only with dev" >&2
  exit 1
fi
[[ "$ROLE" =~ ^[A-Z_]{3,40}$ ]] || { echo "invalid role $ROLE" >&2; exit 2; }

# Git Bash would rewrite arguments that look like paths.
export MSYS2_ARG_CONV_EXCL="*"
cd "$ROOT"
docker compose exec -T -u postgres db psql -X -q -v ON_ERROR_STOP=1 -d snigf \
  -v email="$EMAIL" -v role="$ROLE" <<'SQL'
SET search_path = snigf, public;
INSERT INTO office (id, code, name_fr, name_en)
VALUES ('00000000-0000-7000-8000-00000000d001', 'DEMO1', 'Bureau de démonstration 1', 'Demo office 1')
ON CONFLICT (code) DO NOTHING;

SELECT count(*) = 1 AS found, max(id::text) AS user_id FROM app_user WHERE lower(email) = lower(:'email') \gset
\if :found
\else
  \echo 'no account with that address: register it in the interface first'
  \quit
\endif

UPDATE app_user SET account_type = 'STAFF' WHERE id = :'user_id';
INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason)
SELECT gen_random_uuid(), :'user_id', :'role', o.id, 'development seed (dev-make-staff.sh)'
  FROM office o WHERE o.code = 'DEMO1'
ON CONFLICT DO NOTHING;
INSERT INTO audit_event (id, actor_type, actor_label, action, entity_type, entity_id, outcome, reason, correlation_id, details)
VALUES (gen_random_uuid(), 'SERVICE', 'dev-make-staff', 'ROLE_GRANTED', 'USER', :'user_id', 'SUCCESS',
        'development seed', gen_random_uuid(), jsonb_build_object('role', :'role', 'office', 'DEMO1', 'accountType', 'STAFF'));
\echo 'staff account ready: log in, then register two authenticators'
SQL
