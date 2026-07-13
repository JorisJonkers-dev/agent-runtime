package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One WS per client-attach. Inbound JSON is `{"input": "...", "enter":
 * true}`; outbound JSON is control/cursor/trim metadata plus
 * `{"output": "...bytes-as-utf8...", "off": 123}` for durable sessions. The
 * envelope intentionally stays trivial — the rich Block protocol
 * (Step 7) lives one layer up, inside agents-api, which parses the
 * agent's stdout for fenced JSON blocks and emits Block frames to the
 * browser. Keeping the gateway dumb means a CLI flag flip in Claude
 * Code doesn't ripple into the runner image.
 */
@Component
class AgentAttachHandler(
    private val sessions: AgentSessionManager,
    mapper: ObjectMapper,
    props: GatewayProperties,
    transcriptStore: TranscriptStore,
    telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(AgentAttachHandler::class.java)
    private val tailers = ConcurrentHashMap<String, AutoCloseable>()
    private val sender = AgentWebSocketSender(mapper)
    private val telemetryRecorder = AgentAttachTelemetryRecorder(telemetry, observationRegistry)
    private val durableAttach =
        AgentDurableAttachHandler(props, transcriptStore, sender, telemetryRecorder, tailers, observationRegistry)
    private val liveAttach = AgentLiveAttachHandler(sessions, props, sender, telemetryRecorder, tailers)
    private val inputHandler = AgentInputHandler(sessions, mapper)

    @PreDestroy
    fun shutdown() {
        val active = tailers.values.toList()
        tailers.clear()
        active.forEach { tailer ->
            runCatching { tailer.close() }
                .onFailure { log.warn("closing websocket tailer failed: {}", it.message) }
        }
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val startedAt = Instant.now()
        val query = AgentAttachQuery.parse(session)
        val requestedMode = AgentAttachQuery.requestedModeOf(query)
        val agentId = agentIdOf(session)
        val agent = agentId?.let(sessions::get)

        when {
            agentId == null ->
                closeRejectedAttach(
                    session,
                    requestedMode,
                    GatewayFailureReasonLabel.INVALID_REQUEST,
                    "missing agentId",
                    startedAt,
                )

            query.malformed ->
                closeRejectedAttach(
                    session,
                    requestedMode,
                    GatewayFailureReasonLabel.INVALID_REQUEST,
                    "malformed query",
                    startedAt,
                )

            agent == null ->
                closeRejectedAttach(
                    session,
                    requestedMode,
                    GatewayFailureReasonLabel.NOT_FOUND,
                    "unknown agent",
                    startedAt,
                )

            agent.stableSessionId != null ->
                durableAttach.attach(
                    DurableAttachContext(
                        session = session,
                        stableSessionId = agent.stableSessionId,
                        epoch = agent.epoch,
                        kind = GatewayAgentKindLabel.fromRaw(agent.kind.name),
                        requestedMode = requestedMode,
                        query = query.values,
                        startedAt = startedAt,
                    ),
                )

            else -> {
                log.info("ws attach to agent {} (tmux={})", agentId, agent.tmuxSession)
                liveAttach.attach(
                    LiveAttachContext(
                        session = session,
                        agentId = agentId,
                        agent = agent,
                        kind = GatewayAgentKindLabel.fromRaw(agent.kind.name),
                        requestedMode = requestedMode,
                        startedAt = startedAt,
                    ),
                )
            }
        }
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        inputHandler.handleTextMessage(session, message)
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        tailers.remove(session.id)?.let { tailer ->
            runCatching { tailer.close() }
                .onFailure { log.warn("closing websocket tailer {} failed: {}", session.id, it.message) }
        }
    }

    private fun closeRejectedAttach(
        session: WebSocketSession,
        requestedMode: GatewayModeLabel,
        reason: GatewayFailureReasonLabel,
        closeReason: String,
        startedAt: Instant,
    ) {
        telemetryRecorder.recordAttachTerminal(
            kind = GatewayAgentKindLabel.OTHER,
            mode = requestedMode,
            outcome = GatewayOutcomeLabel.FAILURE,
            reason = reason,
            startedAt = startedAt,
        )
        session.close(CloseStatus.BAD_DATA.withReason(closeReason))
    }

    companion object {
        internal const val MAX_COLD_REPLAY_BYTES = AgentAttachLimits.MAX_COLD_REPLAY_BYTES
        internal const val MAX_RESUME_REPLAY_BYTES = AgentAttachLimits.MAX_RESUME_REPLAY_BYTES
    }
}
