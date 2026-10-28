# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# OpenBao images (ADR-018). Two targets from the same pinned upstream image:
#   server  the key-custody service; nothing added except its configuration and start script
#   init    the provisioning job (bao-init): adds curl and jq, never runs as the server
ARG OPENBAO_IMAGE=openbao/openbao:2.7.1@sha256:6d2b93856e3fcf7b18ad855a0b51eaba474dc8b79cf554379ea32034797d2acf

FROM ${OPENBAO_IMAGE} AS server
LABEL org.opencontainers.image.title="SNIGF/NILGS key custody (OpenBao)" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore"
USER root
# Mount points owned by the openbao user, so named volumes inherit that ownership.
RUN install -d -m 700 -o openbao -g openbao /openbao/data /openbao/audit \
 && install -d -m 755 /etc/snigf
COPY --chmod=444 openbao.hcl.template start-openbao.sh /etc/snigf/
USER openbao
ENTRYPOINT ["sh", "/etc/snigf/start-openbao.sh"]

FROM server AS init
LABEL org.opencontainers.image.title="SNIGF/NILGS key custody provisioning"
USER root
RUN apk add --no-cache curl jq \
 && install -d -m 700 -o openbao -g openbao /bao-keys
COPY --chmod=444 bao-init.sh app-policy.hcl.template payment-sim-policy.hcl.template /etc/snigf/
USER openbao
ENTRYPOINT ["sh", "/etc/snigf/bao-init.sh"]
