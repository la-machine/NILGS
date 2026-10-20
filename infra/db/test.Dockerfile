# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Database test image: the pinned PostGIS image plus pgTAP and pg_prove.
# BASE_IMAGE is passed by compose.yml so tests always run on the deployed image.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      "postgresql-${PG_MAJOR}-pgtap" libtap-parser-sourcehandler-pgtap-perl \
 && rm -rf /var/lib/apt/lists/*
