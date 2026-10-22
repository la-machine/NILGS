#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Entry point of the e2e container: Chromium trusts the development CA through its own NSS
# database (certificate errors are never ignored), then Playwright runs the given project.
set -eu

CHAIN=/run/secrets/pki_ca_chain_crt
NSSDB="$HOME/.pki/nssdb"
[ -r "$CHAIN" ] || { echo "missing $CHAIN" >&2; exit 1; }

mkdir -p "$NSSDB" /tmp/ca
certutil -d "sql:$NSSDB" -N --empty-password
# Every certificate of the chain (intermediate and root) becomes a trust anchor for TLS.
awk '/BEGIN CERTIFICATE/ { n++ } n { print > ("/tmp/ca/" n ".pem") }' "$CHAIN"
for cert in /tmp/ca/*.pem; do
  certutil -d "sql:$NSSDB" -A -t "C,," -n "snigf-dev-$(basename "$cert" .pem)" -i "$cert"
done

mkdir -p /out
exec npx --no-install playwright test "$@"
