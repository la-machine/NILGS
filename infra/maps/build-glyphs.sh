#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Label glyphs for the geographic view (Noto Sans, SIL OFL 1.1), from the archive downloaded
# by maps-fetch into /sources, checked against its pinned SHA-256 again here, then extracted
# into the staged geographic directory. Runs without network.
#
#   build-glyphs.sh <sha256>
set -euo pipefail

expected="${1:?pinned SHA-256 of the glyph archive}"
archive="/sources/fonts/noto-sans.zip"
stage="/work/stage/geographic/fonts"
[[ -r "$archive" ]] || { echo "missing $archive: run the fetch step first" >&2; exit 1; }
echo "$expected  $archive" | sha256sum --check --quiet

rm -rf "$stage"; mkdir -p "$stage"
python3 - "$archive" "$stage" <<'EOF'
import re, sys, zipfile
from pathlib import Path
archive, stage = sys.argv[1], Path(sys.argv[2])
allowed = re.compile(r"^(Noto Sans (Regular|Bold|Italic))/(\d{1,5}-\d{1,5})\.pbf$")
count = 0
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        m = allowed.match(info.filename)
        if not m or info.file_size > 1_000_000:
            continue  # only glyph ranges, never a path of the archive's choosing
        target = stage / m.group(1) / f"{m.group(3)}.pbf"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(info))
        count += 1
print(f"{count} glyph ranges extracted")
EOF
