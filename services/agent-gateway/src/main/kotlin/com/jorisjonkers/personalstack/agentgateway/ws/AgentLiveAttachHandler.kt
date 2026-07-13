package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.LogTailer
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.concurrent.ConcurrentMap

internal class AgentLiveAttachHandler(
    private val sessions: AgentSessionManager,
    private val props: GatewayProperties,
    private val sender: AgentWebSocketSender,
    private val telemetry: AgentAttachTelemetryRecorder,
    private val tailers: ConcurrentMap<String, AutoCloseable>,
) {
    private val log = LoggerFactory.getLogger(AgentLiveAttachHandler::class.java)

    fun attach(context: LiveAttachContext) {
        runCatching {
            sendSnapshot(context.session, context.agentId)
            startTailer(context)
            telemetry.recordTailerStartup(
                context.kind,
                context.requestedMode,
                GatewayOutcomeLabel.SUCCESS,
                GatewayFailureReasonLabel.NONE,
            )
            telemetry.recordAttachTerminal(
                kind = context.kind,
                mode = context.requestedMode,
                outcome = GatewayOutcomeLabel.SUCCESS,
                reason = GatewayFailureReasonLabel.NONE,
                startedAt = context.startedAt,
            )
        }.onFailure { error ->
            telemetry.recordAttachTerminal(
                kind = context.kind,
                mode = context.requestedMode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = failureReasonLabel(error),
                startedAt = context.startedAt,
            )
            closeFailedAttach(context.session)
        }
    }

    private fun sendSnapshot(
        session: WebSocketSession,
        agentId: String,
    ) {
        // capture-pane joins pane rows with bare "\n"; normalize to CRLF so
        // xterm repaints the snapshot flush-left before live PTY bytes arrive.
        val snapshot =
            runCatching { sessions.captureWithEscapes(agentId) }
                .getOrDefault("")
                .replace("\r\n", "\n")
                .replace("\n", "\r\n")
        LogTailer.chunked(snapshot, LogTailer.MAX_CHUNK_CHARS) { sender.sendOutput(session, it) }
    }

    private fun startTailer(context: LiveAttachContext) {
        val tailer =
            LogTailer(context.agent.logFile, intervalMs = props.tmux.tailIntervalMs) { text ->
                sender.sendOutput(context.session, text)
            }
        tailers[context.session.id] = tailer
        tailer.start()
    }

    private fun closeFailedAttach(session: WebSocketSession) {
        runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("attach failed")) }
            .onFailure { log.warn("closing failed websocket attach failed: {}", it.message) }
    }
}

internal data class LiveAttachContext(
    val session: WebSocketSession,
    val agentId: String,
    val agent: AgentSession,
    val kind: GatewayAgentKindLabel,
    val requestedMode: GatewayModeLabel,
    val startedAt: Instant,
)
