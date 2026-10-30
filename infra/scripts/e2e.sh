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
# 3. installs synthetic satellite imagery unless imagery is installed (maps-synthetic-imagery.sh
#    --if-absent: real imagery is never replaced);
# 4. runs the journeys (project "flows"): sign-in with virtual security keys, four-eyes role
#    assignment and custom role, special grant, audit verification, recovery, then parcels and
#    their three map views.
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
# On Linux the bind mount keeps host ownership: the container's user (pwuser, 1001) must be
# able to write reports there. Only reports and the 0600 state file are written.
chmod a+rwx e2e/out 2>/dev/null || true
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
# A second cadastral officer at the office: resolves the alert the officer's parcels raise (E-11).
promote surveyor CADASTRAL_OFFICER
# The intake officer of the office: reviews completeness (E-14).
promote intake INTAKE_OFFICER
# The reviewer and the decision authority of the office: recommend, then decide (E-15).
promote reviewer REVIEWER
promote decider DECISION_AUTHORITY
# The supervisor is also the document officer (ADR-039): a second role, because one address may
# register only SNIGF_RATE_REGISTER_PER_HOUR accounts an hour, and this run registers ten.
promote supervisor DOCUMENT_OFFICER

bash infra/scripts/maps-synthetic-imagery.sh --if-absent

# E2E_TOUR=1 also captures the design tour (e2e/out/tour/, tests/tour.ts).
docker compose --profile e2e run --rm -e E2E_RUN_ID="$RUN" -e E2E_TOUR="${E2E_TOUR:-0}" e2e --project=flows
echo "== e2e run $RUN passed; report: e2e/out/report/index.html"
