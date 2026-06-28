package com.jorisjonkers.personalstack.agentgateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GatewayObservabilityContractTest {
    @Test
    fun `application exposes actuator parity with stable gateway identity`() {
        val application = readProjectFile("src/main/resources/application.yml")

        assertThat(application).contains("name: agent-gateway")
        assertThat(application).contains("base-path: /api/actuator")
        assertThat(application).contains("include: health,info,prometheus,metrics")
        assertThat(application).contains("service.name: \${spring.application.name}")
        assertThat(application).contains("deployment.environment: \${DEPLOYMENT_ENVIRONMENT:unknown}")
        assertThat(application).contains("probes:")
        assertThat(application).contains("enabled: true")
        assertThat(application).contains("liveness:")
        assertThat(application).contains("include: livenessState,ping")
        assertThat(application).contains("readiness:")
        assertThat(application).contains("include: readinessState")
    }

    @Test
    fun `json logs carry gateway custom identity fields`() {
        val logback = readProjectFile("src/main/resources/logback-spring.xml")

        assertThat(logback).contains(
            "\"service\":\"agent-gateway\"",
            "\"service.name\":\"agent-gateway\"",
            "\"service.version\":\"\${serviceVersion}\"",
            "\"deployment.environment\":\"\${deploymentEnvironment}\"",
            "<includeMdcKeyName>trace_id</includeMdcKeyName>",
            "<includeMdcKeyName>span_id</includeMdcKeyName>",
            "<includeMdcKeyName>trace_flags</includeMdcKeyName>",
        )
    }

    @Test
    fun `dockerfile copies entrypoint and launches gateway through otel java agent`() {
        val dockerfile = readProjectFile("Dockerfile")

        assertThat(dockerfile).contains(
            "FROM eclipse-temurin:25-jre-alpine AS otel",
            "opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar",
            "ENV OTEL_SERVICE_NAME=agent-gateway",
            "COPY --from=otel /otel-javaagent.jar /app/otel-javaagent.jar",
            "COPY services/agent-gateway/entrypoint.sh /app/entrypoint.sh",
            "RUN chmod +x /app/entrypoint.sh",
            "ENTRYPOINT [\"/app/entrypoint.sh\"]",
        )
    }

    @Test
    fun `entrypoint preserves gateway jvm flags and appends missing otel resource attributes`() {
        val entrypoint = readProjectFile("entrypoint.sh")

        assertThat(entrypoint).contains(
            "append_otel_resource_attribute \"service.version\" \"\${SERVICE_VERSION:-unknown}\"",
            "append_otel_resource_attribute \"deployment.environment\" \"\${DEPLOYMENT_ENVIRONMENT:-unknown}\"",
            "export OTEL_SERVICE_NAME=\"agent-gateway\"",
            "-XX:+UseZGC",
            "-XX:MaxRAMPercentage=75",
            "-javaagent:/app/otel-javaagent.jar",
            "-jar /app/agent-gateway.jar",
        )
        assertThat(entrypoint).contains("*\",\${key}=\"*) return ;;")
    }

    private fun readProjectFile(path: String): String {
        val file =
            listOf(
                Path.of(path),
                Path.of("services/agent-gateway").resolve(path),
            ).firstOrNull { Files.exists(it) } ?: error("missing project file: $path")
        return Files.readString(file)
    }
}
