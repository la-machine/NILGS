#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Entry point of the `db` service: installs the TLS material with the ownership and
# permissions PostgreSQL requires (Docker secrets are bind mounts it cannot chmod),
# then hands over to the image's entrypoint with the hardened server settings.
# The same settings apply to the temporary server used for first-time initialisation.
set -eu

TLS=/run/snigf-tls
install -d -m 700 -o postgres -g postgres "$TLS"
install -m 600 -o postgres -g postgres /run/secrets/db_tls_key        "$TLS/server.key"
install -m 644 -o postgres -g postgres /run/secrets/db_tls_crt        "$TLS/server.crt"
install -m 644 -o postgres -g postgres /run/secrets/pki_ca_chain_crt  "$TLS/ca.crt"

exec docker-entrypoint.sh postgres \
  -c hba_file=/etc/snigf/pg_hba.conf \
  -c ssl=on \
  -c ssl_cert_file="$TLS/server.crt" \
  -c ssl_key_file="$TLS/server.key" \
  -c ssl_ca_file="$TLS/ca.crt" \
  -c ssl_min_protocol_version=TLSv1.3 \
  -c password_encryption=scram-sha-256 \
  -c log_connections="${SNIGF_DB_LOG_CONNECTIONS:?set in .env}" \
  -c log_disconnections="${SNIGF_DB_LOG_CONNECTIONS}" \
  "$@"
