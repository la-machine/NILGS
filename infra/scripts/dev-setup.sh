#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Local setup for compose.yml, safe to re-run:
#   .env            copied from .env.example (settings, no secrets)
#   infra/secrets/  generated passwords and the development PKI (dev-pki.sh),
#                   mounted as Docker secrets
# Existing files are kept, so re-running never overwrites your settings or locks
# you out of an existing database volume. Both are git-ignored.
# Development and demo only: staging and production secrets are provisioned
# by the release manager, never by this script (Release plan §4).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="$ROOT/infra/secrets"

if [[ -f "$ROOT/.env" ]]; then
  echo "kept      .env"
  # Settings added to .env.example since .env was created are appended with their
  # documented value; existing values are never changed.
  [[ -z "$(tail -c1 "$ROOT/.env")" ]] || echo >> "$ROOT/.env"
  while IFS= read -r line; do
    key="${line%%=*}"
    if ! grep -q "^$key=" "$ROOT/.env"; then
      printf '%s\n' "$line" >> "$ROOT/.env"
      echo "added     .env: $key"
    fi
  done < <(grep -E '^SNIGF_[A-Z0-9_]+=' "$ROOT/.env.example" | tr -d '\r')
else
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "created   .env (from .env.example)"
fi

mkdir -p "$DIR"
chmod 700 "$DIR" 2>/dev/null || true

for name in db_superuser_password db_migrator_password db_app_password db_auditor_password; do
  if [[ -s "$DIR/$name" ]]; then
    echo "kept      infra/secrets/$name"
  else
    openssl rand -hex 24 > "$DIR/$name"
    echo "generated infra/secrets/$name"
  fi
  # A secret file holds exactly the secret: no trailing CR/LF (OpenSSL on Windows
  # ends its output with CRLF, which every consumer would otherwise have to strip).
  printf '%s' "$(tr -d '\r\n' < "$DIR/$name")" > "$DIR/$name.tmp" && mv "$DIR/$name.tmp" "$DIR/$name"
done

bash "$(dirname "$0")/dev-pki.sh" "$@"
