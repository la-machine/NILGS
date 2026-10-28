#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Builds the real satellite background (ADR-029, docs/guides/map-data.md): a national
# Copernicus Sentinel-2 mosaic at 10 m, cloud-masked, with sharper aerial images drawn over it
# where they exist (OpenAerialMap, licences checked), then installs it into map-data.
#
#   bash infra/scripts/maps-build-satellite.sh               fetch (internet), build, install
#   bash infra/scripts/maps-build-satellite.sh --fetch-only  fetch only (resumable; run it again to continue)
#   bash infra/scripts/maps-build-satellite.sh --offline     build and install from what was fetched
#
# Fetching a whole country is tens of GB: it can be stopped at any time and started again,
# finished tiles are kept, and the listed towns come first. A build made before the fetch is
# complete covers the tiles fetched so far. Building and installing run without network; the
# application picks the new background up at the next request.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# Git Bash on Windows must not rewrite container paths such as /sources.
export MSYS2_ARG_CONV_EXCL="*"
setting() { sed -n "s/^$1=//p" .env | tr -d '\r'; }
mode="${1:-}"
[[ -z "$mode" || "$mode" == "--fetch-only" || "$mode" == "--offline" ]] || { sed -n 8,10p "$0" >&2; exit 2; }
AERIAL_MAX="$(setting SNIGF_MAPS_AERIAL_MAX_ZOOM)"
[[ "$AERIAL_MAX" =~ ^(1[5-9]|2[0-2])$ ]] || { echo "SNIGF_MAPS_AERIAL_MAX_ZOOM must be 15..22" >&2; exit 1; }

maps() { docker compose --profile maps "$@"; }
maps build maps-tools
maps run --rm maps-install prepare

if [[ "$mode" != "--offline" ]]; then
  echo "== fetching Sentinel-2 (internet; resumable)"
  maps run --rm --entrypoint python3 maps-fetch /opt/snigf-maps/sentinel-fetch.py
  echo "== fetching aerial images (internet; resumable)"
  maps run --rm --entrypoint python3 maps-fetch /opt/snigf-maps/aerial-fetch.py
fi
[[ "$mode" == "--fetch-only" ]] && exit 0

echo "== building (no network)"
maps run --rm --entrypoint bash maps-tools /opt/snigf-maps/satellite-build.sh "$AERIAL_MAX"
echo "== installing"
maps run --rm maps-install satellite
