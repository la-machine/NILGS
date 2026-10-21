# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Web image: the built front end served by Caddy, which also proxies /api.
# Build context is the repository root (see compose.yml).

FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f
LABEL org.opencontainers.image.title="SNIGF/NILGS web" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore" \
      org.opencontainers.image.description="Demonstration software, synthetic data only"
# The upstream binary carries cap_net_bind_service (for ports < 1024). SNIGF listens on
# 47000 and drops all capabilities, so the file capability is removed: with it, the
# kernel refuses to execute the binary under cap_drop ALL (ADR-020).
RUN apk add --no-cache --virtual .caps libcap-utils \
 && setcap -r /usr/bin/caddy \
 && apk del .caps
COPY infra/proxy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
