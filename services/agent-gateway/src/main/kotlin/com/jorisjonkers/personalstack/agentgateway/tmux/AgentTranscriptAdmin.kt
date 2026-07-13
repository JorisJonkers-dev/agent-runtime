package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import java.time.Instant

internal class AgentTranscriptAdmin(
    private val transcriptStore: TranscriptStore,
    private val telemetry: AgentSessionTelemetry,
) : AgentTranscriptAdminOperations {
    override fun cleanupTranscript(stableSessionId: String): Boolean {
        val startedAt = Instant.now()
        var outcome = GatewayOutcomeLabel.SUCCESS
        var reason = GatewayFailureReasonLabel.NONE
        var failure: Throwable? = null
        try {
            return runCatching {
                val ok = transcriptStore.cleanup(transcriptStore.validateStableSessionId(stableSessionId))
                if (!ok) {
                    outcome = GatewayOutcomeLabel.FAILURE
                    reason = GatewayFailureReasonLabel.NOT_FOUND
                }
                ok
            }.getOrElse { error ->
                outcome = GatewayOutcomeLabel.FAILURE
                reason = telemetry.failureReasonLabel(error)
                failure = error
                throw error
            }
        } finally {
            telemetry.recordSessionOperation(
                SessionOperationRecord(
                    operation = GatewayOperationLabel.REPLAY,
                    kind = GatewayAgentKindLabel.OTHER,
                    outcome = outcome,
                    reason = reason,
                    startedAt = startedAt,
                    observed = false,
                    error = failure,
                ),
            )
        }
    }
}
