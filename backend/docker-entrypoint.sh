#!/bin/sh
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Entry point of the backend image. Copies the database client key from the Docker
# secret to a private tmpfs with mode 0600 (the PostgreSQL driver refuses a key that
# others can read, and bind-mounted secrets keep their host permissions, which are
# 0777 on Docker Desktop for Windows), then starts the application.
set -eu
umask 077

KEYS=/run/snigf-keys
if [ -f /run/secrets/app_db_client_key_pk8 ]; then
  [ -d "$KEYS" ] || { echo "missing tmpfs $KEYS (compose.yml)" >&2; exit 1; }
  install -m 600 /run/secrets/app_db_client_key_pk8 "$KEYS/app_db_client_key_pk8"
fi

exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError org.springframework.boot.loader.launch.JarLauncher "$@"
