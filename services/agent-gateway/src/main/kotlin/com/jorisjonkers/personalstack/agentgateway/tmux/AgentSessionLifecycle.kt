package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import io.micrometer.observation.Observation
import org.slf4j.LoggerFactory
import java.time.Instant

internal class AgentSessionLifecycle(
    private val tmux: TmuxClient,
    private val transcriptStore: TranscriptStore,
    private val registry: AgentSessionRegistry,
    private val telemetry: AgentSessionTelemetry,
) : AgentSessionLifecycleOperations {
    private val log = LoggerFactory.getLogger(AgentSessionLifecycle::class.java)

    override fun stop(id: String): Boolean {
        val startedAt = Instant.now()
        var kind = GatewayAgentKindLabel.OTHER
        var outcome = GatewayOutcomeLabel.FAILURE
        var reason = GatewayFailureReasonLabel.NOT_FOUND
        var failure: Throwable? = null
        var observation: Observation? = null
        try {
            return runCatching {
                val session = registry.remove(id) ?: return@runCatching false
                kind = with(telemetry) { session.kind.toTelemetryKind() }
                observation = telemetry.startSessionObservation(GatewayOperationLabel.STOP, kind)
                tmux.killSession(session.tmuxSession)
                session.stableSessionId?.let {
                    transcriptStore.seal(it)
                    session.transcriptLease?.let(transcriptStore::releaseLease)
                }
                outcome = GatewayOutcomeLabel.SUCCESS
                reason = GatewayFailureReasonLabel.NONE
                log.info("stopped agent {}", id)
                true
            }.getOrElse { error ->
                outcome = GatewayOutcomeLabel.FAILURE
                reason = telemetry.failureReasonLabel(error)
                failure = error
                throw error
            }
        } finally {
            telemetry.recordActiveSessionCounts()
            telemetry.recordSessionOperation(
                SessionOperationRecord(
                    operation = GatewayOperationLabel.STOP,
                    kind = kind,
                    outcome = outcome,
                    reason = reason,
                    startedAt = startedAt,
                    observation = observation,
                    error = failure,
                ),
            )
        }
    }

    fun stopAll(onFailure: (String, Throwable) -> Unit) {
        registry.ids().forEach { id ->
            runCatching { stop(id) }.onFailure { onFailure(id, it) }
        }
    }
}
