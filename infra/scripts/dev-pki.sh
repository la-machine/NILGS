#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Development PKI (ADR-017): root CA -> intermediate CA -> service certificates.
#
#   infra/scripts/dev-pki.sh            create what is missing, renew what expires soon
#   infra/scripts/dev-pki.sh --rotate   re-issue every service certificate now
#
# Settings come from .env (SNIGF_PKI_*, SNIGF_PUBLIC_HOSTNAME, SNIGF_*_CLIENT_CN).
# Output, all git-ignored:
#   infra/secrets/pki/          CA private keys and working files; never mounted
#   infra/secrets/<name>        certificates and service keys, mounted as Docker secrets
#
# DEVELOPMENT AND DEMO ONLY. Staging and production certificates come from a root
# whose key is held offline by the release manager, never from this script.
set -euo pipefail
# Git Bash on Windows rewrites arguments that look like paths; exclude the
# certificate subjects ("/O=.../CN=..."). No effect on Linux or macOS.
export MSYS2_ARG_CONV_EXCL="/O="

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SECRETS="$ROOT/infra/secrets"
PKI="$SECRETS/pki"

# Reads SNIGF_* settings from .env as data, never as shell code (sourcing it would run
# any command a line contained, and breaks on values with spaces such as cron
# expressions). Same rules as Compose: matching outer quotes are removed, and a variable
# already set in the environment wins.
load_env() {
  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^(SNIGF_[A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
      value="${BASH_REMATCH[1]}"
    fi
    [[ -n "${!key+set}" ]] || export "$key=$value"
  done < "$1"
}
if [[ -f "$ROOT/.env" ]]; then
  load_env "$ROOT/.env"
fi
: "${SNIGF_PKI_ORGANIZATION:?set in .env}" "${SNIGF_PKI_ROOT_DAYS:?set in .env}"
: "${SNIGF_PKI_INTERMEDIATE_DAYS:?set in .env}" "${SNIGF_PKI_LEAF_DAYS:?set in .env}"
: "${SNIGF_PKI_RENEW_BEFORE_DAYS:?set in .env}" "${SNIGF_PUBLIC_HOSTNAME:?set in .env}"
: "${SNIGF_PROXY_CLIENT_CN:?set in .env}" "${SNIGF_BAO_APP_CLIENT_CN:?set in .env}"
: "${SNIGF_BAO_ADMIN_CLIENT_CN:?set in .env}" "${SNIGF_BAO_HEALTH_CLIENT_CN:?set in .env}"
: "${SNIGF_PAYMENT_SIM_CLIENT_CN:?set in .env}" "${SNIGF_PAYMENT_APP_CLIENT_CN:?set in .env}"

ROTATE=false
[[ "${1:-}" == "--rotate" ]] && ROTATE=true

mkdir -p "$PKI"
chmod 700 "$SECRETS" "$PKI" 2>/dev/null || true
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

serial() { echo "0x$(openssl rand -hex 16)"; }

new_key() {   # path curve
  openssl genpkey -algorithm EC -pkeyopt "ec_paramgen_curve:$2" -out "$1" 2>/dev/null
  chmod 600 "$1" 2>/dev/null || true
}

# --- Root CA (P-384) ----------------------------------------------------------
NEW_CA=false
if [[ ! -s "$PKI/root_ca.key" || ! -s "$PKI/root_ca.crt" ]]; then
  new_key "$PKI/root_ca.key" P-384
  openssl req -x509 -new -key "$PKI/root_ca.key" -sha384 -days "$SNIGF_PKI_ROOT_DAYS" \
    -set_serial "$(serial)" \
    -subj "/O=$SNIGF_PKI_ORGANIZATION/CN=SNIGF Development Root CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    -out "$PKI/root_ca.crt"
  echo "created   root CA (valid $SNIGF_PKI_ROOT_DAYS days)"
  NEW_CA=true
fi

# --- Intermediate CA (P-384, may only sign end-entity certificates) -------------
if $NEW_CA || [[ ! -s "$PKI/intermediate_ca.key" || ! -s "$PKI/intermediate_ca.crt" ]]; then
  new_key "$PKI/intermediate_ca.key" P-384
  openssl req -new -key "$PKI/intermediate_ca.key" \
    -subj "/O=$SNIGF_PKI_ORGANIZATION/CN=SNIGF Development Services CA" -out "$WORK/int.csr"
  cat > "$WORK/int.ext" <<EOF
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
EOF
  openssl x509 -req -in "$WORK/int.csr" -CA "$PKI/root_ca.crt" -CAkey "$PKI/root_ca.key" \
    -set_serial "$(serial)" -days "$SNIGF_PKI_INTERMEDIATE_DAYS" -sha384 \
    -extfile "$WORK/int.ext" -out "$PKI/intermediate_ca.crt" 2>/dev/null
  echo "created   intermediate CA (valid $SNIGF_PKI_INTERMEDIATE_DAYS days)"
  ROTATE=true   # every service certificate must chain to the new intermediate
fi

cp "$PKI/root_ca.crt" "$SECRETS/pki_root_ca_crt"
cat "$PKI/intermediate_ca.crt" "$PKI/root_ca.crt" > "$SECRETS/pki_ca_chain_crt"

# --- Service certificates (P-256) ----------------------------------------------
# issue <name> <server|client> <common name> [subjectAltName]
#   server: <name>_crt (leaf + intermediate) and <name>_key (PKCS#8 PEM)
#   client: <name>_crt and <name>_key; JDBC clients also get <name>_key_pk8 (DER)
issue() {
  local name="$1" kind="$2" cn="$3" san="${4:-}"
  local crt="$SECRETS/${name}_crt" key="$SECRETS/${name}_key"
  if ! $ROTATE && [[ -s "$crt" && -s "$key" ]] \
     && openssl verify -CAfile "$PKI/root_ca.crt" -untrusted "$PKI/intermediate_ca.crt" "$crt" >/dev/null 2>&1 \
     && openssl x509 -in "$crt" -noout -checkend $((SNIGF_PKI_RENEW_BEFORE_DAYS * 86400)) >/dev/null; then
    echo "kept      $name ($cn)"
    return
  fi
  new_key "$WORK/$name.key" P-256
  openssl req -new -key "$WORK/$name.key" -subj "/O=$SNIGF_PKI_ORGANIZATION/CN=$cn" -out "$WORK/$name.csr"
  {
    echo "basicConstraints=critical,CA:FALSE"
    echo "keyUsage=critical,digitalSignature"
    if [[ "$kind" == server ]]; then echo "extendedKeyUsage=serverAuth"; else echo "extendedKeyUsage=clientAuth"; fi
    echo "subjectKeyIdentifier=hash"
    echo "authorityKeyIdentifier=keyid"
    [[ -n "$san" ]] && echo "subjectAltName=$san"
  } > "$WORK/$name.ext"
  openssl x509 -req -in "$WORK/$name.csr" -CA "$PKI/intermediate_ca.crt" -CAkey "$PKI/intermediate_ca.key" \
    -set_serial "$(serial)" -days "$SNIGF_PKI_LEAF_DAYS" -sha256 \
    -extfile "$WORK/$name.ext" -out "$WORK/$name.crt" 2>/dev/null
  cat "$WORK/$name.crt" "$PKI/intermediate_ca.crt" > "$crt"
  cp "$WORK/$name.key" "$key"
  if [[ "$kind" == client ]]; then
    openssl pkcs8 -topk8 -nocrypt -in "$WORK/$name.key" -outform DER -out "${key}_pk8"
  fi
  echo "issued    $name ($cn, valid $SNIGF_PKI_LEAF_DAYS days)"
}

issue web_tls            server "$SNIGF_PUBLIC_HOSTNAME" "DNS:$SNIGF_PUBLIC_HOSTNAME,IP:127.0.0.1"
issue web_client         client "$SNIGF_PROXY_CLIENT_CN"
issue app_tls            server app  "DNS:app,DNS:localhost,IP:127.0.0.1"
issue db_tls             server db   "DNS:db,DNS:localhost,IP:127.0.0.1"
issue app_db_client      client snigf_app
issue migrator_db_client client snigf_migrator
# Key custody (ADR-018): OpenBao's server identity; the application's login identity;
# the provisioning job's and the health check's transport identities (no login role).
issue bao_tls            server openbao "DNS:openbao,DNS:localhost,IP:127.0.0.1"
issue app_bao_client     client "$SNIGF_BAO_APP_CLIENT_CN"
issue bao_admin_client   client "$SNIGF_BAO_ADMIN_CLIENT_CN"
issue bao_health_client  client "$SNIGF_BAO_HEALTH_CLIENT_CN"
# Simulated payment (ADR-037): the proxy's provider listener ("web" on provider-in); the
# simulator's server identity and its client identity (towards the proxy and OpenBao); the
# application's identity towards the simulator.
issue web_provider_tls     server web         "DNS:web"
issue payment_sim_tls      server payment-sim "DNS:payment-sim,DNS:localhost,IP:127.0.0.1"
issue payment_sim_client   client "$SNIGF_PAYMENT_SIM_CLIENT_CN"
issue app_provider_client  client "$SNIGF_PAYMENT_APP_CLIENT_CN"

# Docker Compose (outside Swarm) mounts secret files with their host permissions, so
# containers running as non-root users must be able to read them. The directory itself
# stays private to the developer (700); the database copies its key to a 600 file.
find "$SECRETS" -maxdepth 1 -type f -exec chmod 644 {} + 2>/dev/null || true

# Windows: POSIX modes are not enforced, and new files inherit ACLs that let every
# administrator read them. Private keys are restricted to the current user only (the
# PostgreSQL driver refuses anything wider, and so should we). Docker Desktop reads
# them as the same user.
if command -v icacls >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
  for key in "$SECRETS"/*_key "$SECRETS"/*_key_pk8 "$PKI"/*.key; do
    [[ -f "$key" ]] || continue
    MSYS2_ARG_CONV_EXCL="*" icacls "$(cygpath -w "$key")" /inheritance:r /grant:r "${USERNAME}:(F)" >/dev/null
  done
  echo "restricted private keys to ${USERNAME} (Windows ACL)"
fi
