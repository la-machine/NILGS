#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Imports licensed imagery as the satellite background (ADR-029, docs/guides/map-data.md).
# Place the file (a GeoTIFF or any GDAL raster with a CRS) in SNIGF_MAPS_IMPORT_DIRECTORY,
# then:
#
#   bash infra/scripts/maps-import-imagery.sh <file> --name <text> --attribution <text> \
#        --license <text> --source <text> --date <YYYY-MM-DD> [--maxzoom <n>]
#
# The attribution and licence are shown to every user who displays the view: they must be
# the ones the licence requires. Runs without network; replaces the installed imagery.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# Git Bash on Windows must not rewrite container paths such as /sources.
export MSYS2_ARG_CONV_EXCL="*"
usage() { sed -n 5,12p "$0" >&2; exit 2; }
[[ $# -ge 1 ]] || usage
file="$1"; shift
name="" attribution="" license="" source="" date="" maxzoom=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) name="$2" ;;
    --attribution) attribution="$2" ;;
    --license) license="$2" ;;
    --source) source="$2" ;;
    --date) date="$2" ;;
    --maxzoom) maxzoom="$2" ;;
    *) usage ;;
  esac
  shift 2
done
[[ -n "$name" && -n "$attribution" && -n "$license" && -n "$source" && -n "$date" ]] || usage
[[ "$file" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "give the file name only, as placed in the import folder" >&2; exit 1; }
[[ -z "$maxzoom" || "$maxzoom" =~ ^(1[0-9]|2[0-2])$ ]] || { echo "--maxzoom must be 10..22" >&2; exit 1; }

maps() { docker compose --profile maps "$@"; }
maps build maps-tools
maps run --rm maps-install prepare
maps run --rm --entrypoint bash maps-tools /opt/snigf-maps/import-imagery.sh "/import/$file" \
  "$name" "$attribution" "$license" "$source" "$date" false $maxzoom
maps run --rm maps-install satellite
