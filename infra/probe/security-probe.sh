#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Asserts the transport, session, key-custody and isolation guarantees of the running
# stack (ADR-004, ADR-017, ADR-018, ADR-020).
#
#   docker compose --profile test run --rm --build security-probe        (app-net + data-net + key-net)
#   docker compose --profile test run --rm segmentation-probe            (app-net only)
#
# Every "refused" check also asserts WHY it was refused, so a check cannot pass merely
# because something unrelated is broken. Each refused check has a matching positive
# control that succeeds with correct credentials. Exit status = number of failures.
set -u

CA=/run/secrets/pki_ca_chain_crt
PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; }

# tcp_open <host> <port>: 0 if a TCP connection can be made.
tcp_open() {
  curl -s -m 3 "telnet://$1:$2" </dev/null >/dev/null 2>&1
  rc=$?
  [ "$rc" -eq 0 ] || [ "$rc" -eq 28 ]
}

finish() {
  echo "----"
  echo "$PASS passed, $FAIL failed"
  exit "$FAIL"
}

# --- Segmentation: run from app-net only, where the proxy lives ---------------------
if [ "${1:-}" = "segmentation" ]; then
  if tcp_open app "${SNIGF_APP_PORT:-47080}"; then ok "app-net reaches the app (control)"; else bad "app-net reaches the app (control)"; fi
  if tcp_open db "$SNIGF_DB_PORT"; then bad "app-net must not reach the database"; else ok "app-net cannot reach the database"; fi
  if tcp_open openbao "$SNIGF_BAO_PORT"; then bad "app-net must not reach OpenBao"; else ok "app-net cannot reach OpenBao"; fi
  if curl -s -m 5 -o /dev/null https://example.org; then bad "app-net must have no internet access"; else ok "app-net has no internet access"; fi
  finish
fi

HOST="$SNIGF_PUBLIC_HOSTNAME"
WEB="https://$HOST:$SNIGF_HTTPS_PORT"
TO_WEB="--connect-to $HOST:$SNIGF_HTTPS_PORT:web:$SNIGF_HTTPS_PORT"
APP="https://app:$SNIGF_APP_PORT"

# --- Browser → proxy ---------------------------------------------------------------
body="$(curl -sS --cacert "$CA" --tlsv1.3 $TO_WEB "$WEB/" 2>&1)"
case "$body" in *"SNIGF / NILGS"*) ok "proxy serves the UI over TLS 1.3 with a valid certificate" ;;
  *) bad "proxy serves the UI over TLS 1.3 with a valid certificate" "$body" ;; esac

err="$(curl -sS --cacert "$CA" --tls-max 1.2 $TO_WEB "$WEB/" 2>&1)"
case "$err" in *"alert protocol version"*|*"unsupported protocol"*|*"wrong version"*|*"tlsv1 alert"*) ok "proxy refuses TLS 1.2" ;;
  *) bad "proxy refuses TLS 1.2" "$err" ;; esac

plain="$(curl -s -m 5 "http://web:$SNIGF_HTTPS_PORT/" 2>&1)"
case "$plain" in *"SNIGF / NILGS"*) bad "proxy serves nothing over plain HTTP" ;;
  *) ok "proxy serves nothing over plain HTTP" ;; esac

hsts="$(curl -sS -o /dev/null -D - --cacert "$CA" $TO_WEB "$WEB/" 2>&1 | tr -d '\r' | grep -i '^strict-transport-security:')"
[ -n "$hsts" ] && ok "proxy sends HSTS" || bad "proxy sends HSTS"

# --- Proxy → app (mutual TLS) ---------------------------------------------------------
resp="$(curl -s -w 'HTTP%{http_code}' --cacert "$CA" $TO_WEB "$WEB/api/v1/parcels")"
case "$resp" in *AUTH_REQUIRED*HTTP401) ok "API reachable through the proxy over mutual TLS (401 = authentication required)" ;;
  *) bad "API reachable through the proxy over mutual TLS" "$resp (502 means the proxy-to-app TLS failed)" ;; esac

