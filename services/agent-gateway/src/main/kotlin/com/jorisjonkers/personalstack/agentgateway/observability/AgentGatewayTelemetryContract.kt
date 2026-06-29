package com.jorisjonkers.personalstack.agentgateway.observability

data class GatewayTelemetryMeterContract(
    val micrometerId: String,
    val prometheusName: String,
    val type: GatewayTelemetryMeterType,
    val baseUnit: String?,
    val description: String,
    val allowedTagKeys: Set<String>,
    val allowedTagValues: Map<String, Set<String>>,
)

enum class GatewayTelemetryMeterType {
    COUNTER,
    GAUGE,
    TIMER,
}

object AgentGatewayTelemetryContract {
    const val SERVICE = "agent-gateway"

    object Tags {
        const val SERVICE = "service"
        const val STATUS = "status"
        const val KIND = "kind"
        const val OPERATION = "operation"
        const val MODE = "mode"
        const val OUTCOME = "outcome"
        const val STORAGE_OBJECT = "storage_object"
        const val REASON = "reason"
    }

    val meters =
        listOf(
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.sessions.active",
                prometheusName = "agent_gateway_sessions_active",
                type = GatewayTelemetryMeterType.GAUGE,
                baseUnit = "sessions",
                description = "Active in-pod gateway sessions by bounded status, kind, and mode.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STATUS, Tags.KIND, Tags.MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STATUS to GatewayStatusLabel.values().labels(),
                        Tags.KIND to GatewayAgentKindLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.session.operations",
                prometheusName = "agent_gateway_session_operations_total",
                type = GatewayTelemetryMeterType.COUNTER,
                baseUnit = "operations",
                description = "Gateway session operation attempts with bounded outcomes and reasons.",
                allowedTagKeys =
                    setOf(Tags.SERVICE, Tags.OPERATION, Tags.KIND, Tags.MODE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OPERATION to GatewayOperationLabel.values().labels(),
                        Tags.KIND to GatewayAgentKindLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                        Tags.OUTCOME to GatewayOutcomeLabel.values().labels(),
                        Tags.REASON to GatewayFailureReasonLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.session.attach.attempts",
                prometheusName = "agent_gateway_session_attach_attempts_total",
                type = GatewayTelemetryMeterType.COUNTER,
                baseUnit = "attempts",
                description = "Gateway WebSocket attach attempts with bounded outcomes.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.KIND, Tags.MODE, Tags.OUTCOME),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.KIND to GatewayAgentKindLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                        Tags.OUTCOME to GatewayOutcomeLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.session.attach.failures",
                prometheusName = "agent_gateway_session_attach_failures_total",
                type = GatewayTelemetryMeterType.COUNTER,
                baseUnit = "failures",
                description = "Failed gateway WebSocket attach attempts with bounded reasons.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.KIND, Tags.MODE, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.KIND to GatewayAgentKindLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                        Tags.REASON to GatewayFailureReasonLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.session.operation.duration",
                prometheusName = "agent_gateway_session_operation_duration_seconds",
                type = GatewayTelemetryMeterType.TIMER,
                baseUnit = "seconds",
                description = "Gateway session operation duration with bounded operation, kind, mode, and outcome.",
                allowedTagKeys =
                    setOf(Tags.SERVICE, Tags.OPERATION, Tags.KIND, Tags.MODE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OPERATION to GatewayOperationLabel.values().labels(),
                        Tags.KIND to GatewayAgentKindLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                        Tags.OUTCOME to GatewayOutcomeLabel.values().labels(),
                        Tags.REASON to GatewayFailureReasonLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.replay.bytes",
                prometheusName = "agent_gateway_replay_bytes_total",
                type = GatewayTelemetryMeterType.COUNTER,
                baseUnit = "bytes",
                description = "Transcript bytes replayed by the gateway attach path.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.OUTCOME, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.OUTCOME to GatewayOutcomeLabel.values().labels(),
                        Tags.REASON to GatewayFailureReasonLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.replay.failures",
                prometheusName = "agent_gateway_replay_failures_total",
                type = GatewayTelemetryMeterType.COUNTER,
                baseUnit = "failures",
                description = "Transcript replay failures in the gateway attach path.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.REASON),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.REASON to GatewayFailureReasonLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.storage.bytes",
                prometheusName = "agent_gateway_storage_bytes",
                type = GatewayTelemetryMeterType.GAUGE,
                baseUnit = "bytes",
                description = "Gateway-observed persisted storage usage by bounded storage object.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STORAGE_OBJECT, Tags.MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STORAGE_OBJECT to GatewayStorageObjectLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                    ),
            ),
            GatewayTelemetryMeterContract(
                micrometerId = "agent.gateway.storage.limit.bytes",
                prometheusName = "agent_gateway_storage_limit_bytes",
                type = GatewayTelemetryMeterType.GAUGE,
                baseUnit = "bytes",
                description = "Configured gateway-managed storage capacity by bounded storage object.",
                allowedTagKeys = setOf(Tags.SERVICE, Tags.STORAGE_OBJECT, Tags.MODE),
                allowedTagValues =
                    mapOf(
                        Tags.SERVICE to setOf(SERVICE),
                        Tags.STORAGE_OBJECT to GatewayStorageObjectLabel.values().labels(),
                        Tags.MODE to GatewayModeLabel.values().labels(),
                    ),
            ),
        )

    val byMicrometerId = meters.associateBy { it.micrometerId }

    private fun <T> Array<T>.labels(): Set<String>
        where T : Enum<T>, T : GatewayTelemetryLabel =
        map { it.label }.toSet()
}
