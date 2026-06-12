# syntax=docker/dockerfile:1

FROM gradle:9.5.1-jdk21-alpine AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY services/agents-api/build.gradle.kts services/agents-api/
COPY services/agent-gateway/build.gradle.kts services/agent-gateway/

RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:agent-gateway:dependencies --no-daemon || true

COPY services/agent-gateway/src/main/ services/agent-gateway/src/main/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :services:agent-gateway:bootJar --no-daemon

FROM bellsoft/liberica-runtime-container:jdk-21-slim-glibc
WORKDIR /app
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
COPY --from=build /app/services/agent-gateway/build/libs/*.jar /app/agent-gateway.jar
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/agent-gateway.jar"]
