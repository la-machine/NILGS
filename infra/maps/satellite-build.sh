#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Builds the satellite background from what sentinel-fetch.py and aerial-fetch.py fetched
# (ADR-029), without network, into /work/stage/satellite:
#   tiles.pmtiles            the national Sentinel-2 mosaic, 10 m, zoom 14 and below
#   detail-<id>.pmtiles      each aerial image, at its own resolution (zoom 20 at most)
#   manifest.json            sources, licences, attributions, dates, extents
#
#   satellite-build.sh <aerial max zoom>
set -euo pipefail

aerial_max="${1:?aerial maximum zoom}"
tmp="/work/tmp/satellite"; stage="/work/stage/satellite"
rm -rf "$tmp" "$stage"; mkdir -p "$tmp" "$stage"
# Web Mercator resolution of zoom 14, so the mosaic falls exactly on the tile grid.
z14=9.554628535647032

mapfile -t scenes < <(find /sources/sentinel -mindepth 2 -name '*.tif' ! -name '*.part' | sort)
[[ ${#scenes[@]} -gt 0 ]] || { echo "no Sentinel-2 contribution fetched: run the fetch step first" >&2; exit 1; }
echo "== national mosaic from ${#scenes[@]} cloud-masked contributions"
gdalwarp -q -t_srs EPSG:3857 -tr "$z14" "$z14" -tap -r bilinear -srcalpha -dstalpha \
  -multi -wo NUM_THREADS=ALL_CPUS -wm 1024 \
  -co TILED=YES -co COMPRESS=WEBP -co WEBP_LEVEL=90 -co BIGTIFF=YES \
  "${scenes[@]}" "$tmp/mosaic.tif"
echo "== tiles (zoom 14), then overviews down to zoom 6"
gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=82 -co ZOOM_LEVEL=14 -co RESAMPLING=BILINEAR \
  "$tmp/mosaic.tif" "$tmp/base.mbtiles"
rm -f "$tmp/mosaic.tif"
gdaladdo -q -r average "$tmp/base.mbtiles" 2 4 8 16 32 64 128 256
pmtiles convert "$tmp/base.mbtiles" "$stage/tiles.pmtiles" >/dev/null
rm -f "$tmp/base.mbtiles"

details="[]"
for image in /sources/aerial/*.tif; do
  [[ -e "$image" ]] || break
  id="$(basename "$image" .tif)"
  meta="/sources/aerial/$id.json"
  echo "== aerial detail $id"
  # The image's own resolution decides its highest zoom, capped: sharper than zoom 20 adds
  # size, not detail. The image is warped straight to that zoom's resolution.
  zoom="$(python3 - "$image" "$aerial_max" <<'EOF'
import math, sys
from osgeo import gdal, osr
ds = gdal.Open(sys.argv[1])
res = abs(ds.GetGeoTransform()[1])
srs = osr.SpatialReference(wkt=ds.GetProjection())
if srs.IsGeographic():
    res *= 111320.0
print(min(int(sys.argv[2]), max(15, round(math.log2(156543.03392804097 / res)))))
EOF
)"
  resolution="$(python3 -c "print(156543.03392804097 / 2 ** $zoom)")"
  gdalwarp -q -t_srs EPSG:3857 -tr "$resolution" "$resolution" -tap -r bilinear -dstalpha -multi \
    -wo NUM_THREADS=ALL_CPUS -wm 1024 -co TILED=YES -co COMPRESS=WEBP -co WEBP_LEVEL=90 -co BIGTIFF=IF_SAFER \
    "$image" "$tmp/$id.tif"
  gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=82 -co ZOOM_LEVEL="$zoom" -co RESAMPLING=BILINEAR \
    "$tmp/$id.tif" "$tmp/$id.mbtiles"
  rm -f "$tmp/$id.tif"
  levels=(); for ((f = 2; f <= 1 << (zoom - 14); f *= 2)); do levels+=("$f"); done
  gdaladdo -q -r average "$tmp/$id.mbtiles" "${levels[@]}"
  pmtiles convert "$tmp/$id.mbtiles" "$stage/detail-$id.pmtiles" >/dev/null
  rm -f "$tmp/$id.mbtiles"
  details="$(python3 - "$details" "$meta" "$id" <<'EOF'
import json, sys
details, meta, image = json.loads(sys.argv[1]), json.load(open(sys.argv[2], encoding='utf-8')), sys.argv[3]
provider = meta.get('provider') or 'OpenAerialMap contributors'
details.append({'file': f'detail-{image}.pmtiles', 'name': meta['title'][:200],
                'attribution': f'Imagery (c) {provider}, {meta["licence"]}, via OpenAerialMap'[:300],
                'license': meta['licence'], 'source': f'OpenAerialMap {image}, {meta.get("platform") or "aerial"}, '
                f'{(meta.get("gsd") or 0) * 100:.0f} cm'[:300], 'date': meta['date']})
print(json.dumps(details))
EOF
)"
done
echo "$details" > "$tmp/details.json"

echo "== manifest"
read -r first last scenes_used < <(python3 - <<'EOF'
import json, pathlib
dates = [s['date'] for f in pathlib.Path('/sources/sentinel').glob('*/selection.json') for s in json.loads(f.read_text())['scenes']]
print(min(dates), max(dates), len(dates))
EOF
)
python3 /opt/snigf-maps/manifest.py "$stage/tiles.pmtiles" satellite \
  "Sentinel-2 mosaic, ${SNIGF_COUNTRY_NAME:-national}" \
  "Contains modified Copernicus Sentinel data ${first:0:4}-${last:0:4}" \
  "Copernicus Sentinel data: free, full and open (EU Regulation 1159/2013)" \
  "Sentinel-2 L2A, ${scenes_used} scenes ${first} to ${last}, cloud-masked mosaic, 10 m" \
  "$last" false "$tmp/details.json"
rm -rf "$tmp"
echo "== staged in $stage"
