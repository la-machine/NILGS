# Satellite imagery: download, build and install (operator guide)

*Copyright (c) 2026 FLYT. Author: Pythagore.*

This guide shows how to get **real satellite imagery** behind parcels in the Satellite view,
for the whole of Cameroon. It replaces the synthetic test imagery. Follow the steps in order.
Every command runs from the project folder (`D:\Me\project\snigf-mvp`) in **Git Bash**.

Why it is built this way, and the licences:
[ADR-029](../adr/029-map-views-and-sovereign-map-data.md). How the tools work inside:
[map data guide](map-data.md).

## 1. What you will get

| Layer | Source | Sharpness | Where | What you see |
| --- | --- | --- | --- | --- |
| National base | Copernicus Sentinel-2 (European satellites) | 10 m per pixel | the whole country, plus 2 km around the border | neighbourhoods, roads, fields, rivers; **not single houses** |
| Details over the base | aerial and drone surveys on OpenAerialMap | 5–11 cm per pixel | part of Douala (May 2026), two districts of Ngaoundéré (2019) | every roof, wall and yard |

Both are free to host ourselves; the map shows each source's credit, as their licences
require. The imagery is served by our own server only: users' browsers never contact an
outside map service.

## 2. Before you start

### Time

The download is limited by your internet connection. At about **0.45 MB/s** (measured on
this PC on 2026-10-23):

| Step | Data | Duration |
| --- | --- | --- |
| Trial: one tile (Yaoundé) | about 0.4–0.8 GB | 15–30 min |
| Whole country (about 55 tiles) | about 20–25 GB | **about 15 hours** |
| Aerial details | about 1.2 GB | about 45 min |
| Build (no internet) | — | 1–3 hours |

The download **can be stopped and resumed** at any time, for example overnight across
several nights: nothing finished is ever downloaded again. The towns are fetched first.

### Disk

Docker keeps its data on drive **C:**. Keep **at least 30 GB free** on C: during the work.

```powershell
Get-PSDrive C | Select-Object @{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}}
```

What stays afterwards: about 6–8 GB of cloud-masked downloads (kept for rebuilds) and about
3 GB for the installed map.

### Memory

A map job may use up to `SNIGF_MAPS_MEMORY` (6 GB by default). If the PC runs low:

- stop Docker projects you do not need at that moment, for example:
  ```bash
  docker stop spectra-keycloak spectra-dq-backend spectra-postgres spectra-vault
  ```
- or lower the limit in `.env`: `SNIGF_MAPS_MEMORY=4g`. The download needs about 2 GB; the
  build is slower with less memory but works.

The SNIGF stack itself (app, db, openbao, web) can keep running.

### Settings

Everything is in `.env` (see [configuration reference](../technical/05-configuration-reference.md)).
The defaults are ready for Cameroon:

| Setting | Default | Change it to… |
| --- | --- | --- |
| `SNIGF_MAPS_SATELLITE_COUNTRY` | `CMR` | build for another country (ISO 3-letter code) |
| `SNIGF_MAPS_SATELLITE_FIRST_PLACES` | 12 towns | fetch other towns first |
| `SNIGF_MAPS_SENTINEL_FROM` / `_TO` | `2024-11-01` / today | use another period (two dry seasons give the clearest images) |
| `SNIGF_MAPS_SENTINEL_MAX_CLOUD` | `40` | accept cloudier scenes where clear ones are missing (e.g. `60`) |
| `SNIGF_MAPS_SENTINEL_MAX_SCENES` | `4` | combine more scenes to fill cloud gaps (more download) |
| `SNIGF_MAPS_SENTINEL_TILES` | empty | limit to some tiles (used for the trial) |
| `SNIGF_MAPS_AERIAL_IMAGES` | 3 OpenAerialMap ids | add or remove aerial details |

After pulling new code, run `bash infra/scripts/dev-setup.sh` once: it adds any new setting
to `.env` without changing yours.

## 3. Step 1: a trial on one tile (Yaoundé)

Do this first: it checks the whole chain on a small area before the long download.

1. In `.env`, set:
   ```
   SNIGF_MAPS_SENTINEL_TILES=32NQK
   ```
