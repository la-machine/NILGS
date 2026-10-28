# Map data for the geographic and satellite views

*Copyright (c) 2026 FLYT. Author: Pythagore.*

The parcel screens offer three views of the same authoritative geometry
([ADR-029](../adr/029-map-views-and-sovereign-map-data.md)). The technical view needs no
data. The geographic and satellite views draw over tile archives that we build or import
and host ourselves. This guide shows how to produce them, install them and check them.

## How it fits together

```text
sources (internet, once)       build / import (no network)       install (no network)
planetiler-fetch, maps-fetch -> planetiler, maps-tools       ->   maps-install -> map-data (volume)
        map-sources volume            map-work volume                   read-only in app at /maps
```

- Each view has a directory in `map-data` holding `tiles.pmtiles` and a `manifest.json`
  (format `snigf-map-manifest/1`: name, file, tile type, attribution, licence, source,
  date, bounds, zoom range, synthetic flag). The tools read the bounds and zoom range from
  the archive's own header.
- The application checks the manifest again (`MapDataset.problems`, `FileMapDataStore`).
  An absent, invalid or incomplete manifest makes the view unavailable, and the interface
  shows the reason. Parcels and the technical view are never affected.
- The app serves archives at `/api/v1/map/{kind}/tiles.pmtiles` with HTTP range requests,
  and glyphs at `/api/v1/map/geographic/fonts/...`. Every map endpoint needs a full session
  and `parcel.read` at some office. The browser loads nothing from any other origin.
- New data is picked up at the next request: no restart.

## The jobs (Compose profile `maps`)

| Service | Network | Writes | Does |
| --- | --- | --- | --- |
| `planetiler-fetch` | `maps-egress` | `map-sources` | downloads the OSM extract (`SNIGF_MAPS_OSM_AREA`), water polygons, Natural Earth |
| `maps-fetch` | `maps-egress` | `map-sources` | downloads the Noto Sans glyph archive, refused unless `SNIGF_MAPS_FONTS_SHA256` matches |
| `planetiler` | none | `map-work` | builds vector tiles in the OpenMapTiles schema |
| `maps-tools` | none | `map-work` | imports imagery (GDAL → WebP tiles → PMTiles), generates synthetic imagery, extracts glyphs, writes manifests |
| `maps-install` | none | `map-data` | `prepare` (gives the builders their volumes), `<kind>` (publishes by copy then rename, files root-owned and read-only), `<kind> --if-absent`, `status` |

All jobs are read-only containers with every capability dropped and
`no-new-privileges`. They run as uid 10003, except `maps-install`, which runs as root with
only `CHOWN`. The volumes are mounted with `nocopy`: otherwise Docker would reset the
owner of an empty volume to the image's on every mount.

## Geographic view (OpenStreetMap)

```bash
bash infra/scripts/maps-build-geographic.sh            # fetch (about 1.3 GB), build, install
bash infra/scripts/maps-build-geographic.sh --offline  # rebuild from sources already fetched
```

The manifest date is the OSM replication time of the extract. For Cameroon the build reads
about 41 million nodes and 4.8 million ways and takes a few minutes; the archive covers zoom 0
to 14, and the map enlarges it beyond (vector tiles stay sharp).

What the view draws (`features/maps/style.ts`, our own style): land cover and use, parks,
water and rivers, buildings from zoom 13, roads by class with outlines, street names from
zoom 13, quarter and neighbourhood names, town and city names, landmarks by name from zoom 15
(schools, health, worship, public offices, markets, shops), house numbers from zoom 17.
Names are shown in French where OpenStreetMap has them, otherwise as mapped locally. The build needs about 4 GB
of memory (`SNIGF_MAPS_MEMORY`, `SNIGF_MAPS_JAVA_HEAP`). It stops if
download.geofabrik.de or osmdata.openstreetmap.de cannot be reached; the view then stays
"not installed".

Licences to respect: ODbL 1.0 for the data, CC-BY 4.0 for the OpenMapTiles schema, and
SIL OFL 1.1 for Noto Sans. The attribution "© OpenStreetMap contributors · © OpenMapTiles"
is written into the manifest and shown on the map.

## Satellite view: the national imagery

Step-by-step operator instructions: [satellite imagery guide](satellite-imagery.md).

```bash
bash infra/scripts/maps-build-satellite.sh               # fetch (internet, resumable), build, install
bash infra/scripts/maps-build-satellite.sh --fetch-only  # fetch only; run again to continue
bash infra/scripts/maps-build-satellite.sh --offline     # build and install from what was fetched
```

What it builds, all hosted by us:

