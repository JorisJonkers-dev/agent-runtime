package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStatusLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import java.io.IOException
import java.time.Duration

internal class HeadlessJobTelemetry(
    private val telemetry: AgentGatewayTelemetry,
) {
    fun recordCounts(jobs: Collection<HeadlessJob>) {
        val counts = jobs.groupingBy { it.kind to it.status }.eachCount()
        AgentKind.values().forEach { kind ->
            HeadlessJobStatus.values().forEach { status ->
                telemetry.recordActiveSessions(
                    GatewayActiveSessionsSample(
                        status = status.toTelemetryStatus(),
                        kind = kind.toTelemetryKind(),
                        mode = GatewayModeLabel.HEADLESS,
                        count = counts[kind to status]?.toLong() ?: 0,
                    ),
                )
            }
        }
    }

    fun recordEvent(
        kind: AgentKind,
        operation: GatewayOperationLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel = GatewayFailureReasonLabel.NONE,
        duration: Duration,
    ) {
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = operation,
                kind = kind.toTelemetryKind(),
                mode = GatewayModeLabel.HEADLESS,
                outcome = outcome,
                reason = reason,
                duration = duration,
            ),
        )
    }

    fun recordCleanup(
        kind: AgentKind,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        recordEvent(
            kind = kind,
            operation = GatewayOperationLabel.STOP,
            outcome = outcome,
            reason = reason,
            duration = Duration.ZERO,
        )
    }

    fun startFailureReason(ex: Throwable): GatewayFailureReasonLabel =
        when (ex) {
            is IOException -> GatewayFailureReasonLabel.IO_ERROR
            is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
            else -> GatewayFailureReasonLabel.UNKNOWN
        }

    private fun AgentKind.toTelemetryKind(): GatewayAgentKindLabel = GatewayAgentKindLabel.fromRaw(name)

    private fun HeadlessJobStatus.toTelemetryStatus(): GatewayStatusLabel = GatewayStatusLabel.fromRaw(name)
}