2. Download (internet). Keep a log in `var/` (ignored by Git):
   ```bash
   mkdir -p var
   bash infra/scripts/maps-build-satellite.sh --fetch-only 2>&1 | tee var/satellite-fetch.log
   ```
   You will see, in order: the list of scenes (about 6 minutes the first time; kept for a
   day afterwards), then for the tile: `downloading … (whole scene)`, then
   `… done; x% of the tile still to fill`, possibly a second or third scene, and finally
   `== done: 1 tiles`.
   The aerial images are downloaded too (about 45 min the first time); press Ctrl+C after
   the line `== fetching aerial images` if you want to leave them for later: the next run
   continues them.
3. Build and install (no internet):
   ```bash
   bash infra/scripts/maps-build-satellite.sh --offline 2>&1 | tee var/satellite-build.log
   ```
   It ends with `satellite installed: { … }`. The application uses the new map at once (no
   restart).
4. Look at it: open https://localhost:47000, sign in as `cadastre.yde@demo.invalid`, open a
   Yaoundé parcel (or record one, see [demo accounts](demo-accounts.md)), choose **Satellite**.
   - The label **"SYNTHETIC TEST"** must be gone.
   - The footer shows "Contains modified Copernicus Sentinel data …" and the dates.
   - The parcel outline sits over real ground.
   Outside the Yaoundé tile the background is empty: expected during the trial.

If any step fails, see section 8 before going further.

## 4. Step 2: the whole country

1. In `.env`, empty the tile list:
   ```
   SNIGF_MAPS_SENTINEL_TILES=
   ```
2. Start the download, ideally in the evening. Keep the PC awake (Windows: Settings →
   System → Power → Screen and sleep → "Never" while plugged in):
   ```bash
   bash infra/scripts/maps-build-satellite.sh --fetch-only 2>&1 | tee -a var/satellite-fetch.log
   ```
   The Yaoundé tile is not downloaded again. Towns' tiles come first (Yaoundé, Douala,
   Bafoussam, Bamenda, Garoua, Maroua, Ngaoundéré, Bertoua, Ebolowa, Buea, Limbe, Kribi),
   then the rest.
3. **To stop:** Ctrl+C, or simply shut down. **To continue:** run the same command again.
   A tile is finished when its `selection.json` is written; an interrupted image download
   resumes from the bytes already received.
4. **Progress** (in another Git Bash window):
   ```bash
   MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-fetch -c \
     'echo "tiles done: $(ls /sources/sentinel/*/selection.json 2>/dev/null | wc -l)"; du -sh /sources/sentinel'
   tail -n 5 var/satellite-fetch.log
   ```
   The log's first lines say how many tiles there are (`== N tiles`).

### Install early, complete later

You do not have to wait for the whole country. Once the towns' tiles are done (the log shows
`== tile 13/…`), you can build and install what you have:

```bash
bash infra/scripts/maps-build-satellite.sh --offline 2>&1 | tee var/satellite-build.log
```

then start the download again; build once more at the end. Each build replaces the installed
map in one step, so users never see a half-built map.

## 5. Step 3: build and install the final map

When the download log ends with `== done: N tiles; with gaps over 1%: …`:

```bash
bash infra/scripts/maps-build-satellite.sh --offline 2>&1 | tee var/satellite-build.log
```

The build makes the national mosaic (the longest part), the tiles from zoom 6 to 14, the
aerial details, and the description of the map with every source and date, then installs it.

Check what is installed:

```bash
MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm maps-install status
```

The `satellite:` line must show `"synthetic": false`, the Sentinel-2 dates, and a `details`
list with the aerial images.

## 6. Check in the application

Sign in as `cadastre.yde@demo.invalid` (or any account that can read parcels):

| Where | What to check |
| --- | --- |
| A parcel in Yaoundé, **Satellite** | real ground under the outline; footer credits Copernicus Sentinel and the dates |
| A parcel in the Douala aerial area (about 9.725–9.763 °E, 3.975–4.001 °N), zoom in | from zoom 14, sharp aerial imagery with roofs; footer lists the aerial detail |
| A parcel in Ngaoundéré (Djalingo/Sabongari or Mount Ngaoundéré), zoom in | same, 5 cm imagery from 2019 |
| Dashboard → Map data | Satellite: available, not "synthetic test data" |

A Douala test parcel inside the aerial area:

```json
{"type":"Polygon","coordinates":[[[9.7440,3.9880],[9.7444,3.9880],[9.7444,3.9884],[9.7440,3.9884],[9.7440,3.9880]]]}
```

