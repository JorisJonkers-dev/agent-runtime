package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class AgentAttachHandlerTest {
    private val sessions = mockk<AgentSessionManager>(relaxed = true)
    private val mapper = ObjectMapper()
    private val props =
        GatewayProperties(
            workspaceRoot = "/workspace",
            tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = "/tmp"),
            cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
            git = GatewayProperties.Git(deployKeyDir = "/x"),
        )
    private val handler = AgentAttachHandler(sessions, mapper, props)

    private fun agent(
        id: String,
        tmp: Path,
        stableSessionId: String? = null,
    ): AgentSession =
        AgentSession(
            id = id,
            kind = AgentKind.SHELL,
            tmuxSession = "agent-$id",
            logFile = tmp.resolve("agent-$id.log").also { it.toFile().createNewFile() },
            cwd = "/workspace",
            createdAt = Instant.now(),
            stableSessionId = stableSessionId,
        )

    private fun wsSession(
        id: String,
        query: String = "",
    ): WebSocketSession {
        val ws = mockk<WebSocketSession>(relaxed = true)
        every { ws.uri } returns URI("ws://host/ws/agents/$id/attach$query")
        every { ws.id } returns "ws-$id"
        every { ws.isOpen } returns true
        return ws
    }

    @Test
    fun `attach sends one ansi snapshot frame before tailing`(
        @TempDir tmp: Path,
    ) {
        val ws = wsSession("abc")
        every { sessions.get("abc") } returns agent("abc", tmp)
        every { sessions.captureWithEscapes("abc") } returns "SCREEN-SNAPSHOT"
        val sent = slot<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        handler.afterConnectionEstablished(ws)

        verify { sessions.captureWithEscapes("abc") }
        assertThat(sent.captured.payload).contains("SCREEN-SNAPSHOT")
        assertThat(sent.captured.payload).contains("\"output\"")

        handler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable attach sends control reset cursor and replay output with offsets`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        assertThat(sent[1].payload).contains("\"reset\":true")
        assertThat(sent[2].payload).contains("\"cursor\":0")
        assertThat(sent.last().payload).contains("\"output\":\"hello\"")
        assertThat(sent.last().payload).contains("\"off\":5")
        verify(exactly = 0) { sessions.captureWithEscapes("abc") }

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable resume starts from valid cursor without reset`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&cursor=3")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"RESUME\"")
        assertThat(sent.none { it.payload.contains("\"reset\"") }).isTrue
        assertThat(sent.any { it.payload.contains("\"cursor\":3") }).isTrue
        assertThat(sent.last().payload).contains("\"output\":\"def\"")
        assertThat(sent.last().payload).contains("\"off\":6")

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `malformed durable cursor falls back to snapshot`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abc", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&cursor=nope")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        assertThat(sent.any { it.payload.contains("\"reset\":true") }).isTrue
    }

    @Test
    fun `resize frame routes to AgentSessionManager resize`() {
        val ws = wsSession("abc")
        handler.handleMessage(ws, TextMessage("""{"resize":{"cols":120,"rows":40}}"""))
        verify { sessions.resize("abc", 120, 40) }
        verify(exactly = 0) { sessions.send(any(), any(), any()) }
    }

    @Test
    fun `input frame still routes to AgentSessionManager send`() {
        val ws = wsSession("abc")
        handler.handleMessage(ws, TextMessage("""{"input":"ls\r","enter":false}"""))
        verify { sessions.send("abc", "ls\r", false) }
        verify(exactly = 0) { sessions.resize(any(), any(), any()) }
    }

    @Test
    fun `unknown frame is ignored without resize or send`() {
        val ws = wsSession("abc")
        handler.handleMessage(ws, TextMessage("""{"hello":"world"}"""))
        verify(exactly = 0) { sessions.resize(any(), any(), any()) }
        verify(exactly = 0) { sessions.send(any(), any(), any()) }
    }

    @Test
    fun `malformed json is ignored`() {
        val ws = wsSession("abc")
        handler.handleMessage(ws, TextMessage("not json at all"))
        verify(exactly = 0) { sessions.resize(any(), any(), any()) }
        verify(exactly = 0) { sessions.send(any(), any(), any()) }
    }

    private fun transcriptStore(tmp: Path): TranscriptStore =
        TranscriptStore(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
            ),
        )
}
