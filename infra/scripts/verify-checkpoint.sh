#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
#
# Verifies signed audit checkpoints offline, with bash and OpenSSL 3 only: nothing from
# the application, its database or OpenBao is involved (docs/audit.md, ADR-007).
#
#   bash infra/scripts/verify-checkpoint.sh <checkpoint-keys.json> <checkpoint-N.json>...
#
# The keys file must be the auditor's own pinned copy, taken from a trusted first
# download, never the copy that sits next to the checkpoints: whoever can replace both can
# forge both. For each file, prints "VALID <pos> <head hash>" or "INVALID <file>: <reason>".
# Exit status: 0 when every checkpoint is valid, 1 otherwise, 2 on usage errors.
#
# To compare a valid checkpoint with the live chain, run as the auditor role:
#   SELECT encode(hash, 'hex') FROM snigf.audit_event WHERE chain_pos = <pos>;
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <checkpoint-keys.json> <checkpoint-N.json>..." >&2
  exit 2
fi
command -v openssl >/dev/null || { echo "openssl 3 is required" >&2; exit 2; }

KEYS="$1"
shift
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Reads one top-level field written by the application (one "key" : value per line).
field() {
  sed -n -E "s/^  \"$2\" : \"?([^\",]*)\"?,?\r?$/\1/p" "$1" | head -n 1
}

# <UTF-8 byte length>:<value>, as audit_field in V3__audit.sql.
encode() {
  printf '%s:%s' "$(printf '%s' "$1" | LC_ALL=C wc -c | tr -d ' ')" "$1"
}

# The PEM public key of one key version, from the keys file.
pem_for_version() {
  awk -v want="$2" '
    /"version" :/ { gsub(/[^0-9]/, "", $0); current = $0 }
    /"pem" :/ && current == want {
      sub(/^[^"]*"pem" : "/, ""); sub(/",?\r?$/, ""); gsub(/\\n/, "\n"); printf "%s", $0; exit
    }' "$1"
}

status=0
for file in "$@"; do
  id="$(field "$file" id)"
  pos="$(field "$file" chainPos)"
  head="$(field "$file" headHash)"
  created="$(field "$file" createdAt)"
  version="$(field "$file" keyVersion)"
  signature="$(field "$file" signature)"
  format="$(field "$file" format)"
  if [[ "$format" != "snigf-audit-checkpoint-v1" || -z "$id" || -z "$pos" || -z "$head" || -z "$created" \
        || -z "$version" || -z "$signature" ]]; then
    echo "INVALID $file: not a snigf-audit-checkpoint-v1 document"; status=1; continue
  fi
  name="$(basename "$file")"
  if [[ "$name" != "checkpoint-$pos.json" ]]; then
    echo "INVALID $file: file name does not match chainPos $pos"; status=1; continue
  fi
  pem_for_version "$KEYS" "$version" > "$WORK/key.pem"
  if [[ ! -s "$WORK/key.pem" ]]; then
    echo "INVALID $file: key version $version is not in $KEYS"; status=1; continue
  fi
  printf '%s' "snigf-audit-checkpoint-v1|$(encode "$id")|$(encode "$pos")|$(encode "$head")|$(encode "$created")" \
    > "$WORK/payload"
  printf '%s' "$signature" | openssl base64 -d -A > "$WORK/signature"
  if openssl pkeyutl -verify -pubin -inkey "$WORK/key.pem" -rawin -in "$WORK/payload" \
       -sigfile "$WORK/signature" >/dev/null 2>&1; then
    echo "VALID $pos $head"
  else
    echo "INVALID $file: signature does not verify"; status=1
  fi
done
exit "$status"
