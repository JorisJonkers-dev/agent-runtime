package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStatusLabel
import com.jorisjonkers.personalstack.agentgateway.process.ProcessFailedException
import com.jorisjonkers.personalstack.agentgateway.process.ProcessTimeoutException
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.io.IOException
import java.time.Duration
import java.time.Instant

internal class AgentSessionTelemetry(
    private val registry: AgentSessionRegistry,
    private val telemetry: AgentGatewayTelemetry,
    private val observationRegistry: ObservationRegistry,
) {
    fun recordActiveSessionCounts() {
        val counts = registry.countsByKind()
        AgentKind.values().forEach { kind ->
            telemetry.recordActiveSessions(
                GatewayActiveSessionsSample(
                    status = GatewayStatusLabel.RUNNING,
                    kind = kind.toTelemetryKind(),
                    mode = GatewayModeLabel.INTERACTIVE,
                    count = counts[kind]?.toLong() ?: 0,
                ),
            )
        }
    }

    fun startSessionObservation(
        operation: GatewayOperationLabel,
        kind: GatewayAgentKindLabel,
    ): Observation =
        Observation
            .start(SESSION_OBSERVATION_NAME, observationRegistry)
            .lowCardinalityKeyValue("operation", operation.label)
            .lowCardinalityKeyValue("kind", kind.label)
            .lowCardinalityKeyValue("mode", GatewayModeLabel.INTERACTIVE.label)

    fun recordSessionOperation(record: SessionOperationRecord) {
        val activeObservation =
            record.observation ?: if (record.observed) startSessionObservation(record.operation, record.kind) else null
        activeObservation
            ?.lowCardinalityKeyValue("outcome", record.outcome.label)
            ?.lowCardinalityKeyValue("reason", record.reason.label)
        record.error?.let { activeObservation?.error(it) }
        activeObservation?.stop()
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = record.operation,
                kind = record.kind,
                mode = GatewayModeLabel.INTERACTIVE,
                outcome = record.outcome,
                reason = record.reason,
                duration = Duration.between(record.startedAt, Instant.now()),
            ),
        )
    }

    fun failureReasonLabel(error: Throwable): GatewayFailureReasonLabel =
        when (error) {
            is IllegalArgumentException -> GatewayFailureReasonLabel.INVALID_REQUEST
            is IllegalStateException ->
                if (error.message?.startsWith("unknown agent:") == true) {
                    GatewayFailureReasonLabel.NOT_FOUND
                } else {
                    GatewayFailureReasonLabel.OTHER
                }
            is IOException -> GatewayFailureReasonLabel.IO_ERROR
            is ProcessFailedException -> GatewayFailureReasonLabel.PROCESS_EXITED
            is ProcessTimeoutException -> GatewayFailureReasonLabel.TIMEOUT
            is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
            is InterruptedException -> GatewayFailureReasonLabel.CANCELLED
            else -> GatewayFailureReasonLabel.UNKNOWN
        }

    fun AgentKind.toTelemetryKind(): GatewayAgentKindLabel = GatewayAgentKindLabel.fromRaw(name)

    private companion object {
        const val SESSION_OBSERVATION_NAME = "agent.gateway.session.operation"
    }
}

internal data class SessionOperationRecord(
    val operation: GatewayOperationLabel,
    val kind: GatewayAgentKindLabel,
    val outcome: GatewayOutcomeLabel,
    val reason: GatewayFailureReasonLabel,
    val startedAt: Instant,
    val observed: Boolean = true,
    val observation: Observation? = null,
    val error: Throwable? = null,
)
