# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
"""Fetches the Copernicus Sentinel-2 imagery for the national satellite base (ADR-029).

Runs in maps-fetch (network). For every Sentinel-2 tile (MGRS, 110 x 110 km) that touches the
country, it takes the clearest scenes of the period and keeps, from each, only the pixels that
are inside the country (with a margin), not cloud or cloud shadow by the scene's own
classification (SCL), and not already filled by a clearer scene. It stops when the tile is
complete or after the maximum number of scenes. Each contribution is written as an RGBA
GeoTIFF (transparent elsewhere) in /sources/sentinel/<tile>/, with selection.json describing
what was taken.

The connection is the limit (a national mosaic is tens of GB), so:
  - tiles holding the listed towns are fetched first, then the rest;
  - a finished tile is never fetched again (selection.json), so the job can be stopped and
    started again at any time and continues where it stopped;
  - a scene used for most of a tile is downloaded whole, in one stream (resumed if cut);
    a scene that only fills cloud gaps is read only over the gaps' extent.

Settings (environment): SNIGF_MAPS_SATELLITE_COUNTRY (ISO A3), SNIGF_MAPS_SATELLITE_BUFFER_KM,
SNIGF_MAPS_SATELLITE_FIRST_PLACES (name:lon,lat;...), SNIGF_MAPS_SENTINEL_STAC_URL,
SNIGF_MAPS_SENTINEL_TOKEN_URL (empty: no signing; Planetary Computer: its SAS token API),
SNIGF_MAPS_SENTINEL_FROM, SNIGF_MAPS_SENTINEL_TO (empty: today), SNIGF_MAPS_SENTINEL_MAX_CLOUD,
SNIGF_MAPS_SENTINEL_MAX_SCENES, SNIGF_MAPS_SENTINEL_TILES (optional: only these tiles).
"""
import datetime
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()
for key, value in {
    'GDAL_DISABLE_READDIR_ON_OPEN': 'EMPTY_DIR', 'CPL_VSIL_CURL_ALLOWED_EXTENSIONS': '.tif',
    'GDAL_HTTP_MAX_RETRY': '8', 'GDAL_HTTP_RETRY_DELAY': '5', 'GDAL_HTTP_MERGE_CONSECUTIVE_RANGES': 'YES',
    'CPL_VSIL_CURL_CHUNK_SIZE': str(8 * 1024 * 1024), 'CPL_VSIL_CURL_CACHE_SIZE': str(256 * 1024 * 1024),
}.items():
    gdal.SetConfigOption(key, value)

NATURAL_EARTH = '/vsizip//sources/planetiler/natural_earth_vector.sqlite.zip/packages/natural_earth_vector.sqlite'
OUT = Path('/sources/sentinel')
CACHE = OUT / 'cache'
# Scene classification (SCL): kept = vegetation, bare soil, water, unclassified, snow, dark area.
KEEP = [2, 4, 5, 6, 7, 11]
CLOUD = [3, 8, 9, 10]
WGS84 = osr.SpatialReference()
WGS84.ImportFromEPSG(4326)
WGS84.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)


def setting(name, default=None):
    value = os.environ.get(name, '').strip()
    if not value and default is None:
        sys.exit(f'{name} is not set')
    return value or default


def log(message):
    print(f'{datetime.datetime.now():%H:%M:%S} {message}', flush=True)


class Signer:
    """Adds a read-only access token to archive URLs when the catalogue needs one."""

    def __init__(self, token_url):
        self.token_url = token_url.rstrip('/')
        self.tokens = {}

    def sign(self, href):
        if not self.token_url:
            return href
        parts = urllib.parse.urlparse(href)
        account = parts.netloc.split('.')[0]
        container = parts.path.lstrip('/').split('/')[0]
        key = f'{account}/{container}'
        token, expires = self.tokens.get(key, (None, 0))
        if time.time() > expires - 600:
            answer = json.load(urllib.request.urlopen(f'{self.token_url}/{key}', timeout=60))
            token = answer['token']
            expiry = answer.get('msft:expiry', '')
            expires = datetime.datetime.fromisoformat(expiry.replace('Z', '+00:00')).timestamp() if expiry else time.time() + 3000
            self.tokens[key] = (token, expires)
        return f'{href}?{token}'


