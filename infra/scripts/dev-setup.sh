#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Local setup for compose.yml, safe to re-run:
#   .env            copied from .env.example (settings, no secrets)
#   infra/secrets/  generated passwords, mounted as Docker secrets
# Existing files are kept, so re-running never overwrites your settings or locks
# you out of an existing database volume. Both are git-ignored.
# Development and demo only: staging and production secrets are provisioned
# by the release manager, never by this script (Release plan §4).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="$ROOT/infra/secrets"

if [[ -f "$ROOT/.env" ]]; then
  echo "kept      .env"
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
    (umask 077; openssl rand -hex 24 | tr -d '\n' > "$DIR/$name")
    echo "generated infra/secrets/$name"
  fi
done
