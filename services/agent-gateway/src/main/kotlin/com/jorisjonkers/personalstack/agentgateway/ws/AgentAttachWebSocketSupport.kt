package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object AgentAttachLimits {
    // Upper bound on bytes replayed into the browser on a cold/snapshot
    // attach. A full-screen TUI repaint is tens of KiB, so 512 KiB always
    // contains at least one complete repaint while keeping main-thread
    // parse cost constant instead of O(session history).
    const val MAX_COLD_REPLAY_BYTES = 512L * 1024L

    // A resuming client whose offset is further than this behind the live
    // end has effectively been away too long to "catch up" cheaply; treat
    // it as cold and send the bounded snapshot instead of replaying the
    // whole gap.
    const val MAX_RESUME_REPLAY_BYTES = 512L * 1024L
}

internal class AgentWebSocketSender(
    private val mapper: ObjectMapper,
) {
    fun sendOutput(
        session: WebSocketSession,
        text: String,
    ) {
        if (text.isEmpty() || !session.isOpen) return
        sendJson(session, mapOf("output" to text))
    }

    fun sendJson(
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
}

internal object AgentAttachQuery {
    fun parse(session: WebSocketSession): QueryParseResult {
        val raw = session.uri?.rawQuery ?: return QueryParseResult()
        val values = mutableMapOf<String, String>()
        var malformed = false
        raw
            .split('&')
            .filter { it.isNotBlank() }
            .forEach { part ->
                val pieces = part.split('=', limit = 2)
                val key = decodePart(pieces[0], onMalformed = { malformed = true }) ?: return@forEach
                val value =
                    if (pieces.size == 2) {
                        decodePart(pieces[1], onMalformed = { malformed = true }) ?: return@forEach
                    } else {
                        ""
                    }
                values[key] = value
            }
        return QueryParseResult(values, malformed)
    }

    fun requestedModeOf(query: QueryParseResult): GatewayModeLabel {
        val mode = query.values["mode"]?.uppercase()
        return when {
            mode == "SNAPSHOT" -> GatewayModeLabel.SNAPSHOT
            mode == "RESUME" -> GatewayModeLabel.RESUME
            query.values.keys.any { it == "offset" || it == "cursor" || it == "off" } -> GatewayModeLabel.RESUME
            else -> GatewayModeLabel.SNAPSHOT
        }
    }

    fun parseOffset(query: Map<String, String>): ParsedLong {
        val raw = query["offset"] ?: query["cursor"] ?: query["off"] ?: return ParsedLong()
        val value = raw.toLongOrNull()
        return ParsedLong(value = value, malformed = value == null)
    }

    fun parseEpoch(query: Map<String, String>): ParsedLong {
        val raw = query["epoch"] ?: return ParsedLong()
        val value = raw.toLongOrNull()
        return ParsedLong(value = value, malformed = value == null)
    }

    private fun decodePart(
        value: String,
        onMalformed: () -> Unit,
    ): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }
            .getOrElse {
                onMalformed()
                null
            }?.takeIf { it.isNotBlank() }
}

internal fun agentIdOf(session: WebSocketSession): String? =
    session.uri
        ?.path
        ?.let { Regex("/ws/agents/([^/]+)/attach").find(it) }
        ?.groupValues
        ?.get(1)

internal data class QueryParseResult(
    val values: Map<String, String> = emptyMap(),
    val malformed: Boolean = false,
)

internal data class ParsedLong(
    val value: Long? = null,
    val malformed: Boolean = false,
)