def country_area(code, buffer_km):
    """The country's outline from Natural Earth, widened by buffer_km (in degrees, near enough)."""
    if not re.fullmatch(r'[A-Z]{3}', code):
        sys.exit('SNIGF_MAPS_SATELLITE_COUNTRY must be an ISO 3166 alpha-3 code')
    source = ogr.Open(NATURAL_EARTH)  # kept: the layer lives only as long as its source
    layer = source.GetLayerByName('ne_10m_admin_0_countries')
    layer.SetAttributeFilter(f"adm0_a3 = '{code}'")
    feature = layer.GetNextFeature()
    if feature is None:
        sys.exit(f'country {code} not found in Natural Earth')
    geometry = feature.GetGeometryRef().Clone()
    geometry.AssignSpatialReference(WGS84)
    return geometry.Buffer(buffer_km / 111.0)


def places(text):
    """'Yaounde:11.52,3.87;Douala:9.70,4.05' -> [('Yaounde', point), ...]."""
    out = []
    for entry in filter(None, (e.strip() for e in text.split(';'))):
        name, _, coords = entry.partition(':')
        lon, lat = (float(v) for v in coords.split(','))
        point = ogr.Geometry(ogr.wkbPoint)
        point.AddPoint_2D(lon, lat)
        out.append((name.strip(), point))
    return out


def stac_search(stac, area, start, end, max_cloud):
    """Every Sentinel-2 L2A scene over the area in the period, below the cloud limit."""
    body = {
        'collections': ['sentinel-2-l2a'],
        'intersects': json.loads(area.SimplifyPreserveTopology(0.05).ExportToJson()),
        'datetime': f'{start}T00:00:00Z/{end}T23:59:59Z',
        'query': {'eo:cloud_cover': {'lt': max_cloud}},
        'limit': 200,
    }
    url, items = f'{stac}/search', []
    while url:
        request = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
                                         headers={'Content-Type': 'application/json'})
        page = json.load(urllib.request.urlopen(request, timeout=180))
        items += page['features']
        following = next((link for link in page.get('links', []) if link.get('rel') == 'next'), None)
        if not following:
            break
        url = following['href']
        body = following.get('body') if following.get('method', 'GET').upper() == 'POST' else None
        log(f'   {len(items)} scenes listed')
    return items


def tile_of(item):
    p = item['properties']
    code = p.get('s2:mgrs_tile') or p.get('grid:code') or ''
    match = re.search(r'(\d{2}[A-Z]{3})', code) or re.search(r'_T(\d{2}[A-Z]{3})_', item['id'])
    return match.group(1) if match else None


def asset(item, *names):
    for name in names:
        if name in item['assets']:
            return item['assets'][name]['href']
    return None


def download(url, target, attempts=30):
    """The whole file, resumed until complete: a connection that ends early (silently or not)
    is picked up from the bytes already written; the file is accepted only at its full size."""
    part = target.with_suffix('.part')
    total = None
    for attempt in range(1, attempts + 1):
        done = part.stat().st_size if part.exists() else 0
        if total is not None and done >= total:
            break
        try:
            request = urllib.request.Request(url() if callable(url) else url,
                                             headers={'Range': f'bytes={done}-'} if done else {})
            with urllib.request.urlopen(request, timeout=120) as response:
                if done and response.status != 206:
                    done = 0
                length = response.headers.get('Content-Range', '').rpartition('/')[2] or response.headers.get('Content-Length')
                if length and length.isdigit():
                    total = int(length) if response.status == 206 or not done else total
                with open(part, 'ab' if done else 'wb') as out:
                    while chunk := response.read(4 * 1024 * 1024):
                        out.write(chunk)
        except (OSError, ValueError) as error:
            print(f'   download interrupted ({error}); resuming, attempt {attempt}', flush=True)
            time.sleep(min(60, 5 * attempt))
            continue
        if total is not None and part.stat().st_size >= total:
            break
        print(f'   download ended early at {part.stat().st_size} of {total} bytes; resuming', flush=True)
    if total is None or part.stat().st_size != total:
        raise SystemExit(f'could not download {target.name} completely')
    part.rename(target)


def open_whole(path, fetch_again):
    """Opens a downloaded scene after checking every block reads; otherwise fetches it again."""
    for _ in range(2):
        try:
            dataset = gdal.Open(str(path))
            band = dataset.GetRasterBand(1)
            band.Checksum()  # reads every block: fails on a damaged file
            return dataset
        except RuntimeError as error:
            print(f'   {path.name} unreadable ({error}); fetching it again', flush=True)
            path.unlink(missing_ok=True)
            fetch_again()
    raise SystemExit(f'{path.name} remains unreadable')