Record it as `cadastre.dla@demo.invalid` (office DLA1).

The end-to-end tests keep working: they use whatever imagery is installed, and install
synthetic imagery only when none is.

## 7. Keeping it up to date

- **New Sentinel-2 imagery** (for example once a year, after the dry season): set
  `SNIGF_MAPS_SENTINEL_FROM` to the new period, then clear the previous downloads and fetch
  again:
  ```bash
  MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-fetch -c 'rm -rf /sources/sentinel'
  bash infra/scripts/maps-build-satellite.sh
  ```
  The installed map stays in service until the new build replaces it.
- **Another aerial detail:** find it on https://map.openaerialmap.org (check the licence:
  CC-BY 4.0 is accepted; non-commercial licences are refused unless legal advice allows them
  and `SNIGF_MAPS_AERIAL_LICENCES` is changed), copy its id (24 characters, in the image's
  address) into `SNIGF_MAPS_AERIAL_IMAGES`, then:
  ```bash
  bash infra/scripts/maps-build-satellite.sh            # fetches only what is missing, then builds
  ```
- **Licensed very-high-resolution imagery** (national orthophotos, purchased imagery): see
  "Satellite view: importing other licensed imagery" in the [map data guide](map-data.md).

## 8. Troubleshooting

| You see | Meaning | What to do |
| --- | --- | --- |
| The job stops by itself, or "low on memory" | the PC ran out of memory | section 2, Memory; run the same command again: it resumes |
| `download interrupted (…); resuming, attempt N` | the connection dropped | nothing: it resumes by itself (30 attempts) |
| `… unreadable (…); fetching it again` | a downloaded image was damaged | nothing: it is downloaded again |
| `could not download … completely` | 30 failed attempts in a row | check the internet connection; run the command again |
| `HTTP Error 403` while downloading | the archive's access token expired or was refused | run again (a new token is taken automatically) |
| `country CMR not found in Natural Earth` or missing `natural_earth_vector` | the geographic sources were never fetched | run `bash infra/scripts/maps-build-geographic.sh` once (it downloads them) |
| `no Sentinel-2 contribution fetched` at build | nothing downloaded yet | run the `--fetch-only` step first |
| `licence "…" is not among the allowed ones` | an aerial image with another licence | remove its id from `SNIGF_MAPS_AERIAL_IMAGES`, or get legal advice |
| Build fails with "No space left on device" | drive C: is full | free space (section 2, Disk; section 9) and run `--offline` again |
| Satellite view says "could not be displayed" | the browser could not draw the map | press F12, Console tab; send the red message to the developers |
| Grey or empty patches in the Satellite view | cloud gaps no clear scene could fill (frequent in the south) | raise `SNIGF_MAPS_SENTINEL_MAX_CLOUD` and `MAX_SCENES`, delete those tiles' folders (section 9) and fetch again |
| Colour seams between areas | neighbouring tiles come from different dates or seasons | expected with a free mosaic; a narrower period (one dry season) reduces it |

## 9. Cleaning up

```bash
# Space used:
MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-fetch -c 'du -sh /sources/* 2>/dev/null'

# Partial downloads left by an interruption (they would otherwise be resumed):
MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-fetch -c 'rm -rf /sources/sentinel/cache'

# Fetch some tiles again (e.g. to fill gaps with new settings): delete their folders
MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-fetch -c 'rm -rf /sources/sentinel/32NQK'

# Build workspace (safe once the map is installed):
MSYS2_ARG_CONV_EXCL="*" docker compose --profile maps run --rm --entrypoint sh maps-tools -c 'rm -rf /work/tmp /work/stage'
```

Never delete the volume `map-data` to free space: it holds the installed maps in service.

## 10. Status of this pipeline

| Part | State on 2026-10-23 |
| --- | --- |
| Server support for a base plus detail archives, credits, access only for parcel readers | done and tested (`FileMapDataStoreTest`, `MapApiIT`) |
| Interface drawing the details over the base and listing each source | done and tested (`style.test.ts`, validated by MapLibre's own validator) |
| Download with resume, integrity check, towns first; aerial licence check | written; the first trial download was interrupted (memory), so **step 1 is the first complete run**: report anything unexpected |
| National mosaic build and installation | written; not yet run on real data |
