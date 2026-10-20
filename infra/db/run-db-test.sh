#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Entry point of the `dbtest` Compose service: starts a throwaway PostgreSQL
# server inside the container, then runs infra/scripts/db-test.sh against it as
# the postgres user. The superuser password exists only for this run.
set -eu

POSTGRES_PASSWORD="$(openssl rand -hex 16)"
export POSTGRES_PASSWORD

docker-entrypoint.sh postgres >/tmp/postgres.log 2>&1 &

# The entrypoint's temporary initialisation server listens on the Unix socket
# only, so a TCP answer means the real server is up.
tries=0
until pg_isready -q -h 127.0.0.1 -U postgres; do
  tries=$((tries + 1))
  if [ "$tries" -ge 90 ]; then cat /tmp/postgres.log >&2; exit 1; fi
  sleep 1
done

exec gosu postgres bash /repo/infra/scripts/db-test.sh
