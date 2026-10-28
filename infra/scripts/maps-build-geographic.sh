#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Builds the geographic background (ADR-029, docs/guides/map-data.md): OpenStreetMap vector
# tiles for SNIGF_MAPS_OSM_AREA in the OpenMapTiles schema, the Noto Sans label glyphs, the
# manifest, then publishes them into the map-data volume.
#
#   bash infra/scripts/maps-build-geographic.sh            fetch (internet), build, install
#   bash infra/scripts/maps-build-geographic.sh --offline  build from sources already fetched
#
# Fetching downloads about 1.3 GB (the extract, OSM water polygons, Natural Earth, glyphs)
# into the map-sources volume; building and installing run without network. The running
# application picks the new archive up at the next request: no restart.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# Git Bash on Windows must not rewrite container paths such as /sources.
export MSYS2_ARG_CONV_EXCL="*"
setting() { sed -n "s/^$1=//p" .env | tr -d '\r'; }
AREA="$(setting SNIGF_MAPS_OSM_AREA)"
MAXZOOM="$(setting SNIGF_MAPS_GEOGRAPHIC_MAXZOOM)"
FONTS_SHA256="$(setting SNIGF_MAPS_FONTS_SHA256)"
[[ "$AREA" =~ ^[a-z][a-z0-9-]{1,60}$ ]] || { echo "SNIGF_MAPS_OSM_AREA must be a Geofabrik area name" >&2; exit 1; }
[[ "$MAXZOOM" =~ ^1[2-5]$ ]] || { echo "SNIGF_MAPS_GEOGRAPHIC_MAXZOOM must be 12..15" >&2; exit 1; }

maps() { docker compose --profile maps "$@"; }
planetiler_args=(--area="$AREA" --download_dir=/sources/planetiler --languages=fr,en)

maps build maps-tools
maps run --rm maps-install prepare

if [[ "${1:-}" != "--offline" ]]; then
  echo "== fetching sources (internet)"
  maps run --rm planetiler-fetch "${planetiler_args[@]}" --download --only_download=true --tmpdir=/sources/tmp
  maps run --rm --entrypoint python3 maps-fetch - <<'EOF'
import hashlib, os, urllib.request
url, expected = os.environ["SNIGF_MAPS_FONTS_URL"], os.environ["SNIGF_MAPS_FONTS_SHA256"]
os.makedirs("/sources/fonts", exist_ok=True)
target, part = "/sources/fonts/noto-sans.zip", "/sources/fonts/noto-sans.zip.part"
if os.path.exists(target) and hashlib.sha256(open(target, "rb").read()).hexdigest() == expected:
    print("glyph archive already fetched")
else:
    if not url.startswith("https://"):
        raise SystemExit("SNIGF_MAPS_FONTS_URL must be https")
    with urllib.request.urlopen(url, timeout=60) as response, open(part, "wb") as out:
        out.write(response.read(200_000_000))
    actual = hashlib.sha256(open(part, "rb").read()).hexdigest()
    if actual != expected:
        os.remove(part)
        raise SystemExit(f"glyph archive SHA-256 {actual} does not match the pinned {expected}")
    os.replace(part, target)
    print("glyph archive fetched and verified")
EOF
fi

echo "== building vector tiles (no network)"
maps run --rm --entrypoint sh maps-tools -c 'rm -rf /work/stage/geographic /work/tmp/planetiler; mkdir -p /work/stage/geographic /work/tmp'
maps run --rm planetiler "${planetiler_args[@]}" --maxzoom="$MAXZOOM" --tmpdir=/work/tmp/planetiler \
  --output=/work/stage/geographic/tiles.pmtiles --force
maps run --rm --entrypoint sh maps-tools -c 'rm -rf /work/tmp/planetiler'

echo "== glyphs and manifest"
maps run --rm --entrypoint bash maps-tools /opt/snigf-maps/build-glyphs.sh "$FONTS_SHA256"
maps run --rm --entrypoint sh maps-tools -c '
  set -e
  archive=/work/stage/geographic/tiles.pmtiles
  stamp=$(pmtiles show --metadata "$archive" | python3 -c "import json,sys; print(json.load(sys.stdin).get(\"planetiler:osm:osmosisreplicationtime\", \"\")[:10])")
  [ -n "$stamp" ] || stamp=$(date -u +%F)
  python3 /opt/snigf-maps/manifest.py "$archive" geographic \
    "OpenStreetMap, '"$AREA"'" \
    "© OpenStreetMap contributors · © OpenMapTiles" \
    "ODbL 1.0 (OpenStreetMap data); CC-BY 4.0 (OpenMapTiles schema); SIL OFL 1.1 (Noto Sans)" \
    "Geofabrik extract '"$AREA"', OSM water polygons, Natural Earth; built with Planetiler 0.10.2" \
    "$stamp" false'

echo "== installing"
maps run --rm maps-install geographic
