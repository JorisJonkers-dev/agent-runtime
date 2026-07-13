package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import org.slf4j.LoggerFactory
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper

internal class AgentInputHandler(
    private val sessions: AgentSessionManager,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AgentInputHandler::class.java)

    fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val agentId = agentIdOf(session) ?: return
        val payload = payloadOf(message) ?: return
        val resize = payload["resize"] as? Map<*, *>
        if (resize != null) {
            handleResize(agentId, resize)
        } else {
            handleInput(agentId, payload)
        }
    }

    private fun payloadOf(message: TextMessage): Map<*, *>? =
        runCatching { mapper.readValue(message.payload, Map::class.java) }
            .getOrElse {
                log.warn("bad ws payload: {}", message.payload.take(PAYLOAD_PREVIEW_CHARS))
                null
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

    private fun handleInput(
        agentId: String,
        payload: Map<*, *>,
    ) {
        (payload["input"] as? String)?.let { input ->
            val enter = payload["enter"] as? Boolean ?: true
            sessions.send(agentId, input, enter)
        }
    }

    private companion object {
        const val PAYLOAD_PREVIEW_CHARS = 120
    }
}