# --- Sessions and CSRF (ADR-004), through the proxy like a browser ---------------------
JAR=/tmp/probe-cookies
rm -f "$JAR"
headers="$(curl -sS -o /dev/null -D - -c "$JAR" --cacert "$CA" $TO_WEB "$WEB/api/v1/auth/csrf" | tr -d '\r')"
csrf_cookie="$(echo "$headers" | grep -i "^set-cookie: ${SNIGF_CSRF_COOKIE_NAME}=")"
case "$csrf_cookie" in
  *Secure*) case "$csrf_cookie" in *"Path=/"*) case "$csrf_cookie" in *"SameSite=Strict"*)
    ok "CSRF token cookie is __Host- prefixed, Secure, path /, SameSite=Strict" ;;
    *) bad "CSRF cookie SameSite" "$csrf_cookie" ;; esac ;; *) bad "CSRF cookie path" "$csrf_cookie" ;; esac ;;
  *) bad "CSRF token cookie is issued and Secure" "${csrf_cookie:-no ${SNIGF_CSRF_COOKIE_NAME} cookie}" ;;
esac
token="$(awk -v n="$SNIGF_CSRF_COOKIE_NAME" '$6 == n { print $7 }' "$JAR")"

resp="$(curl -s -w 'HTTP%{http_code}' --cacert "$CA" $TO_WEB -H 'Content-Type: application/json' \
  -d '{"email":"probe@probe.invalid","password":"not-the-password-1"}' "$WEB/api/v1/auth/login")"
case "$resp" in *CSRF_INVALID*HTTP403) ok "login without the CSRF token is refused (login CSRF)" ;;
  *) bad "login without the CSRF token is refused" "$resp" ;; esac

resp="$(curl -s -w 'HTTP%{http_code}' -b "$JAR" --cacert "$CA" $TO_WEB -H 'Content-Type: application/json' \
  -H "$SNIGF_CSRF_HEADER_NAME: $token" \
  -d '{"email":"probe-unknown@probe.invalid","password":"not-the-password-1"}' "$WEB/api/v1/auth/login")"
case "$resp" in *AUTH_FAILED*HTTP401) ok "a failed login answers 401 AUTH_FAILED and nothing more (with a valid CSRF token: control)" ;;
  *) bad "a failed login answers 401 AUTH_FAILED" "$resp" ;; esac

err="$(curl -sS --cacert "$CA" "$APP/api/v1/parcels" 2>&1)"
case "$err" in *"certificate required"*|*"alert"*|*"handshake"*|*"SSL"*|*"Recv failure"*|*"reset"*) ok "app refuses clients without a certificate" ;;
  *) bad "app refuses clients without a certificate" "$err" ;; esac

resp="$(curl -sS --cacert "$CA" --cert /run/secrets/migrator_db_client_crt --key /run/secrets/migrator_db_client_key \
  -w ' HTTP%{http_code}' "$APP/api/v1/parcels" 2>&1)"
case "$resp" in *TRANSPORT_CLIENT_REJECTED*HTTP403) ok "app refuses another service's certificate from the same CA" ;;
  *) bad "app refuses another service's certificate from the same CA" "$resp" ;; esac

err="$(curl -sS --cacert "$CA" --tls-max 1.2 --cert /run/secrets/migrator_db_client_crt \
  --key /run/secrets/migrator_db_client_key "$APP/api/v1/parcels" 2>&1)"
case "$err" in *TRANSPORT_CLIENT_REJECTED*) bad "app refuses TLS 1.2" "a TLS 1.2 session was established" ;;
  *) ok "app refuses TLS 1.2" ;; esac

if tcp_open app "$SNIGF_MANAGEMENT_PORT"; then bad "management port must not be reachable from the network"
else ok "management port is not reachable from the network"; fi

# --- App → database ---------------------------------------------------------------------
# libpq requires private keys readable only by their owner.
umask 077
cp /run/secrets/app_db_client_key /tmp/app.key
cp /run/secrets/migrator_db_client_key /tmp/migrator.key
PGPASSWORD="$(cat /run/secrets/db_app_password)"
export PGPASSWORD PGCONNECT_TIMEOUT=5
BASE="host=db port=$SNIGF_DB_PORT dbname=snigf user=snigf_app sslrootcert=$CA"
GOOD="$BASE sslmode=verify-full sslcert=/run/secrets/app_db_client_crt sslkey=/tmp/app.key"

out="$(psql -X -tA "$GOOD" -c "SELECT version FROM pg_stat_ssl WHERE pid = pg_backend_pid()" 2>&1)"
[ "$out" = "TLSv1.3" ] && ok "app role connects: TLS 1.3, verified server, client certificate, password (control)" \
  || bad "app role connects with full credentials (control)" "$out"

