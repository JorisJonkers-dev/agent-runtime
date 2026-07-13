package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.net.URI

class AgentInputHandlerTest {
    private val sessions = mockk<AgentSessionManager>(relaxed = true)
    private val handler = AgentInputHandler(sessions, ObjectMapper())

    @Test
    fun `resize frame routes to AgentSessionManager resize`() {
        handler.handleTextMessage(wsSession("abc"), TextMessage("""{"resize":{"cols":120,"rows":40}}"""))

        verify { sessions.resize("abc", 120, 40) }
        verify(exactly = 0) { sessions.send(any(), any(), any()) }
    }

    @Test
    fun `input frame routes to AgentSessionManager send`() {
        handler.handleTextMessage(wsSession("abc"), TextMessage("""{"input":"ls\r","enter":false}"""))

        verify { sessions.send("abc", "ls\r", false) }
        verify(exactly = 0) { sessions.resize(any(), any(), any()) }
    }

    @Test
    fun `malformed json is ignored`() {
        handler.handleTextMessage(wsSession("abc"), TextMessage("not json at all"))

        verify(exactly = 0) { sessions.resize(any(), any(), any()) }
        verify(exactly = 0) { sessions.send(any(), any(), any()) }
    }

    private fun wsSession(id: String): WebSocketSession {
        val ws = mockk<WebSocketSession>(relaxed = true)
        every { ws.uri } returns URI("ws://host/ws/agents/$id/attach")
        return ws
    }
}
