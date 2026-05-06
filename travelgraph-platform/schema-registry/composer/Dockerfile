# syntax=docker/dockerfile:1.6
#
# Multi-stage build for the supergraph composer:
#   1. JDK image builds the Kotlin shadow jar.
#   2. Runtime image bundles JRE + Node + composer jar + Node deps.
#
# Run with:
#   docker run --rm \
#     -v "$(pwd)/schema-registry/output:/output" \
#     -e PROPERTY_SERVICE_URL=... \
#     travelgraph-composer

FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts gradle.properties build.gradle.kts ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN gradle --no-daemon clean shadowJar

FROM node:20-bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/composer
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund \
    && rm -rf /root/.npm
COPY scripts ./scripts
COPY --from=build /workspace/build/libs/travelgraph-composer-all.jar ./composer.jar
COPY composer-config.yaml ./
ENV NODE_BIN=/usr/local/bin/node
ENTRYPOINT ["java", "-jar", "composer.jar"]
CMD ["composer-config.yaml"]
