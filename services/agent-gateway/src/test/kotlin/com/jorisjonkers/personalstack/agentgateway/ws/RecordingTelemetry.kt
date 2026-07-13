package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAttachTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayReplayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayStorageTelemetrySample
import java.util.concurrent.CopyOnWriteArrayList

internal class RecordingTelemetry : AgentGatewayTelemetry {
    val attachAttempts = CopyOnWriteArrayList<GatewayAttachTelemetry>()
    val attachFailures = CopyOnWriteArrayList<GatewayAttachTelemetry>()
    val replayEvents = CopyOnWriteArrayList<GatewayReplayTelemetry>()
    val replayFailures = CopyOnWriteArrayList<GatewayReplayTelemetry>()
    val operations = CopyOnWriteArrayList<GatewayOperationTelemetry>()

    override fun recordActiveSessions(sample: GatewayActiveSessionsSample) = Unit

    override fun recordOperation(event: GatewayOperationTelemetry) {
        operations += event
    }

    override fun recordAttachAttempt(event: GatewayAttachTelemetry) {
        attachAttempts += event
    }

    override fun recordAttachFailure(event: GatewayAttachTelemetry) {
        attachFailures += event
    }

    override fun recordReplay(event: GatewayReplayTelemetry) {
        replayEvents += event
    }

    override fun recordReplayFailure(event: GatewayReplayTelemetry) {
        replayFailures += event
    }

    override fun recordStorage(sample: GatewayStorageTelemetrySample) = Unit

    override fun recordStorageLimit(sample: GatewayStorageTelemetrySample) = Unit
}