| Layer | Source | Resolution | Licence (shown on the map) |
| --- | --- | --- | --- |
| National base (`tiles.pmtiles`) | Copernicus Sentinel-2 L2A, true colour, from the European mirror of the archive (Microsoft Planetary Computer; anonymous read-only tokens) | 10 m (zoom 14), enlarged beyond | free, full and open; "Contains modified Copernicus Sentinel data" |
| Details (`detail-<id>.pmtiles`) | aerial and drone surveys published on OpenAerialMap (`SNIGF_MAPS_AERIAL_IMAGES`) | each at its own resolution, zoom 20 at most (about 15 cm) | as published; only `SNIGF_MAPS_AERIAL_LICENCES` are accepted (CC-BY 4.0 by default; non-commercial refused) |

How the national base is made (`infra/maps/sentinel-fetch.py`): for each Sentinel-2 tile
(110 × 110 km) touching the country (Natural Earth outline, widened by
`SNIGF_MAPS_SATELLITE_BUFFER_KM`), the scenes of the period below `SNIGF_MAPS_SENTINEL_MAX_CLOUD`
are taken clearest first. From each, only pixels inside the country, not cloud or cloud shadow
by the scene's own classification (SCL, widened by 40 m), and not already filled are kept;
it stops when the tile is complete or after `SNIGF_MAPS_SENTINEL_MAX_SCENES`. So the base is
mostly from one scene per tile, with cloud gaps filled by others; seams between tiles taken in
different seasons can show. The south of the country is cloudy most of the year: a tile can
keep small gaps, shown as the plain map background.

Time and space: Cameroon is about 55 tiles; the download is tens of GB and is limited by the
connection (about 15 hours at 0.45 MB/s). Tiles holding the towns of
`SNIGF_MAPS_SATELLITE_FIRST_PLACES` come first; a finished tile is never fetched again, so the
job can be stopped and resumed at any time, and a build made meanwhile covers the tiles fetched
so far. Downloaded scenes are deleted once used; what is kept (`map-sources`) is the
cloud-masked contributions, about a third of the downloads. The installed archive is about
3 GB for the country.

Where an aerial detail exists, it is drawn over the base from zoom 14: there, individual
buildings and yards are visible. Elsewhere, the 10 m base shows neighbourhoods, roads and
fields, not single houses.

## Satellite view: importing other licensed imagery

1. Obtain imagery whose licence allows hosting on our servers for our users (see the
   source table in ADR-029).
2. Place the file (GeoTIFF or any GDAL raster with a CRS) in `SNIGF_MAPS_IMPORT_DIRECTORY`
   (`var/maps-import`, git-ignored).
3. Import it:

   ```bash
   bash infra/scripts/maps-import-imagery.sh ortho-yaounde.tif \
     --name "Orthophoto Yaoundé" --attribution "© INC Cameroun 2026" \
     --license "Data-sharing agreement INC/2026/…" --source "INC orthophoto, 0.2 m" \
     --date 2026-03-01 [--maxzoom 20]
   ```

The attribution and licence are shown to every user who displays the view, so give the
exact wording the licence requires.

## Synthetic test imagery

```bash
bash infra/scripts/maps-synthetic-imagery.sh               # replaces any imagery
bash infra/scripts/maps-synthetic-imagery.sh --if-absent   # used by e2e.sh
```

The script writes an obviously artificial pattern (1 km colour bands, a 100 m grid, hatching)
over `SNIGF_MAPS_SYNTHETIC_BOUNDS` at `SNIGF_MAPS_SYNTHETIC_RESOLUTION`. It goes through the
same import pipeline as real imagery. Its manifest says `synthetic: true`, and the interface
shows a warning over it. `--if-absent` never replaces real imagery.

## Checking

```bash
docker compose --profile maps run --rm maps-install status
```

In the interface, a parcel's map shows each view either enabled or disabled with its
reason. The end-to-end journeys (`tests/parcels.ts`) check that:

- each available background renders from our archive with 206 range responses;
- switching views never calls the parcel API;
- the browser contacts no other origin;
- no Content Security Policy violation occurs.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| View disabled: "data not installed" | no `manifest.json` for that view | build or import, then install |
| View disabled: "data installed but invalid" | the manifest fails the application's checks; the app log lists the problem codes | rebuild; never edit a manifest by hand |
| `Unable to create directories /sources/...` | a volume owned by root | `docker compose --profile maps run --rm maps-install prepare` |
| A path like `C:/Program Files/Git/sources` | Git Bash rewrote a container path | the scripts set `MSYS2_ARG_CONV_EXCL="*"`; do the same in ad-hoc commands |
| `Error getting size of https://osmdata.openstreetmap.de/...` | the download host is unreachable | retry later; nothing else is affected |
| `UnsatisfiedLinkError ... libsqlitejdbc.so: failed to map segment` during the build | SQLite unpacks a native library into `/tmp`, mounted without execute rights | `planetiler` sets `-Dorg.sqlite.tmpdir=/work/tmp` (compose); keep it |
| The view says "could not be displayed" | MapLibre refused the style (an invalid expression) or a tile | browser console; `features/maps/style.test.ts` validates our styles with MapLibre's own validator |
| Geographic or satellite view blank in a container browser | no GPU | Chromium needs `--enable-unsafe-swiftshader` (test browser only) |
