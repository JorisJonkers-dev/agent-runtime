package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAttachTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayReplayTelemetry
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.LogTailer
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptTailer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
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
@Suppress("LargeClass", "TooManyFunctions")
class AgentAttachHandler(
    private val sessions: AgentSessionManager,
    private val mapper: ObjectMapper,
    private val props: GatewayProperties,
    private val transcriptStore: TranscriptStore,
    private val telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(AgentAttachHandler::class.java)
    private val tailers = ConcurrentHashMap<String, AutoCloseable>()

    @PreDestroy
    fun shutdown() {
        val active = tailers.values.toList()
        tailers.clear()
        active.forEach { tailer ->
            runCatching { tailer.close() }
                .onFailure { log.warn("closing websocket tailer failed: {}", it.message) }
        }
    }

    @Suppress("LongMethod", "ReturnCount")
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val startedAt = Instant.now()
        val query = queryOf(session)
        val requestedMode = requestedModeOf(query)
        val agentId =
            agentIdOf(session) ?: run {
                recordAttachTerminal(
                    kind = GatewayAgentKindLabel.OTHER,
                    mode = requestedMode,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = GatewayFailureReasonLabel.INVALID_REQUEST,
                    startedAt = startedAt,
                )
                session.close(CloseStatus.BAD_DATA.withReason("missing agentId"))
                return
            }
        if (query.malformed) {
            recordAttachTerminal(
                kind = GatewayAgentKindLabel.OTHER,
                mode = requestedMode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = GatewayFailureReasonLabel.INVALID_REQUEST,
                startedAt = startedAt,
            )
            session.close(CloseStatus.BAD_DATA.withReason("malformed query"))
            return
        }
        val agent =
            sessions.get(agentId) ?: run {
                recordAttachTerminal(
                    kind = GatewayAgentKindLabel.OTHER,
                    mode = requestedMode,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = GatewayFailureReasonLabel.NOT_FOUND,
                    startedAt = startedAt,
                )
                session.close(CloseStatus.BAD_DATA.withReason("unknown agent"))
                return
            }
        val kind = GatewayAgentKindLabel.fromRaw(agent.kind.name)
        log.info("ws attach to agent {} (tmux={})", agentId, agent.tmuxSession)

        val stableSessionId = agent.stableSessionId
        if (stableSessionId != null) {
            attachDurable(session, stableSessionId, agent.epoch, kind, requestedMode, query.values, startedAt)
            return
        }

        runCatching {
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
            recordTailerStartup(kind, requestedMode, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE)
            recordAttachTerminal(
                kind = kind,
                mode = requestedMode,
                outcome = GatewayOutcomeLabel.SUCCESS,
                reason = GatewayFailureReasonLabel.NONE,
                startedAt = startedAt,
            )
        }.onFailure {
            recordAttachTerminal(
                kind = kind,
                mode = requestedMode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = failureReasonLabel(it),
                startedAt = startedAt,
            )
            runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("attach failed")) }
                .onFailure { closeError -> log.warn("closing failed websocket attach failed: {}", closeError.message) }
        }
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
    @Suppress("LongMethod", "LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
    private fun attachDurable(
        session: WebSocketSession,
        stableSessionId: String,
        epoch: Long,
        kind: GatewayAgentKindLabel,
        requestedMode: GatewayModeLabel,
        query: Map<String, String>,
        startedAt: Instant,
    ) {
        val metadata =
            runCatching { transcriptStore.recoverMetadata(stableSessionId) }
                .getOrElse {
                    val reason = failureReasonLabel(it)
                    log.warn("metadata recovery for transcript {} failed: {}", stableSessionId, it.message)
                    recordAttachTerminal(kind, requestedMode, GatewayOutcomeLabel.FAILURE, reason, startedAt)
                    runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("metadata recovery failed")) }
                        .onFailure { closeError ->
                            log.warn("closing websocket after metadata recovery failure failed: {}", closeError.message)
                        }
                    return
                }
        val requestedOffset = parseOffset(query)
        val requestedEpoch = parseEpoch(query)
        if (requestedOffset.malformed || requestedEpoch.malformed) {
            telemetry.recordAttachFailure(
                GatewayAttachTelemetry(
                    kind = kind,
                    mode = requestedMode,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = GatewayFailureReasonLabel.INVALID_REQUEST,
                ),
            )
        }
        val mode = query["mode"]?.uppercase()
        val canResume =
            requestedEpoch.value == epoch &&
                requestedOffset.value != null &&
                requestedOffset.value in metadata.logicalStart..metadata.logicalEnd &&
                metadata.logicalEnd - requestedOffset.value <= MAX_RESUME_REPLAY_BYTES
        val resume = (mode == "RESUME" || mode == null) && canResume
        // Cold/snapshot attach used to replay from logicalStart — the entire
        // retained transcript (up to the ~50MB cap). For a TUI that repaints
        // the whole pane continuously that is megabytes of escape sequences
        // the browser must parse on the main thread before it can even echo a
        // keystroke, so perceived lag scaled with session age. Bound the cold
        // replay to a recent tail window instead: a screenful of repaints
        // lives well inside it, and attach cost becomes constant regardless of
        // how long the session has run. (Resume from a live client's own
        // offset is unchanged, but is itself capped above so a long-absent
        // client falls back to this bounded snapshot rather than a huge gap.)
        val coldStart = maxOf(metadata.logicalStart, metadata.logicalEnd - MAX_COLD_REPLAY_BYTES)
        val replayStart = if (resume) requestedOffset.value else coldStart
        val control = if (resume) "RESUME" else "SNAPSHOT"

        runCatching {
            sendJson(
                session,
                mapOf(
                    "control" to control,
                    "stableSessionId" to stableSessionId,
                    "epoch" to epoch,
                    "start" to metadata.logicalStart,
                    "end" to metadata.logicalEnd,
                ),
                requireOpen = true,
            )
            if (!resume) sendJson(session, mapOf("reset" to true), requireOpen = true)
            if (metadata.logicalStart > 0) sendJson(session, mapOf("trim" to metadata.logicalStart), requireOpen = true)
            sendJson(session, mapOf("cursor" to replayStart), requireOpen = true)
        }.onFailure {
            recordAttachTerminal(kind, requestedMode, GatewayOutcomeLabel.FAILURE, failureReasonLabel(it), startedAt)
            runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("websocket send failed")) }
                .onFailure { closeError -> log.warn("closing failed websocket attach failed: {}", closeError.message) }
            return
        }

        var replaySendFailure = false

        val tailer =
            TranscriptTailer(
                transcriptStore,
                stableSessionId,
                replayStart,
                intervalMs = props.tmux.tailIntervalMs,
                onTrim = { sendJson(session, mapOf("trim" to it, "cursor" to it), requireOpen = true) },
                observationRegistry = observationRegistry,
            ) { frame ->
                runCatching {
                    sendJson(session, mapOf("output" to frame.output, "off" to frame.off), requireOpen = true)
                }.onFailure { replaySendFailure = true }
                    .getOrThrow()
            }
        tailers[session.id] = tailer
        val replay = tailer.replayAvailable()
        val replayReason =
            if (replaySendFailure) GatewayFailureReasonLabel.IO_ERROR else failureReasonLabel(replay.failureReason)
        recordReplay(replay.bytes, replay.success, replayReason)
        if (!replay.success) {
            log.warn(
                "replay of transcript {} failed before live tailing after {} bytes and {} frames: {}",
                stableSessionId,
                replay.bytes,
                replay.frames,
                replay.failureReason,
            )
            telemetry.recordReplayFailure(
                GatewayReplayTelemetry(
                    bytes = replay.bytes,
                    outcome = GatewayOutcomeLabel.FAILURE,
                    reason = replayReason,
                ),
            )
            recordReplayOperation(
                kind = kind,
                mode = requestedMode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = replayReason,
            )
        } else {
            recordReplayOperation(kind, requestedMode, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE)
        }
        if (replaySendFailure) {
            recordAttachTerminal(
                kind = kind,
                mode = requestedMode,
                outcome = GatewayOutcomeLabel.FAILURE,
                reason = GatewayFailureReasonLabel.IO_ERROR,
                startedAt = startedAt,
            )
            runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("websocket send failed")) }
                .onFailure { closeError -> log.warn("closing failed websocket attach failed: {}", closeError.message) }
            return
        }
        val replayEnd =
            runCatching { transcriptStore.recoverMetadata(stableSessionId).logicalEnd }
                .getOrElse {
                    val reason = failureReasonLabel(it)
                    log.warn(
                        "metadata recovery for transcript {} failed before replay completion: {}",
                        stableSessionId,
                        it.message,
                    )
                    recordAttachTerminal(kind, requestedMode, GatewayOutcomeLabel.FAILURE, reason, startedAt)
                    runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("metadata recovery failed")) }
                        .onFailure { closeError ->
                            log.warn("closing websocket after metadata recovery failure failed: {}", closeError.message)
                        }
                    return
                }
        runCatching {
            sendJson(
                session,
                mapOf(
                    "control" to "REPLAY_COMPLETE",
                    "cursor" to replayEnd,
                ),
                requireOpen = true,
            )
        }.onFailure {
            recordAttachTerminal(kind, requestedMode, GatewayOutcomeLabel.FAILURE, failureReasonLabel(it), startedAt)
            runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("websocket send failed")) }
                .onFailure { closeError -> log.warn("closing failed websocket attach failed: {}", closeError.message) }
            return
        }
        val start = tailer.start()
        val startReason =
            if (start.success) GatewayFailureReasonLabel.NONE else failureReasonLabel(start.failureReason)
        recordTailerStartup(
            kind,
            requestedMode,
            if (start.success) GatewayOutcomeLabel.SUCCESS else GatewayOutcomeLabel.FAILURE,
            startReason,
        )
        recordAttachTerminal(
            kind = kind,
            mode = requestedMode,
            outcome = if (start.success) GatewayOutcomeLabel.SUCCESS else GatewayOutcomeLabel.FAILURE,
            reason = startReason,
            startedAt = startedAt,
        )
        if (!start.success) {
            runCatching { session.close(CloseStatus.SERVER_ERROR.withReason("tailer startup failed")) }
                .onFailure { closeError -> log.warn("closing failed websocket attach failed: {}", closeError.message) }
        }
    }

    private fun sendJson(
        session: WebSocketSession,
        payload: Map<String, Any?>,
        requireOpen: Boolean = false,
    ) {
        if (!session.isOpen) {
            if (requireOpen) throw IOException("websocket session is closed")
            return
        }
        val msg = mapper.writeValueAsString(payload)
        synchronized(session) { session.sendMessage(TextMessage(msg)) }
    }

    private fun queryOf(session: WebSocketSession): QueryParseResult {
        val raw = session.uri?.rawQuery ?: return QueryParseResult()
        val values = mutableMapOf<String, String>()
        var malformed = false
        raw
            .split('&')
            .filter { it.isNotBlank() }
            .forEach { part ->
                val pieces = part.split('=', limit = 2)
                val key =
                    runCatching { decodeQuery(pieces[0]) }
                        .getOrElse {
                            malformed = true
                            return@forEach
                        }.takeIf { it.isNotBlank() }
                        ?: return@forEach
                val value =
                    if (pieces.size == 2) {
                        runCatching { decodeQuery(pieces[1]) }
                            .getOrElse {
                                malformed = true
                                return@forEach
                            }
                    } else {
                        ""
                    }
                values[key] = value
            }
        return QueryParseResult(values, malformed)
    }

    private fun parseOffset(query: Map<String, String>): ParsedLong {
        val raw = query["offset"] ?: query["cursor"] ?: query["off"] ?: return ParsedLong()
        return ParsedLong(value = raw.toLongOrNull(), malformed = raw.toLongOrNull() == null)
    }

    private fun parseEpoch(query: Map<String, String>): ParsedLong {
        val raw = query["epoch"] ?: return ParsedLong()
        return ParsedLong(value = raw.toLongOrNull(), malformed = raw.toLongOrNull() == null)
    }

    private fun requestedModeOf(query: QueryParseResult): GatewayModeLabel {
        val mode = query.values["mode"]?.uppercase()
        return when {
            mode == "SNAPSHOT" -> GatewayModeLabel.SNAPSHOT
            mode == "RESUME" -> GatewayModeLabel.RESUME
            query.values.keys.any { it == "offset" || it == "cursor" || it == "off" } -> GatewayModeLabel.RESUME
            else -> GatewayModeLabel.SNAPSHOT
        }
    }

    private fun decodeQuery(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun recordAttachTerminal(
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

    private fun recordReplay(
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
            telemetry.recordReplay(
                GatewayReplayTelemetry(
                    bytes = bytes,
                    outcome = outcome,
                    reason = reason,
                ),
            )
        } finally {
            observation.stop()
        }
    }

    private fun recordReplayOperation(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = GatewayOperationLabel.REPLAY,
                kind = kind,
                mode = mode,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }

    private fun recordTailerStartup(
        kind: GatewayAgentKindLabel,
        mode: GatewayModeLabel,
        outcome: GatewayOutcomeLabel,
        reason: GatewayFailureReasonLabel,
    ) {
        telemetry.recordOperation(
            GatewayOperationTelemetry(
                operation = GatewayOperationLabel.REPLAY,
                kind = kind,
                mode = mode,
                outcome = outcome,
                reason = reason,
                duration = Duration.ZERO,
            ),
        )
    }

    private fun failureReasonLabel(error: Throwable): GatewayFailureReasonLabel =
        when (error) {
            is IOException -> GatewayFailureReasonLabel.IO_ERROR
            is IllegalArgumentException -> GatewayFailureReasonLabel.INVALID_REQUEST
            is SecurityException -> GatewayFailureReasonLabel.PERMISSION_DENIED
            else -> GatewayFailureReasonLabel.UNKNOWN
        }

    private fun failureReasonLabel(reason: String?): GatewayFailureReasonLabel =
        GatewayFailureReasonLabel.fromRaw(reason)

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
        tailers.remove(session.id)?.let { tailer ->
            runCatching { tailer.close() }
                .onFailure { log.warn("closing websocket tailer {} failed: {}", session.id, it.message) }
        }
    }

    private fun agentIdOf(session: WebSocketSession): String? {
        val path = session.uri?.path ?: return null
        val match = Regex("/ws/agents/([^/]+)/attach").find(path) ?: return null
        return match.groupValues[1]
    }

    private data class QueryParseResult(
        val values: Map<String, String> = emptyMap(),
        val malformed: Boolean = false,
    )

    private data class ParsedLong(
        val value: Long? = null,
        val malformed: Boolean = false,
    )

    companion object {
        // Upper bound on bytes replayed into the browser on a cold/snapshot
        // attach. A full-screen TUI repaint is tens of KiB, so 512 KiB always
        // contains at least one complete repaint while keeping main-thread
        // parse cost constant instead of O(session history).
        internal const val MAX_COLD_REPLAY_BYTES = 512L * 1024L

        // A resuming client whose offset is further than this behind the live
        // end has effectively been away too long to "catch up" cheaply; treat
        // it as cold and send the bounded snapshot instead of replaying the
        // whole gap.
        internal const val MAX_RESUME_REPLAY_BYTES = 512L * 1024L
    }
}
