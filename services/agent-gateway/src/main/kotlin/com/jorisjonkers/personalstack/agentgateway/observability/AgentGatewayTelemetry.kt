package com.jorisjonkers.personalstack.agentgateway.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface AgentGatewayTelemetry {
    fun recordActiveSessions(sample: GatewayActiveSessionsSample) = Unit

    fun recordOperation(event: GatewayOperationTelemetry) = Unit

    fun recordAttachAttempt(event: GatewayAttachTelemetry) = Unit

    fun recordAttachFailure(event: GatewayAttachTelemetry) = Unit

    fun recordReplay(event: GatewayReplayTelemetry) = Unit

    fun recordReplayFailure(event: GatewayReplayTelemetry) = Unit

    fun recordStorage(sample: GatewayStorageTelemetrySample) = Unit

    fun recordStorageLimit(sample: GatewayStorageTelemetrySample) = Unit

    companion object {
        val NOOP: AgentGatewayTelemetry = NoopAgentGatewayTelemetry
    }
}

object NoopAgentGatewayTelemetry : AgentGatewayTelemetry

data class GatewayActiveSessionsSample(
    val status: GatewayStatusLabel,
    val kind: GatewayAgentKindLabel,
    val mode: GatewayModeLabel,
    val count: Long,
)

data class GatewayOperationTelemetry(
    val operation: GatewayOperationLabel,
    val kind: GatewayAgentKindLabel,
    val mode: GatewayModeLabel,
    val outcome: GatewayOutcomeLabel,
    val reason: GatewayFailureReasonLabel = GatewayFailureReasonLabel.NONE,
    val duration: Duration,
)

data class GatewayReplayTelemetry(
    val bytes: Long,
    val outcome: GatewayOutcomeLabel,
    val reason: GatewayFailureReasonLabel = GatewayFailureReasonLabel.NONE,
)

data class GatewayAttachTelemetry(
    val kind: GatewayAgentKindLabel,
    val mode: GatewayModeLabel,
    val outcome: GatewayOutcomeLabel,
    val reason: GatewayFailureReasonLabel = GatewayFailureReasonLabel.NONE,
)

data class GatewayStorageTelemetrySample(
    val storageObject: GatewayStorageObjectLabel,
    val mode: GatewayModeLabel,
    val bytes: Long,
)

@Component
class MicrometerAgentGatewayTelemetry(
    private val registry: MeterRegistry,
) : AgentGatewayTelemetry {
    private val activeSessionGauges = ConcurrentHashMap<List<String>, AtomicLong>()
    private val storageGauges = ConcurrentHashMap<List<String>, AtomicLong>()
    private val storageLimitGauges = ConcurrentHashMap<List<String>, AtomicLong>()

    override fun recordActiveSessions(sample: GatewayActiveSessionsSample) {
        activeSessionGauges
            .computeIfAbsent(
                listOf(sample.status.label, sample.kind.label, sample.mode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(activeSessions.micrometerId, state) { value -> value.get().toDouble() }
                    .description(activeSessions.description)
                    .baseUnit(activeSessions.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
                    .tag(tag.STATUS, sample.status.label)
                    .tag(tag.KIND, sample.kind.label)
                    .tag(tag.MODE, sample.mode.label)
                    .register(registry)
                state
            }.set(sample.count)
    }

    override fun recordOperation(event: GatewayOperationTelemetry) {
        Counter
            .builder(sessionOperations.micrometerId)
            .description(sessionOperations.description)
            .baseUnit(sessionOperations.baseUnit)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.OPERATION, event.operation.label)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.MODE, event.mode.label)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()

        Timer
            .builder(operationDuration.micrometerId)
            .description(operationDuration.description)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.OPERATION, event.operation.label)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.MODE, event.mode.label)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .record(event.duration)
    }

    override fun recordAttachAttempt(event: GatewayAttachTelemetry) {
        Counter
            .builder(attachAttempts.micrometerId)
            .description(attachAttempts.description)
            .baseUnit(attachAttempts.baseUnit)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.MODE, event.mode.label)
            .tag(tag.OUTCOME, event.outcome.label)
            .register(registry)
            .increment()
    }

    override fun recordAttachFailure(event: GatewayAttachTelemetry) {
        Counter
            .builder(attachFailures.micrometerId)
            .description(attachFailures.description)
            .baseUnit(attachFailures.baseUnit)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.KIND, event.kind.label)
            .tag(tag.MODE, event.mode.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordReplay(event: GatewayReplayTelemetry) {
        Counter
            .builder(replayBytes.micrometerId)
            .description(replayBytes.description)
            .baseUnit(replayBytes.baseUnit)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.OUTCOME, event.outcome.label)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment(event.bytes.coerceAtLeast(0).toDouble())
    }

    override fun recordReplayFailure(event: GatewayReplayTelemetry) {
        Counter
            .builder(replayFailures.micrometerId)
            .description(replayFailures.description)
            .baseUnit(replayFailures.baseUnit)
            .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
            .tag(tag.REASON, event.reason.label)
            .register(registry)
            .increment()
    }

    override fun recordStorage(sample: GatewayStorageTelemetrySample) {
        storageGauges
            .computeIfAbsent(
                listOf(sample.storageObject.label, sample.mode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(storageBytes.micrometerId, state) { value -> value.get().toDouble() }
                    .description(storageBytes.description)
                    .baseUnit(storageBytes.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
                    .tag(tag.STORAGE_OBJECT, sample.storageObject.label)
                    .tag(tag.MODE, sample.mode.label)
                    .register(registry)
                state
            }.set(sample.bytes.coerceAtLeast(0))
    }

    override fun recordStorageLimit(sample: GatewayStorageTelemetrySample) {
        storageLimitGauges
            .computeIfAbsent(
                listOf(sample.storageObject.label, sample.mode.label),
            ) {
                val state = AtomicLong(0)
                Gauge
                    .builder(storageLimitBytes.micrometerId, state) { value -> value.get().toDouble() }
                    .description(storageLimitBytes.description)
                    .baseUnit(storageLimitBytes.baseUnit)
                    .strongReference(true)
                    .tag(tag.SERVICE, AgentGatewayTelemetryContract.SERVICE)
                    .tag(tag.STORAGE_OBJECT, sample.storageObject.label)
                    .tag(tag.MODE, sample.mode.label)
                    .register(registry)
                state
            }.set(sample.bytes.coerceAtLeast(0))
    }

    private companion object {
        val tag = AgentGatewayTelemetryContract.Tags
        val activeSessions = AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.sessions.active")
        val sessionOperations =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.operations")
        val attachAttempts =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.attach.attempts")
        val attachFailures =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.attach.failures")
        val operationDuration =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.session.operation.duration")
        val replayBytes = AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.replay.bytes")
        val replayFailures = AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.replay.failures")
        val storageBytes = AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.storage.bytes")
        val storageLimitBytes =
            AgentGatewayTelemetryContract.byMicrometerId.getValue("agent.gateway.storage.limit.bytes")
    }
}