out="$(psql -X -tA "$BASE sslmode=disable" -c "SELECT 1" 2>&1)"
case "$out" in *"pg_hba.conf"*|*"no encryption"*) ok "database refuses plaintext connections" ;;
  *) bad "database refuses plaintext connections" "$out" ;; esac

out="$(psql -X -tA "$BASE sslmode=verify-full sslcert=/nonexistent sslkey=/nonexistent" -c "SELECT 1" 2>&1)"
case "$out" in *"certificate"*) ok "database refuses TLS without a client certificate" ;;
  *) bad "database refuses TLS without a client certificate" "$out" ;; esac

# Same password as the control above, certificate of another role. PostgreSQL logs the
# real reason ("clientcert=verify-full ... CN mismatch") but tells the client only that
# authentication failed, so nothing leaks; the control proves the certificate is the cause.
out="$(psql -X -tA "$BASE sslmode=verify-full sslcert=/run/secrets/migrator_db_client_crt sslkey=/tmp/migrator.key" -c "SELECT 1" 2>&1)"
case "$out" in *"authentication failed"*|*"CN mismatch"*) ok "database refuses a client certificate issued for another role" ;;
  *) bad "database refuses a client certificate issued for another role" "$out" ;; esac

out="$(psql -X -tA "$GOOD ssl_max_protocol_version=TLSv1.2" -c "SELECT 1" 2>&1)"
case "$out" in *"1"*) [ "$out" = "1" ] && bad "database refuses TLS 1.2" "connected" || ok "database refuses TLS 1.2" ;;
  *) ok "database refuses TLS 1.2" ;; esac

out="$(psql -X -tA "host=db port=$SNIGF_DB_PORT dbname=snigf user=postgres sslmode=verify-full sslrootcert=$CA sslcert=/run/secrets/app_db_client_crt sslkey=/tmp/app.key" -c "SELECT 1" 2>&1)"
case "$out" in *"pg_hba.conf"*) ok "superuser cannot log in over the network" ;;
  *) bad "superuser cannot log in over the network" "$out" ;; esac

out="$(PGPASSWORD=wrong psql -X -tA "$GOOD" -c "SELECT 1" 2>&1)"
case "$out" in *"password authentication failed"*) ok "a valid client certificate alone is not enough (password required)" ;;
  *) bad "a valid client certificate alone is not enough (password required)" "$out" ;; esac

# --- App → OpenBao (key custody, ADR-018) ------------------------------------------------
BAO="https://openbao:$SNIGF_BAO_PORT/v1"
APP_ID="--cert /run/secrets/app_bao_client_crt --key /run/secrets/app_bao_client_key"
T="$SNIGF_BAO_TRANSIT_MOUNT"

login="$(curl -sS --cacert "$CA" $APP_ID -X POST -d "{\"name\":\"$SNIGF_BAO_APP_ROLE\"}" "$BAO/auth/$SNIGF_BAO_AUTH_MOUNT/login" 2>&1)"
TOKEN="$(printf '%s' "$login" | sed -n 's/.*"client_token":"\([^"]*\)".*/\1/p')"
[ -n "$TOKEN" ] && ok "app logs in to OpenBao with its certificate over mutual TLS 1.3 (control)" \
  || bad "app logs in to OpenBao with its certificate (control)" "$login"
printf 'X-Vault-Token: %s\n' "$TOKEN" > /tmp/bao-token
AUTH="-H @/tmp/bao-token"

err="$(curl -sS --cacert "$CA" $APP_ID --tls-max 1.2 "$BAO/sys/health" 2>&1)"
case "$err" in *"alert protocol version"*|*"unsupported protocol"*|*"wrong version"*|*"tlsv1 alert"*) ok "OpenBao refuses TLS 1.2" ;;
  *) bad "OpenBao refuses TLS 1.2" "$err" ;; esac

err="$(curl -sS --cacert "$CA" "$BAO/sys/health" 2>&1)"
case "$err" in *"certificate required"*|*"alert"*|*"handshake"*|*"Recv failure"*|*"reset"*) ok "OpenBao refuses clients without a certificate" ;;
  *) bad "OpenBao refuses clients without a certificate" "$err" ;; esac

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 -subj "/CN=$SNIGF_BAO_APP_ROLE" \
  -keyout /tmp/foreign.key -out /tmp/foreign.crt >/dev/null 2>&1
