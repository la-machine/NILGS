# ADR-029: Three map views over one authoritative geometry; map data hosted by us

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-22 | Project owner, Pythagore (FLYT) |

## Context

The parcel screens (task E-08) show a parcel on a map. The project owner asked for three
views from the start: a **technical** view (offline, boundaries, coordinate grid, zoom and
pan, scale, coordinate readout, measurements), a **geographic** view (boundaries over a
map, preferably self-hosted) and a **satellite** view (boundaries over imagery, if suitable
imagery and rights can be obtained). Switching views must never change parcel data or
bypass authorization and audit; missing map data must not block anything else; external
providers stay disabled unless explicitly approved. The security policy allows the page
to load nothing but its own origin (`default-src 'self'`), and connectivity in offices may
be poor.

## Decision

**One geometry, three backgrounds.** The parcel API returns the authoritative geometry
(EPSG:4326, as stored and validated by `normalize_parcel_geometry`) and, for display, the
same geometry transformed by PostGIS into the parcel's UTM zone (EPSG:32632 west of 12°E,
32633 east of it: the zones the validation accepts). The browser never transforms or
edits geometry; switching view changes only what is drawn behind the boundary. Parcel
data is fetched once, through the parcel API and its permission checks; no parcel data is
ever put into map tiles.

| View | How it is drawn | Data it needs |
| --- | --- | --- |
| Technical | SVG in UTM metres: boundary, vertices, metric grid adapting to zoom, zoom/pan, scale bar, coordinate readout (easting/northing; latitude/longitude by the server's transformation of the vertices, cursor values computed for display), distance and area measurement | none: works offline, no WebGL |
| Geographic | MapLibre GL JS (BSD-3-Clause) over vector tiles in one PMTiles archive, our own style | a tile archive built from OpenStreetMap |
| Satellite | MapLibre over raster tiles in one PMTiles archive | imagery we have the right to host |

**Map data is ours.** Tile archives live in a `map-data` volume, mounted read-only into the
application, and are served by the application at `/api/v1/map/...` with HTTP range
requests, behind a full session and the parcel reading permission (licensed imagery may be
restricted to authorized users). Each archive has a manifest (source, licence,
attribution, date, bounds, zoom range). `GET /api/v1/map/backgrounds` tells the interface
which views are available and, when one is not, why. The page keeps loading only its own
origin: no CSP change, and the MapLibre build used is its CSP build (no `blob:` workers).

**External providers are disabled.** `SNIGF_MAP_EXTERNAL_PROVIDERS=none` is the only value
accepted today; the application refuses to start otherwise. Enabling one later needs its
own decision (contract, licence, which viewed locations it would learn, CSP change).

**Missing data never blocks.** All three views are in the interface from the first
release. A view without data is shown disabled with the reason and what would enable it;
the technical view and the whole parcel workflow work without any map data.

## Dependencies, licences and infrastructure

### Geographic view

| Item | Choice | Licence and obligations |
| --- | --- | --- |
| Data | OpenStreetMap extract for Cameroon (Geofabrik) | ODbL 1.0: show "© OpenStreetMap contributors"; share-alike applies to derived databases made public, not to the rendered map |
| Low-zoom and water data | Natural Earth; OSM water polygons | public domain; ODbL |
| Tile schema | OpenMapTiles, produced by Planetiler | schema CC-BY 4.0: show "© OpenMapTiles" |
| Tile builder | Planetiler 0.10.2 (container) | Apache-2.0 |
| Style | written for this project | ours |
| Label fonts | Noto Sans glyphs (openmaptiles/fonts) | SIL OFL 1.1 |
| Viewer | MapLibre GL JS 6.13, pmtiles 4.5 | BSD-3-Clause |

Infrastructure: one build run with internet access (about 1 GB of downloads: the
Cameroon extract, water polygons, Natural Earth), about 4 GB of memory and a few GB of
temporary disk for some minutes; the result, one archive of a few hundred MB, is copied to
the `map-data` volume. It can be rebuilt periodically (OSM changes daily); the manifest
records the extract date. No tile server is needed: the application serves byte ranges of
the archive.

### Satellite view

| Source | Resolution | Rights | Fit for parcels |
| --- | --- | --- | --- |
| Copernicus Sentinel-2 | 10 m | free, full and open; show "Contains modified Copernicus Sentinel data [year]" | context only: a 20 × 30 m parcel is 2 × 3 pixels; building a cloud-free mosaic is real processing work |
| Sentinel-2 cloudless mosaic (EOX) | 10 m | 2016 edition CC-BY 4.0; later editions CC-BY-NC-SA 4.0 (non-commercial: needs a legal opinion for government use) | context only |
| Very high resolution commercial (e.g. Pléiades Neo, Maxar) | 0.3–0.5 m | purchased licence per area; hosting, number of users and derivative rights set by contract | yes |
| National orthophotos (Institut National de Cartographie, cadastre services) | typically 0.1–0.5 m | data-sharing agreement | yes: the best source for cadastral work |
| Online imagery services (Esri, Google, Bing, Mapbox) | high | terms generally forbid storing tiles or offline use; they are external providers that learn every viewed location | excluded by this ADR |

Infrastructure: an import tool (GDAL 3.13 and go-pmtiles 1.31, containers) turns licensed
GeoTIFF imagery into a raster PMTiles archive with its manifest (licence, attribution,
capture date, resolution). Storage grows with resolution: a city at 0.5 m is several GB.
The licence must allow hosting on our servers for our users.

### Satellite imagery chosen (2026-10-23)

The project owner asked for real imagery over the whole country. Chosen, because their
licences allow us to host them:

- **National base:** Copernicus Sentinel-2 (10 m), a cloud-masked mosaic built from the
  clearest scenes of each tile, fetched from the archive's European mirror (the fastest from
  here) with anonymous read-only tokens.
