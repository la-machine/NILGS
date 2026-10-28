# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
"""Fetches the aerial images drawn as detail over the satellite base (ADR-029).

Runs in maps-fetch (network). Each image is named by its OpenAerialMap id; its metadata
(provider, capture date, resolution, licence) is read from the catalogue, the licence must be
one of the allowed ones (refused otherwise: a non-commercial licence, for example, is not for
a government system without legal advice), and the GeoTIFF is downloaded whole, resumed if
cut, into /sources/aerial/<id>.tif with <id>.json beside it.

Settings: SNIGF_MAPS_AERIAL_CATALOGUE_URL, SNIGF_MAPS_AERIAL_IMAGES (ids, comma-separated),
SNIGF_MAPS_AERIAL_LICENCES (allowed licences, separated by ';').
"""
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

OUT = Path('/sources/aerial')


def setting(name, default=None):
    value = os.environ.get(name, '').strip()
    if not value and default is None:
        sys.exit(f'{name} is not set')
    return value or default


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


def main():
    catalogue = setting('SNIGF_MAPS_AERIAL_CATALOGUE_URL')
    if not catalogue.startswith('https://'):
        sys.exit('SNIGF_MAPS_AERIAL_CATALOGUE_URL must be https')
    images = [i.strip() for i in setting('SNIGF_MAPS_AERIAL_IMAGES', '').split(',') if i.strip()]
    allowed = {l.strip() for l in setting('SNIGF_MAPS_AERIAL_LICENCES').split(';') if l.strip()}
    OUT.mkdir(parents=True, exist_ok=True)
    for image in images:
        if not re.fullmatch(r'[0-9a-f]{24}', image):
            sys.exit(f'not an OpenAerialMap id: {image}')
        target = OUT / f'{image}.tif'
        if target.exists():
            print(f'{image}: already fetched', flush=True)
            continue
        meta = json.load(urllib.request.urlopen(f'{catalogue}/meta/{image}', timeout=60))['results']
        licence = (meta.get('properties') or {}).get('license') or meta.get('license')
        if licence not in allowed:
            sys.exit(f'{image}: licence "{licence}" is not among the allowed ones ({"; ".join(sorted(allowed))})')
        url = meta['uuid']
        if not url.startswith('https://'):
            sys.exit(f'{image}: the image is not served over https')
        description = {
            'id': image, 'title': meta['title'], 'provider': meta.get('provider'), 'date': meta['acquisition_start'][:10],
            'gsd': meta.get('gsd'), 'licence': licence, 'platform': meta.get('platform'),
            'page': f'https://map.openaerialmap.org/#/-/-/-/{image}',
        }
        print(f'{image}: downloading "{meta["title"]}" ({meta.get("file_size", 0) / 1e6:.0f} MB, {licence})', flush=True)
        download(url, target)
        (OUT / f'{image}.json').write_text(json.dumps(description, indent=2, ensure_ascii=False))
    print(f'== {len(images)} aerial image(s) ready', flush=True)


if __name__ == '__main__':
    main()
