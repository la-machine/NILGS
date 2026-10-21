# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Security probe image (ADR-020): the clients an attacker inside the Docker networks
# would use. Runs as a non-root user with a read-only filesystem.
FROM alpine:3.24.2@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
LABEL org.opencontainers.image.title="SNIGF/NILGS security probe" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore"
RUN apk add --no-cache curl openssl postgresql18-client \
 && adduser -D -H -u 10009 probe
USER probe
