package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAttachTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayReplayTelemetry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.io.IOException
import java.time.Duration
import java.time.Instant

internal class AgentAttachTelemetryRecorder(
    private val telemetry: AgentGatewayTelemetry,
    private val observationRegistry: ObservationRegistry,
) {
    fun recordAttachTerminal(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
        startedAt: Instant,
    ) {
        val observation =
            Observation
                .start("agent.gateway.session.attach", observationRegistry)
                .lowCardinalityKeyValue("kind", kind.label)
                .lowCardinalityKeyValue("mode", mode.label)
                .lowCardinalityKeyValue("outcome", outcome.label)
                .lowCardinalityKeyValue("reason", reason.label)
        try {
            val event = GatewayAttachTelemetry(kind = kind, mode = mode, outcome = outcome, reason = reason)
            telemetry.recordAttachAttempt(event)
            if (outcome == GatewayOutcomeLabel.FAILURE) telemetry.recordAttachFailure(event)
            telemetry.recordOperation(
                GatewayOperationTelemetry(
                    operation = GatewayOperationLabel.ATTACH,
                    kind = kind,
                    mode = mode,
                    outcome = outcome,
                    reason = reason,
                    duration = Duration.between(startedAt, Instant.now()),
                ),
            )
        } finally {
            observation.stop()
        }
    }

    fun recordReplay(
        bytes: Long,
        success: Boolean,
        failureReason: GatewayFailureReasonLabel,
    ) {
        val outcome = if (success) GatewayOutcomeLabel.SUCCESS else GatewayOutcomeLabel.FAILURE
        val reason = if (success) GatewayFailureReasonLabel.NONE else failureReason
        val observation =
            Observation
                .start("agent.gateway.replay", observationRegistry)
                .lowCardinalityKeyValue("outcome", outcome.label)
                .lowCardinalityKeyValue("reason", reason.label)
        try {
            telemetry.recordReplay(GatewayReplayTelemetry(bytes = bytes, outcome = outcome, reason = reason))
        } finally {
            observation.stop()
        }
    }

    fun recordReplayFailure(
        bytes: Long,
        reason: GatewayFailureReasonLabel,
    ) {
        telemetry.recordReplayFailure(
            GatewayReplayTelemetry(
                bytes = bytes,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = reason,
            ),
        )
    }

    fun recordAttachFailure(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        telemetry.recordAttachFailure(
            GatewayAttachTelemetry(
                kind = kind,
                mode = mode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = reason,
            ),
        )
    }

    fun recordReplayOperation(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        recordOperation(GatewayOperationLabel.REPLAY, kind, mode, outcome, reason)
    }

    fun recordTailerStartup(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        recordOperation(GatewayOperationLabel.REPLAY, kind, mode, outcome, reason)
    }

    private fun recordOperation(
        operation: GatewayOperationLabel,
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = operation,
                kind = kind,
                mode = mode,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }
}

internal fun failureReasonLabel(error: Throwable): GatewayFailureReasonLabel =
    when (error) {
        is IOException -> GatewayFailureReasonLabel.IO_ERROR
        is IllegalArgumentException -> GatewayFailureReasonLabel.INVALID_REQUEST
        is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
        else -> GatewayFailureReasonLabel.UNKNOWN
    }

internal fun failureReasonLabel(reason: String?): GatewayFailureReasonLabel = GatewayFailureReasonLabel.fromRaw(reason)
