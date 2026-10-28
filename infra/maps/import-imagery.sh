#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Imagery (any GDAL raster with a CRS, usually a GeoTIFF) -> raster PMTiles + manifest, staged
# in /work/stage/satellite (ADR-029). Runs in the maps-tools container, without network.
#
#   import-imagery.sh <input> <name> <attribution> <license> <source> <date> <synthetic> [maxzoom]
#
# Reprojected to Web Mercator, tiled in WebP with an alpha band (no black edges), overviews
# averaged down to zoom 8. The maximum zoom is GDAL's choice from the native resolution
# unless given.
set -euo pipefail

[[ $# -ge 7 ]] || { sed -n 2,12p "$0"; exit 2; }
input="$1" name="$2" attribution="$3" license="$4" source="$5" date="$6" synthetic="$7" maxzoom="${8:-}"
[[ -r "$input" ]] || { echo "cannot read $input" >&2; exit 1; }
gdalsrsinfo -o epsg "$input" >/dev/null 2>&1 || gdalinfo "$input" | grep -q 'Coordinate System is:' \
  || { echo "$input has no coordinate reference system" >&2; exit 1; }

tmp="/work/tmp/imagery"; stage="/work/stage/satellite"
rm -rf "$tmp" "$stage"; mkdir -p "$tmp" "$stage"

echo "== reprojecting to EPSG:3857"
gdalwarp -q -t_srs EPSG:3857 -r bilinear -dstalpha -multi -wo NUM_THREADS=ALL_CPUS \
  -co TILED=YES -co COMPRESS=DEFLATE "$input" "$tmp/mercator.tif"

echo "== tiling"
zoom_options=()
[[ -n "$maxzoom" ]] && zoom_options=(-co "ZOOM_LEVEL=$maxzoom")
gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=85 -co RESAMPLING=BILINEAR \
  "${zoom_options[@]}" "$tmp/mercator.tif" "$tmp/tiles.mbtiles"
levels=(); for f in 2 4 8 16 32 64 128 256; do levels+=("$f"); done
gdaladdo -q -r average "$tmp/tiles.mbtiles" "${levels[@]}" 2>/dev/null || true

echo "== packaging"
pmtiles convert "$tmp/tiles.mbtiles" "$stage/tiles.pmtiles" >/dev/null
python3 /opt/snigf-maps/manifest.py "$stage/tiles.pmtiles" satellite "$name" "$attribution" "$license" \
  "$source" "$date" "$synthetic"
rm -rf "$tmp"
echo "== staged in $stage"
