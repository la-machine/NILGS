# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
"""Writes the manifest of a staged archive (format snigf-map-manifest/1, ADR-029).

Bounds and zoom range are read from the archive's own header, so the manifest cannot claim
more than the archive holds. The application checks the manifest again before offering it.

usage: manifest.py <archive.pmtiles> <kind> <name> <attribution> <license> <source> <date> <synthetic:true|false> [details.json]

details.json (optional) lists detail archives beside the base, each
{"file", "name", "attribution", "license", "source", "date"}; their bounds and zooms are read
from their own headers too.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

TILE_TYPES = {"geographic": "vector", "satellite": "raster"}
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def header(archive):
    return json.loads(subprocess.run(["pmtiles", "show", "--header-json", str(archive)],
                                     check=True, capture_output=True, text=True).stdout)


def main(archive, kind, name, attribution, license_, source, date, synthetic, details_file=None):
    if kind not in TILE_TYPES:
        sys.exit(f"unknown kind {kind}")
    if not DATE.fullmatch(date):
        sys.exit(f"date must be YYYY-MM-DD, got {date!r}")
    h = header(archive)
    tile_type = h.get("tile_type")
    expected = {"vector": ["mvt"], "raster": ["png", "jpeg", "webp"]}[TILE_TYPES[kind]]
    if tile_type not in expected:
        sys.exit(f"{archive} holds {tile_type} tiles; a {kind} archive needs {expected}")
    manifest = {
        "format": "snigf-map-manifest/1",
        "name": name,
        "file": Path(archive).name,
        "tileType": TILE_TYPES[kind],
        "attribution": attribution,
        "license": license_,
        "source": source,
        "date": date,
        "bounds": [round(v, 6) for v in h["bounds"]],
        "minzoom": h["minzoom"],
        "maxzoom": h["maxzoom"],
        "synthetic": synthetic == "true",
    }
    if details_file:
        details = []
        for d in json.loads(Path(details_file).read_text(encoding="utf-8")):
            dh = header(Path(archive).with_name(d["file"]))
            if dh.get("tile_type") not in expected:
                sys.exit(f"{d['file']} holds {dh.get('tile_type')} tiles")
            if not DATE.fullmatch(d["date"]):
                sys.exit(f"{d['file']}: date must be YYYY-MM-DD")
            details.append({"name": d["name"], "file": d["file"], "attribution": d["attribution"], "license": d["license"],
                            "source": d["source"], "date": d["date"], "bounds": [round(v, 6) for v in dh["bounds"]],
                            "minzoom": dh["minzoom"], "maxzoom": dh["maxzoom"]})
        manifest["details"] = details
    target = Path(archive).with_name("manifest.json")
    target.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) not in (9, 10):
        sys.exit(__doc__)
    main(*sys.argv[1:])