def area_mask(scl, area):
    """1 where the SCL grid lies inside the (buffered) country."""
    srs = osr.SpatialReference(wkt=scl.GetProjection())
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    local = area.Clone()
    local.TransformTo(srs)
    mem = gdal.GetDriverByName('MEM').Create('', scl.RasterXSize, scl.RasterYSize, 1, gdal.GDT_Byte)
    mem.SetGeoTransform(scl.GetGeoTransform())
    mem.SetProjection(scl.GetProjection())
    vector = ogr.GetDriverByName('MEM').CreateDataSource('')
    layer = vector.CreateLayer('area', srs, ogr.wkbMultiPolygon)
    feature = ogr.Feature(layer.GetLayerDefn())
    feature.SetGeometry(local)
    layer.CreateFeature(feature)
    gdal.RasterizeLayer(mem, [1], layer, burn_values=[1])
    return mem.ReadAsArray().astype(bool)


def dilate(mask, steps):
    out = mask.copy()
    for _ in range(steps):
        grown = out.copy()
        grown[1:, :] |= out[:-1, :]
        grown[:-1, :] |= out[1:, :]
        grown[:, 1:] |= out[:, :-1]
        grown[:, :-1] |= out[:, 1:]
        out = grown
    return out


def write_contribution(path, tci, new20, offset):
    """RGBA GeoTIFF of the TCI window, opaque only where `new20` (20 m) says so."""
    x0, y0, w, h = offset
    gt = list(tci.GetGeoTransform())
    gt[0] += x0 * gt[1]
    gt[3] += y0 * gt[5]
    out = gdal.GetDriverByName('GTiff').Create(str(path) + '.part', w, h, 4, gdal.GDT_Byte,
                                               ['TILED=YES', 'COMPRESS=WEBP', 'WEBP_LEVEL=90', 'BIGTIFF=IF_SAFER'])
    out.SetGeoTransform(gt)
    out.SetProjection(tci.GetProjection())
    # Strips of 2048 rows keep memory low whatever the window.
    for top in range(0, h, 2048):
        rows = min(2048, h - top)
        for b in (1, 2, 3):
            out.GetRasterBand(b).WriteArray(tci.GetRasterBand(b).ReadAsArray(x0, y0 + top, w, rows), 0, top)
        mask = np.repeat(np.repeat(new20[top // 2:(top + rows + 1) // 2 + 1], 2, axis=0), 2, axis=1)
        out.GetRasterBand(4).WriteArray((mask[top % 2:top % 2 + rows, :w] * 255).astype(np.uint8), 0, top)
    out.GetRasterBand(4).SetColorInterpretation(gdal.GCI_AlphaBand)
    out.FlushCache()
    del out
    os.replace(str(path) + '.part', path)


def fetch_tile(tile, candidates, area, max_scenes, signer):
    folder = OUT / tile
    done = folder / 'selection.json'
    if done.exists():
        log(f'{tile}: already fetched')
        return json.loads(done.read_text())
    folder.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    for old in list(folder.glob('*.tif')) + list(folder.glob('*.part')):
        old.unlink()
    need = filled = None
    taken = []
    for item in candidates:
        scl_href, tci_href = asset(item, 'scl', 'SCL'), asset(item, 'visual')
        scl_file = CACHE / f'{item["id"]}-SCL.tif'
        if not scl_file.exists():
            download(lambda: signer.sign(scl_href), scl_file)
        scl = open_whole(scl_file, lambda: download(lambda: signer.sign(scl_href), scl_file))
        if need is None:
            need = area_mask(scl, area)
            filled = np.zeros_like(need)
            if need.sum() == 0:
                break
        classes = scl.ReadAsArray()
        valid = np.isin(classes, KEEP) & ~dilate(np.isin(classes, CLOUD), 2)
        new = valid & need & ~filled
        if new.sum() < 0.005 * need.sum():
            scl_file.unlink()
            continue
        rows, cols = np.nonzero(new)
        r0, r1, c0, c1 = rows.min(), rows.max() + 1, cols.min(), cols.max() + 1
        window_share = (r1 - r0) * (c1 - c0) / new.size
        tci_file = CACHE / f'{item["id"]}-TCI.tif'
        if window_share > 0.4 or tci_file.exists():
            log(f'{tile}: downloading {item["id"]} (whole scene)')
            if not tci_file.exists():
                download(lambda: signer.sign(tci_href), tci_file)
            tci = open_whole(tci_file, lambda: download(lambda: signer.sign(tci_href), tci_file))
        else:
            log(f'{tile}: reading {item["id"]} over {window_share:.0%} of the tile')
            tci = gdal.Open('/vsicurl/' + signer.sign(tci_href))
        offset = (int(c0 * 2), int(r0 * 2), int(min((c1 - c0) * 2, tci.RasterXSize - c0 * 2)),
                  int(min((r1 - r0) * 2, tci.RasterYSize - r0 * 2)))
        name = f'{len(taken) + 1}-{item["id"]}.tif'
        write_contribution(folder / name, tci, new[r0:r1, c0:c1], offset)
        del tci
        for cached in (tci_file, scl_file):
            cached.unlink(missing_ok=True)
        filled |= new
        remaining = float((need & ~filled).sum() / need.sum())
        taken.append({'file': name, 'scene': item['id'], 'date': item['properties']['datetime'][:10],
                      'cloud': item['properties'].get('eo:cloud_cover'), 'remaining': round(remaining, 4)})
        log(f'{tile}: {item["id"]} done; {remaining:.1%} of the tile still to fill')
        if remaining < 0.003 or len(taken) >= max_scenes:
            break
    summary = {'tile': tile, 'scenes': taken,
               'remaining': taken[-1]['remaining'] if taken else (0.0 if need is not None and need.sum() == 0 else 1.0)}
    done.write_text(json.dumps(summary, indent=2))
    return summary


def main():
    country = setting('SNIGF_MAPS_SATELLITE_COUNTRY')
    area = country_area(country, float(setting('SNIGF_MAPS_SATELLITE_BUFFER_KM')))
    stac = setting('SNIGF_MAPS_SENTINEL_STAC_URL')
    token_url = setting('SNIGF_MAPS_SENTINEL_TOKEN_URL', '')
    for url in filter(None, (stac, token_url)):
        if not url.startswith('https://'):
            sys.exit('catalogue and token URLs must be https')
    start = setting('SNIGF_MAPS_SENTINEL_FROM')
    end = setting('SNIGF_MAPS_SENTINEL_TO', datetime.date.today().isoformat())
    max_cloud = float(setting('SNIGF_MAPS_SENTINEL_MAX_CLOUD'))
    max_scenes = int(setting('SNIGF_MAPS_SENTINEL_MAX_SCENES'))
    only = {t.strip() for t in setting('SNIGF_MAPS_SENTINEL_TILES', '').split(',') if t.strip()}
    first = places(setting('SNIGF_MAPS_SATELLITE_FIRST_PLACES', ''))
    signer = Signer(token_url)

    # The scene list is kept a day for the same search, so a resumed run starts at once.
    listing = OUT / 'scenes.json'
    search = {'stac': stac, 'country': country, 'from': start, 'to': end, 'max_cloud': max_cloud}
    cached = json.loads(listing.read_text()) if listing.exists() else {}
    if cached.get('search') == search and time.time() - cached.get('listed', 0) < 86400:
        log(f'== {len(cached["items"])} scenes from the list made {(time.time() - cached["listed"]) / 3600:.1f} h ago')
        scenes = cached['items']
    else:
        log('== listing scenes')
        scenes = stac_search(stac, area, start, end, max_cloud)
        OUT.mkdir(parents=True, exist_ok=True)
        listing.write_text(json.dumps({'search': search, 'listed': time.time(), 'items': scenes}))
    by_tile, footprint = {}, {}
    for item in scenes:
        tile = tile_of(item)
        if tile and (not only or tile in only) and asset(item, 'visual') and asset(item, 'scl', 'SCL'):
            by_tile.setdefault(tile, []).append(item)
            footprint.setdefault(tile, ogr.CreateGeometryFromJson(json.dumps(item['geometry'])))

    def priority(tile):
        towns = [n for n, point in first if footprint[tile].Contains(point)]
        return (0 if towns else 1, tile)

    order = sorted(by_tile, key=priority)
    log(f'== {len(order)} tiles; first: {", ".join(t for t in order if priority(t)[0] == 0) or "none"}')
    summaries = []
    for number, tile in enumerate(order, start=1):
        log(f'== tile {number}/{len(order)}: {tile}')
        # Clearest first; among equally clear, the most recent.
        candidates = sorted(by_tile[tile], key=lambda i: (round(i['properties'].get('eo:cloud_cover', 100)),
                                                          -datetime.date.fromisoformat(i['properties']['datetime'][:10]).toordinal()))
        summaries.append(fetch_tile(tile, candidates[:12], area, max_scenes, signer))
    (OUT / 'summary.json').write_text(json.dumps({'country': country, 'from': start, 'to': end, 'tiles': summaries}, indent=2))
    gaps = [s['tile'] for s in summaries if s['remaining'] > 0.01]
    log(f'== done: {len(summaries)} tiles; with gaps over 1%: {", ".join(gaps) or "none"}')


if __name__ == '__main__':
    main()
