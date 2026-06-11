package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.LogTailer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * One WS per client-attach. Inbound JSON is `{"input": "...", "enter":
 * true}`; outbound JSON is `{"output": "...bytes-as-utf8..."}`. The
 * envelope intentionally stays trivial — the rich Block protocol
 * (Step 7) lives one layer up, inside agents-api, which parses the
 * agent's stdout for fenced JSON blocks and emits Block frames to the
 * browser. Keeping the gateway dumb means a CLI flag flip in Claude
 * Code doesn't ripple into the runner image.
 */
@Component
class AgentAttachHandler(
    private val sessions: AgentSessionManager,
    private val mapper: ObjectMapper,
    private val props: GatewayProperties,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(AgentAttachHandler::class.java)
    private val tailers = ConcurrentHashMap<String, LogTailer>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val agentId =
            agentIdOf(session) ?: run {
                session.close(CloseStatus.BAD_DATA.withReason("missing agentId"))
                return
            }
        val agent =
            sessions.get(agentId) ?: run {
                session.close(CloseStatus.BAD_DATA.withReason("unknown agent"))
                return
            }
        log.info("ws attach to agent {} (tmux={})", agentId, agent.tmuxSession)

        // One current-screen snapshot (with ANSI) so the TUI renders
        // immediately; the EOF-based tailer then streams only new bytes,
        // so nothing in the snapshot gets replayed from the log.
        //
        // capture-pane joins pane rows with bare "\n"; the xterm runs
        // with convertEol off (correct for the live PTY stream, which
        // already emits CR), so each "\n" in the snapshot would drop a
        // row without returning to column 0 — the banner staircases to
        // the right. Normalise the snapshot's line ends to CRLF so it
        // repaints flush-left. Existing CRLF is left intact.
        val snapshot =
            runCatching { sessions.captureWithEscapes(agentId) }
                .getOrDefault("")
                .replace("\r\n", "\n")
                .replace("\n", "\r\n")
        LogTailer.chunked(snapshot, LogTailer.MAX_CHUNK_CHARS) { sendOutput(session, it) }

        val tailer =
            LogTailer(agent.logFile, intervalMs = props.tmux.tailIntervalMs) { text ->
                sendOutput(session, text)
            }
        tailers[session.id] = tailer
        tailer.start()
    }

    // Output is relayed as small `{"output": "..."}` frames; LogTailer
    // already bounds each chunk so a JSON frame stays under the default
    // WebSocket buffer and nothing is buffered whole on the heap.
    private fun sendOutput(
        session: WebSocketSession,
        text: String,
    ) {
        if (text.isEmpty() || !session.isOpen) return
        val msg = mapper.writeValueAsString(mapOf("output" to text))
        synchronized(session) { session.sendMessage(TextMessage(msg)) }
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val agentId = agentIdOf(session) ?: return
        val payload =
            runCatching { mapper.readValue(message.payload, Map::class.java) }
                .getOrElse {
                    log.warn("bad ws payload: {}", message.payload.take(120))
                    return
                }
        val resize = payload["resize"] as? Map<*, *>
        if (resize != null) {
            handleResize(agentId, resize)
        } else {
            val input = payload["input"] as? String ?: return
            val enter = payload["enter"] as? Boolean ?: true
            sessions.send(agentId, input, enter)
        }
    }

    private fun handleResize(
        agentId: String,
        resize: Map<*, *>,
    ) {
        val cols = (resize["cols"] as? Number)?.toInt() ?: return
        val rows = (resize["rows"] as? Number)?.toInt() ?: return
        runCatching { sessions.resize(agentId, cols, rows) }
            .onFailure { log.warn("resize of agent {} failed: {}", agentId, it.message) }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        tailers.remove(session.id)?.close()
    }

    private fun agentIdOf(session: WebSocketSession): String? {
        val path = session.uri?.path ?: return null
        val match = Regex("/ws/agents/([^/]+)/attach").find(path) ?: return null
        return match.groupValues[1]
    }
}
