#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Publishes a staged map directory into the map-data volume (ADR-029), the only writer of that
# volume. Runs as root with no capability but CHOWN, without network.
#
#   install.sh prepare             give the builders (uid 10003) the sources and work volumes
#   install.sh <kind>              publish /work/stage/<kind> as /maps/<kind>
#   install.sh <kind> --if-absent  the same, unless that view already has data (never replaces
#                                  real data with synthetic test data)
#   install.sh status              list what is installed
#
# The new directory is copied beside the old one, then swapped in by rename, so the
# application never reads a half-written archive. Files end up root-owned and read-only.
set -euo pipefail

case "${1:?prepare, status or a kind}" in
  prepare)
    chown 10003:10003 /sources /work
    exit 0 ;;
  status)
    for kind in geographic satellite; do
      if [[ -f "/maps/$kind/manifest.json" ]]; then echo "$kind: $(tr -d '\n' < "/maps/$kind/manifest.json")"
      else echo "$kind: not installed"; fi
    done
    exit 0 ;;
  geographic|satellite) kind="$1" ;;
  *) echo "unknown kind $1" >&2; exit 2 ;;
esac

if [[ "${2:-}" == "--if-absent" && -f "/maps/$kind/manifest.json" ]]; then
  echo "$kind already installed; left unchanged"
  exit 0
fi
stage="/work/stage/$kind"
[[ -f "$stage/manifest.json" && -f "$stage/tiles.pmtiles" ]] || { echo "nothing staged in $stage" >&2; exit 1; }

rm -rf "/maps/.$kind.new" "/maps/.$kind.old"
cp -R "$stage" "/maps/.$kind.new"
chown -R 0:0 "/maps/.$kind.new"
find "/maps/.$kind.new" -type d -exec chmod 0755 {} +
find "/maps/.$kind.new" -type f -exec chmod 0444 {} +
[[ -d "/maps/$kind" ]] && mv "/maps/$kind" "/maps/.$kind.old"
mv "/maps/.$kind.new" "/maps/$kind"
rm -rf "/maps/.$kind.old"
echo "$kind installed: $(tr -d '\n' < "/maps/$kind/manifest.json")"