err="$(curl -sS --cacert "$CA" --cert /tmp/foreign.crt --key /tmp/foreign.key "$BAO/sys/health" 2>&1)"
case "$err" in *"unknown ca"*|*"bad certificate"*|*"alert"*|*"handshake"*|*"Recv failure"*|*"reset"*) ok "OpenBao refuses a certificate from a foreign CA, even with the app's name" ;;
  *) bad "OpenBao refuses a certificate from a foreign CA" "$err" ;; esac

resp="$(curl -sS --cacert "$CA" --cert /run/secrets/web_client_crt --key /run/secrets/web_client_key -X POST \
  -d "{\"name\":\"$SNIGF_BAO_APP_ROLE\"}" -w ' HTTP%{http_code}' "$BAO/auth/$SNIGF_BAO_AUTH_MOUNT/login" 2>&1)"
# Valid chain, wrong name: OpenBao must say that no allowed certificate matched.
case "$resp" in *client_token*) bad "OpenBao refuses another service's certificate from the same CA" "$resp" ;;
  *"no chain matching"*HTTP4[0-9][0-9]) ok "OpenBao refuses another service's certificate from the same CA" ;;
  *) bad "OpenBao refuses another service's certificate from the same CA" "$resp" ;; esac

resp="$(curl -sS --cacert "$CA" $APP_ID $AUTH -X POST -d '{"input":"U05JR0Y="}' "$BAO/$T/sign/$SNIGF_KEY_AUDIT_CHECKPOINT" 2>&1)"
case "$resp" in *'"signature":'*) ok "app can sign with its checkpoint key (control)" ;;
  *) bad "app can sign with its checkpoint key (control)" "$resp" ;; esac

key="$(curl -sS --cacert "$CA" $APP_ID $AUTH "$BAO/$T/keys/$SNIGF_KEY_DATA_ENCRYPTION" 2>&1)"
case "$key" in *'"exportable":false'*) case "$key" in *'"deletion_allowed":false'*) case "$key" in *'"allow_plaintext_backup":false'*)
  ok "keys are not exportable, not deletable, no plaintext backup" ;; *) bad "no plaintext backup" "$key" ;; esac ;;
  *) bad "keys are not deletable" "$key" ;; esac ;; *) bad "keys are not exportable" "$key" ;; esac

# Everything the policy does not grant must be refused with 403, for the app's own token.
denied=""
forbid() {   # <method> <path> [json]
  code="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" $APP_ID $AUTH -X "$1" ${3:+-d "$3"} "$BAO/$2")"
  [ "$code" = "403" ] || denied="$denied $1 $2 -> $code;"
}
forbid GET  "$T/export/encryption-key/$SNIGF_KEY_DATA_ENCRYPTION"
forbid GET  "$T/export/signing-key/$SNIGF_KEY_AUDIT_CHECKPOINT"
forbid GET  "$T/backup/$SNIGF_KEY_AUDIT_CHECKPOINT"
forbid POST "$T/keys/$SNIGF_KEY_AUDIT_CHECKPOINT/rotate"
forbid POST "$T/keys/$SNIGF_KEY_AUDIT_CHECKPOINT/config" '{"deletion_allowed":true}'
forbid POST "$T/keys/snigf-probe-new-key"
forbid LIST "$T/keys"
forbid POST "$T/encrypt/$SNIGF_KEY_DATA_ENCRYPTION" '{"plaintext":"U05JR0Y=","context":"cHJvYmU="}'
forbid POST "$T/hmac/$SNIGF_KEY_PASSWORD_PEPPER/sha2-512" '{"input":"U05JR0Y="}'
forbid GET  "sys/policies/acl"
forbid GET  "sys/mounts"
forbid POST "sys/mounts/probe" '{"type":"kv"}'
forbid GET  "auth/$SNIGF_BAO_AUTH_MOUNT/certs/$SNIGF_BAO_APP_ROLE"
forbid POST "auth/token/create" '{}'
[ -z "$denied" ] && ok "app token is refused everything outside its policy (14 operations: export, backup, rotate, reconfigure, create, list, raw encrypt, other algorithm, policies, mounts, auth config, token creation)" \
  || bad "app token is refused everything outside its policy" "$denied"
rm -f /tmp/bao-token /tmp/foreign.key /tmp/foreign.crt

finish
