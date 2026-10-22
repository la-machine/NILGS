#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# End-to-end tests against the running development stack (docs/guides/end-to-end-testing.md).
#
#   bash infra/scripts/e2e.sh
#
# 1. registers this run's six accounts through the interface (Playwright project "accounts");
# 2. makes five of them staff with dev-make-staff.sh (the superuser bootstrap path, dev only):
#    two SYSTEM_ADMIN, one REVIEWER, one AUDITOR, one SUPERVISOR;
# 3. runs the journeys (project "flows"): sign-in with virtual security keys, four-eyes role
#    assignment and custom role, special grant, audit verification.
# Reports and traces land in e2e/out/ (git-ignored). Each run uses new accounts, so the
# registration limit (SNIGF_RATE_REGISTER_PER_HOUR, 10 in development) allows one run per
# hour from this machine.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

profile="$(sed -n 's/^SNIGF_PROFILE=//p' .env | tr -d '\r')"
if [[ "$profile" != "dev" ]]; then
  echo "refusing: SNIGF_PROFILE is '$profile', end-to-end tests run only against dev" >&2
  exit 1
fi
if ! docker compose ps --status running --services | grep -qx web; then
  echo "the stack is not running: docker compose up -d --wait" >&2
  exit 1
fi

RUN="$(date -u +%Y%m%d%H%M%S)"
mkdir -p e2e/out
# The run's random password is a test credential: removed however the run ends.
trap 'rm -f "e2e/out/state-$RUN.json"' EXIT
echo "== e2e run $RUN"

docker compose --profile e2e build e2e
docker compose --profile e2e run --rm -e E2E_RUN_ID="$RUN" e2e --project=branding
docker compose --profile e2e run --rm -e E2E_RUN_ID="$RUN" e2e --project=accounts

promote() {
  bash infra/scripts/dev-make-staff.sh "e2e-$RUN-$1@demo.invalid" "$2"
}
promote admin1 SYSTEM_ADMIN
promote admin2 SYSTEM_ADMIN
promote officer REVIEWER
promote auditor AUDITOR
promote supervisor SUPERVISOR

docker compose --profile e2e run --rm -e E2E_RUN_ID="$RUN" e2e --project=flows
echo "== e2e run $RUN passed; report: e2e/out/report/index.html"
