#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Provisioning job of the key-custody service (ADR-018), run by the `bao-init` service
# before the application starts. Idempotent; every run:
#
#   1. waits for OpenBao, initialises it on first run (Shamir: SNIGF_BAO_UNSEAL_SHARES
#      shares, SNIGF_BAO_UNSEAL_THRESHOLD needed), and unseals it if sealed;
#   2. if the provisioning inputs changed since the last run (or never ran): obtains a
#      root token, configures transit keys, the application policy and its certificate
#      login, then REVOKES the root token. No root or admin token is ever stored;
#      the next run generates a fresh one from a quorum of unseal shares.
#
# DEVELOPMENT AND DEMO CUSTODY: the unseal shares are files on the bao-keys volume,
# standing in for the custodians who hold one share each in staging and production,
# where this job runs without that volume and unsealing is a ceremony (docs/guides).
set -eu
umask 077

: "${SNIGF_BAO_PORT:?}" "${SNIGF_BAO_UNSEAL_SHARES:?}" "${SNIGF_BAO_UNSEAL_THRESHOLD:?}"
: "${SNIGF_BAO_TRANSIT_MOUNT:?}" "${SNIGF_BAO_AUTH_MOUNT:?}" "${SNIGF_BAO_APP_ROLE:?}"
: "${SNIGF_BAO_APP_CLIENT_CN:?}" "${SNIGF_BAO_TOKEN_TTL:?}" "${SNIGF_BAO_TOKEN_MAX_TTL:?}"
: "${SNIGF_BAO_INIT_TIMEOUT:?}"
: "${SNIGF_KEY_AUDIT_CHECKPOINT:?}" "${SNIGF_KEY_RECORD_SIGNING:?}" "${SNIGF_KEY_DATA_ENCRYPTION:?}"
: "${SNIGF_KEY_PAYMENT_CALLBACK:?}" "${SNIGF_KEY_PASSWORD_PEPPER:?}" "${SNIGF_KEY_BLIND_INDEX:?}"
: "${SNIGF_KEY_ROTATE_SIGNING:?}" "${SNIGF_KEY_ROTATE_DATA_ENCRYPTION:?}" "${SNIGF_KEY_ROTATE_PAYMENT_CALLBACK:?}"

ADDR="https://openbao:$SNIGF_BAO_PORT"
CA=/run/secrets/pki_ca_chain_crt
KEYS=/bao-keys
HEADERS=/tmp/token-header
TRANSIT="$SNIGF_BAO_TRANSIT_MOUNT"

log() { echo "bao-init: $*"; }
die() { echo "bao-init: ERROR: $*" >&2; exit 1; }

