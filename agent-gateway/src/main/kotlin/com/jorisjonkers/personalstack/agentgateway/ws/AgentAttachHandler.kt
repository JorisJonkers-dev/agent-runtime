package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.LogTailer
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptTailer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    private val mapper: ObjectMapper,
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore = TranscriptStore(props),
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(AgentAttachHandler::class.java)
    private val tailers = ConcurrentHashMap<String, AutoCloseable>()

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

        val stableSessionId = agent.stableSessionId
        if (stableSessionId != null) {
            attachDurable(session, stableSessionId, agent.epoch)
            return
        }

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
        sendJson(session, mapOf("output" to text))
    }

    // Durable attach sends control frames before starting the transcript tailer.
    @Suppress("LongMethod")
    private fun attachDurable(
        session: WebSocketSession,
        stableSessionId: String,
        epoch: Long,
    ) {
        val metadata = transcriptStore.recoverMetadata(stableSessionId)
        val query = queryOf(session)
        val requestedCursor = parseCursor(query)
        val mode = query["mode"]?.uppercase()
        val canResume = requestedCursor != null && requestedCursor in metadata.logicalStart..metadata.logicalEnd
        val resume = (mode == "RESUME" || mode == null) && canResume
        val replayStart = if (resume) requestedCursor else metadata.logicalStart
        val control = if (resume) "RESUME" else "SNAPSHOT"

        sendJson(
            session,
            mapOf(
                "control" to control,
                "stableSessionId" to stableSessionId,
                "epoch" to epoch,
                "start" to metadata.logicalStart,
                "end" to metadata.logicalEnd,
            ),
        )
        if (!resume) sendJson(session, mapOf("reset" to true))
        if (metadata.logicalStart > 0) sendJson(session, mapOf("trim" to metadata.logicalStart))
        sendJson(session, mapOf("cursor" to replayStart))

        val tailer =
            TranscriptTailer(
                transcriptStore,
                stableSessionId,
                replayStart,
                intervalMs = props.tmux.tailIntervalMs,
                onTrim = { sendJson(session, mapOf("trim" to it, "cursor" to it)) },
            ) { frame ->
                sendJson(session, mapOf("output" to frame.output, "off" to frame.off))
            }
        tailers[session.id] = tailer
        tailer.replayAvailable()
        tailer.start()
    }

    private fun sendJson(
        session: WebSocketSession,
        payload: Map<String, Any?>,
    ) {
        if (!session.isOpen) return
        val msg = mapper.writeValueAsString(payload)
        synchronized(session) { session.sendMessage(TextMessage(msg)) }
    }

    private fun queryOf(session: WebSocketSession): Map<String, String> =
        session.uri
            ?.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                val key = decodeQuery(pieces[0]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = if (pieces.size == 2) decodeQuery(pieces[1]) else ""
                key to value
            }?.toMap()
            ?: emptyMap()

    private fun parseCursor(query: Map<String, String>): Long? {
        val raw = query["cursor"] ?: query["off"] ?: return null
        return raw.toLongOrNull()
    }

    private fun decodeQuery(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

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
