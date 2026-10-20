#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Runs once from /docker-entrypoint-initdb.d when the data directory is empty:
# applies infra/db/init/01-roles.sql as the superuser, with role passwords read
# from Docker secrets (never from the repository or the environment).
set -eu

secret() { tr -d '\r\n' < "/run/secrets/$1"; }

psql -X -q -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_pw="$(secret db_migrator_password)" \
  -v app_pw="$(secret db_app_password)" \
  -v auditor_pw="$(secret db_auditor_password)" \
  -f /snigf/01-roles.sql
