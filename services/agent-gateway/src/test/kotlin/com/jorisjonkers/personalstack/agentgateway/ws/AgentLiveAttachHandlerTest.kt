package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayAgentKindLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AgentLiveAttachHandlerTest {
    private val sessions = mockk<AgentSessionManager>(relaxed = true)
    private val props =
        GatewayProperties(
            workspaceRoot = "/workspace",
            tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = "/tmp"),
            cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
        )

    @Test
    fun `live attach sends normalized snapshot and records success`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val tailers = ConcurrentHashMap<String, AutoCloseable>()
        val handler = attachHandler(telemetry, tailers)
        val ws = wsSession("abc")
        val sent = slot<TextMessage>()
        every { sessions.captureWithEscapes("abc") } returns "row1\nrow2"
        every { ws.sendMessage(capture(sent)) } returns Unit

        handler.attach(context(ws, agent(tmp)))

        verify { sessions.captureWithEscapes("abc") }
        assertThat(sent.captured.payload).contains("row1\\r\\nrow2")
        assertThat(tailers).containsKey("ws-abc")
        assertThat(telemetry.attachAttempts.single().outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        assertThat(telemetry.operations.map { it.reason }).contains(GatewayFailureReasonLabel.NONE)

        tailers.remove("ws-abc")?.close()
    }

    @Test
    fun `live attach closes websocket when snapshot send fails`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val handler = attachHandler(telemetry, ConcurrentHashMap())
        val ws = wsSession("abc")
        every { sessions.captureWithEscapes("abc") } returns "snapshot"
        every { ws.sendMessage(any()) } throws IOException("send failed")

        handler.attach(context(ws, agent(tmp)))

        assertThat(telemetry.attachAttempts.single().outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
        verify { ws.close(any<CloseStatus>()) }
    }

    private fun attachHandler(
        telemetry: RecordingTelemetry,
        tailers: ConcurrentHashMap<String, AutoCloseable>,
    ): AgentLiveAttachHandler =
        AgentLiveAttachHandler(
            sessions = sessions,
            props = props,
            sender = AgentWebSocketSender(ObjectMapper()),
            telemetry = AgentAttachTelemetryRecorder(telemetry, ObservationRegistry.NOOP),
            tailers = tailers,
        )

    private fun context(
        ws: WebSocketSession,
        agent: AgentSession,
    ): LiveAttachContext =
        LiveAttachContext(
            session = ws,
            agentId = agent.id,
            agent = agent,
            kind = GatewayAgentKindLabel.SHELL,
            requestedMode = GatewayModeLabel.SNAPSHOT,
            startedAt = Instant.now(),
        )

    private fun agent(tmp: Path): AgentSession {
        val logFile = tmp.resolve("agent-abc.log")
        logFile.toFile().createNewFile()
        return AgentSession(
            id = "abc",
            kind = AgentKind.SHELL,
            tmuxSession = "agent-abc",
            logFile = logFile,
            cwd = "/workspace",
            createdAt = Instant.now(),
        )
    }

    private fun wsSession(id: String): WebSocketSession {
        val ws = mockk<WebSocketSession>(relaxed = true)
        every { ws.uri } returns URI("ws://host/ws/agents/$id/attach")
        every { ws.id } returns "ws-$id"
        every { ws.isOpen } returns true
        return ws
    }
}
