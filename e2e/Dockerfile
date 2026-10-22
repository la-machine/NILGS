# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# End-to-end test image: Playwright with its Chromium, pinned by digest, plus the NSS tools
# that let run.sh make Chromium trust the development CA. Runs as the image's non-root user.
FROM mcr.microsoft.com/playwright:v1.64.0-noble@sha256:06a9939e57531807f8d5fd76ce44b53165ffb7d7501d87ab10e285c20b1e971f
LABEL org.opencontainers.image.title="SNIGF/NILGS end-to-end tests" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore"
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends libnss3-tools \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /e2e
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund && npm cache clean --force
COPY playwright.config.ts run.sh ./
COPY tests ./tests
RUN chmod 0555 run.sh
USER pwuser
ENTRYPOINT ["/bin/sh", "/e2e/run.sh"]