- **Details:** aerial surveys published on OpenAerialMap under CC-BY 4.0 (Douala, May 2026,
  11 cm; Ngaoundéré, 2019, 5 cm). Non-commercial imagery is refused by configuration.

A background may therefore hold a base archive and up to 20 **detail archives**, each with its
own attribution, licence, date, bounds and zooms in the manifest, checked like the base; only
files the manifest lists are ever served. Very-high-resolution imagery for the whole country
(national orthophotos, commercial imagery) remains the long-term need: it goes through the
same import, as base or details.

### What is done now, and what waits for data

| Can be implemented and tested now | Waits for an external decision or data |
| --- | --- |
| The technical view, complete | Geographic tiles in production: running the build where internet is allowed, and deciding how often to refresh |
| The background catalogue, the authenticated range delivery, the manifests, the disabled states with reasons | Very-high-resolution imagery beyond the open details: national orthophotos (agreement) or commercial imagery (licence) |
| The satellite rendering, tested end to end with synthetic imagery put through the same import pipeline (labelled synthetic in the interface); the geographic rendering, tested with a real build | Any external provider: a separate decision, never by default |
| The geographic build tool, runnable on a developer machine with internet (real OSM data for Cameroon) | |

## Alternatives considered

- **Leaflet with raster tiles from OSM's public servers:** external, against the OSM tile
  usage policy for an application, and leaks viewed locations.
- **One renderer (MapLibre) for all three views:** the technical view would depend on
  WebGL and Web Mercator; a surveyor works in UTM metres.
- **A tile server (TileServer GL, Martin):** another service to run and harden; serving byte
  ranges of a PMTiles archive needs none.

## Consequences

- New settings: `SNIGF_MAP_EXTERNAL_PROVIDERS` (none), and the volume `map-data`.
- New tools, not part of the running stack: `infra/scripts/maps-build-geographic.sh`,
  `infra/scripts/maps-import-imagery.sh`, `infra/scripts/maps-synthetic-imagery.sh`
  (Compose profile `maps`, [map data guide](../guides/map-data.md)).
- The geographic build depends on two download hosts (download.geofabrik.de and
  osmdata.openstreetmap.de); when either is unreachable the build stops and the view stays
  unavailable, nothing else is affected.
- Attributions are shown on the map whenever a background is displayed.
