# syntax=docker/dockerfile:1

FROM gradle:9.5.1-jdk21-alpine AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
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

FROM eclipse-temurin:25-jre-alpine AS otel
RUN apk add --no-cache curl && \
    curl -fsSL -o /otel-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar"

FROM bellsoft/liberica-runtime-container:jdk-21-slim-glibc
WORKDIR /app
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
ENV OTEL_SERVICE_NAME=agent-gateway
COPY --from=build /app/services/agent-gateway/build/libs/*.jar /app/agent-gateway.jar
COPY --from=otel /otel-javaagent.jar /app/otel-javaagent.jar
COPY services/agent-gateway/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8090
ENTRYPOINT ["/app/entrypoint.sh"]
