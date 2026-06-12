package com.jorisjonkers.personalstack.agentgateway.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentGatewayTelemetryContractTest {
    @Test
    fun `contract defines exact micrometer and prometheus names`() {
        assertThat(AgentGatewayTelemetryContract.meters)
            .extracting("micrometerId", "prometheusName", "type", "baseUnit")
            .containsExactly(
                tuple(
                    "agent.gateway.sessions.active",
                    "agent_gateway_sessions_active",
                    GatewayTelemetryMeterType.GAUGE,
                    "sessions",
                ),
                tuple(
                    "agent.gateway.session.operations",
                    "agent_gateway_session_operations_total",
                    GatewayTelemetryMeterType.COUNTER,
                    "operations",
                ),
                tuple(
                    "agent.gateway.session.attach.attempts",
                    "agent_gateway_session_attach_attempts_total",
                    GatewayTelemetryMeterType.COUNTER,
                    "attempts",
                ),
                tuple(
                    "agent.gateway.session.attach.failures",
                    "agent_gateway_session_attach_failures_total",
                    GatewayTelemetryMeterType.COUNTER,
                    "failures",
                ),
                tuple(
                    "agent.gateway.session.operation.duration",
                    "agent_gateway_session_operation_duration_seconds",
                    GatewayTelemetryMeterType.TIMER,
                    "seconds",
                ),
                tuple(
                    "agent.gateway.replay.bytes",
                    "agent_gateway_replay_bytes_total",
                    GatewayTelemetryMeterType.COUNTER,
                    "bytes",
                ),
                tuple(
                    "agent.gateway.replay.failures",
                    "agent_gateway_replay_failures_total",
                    GatewayTelemetryMeterType.COUNTER,
                    "failures",
                ),
                tuple(
                    "agent.gateway.storage.bytes",
                    "agent_gateway_storage_bytes",
                    GatewayTelemetryMeterType.GAUGE,
                    "bytes",
                ),
                tuple(
                    "agent.gateway.storage.limit.bytes",
                    "agent_gateway_storage_limit_bytes",
                    GatewayTelemetryMeterType.GAUGE,
                    "bytes",
                ),
            )
    }

    @Test
    fun `contract exposes only bounded tag values`() {
        val operations = AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.operations")

        assertThat(operations.allowedTagKeys)
            .containsExactlyInAnyOrder("service", "operation", "kind", "mode", "outcome", "reason")
        assertThat(operations.allowedTagValues.getValue("operation"))
            .containsExactlyInAnyOrder(
                "spawn",
                "attach",
                "input",
                "resize",
                "replay",
                "stop",
                "headless_job",
                "unknown",
                "other",
            )
        assertThat(operations.allowedTagValues.getValue("kind"))
            .containsExactlyInAnyOrder("claude", "codex", "shell", "other")
        assertThat(operations.allowedTagValues.getValue("mode"))
            .contains("live", "resume", "snapshot", "interactive", "headless")
        assertThat(operations.allowedTagValues.getValue("reason"))
            .contains("none", "not_found", "tmux_unavailable", "timeout", "other")

        val attachFailures =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.attach.failures")
        assertThat(attachFailures.allowedTagKeys)
            .containsExactlyInAnyOrder("service", "kind", "mode", "reason")
    }

    @Test
    fun `classifiers collapse raw and forbidden values to bounded labels`() {
        assertThat(GatewayAgentKindLabel.fromRaw("new-cli-vendor").label).isEqualTo("other")
        assertThat(GatewayStatusLabel.fromRaw("pod runner-abc crashloop").label).isEqualTo("other")
        assertThat(GatewayOperationLabel.fromRaw("workspace-123/delete").label).isEqualTo("other")
        assertThat(GatewayModeLabel.fromRaw("user=alice").label).isEqualTo("other")
        assertThat(GatewayOutcomeLabel.fromRaw("repository-url-leaked").label).isEqualTo("unknown")
        assertThat(GatewayStorageObjectLabel.fromRaw("/workspace/private/transcript.log").label).isEqualTo("other")
        assertThat(GatewayFailureReasonLabel.fromRaw("tmux")).isEqualTo(GatewayFailureReasonLabel.TMUX_UNAVAILABLE)
        assertThat(GatewayFailureReasonLabel.fromRaw("repository https://example.invalid/private timed out").label)
            .isEqualTo("timeout")
    }

    @Test
    fun `noop telemetry accepts events without side effects`() {
        AgentGatewayTelemetry.NOOP.recordReplay(
            GatewayReplayTelemetry(
                bytes = 42,
                outcome = GatewayOutcomeLabel.SUCCESS,
            ),
        )
    }

    private fun tuple(
        micrometerId: String,
        prometheusName: String,
        type: GatewayTelemetryMeterType,
        baseUnit: String,
    ) = org.assertj.core.groups.Tuple
        .tuple(micrometerId, prometheusName, type, baseUnit)
}
