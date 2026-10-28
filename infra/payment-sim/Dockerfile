# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# DEMO payment simulator (ADR-037): Node.js with no dependency beyond itself. No real money moves.
FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f
LABEL org.opencontainers.image.title="SNIGF/NILGS DEMO payment simulator" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore" \
      org.opencontainers.image.description="Demonstration software: simulated payments only, no real money moves"
# The directory first: created by COPY --chmod it would get the file's mode, untraversable.
RUN install -d -m 755 /opt/payment-sim
COPY --chmod=444 simulator.mjs /opt/payment-sim/simulator.mjs
USER 10004:10004
ENTRYPOINT ["node", "/opt/payment-sim/simulator.mjs"]
