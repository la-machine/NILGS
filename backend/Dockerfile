# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Backend image: built once per commit, promoted unchanged from CI to staging to
# production; only configuration and secrets differ (Release plan §4).
# Base images are pinned by digest (threat model: supply chain).

FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
# mvnw needs unzip for the .zip distribution whose checksum is pinned in
# .mvn/wrapper/maven-wrapper.properties (without it, it silently switches to .tar.gz).
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -q dependency:resolve
COPY src/ src/
# Tests run in CI (`./mvnw verify`), not here: integration tests need Docker.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -q package -DskipTests \
 && java -Djarmode=tools -jar target/snigf-backend.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
LABEL org.opencontainers.image.title="SNIGF/NILGS backend" \
      org.opencontainers.image.vendor="FLYT" \
      org.opencontainers.image.authors="Pythagore" \
      org.opencontainers.image.description="Demonstration software, synthetic data only"
RUN groupadd --system --gid 10001 snigf && useradd --system --uid 10001 --gid snigf --no-create-home snigf \
 && install -d -o snigf -g snigf -m 700 /var/lib/snigf/audit-checkpoints
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
COPY --chmod=555 docker-entrypoint.sh /usr/local/bin/snigf-entrypoint
# Private copy of the database client key, made by the entry point (mode 0600).
ENV SNIGF_DB_CLIENT_KEY=/run/snigf-keys/app_db_client_key_pk8
# Signed audit checkpoints: the volume audit-checkpoints (compose.yml) is mounted here and
# inherits this directory's owner and mode on first use.
ENV SNIGF_AUDIT_CHECKPOINT_DIR=/var/lib/snigf/audit-checkpoints
USER snigf:snigf
# Ports come from the environment (.env); this is the documented API port (ADR-015).
EXPOSE 47080
# Readiness on the management port, which listens on the container's loopback only
# (never on a network), so plain HTTP never leaves the container. No curl in the image.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SNIGF_MANAGEMENT_PORT:?} &&printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["/usr/local/bin/snigf-entrypoint"]
