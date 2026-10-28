# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Map tools (ADR-029): GDAL to reproject and tile imagery, go-pmtiles to package archives.
# Not part of the running stack: used through Compose profile "maps" only.
ARG GDAL_IMAGE=ghcr.io/osgeo/gdal:ubuntu-small-3.13.3@sha256:64250faf833c06d4b21afce4c27190039ba7ab58d70f0eebc87cf77d929c0b40
ARG PMTILES_IMAGE=protomaps/go-pmtiles:v1.31.2@sha256:06574f01f55a78f78f887bc7ebf729a5c093c0d6e17d9876300cfcb0758b59d3

FROM ${PMTILES_IMAGE} AS pmtiles

FROM ${GDAL_IMAGE}
COPY --from=pmtiles /go-pmtiles /usr/local/bin/pmtiles
COPY build-glyphs.sh import-imagery.sh synthetic-imagery.py install.sh manifest.py sentinel-fetch.py aerial-fetch.py satellite-build.sh /opt/snigf-maps/
RUN groupadd --system --gid 10003 snigfmaps \
 && useradd --system --uid 10003 --gid snigfmaps --no-create-home snigfmaps \
 && chmod 0555 /opt/snigf-maps/* \
 && pmtiles version
USER snigfmaps:snigfmaps
WORKDIR /work
