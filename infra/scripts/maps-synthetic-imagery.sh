#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Installs synthetic satellite imagery for tests (ADR-029): an obviously artificial pattern
# over SNIGF_MAPS_SYNTHETIC_BOUNDS, put through the same import pipeline as real imagery and
# marked synthetic, which the interface shows. Runs without network.
#
#   bash infra/scripts/maps-synthetic-imagery.sh               install (replaces any imagery)
#   bash infra/scripts/maps-synthetic-imagery.sh --if-absent   only when no imagery is installed
#
# e2e.sh uses --if-absent: real imagery is never replaced by test data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# Git Bash on Windows must not rewrite container paths such as /sources.
export MSYS2_ARG_CONV_EXCL="*"
setting() { sed -n "s/^$1=//p" .env | tr -d '\r'; }
BOUNDS="$(setting SNIGF_MAPS_SYNTHETIC_BOUNDS)"
RESOLUTION="$(setting SNIGF_MAPS_SYNTHETIC_RESOLUTION)"
number='-?[0-9]+(\.[0-9]+)?'
[[ "$BOUNDS" =~ ^$number,$number,$number,$number$ ]] || { echo "SNIGF_MAPS_SYNTHETIC_BOUNDS: west,south,east,north" >&2; exit 1; }
[[ "$RESOLUTION" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "SNIGF_MAPS_SYNTHETIC_RESOLUTION: metres per pixel" >&2; exit 1; }
IFS=, read -r WEST SOUTH EAST NORTH <<< "$BOUNDS"
if_absent="${1:-}"
[[ -z "$if_absent" || "$if_absent" == "--if-absent" ]] || { sed -n 8,11p "$0" >&2; exit 2; }

maps() { docker compose --profile maps "$@"; }
maps build maps-tools
maps run --rm maps-install prepare
if [[ -n "$if_absent" ]] && maps run --rm maps-install status | grep -q '^satellite: {'; then
  echo "satellite imagery already installed; left unchanged"
  exit 0
fi
maps run --rm --entrypoint sh maps-tools -c "
  set -e
  mkdir -p /work/tmp
  python3 /opt/snigf-maps/synthetic-imagery.py /work/tmp/synthetic.tif $WEST $SOUTH $EAST $NORTH $RESOLUTION
  bash /opt/snigf-maps/import-imagery.sh /work/tmp/synthetic.tif \
    'Synthetic test imagery' 'Synthetic test imagery, SNIGF (not a photograph)' \
    'Generated for tests; no third-party rights' 'infra/maps/synthetic-imagery.py' \
    \$(date -u +%F) true
  rm -f /work/tmp/synthetic.tif"
maps run --rm maps-install satellite $if_absent