# api <METHOD> <path> [json]: JSON body on stdin, token from a 0600 file, never argv.
api() {
  if [ $# -ge 3 ]; then
    printf '%s' "$3" | curl -sS --fail-with-body --tlsv1.3 --cacert "$CA" \
      --cert /run/secrets/bao_admin_client_crt --key /run/secrets/bao_admin_client_key \
      -H "@$HEADERS" -H "Content-Type: application/json" -X "$1" --data-binary @- "$ADDR/v1/$2"
  else
    curl -sS --fail-with-body --tlsv1.3 --cacert "$CA" \
      --cert /run/secrets/bao_admin_client_crt --key /run/secrets/bao_admin_client_key \
      -H "@$HEADERS" -X "$1" "$ADDR/v1/$2"
  fi
}
# decode_token <encoded> <otp>: the generated root token is base64 (unpadded) of
# token XOR one-time password. Decoded locally: `bao operator generate-root -decode`
# calls an authenticated endpoint, and no token exists yet.
decode_token() {
  enc="$1"
  while [ $(( ${#enc} % 4 )) -ne 0 ]; do enc="$enc="; done
  printf '%s' "$enc" | base64 -d | od -An -v -tu1 | tr -s ' ' '\n' | grep -v '^$' > /tmp/gr.enc
  printf '%s' "$2" | od -An -v -tu1 | tr -s ' ' '\n' | grep -v '^$' > /tmp/gr.otp
  paste /tmp/gr.enc /tmp/gr.otp | awk '{ printf "%c", xor($1, $2) }'
  rm -f /tmp/gr.enc /tmp/gr.otp
}
set_token() { printf 'X-Vault-Token: %s\n' "$1" > "$HEADERS"; }
set_token ""
trap 'rm -f "$HEADERS"' EXIT

# --- 1. Reachable, initialised, unsealed --------------------------------------------
deadline=$(( $(date +%s) + SNIGF_BAO_INIT_TIMEOUT ))
until status="$(api GET sys/seal-status 2>/dev/null)"; do
  [ "$(date +%s)" -lt "$deadline" ] || die "OpenBao unreachable at $ADDR after ${SNIGF_BAO_INIT_TIMEOUT}s"
  sleep 1
done

ROOT_TOKEN=""
if [ "$(echo "$status" | jq -r .initialized)" != "true" ]; then
  [ "$SNIGF_BAO_UNSEAL_THRESHOLD" -le "$SNIGF_BAO_UNSEAL_SHARES" ] || die "threshold exceeds shares"
  out="$(api PUT sys/init "$(jq -n --argjson n "$SNIGF_BAO_UNSEAL_SHARES" --argjson t "$SNIGF_BAO_UNSEAL_THRESHOLD" \
    '{secret_shares: $n, secret_threshold: $t}')")"
  i=0
  for share in $(echo "$out" | jq -r '.keys_base64[]'); do
    i=$((i + 1)); printf '%s' "$share" > "$KEYS/unseal_share_$i"
  done
  ROOT_TOKEN="$(echo "$out" | jq -r .root_token)"
  rm -f "$KEYS/provisioned"
  log "initialised: $SNIGF_BAO_UNSEAL_SHARES unseal shares, threshold $SNIGF_BAO_UNSEAL_THRESHOLD"
  status="$(api GET sys/seal-status)"
fi

shares() { ls "$KEYS"/unseal_share_* 2>/dev/null | head -n "$SNIGF_BAO_UNSEAL_THRESHOLD"; }
[ "$(shares | wc -l)" -ge "$SNIGF_BAO_UNSEAL_THRESHOLD" ] || die "fewer than $SNIGF_BAO_UNSEAL_THRESHOLD unseal shares in $KEYS"

if [ "$(echo "$status" | jq -r .sealed)" = "true" ]; then
  for f in $(shares); do
    api PUT sys/unseal "$(jq -n --rawfile k "$f" '{key: $k}')" >/dev/null
  done
  [ "$(api GET sys/seal-status | jq -r .sealed)" = "false" ] || die "still sealed after $SNIGF_BAO_UNSEAL_THRESHOLD shares"
  log "unsealed"
fi
# Raft elects itself leader shortly after unsealing; wait until requests are served.
until api GET sys/health >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "$deadline" ] || die "OpenBao not active after unsealing"
  sleep 1
done

# --- 2. Provisioning, only when its inputs changed ----------------------------------
fingerprint="$( { cat /etc/snigf/bao-init.sh /etc/snigf/app-policy.hcl.template "$CA"; env | grep -E '^SNIGF_(BAO|KEY)_' | sort; } | sha256sum | cut -d' ' -f1)"
if [ "$(cat "$KEYS/provisioned" 2>/dev/null)" = "$fingerprint" ]; then
  log "provisioning up to date"
  exit 0
fi

if [ -z "$ROOT_TOKEN" ]; then
  # Generate a root token from a quorum of unseal shares (one-time password flow).
  api DELETE sys/generate-root/attempt >/dev/null 2>&1 || true
  attempt="$(api PUT sys/generate-root/attempt '{}')"
  nonce="$(echo "$attempt" | jq -r .nonce)"
  otp="$(echo "$attempt" | jq -r .otp)"
  for f in $(shares); do
    progress="$(api PUT sys/generate-root/update "$(jq -n --rawfile k "$f" --arg n "$nonce" '{key: $k, nonce: $n}')")"
  done
  [ "$(echo "$progress" | jq -r .complete)" = "true" ] || die "root token generation incomplete"
  ROOT_TOKEN="$(decode_token "$(echo "$progress" | jq -r .encoded_token)" "$otp")"
  [ -n "$ROOT_TOKEN" ] || die "could not decode the generated root token"
  log "temporary root token generated from $SNIGF_BAO_UNSEAL_THRESHOLD unseal shares"
fi
set_token "$ROOT_TOKEN"
ROOT_TOKEN=""
trap 'api POST auth/token/revoke-self >/dev/null 2>&1 || true; rm -f "$HEADERS"' EXIT

# Transit secrets engine.
if ! api GET sys/mounts | jq -e --arg m "$TRANSIT/" '.data[$m]' >/dev/null; then
  api POST "sys/mounts/$TRANSIT" '{"type":"transit","description":"SNIGF signing, data-key wrapping and HMAC keys"}' >/dev/null
  log "enabled transit at $TRANSIT/"
fi

# key <name> <type> <auto_rotate_period> [derived]
key() {
  name="$1" type="$2" rotate="$3" derived="${4:-false}"
  if existing="$(api GET "$TRANSIT/keys/$name" 2>/dev/null)"; then
    [ "$(echo "$existing" | jq -r .data.type)" = "$type" ] || die "key $name exists with another type"
  else
    body="$(jq -n --arg t "$type" --argjson d "$derived" \
      '{type: $t, derived: $d, exportable: false, allow_plaintext_backup: false}
       + (if $t == "hmac" then {key_size: 32} else {} end)')"
    api POST "$TRANSIT/keys/$name" "$body" >/dev/null
    log "created key $name ($type)"
  fi
  api POST "$TRANSIT/keys/$name/config" \
    "$(jq -n --arg r "$rotate" '{deletion_allowed: false, auto_rotate_period: $r}')" >/dev/null
}
key "$SNIGF_KEY_AUDIT_CHECKPOINT" ed25519      "$SNIGF_KEY_ROTATE_SIGNING"
key "$SNIGF_KEY_RECORD_SIGNING"   ed25519      "$SNIGF_KEY_ROTATE_SIGNING"
key "$SNIGF_KEY_DATA_ENCRYPTION"  aes256-gcm96 "$SNIGF_KEY_ROTATE_DATA_ENCRYPTION" true
key "$SNIGF_KEY_PAYMENT_CALLBACK" hmac         "$SNIGF_KEY_ROTATE_PAYMENT_CALLBACK"
# Rotating the pepper or the blind index invalidates stored values: manual only, with
# re-hashing or re-indexing (0 = no automatic rotation).
key "$SNIGF_KEY_PASSWORD_PEPPER"  hmac         0
key "$SNIGF_KEY_BLIND_INDEX"      hmac         0

# Application policy.
policy="$(sed -e "s/@TRANSIT@/$TRANSIT/g" \
  -e "s/@KEY_AUDIT_CHECKPOINT@/$SNIGF_KEY_AUDIT_CHECKPOINT/g" \
  -e "s/@KEY_RECORD_SIGNING@/$SNIGF_KEY_RECORD_SIGNING/g" \
  -e "s/@KEY_DATA_ENCRYPTION@/$SNIGF_KEY_DATA_ENCRYPTION/g" \
  -e "s/@KEY_PAYMENT_CALLBACK@/$SNIGF_KEY_PAYMENT_CALLBACK/g" \
  -e "s/@KEY_PASSWORD_PEPPER@/$SNIGF_KEY_PASSWORD_PEPPER/g" \
  -e "s/@KEY_BLIND_INDEX@/$SNIGF_KEY_BLIND_INDEX/g" \
  /etc/snigf/app-policy.hcl.template)"
echo "$policy" | grep -v '^#' | grep -q '@[A-Z_]*@' && die "unrendered placeholder in the application policy"
api PUT "sys/policies/acl/$SNIGF_BAO_APP_ROLE" "$(printf '%s' "$policy" | jq -Rs '{policy: .}')" >/dev/null

# Certificate login: the application proves possession of its TLS client key; only
# the configured common name, chained to the SNIGF CA, gets the application policy.
if ! api GET sys/auth | jq -e --arg m "$SNIGF_BAO_AUTH_MOUNT/" '.data[$m]' >/dev/null; then
  api POST "sys/auth/$SNIGF_BAO_AUTH_MOUNT" '{"type":"cert","description":"SNIGF service certificates"}' >/dev/null
  log "enabled certificate login at auth/$SNIGF_BAO_AUTH_MOUNT/"
fi
api POST "auth/$SNIGF_BAO_AUTH_MOUNT/certs/$SNIGF_BAO_APP_ROLE" "$(jq -n \
  --rawfile ca "$CA" --arg cn "$SNIGF_BAO_APP_CLIENT_CN" --arg p "$SNIGF_BAO_APP_ROLE" \
  --arg ttl "$SNIGF_BAO_TOKEN_TTL" --arg max "$SNIGF_BAO_TOKEN_MAX_TTL" \
  '{certificate: $ca, allowed_common_names: [$cn], token_policies: [$p],
    token_no_default_policy: true, token_type: "service",
    token_ttl: $ttl, token_max_ttl: $max}')" >/dev/null

api POST auth/token/revoke-self >/dev/null
api GET auth/token/lookup-self >/dev/null 2>&1 && die "the root token is still valid after revocation"
trap 'rm -f "$HEADERS"' EXIT
printf '%s' "$fingerprint" > "$KEYS/provisioned"
log "provisioned keys, policy and certificate login for $SNIGF_BAO_APP_CLIENT_CN; root token revoked"
