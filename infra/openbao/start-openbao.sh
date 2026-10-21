#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Entry point of the `openbao` service: renders the configuration from .env values
# (OpenBao's HCL cannot read environment variables) and starts the server.
set -eu

: "${SNIGF_BAO_PORT:?set in .env}" "${SNIGF_BAO_CLUSTER_PORT:?set in .env}" "${SNIGF_BAO_LOG_LEVEL:?set in .env}"

CONFIG=/run/snigf-bao/openbao.hcl
sed -e "s/@BAO_PORT@/$SNIGF_BAO_PORT/g" \
    -e "s/@BAO_CLUSTER_PORT@/$SNIGF_BAO_CLUSTER_PORT/g" \
    -e "s/@BAO_LOG_LEVEL@/$SNIGF_BAO_LOG_LEVEL/g" \
    /etc/snigf/openbao.hcl.template > "$CONFIG"
if grep -v '^#' "$CONFIG" | grep -q '@[A-Z_]*@'; then
  echo "unrendered placeholder in $CONFIG" >&2
  exit 1
fi

# No core dumps: they would contain key material.
ulimit -c 0
exec bao server -config="$CONFIG"
