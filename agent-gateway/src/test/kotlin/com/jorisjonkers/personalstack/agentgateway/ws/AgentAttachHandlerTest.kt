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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

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
    fun `durable attach sends control reset cursor replay output and replay complete`(
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
        val outputIndex = sent.indexOfFirst { it.payload.contains("\"output\":\"hello\"") }
        val replayCompleteIndex = sent.indexOfFirst { it.payload.contains("\"control\":\"REPLAY_COMPLETE\"") }
        assertThat(outputIndex).isGreaterThan(0)
        assertThat(replayCompleteIndex).isGreaterThan(outputIndex)
        assertThat(sent[outputIndex].payload).contains("\"off\":5")
        assertThat(sent[replayCompleteIndex].payload).contains("\"cursor\":5")
        verify(exactly = 0) { sessions.captureWithEscapes("abc") }

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable resume prefers canonical offset over compatibility cursors`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=4&cursor=1&off=2")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"RESUME\"")
        assertThat(sent.none { it.payload.contains("\"reset\"") }).isTrue
        assertThat(sent.any { it.payload.contains("\"cursor\":4") }).isTrue
        assertThat(sent.any { it.payload.contains("\"output\":\"ef\"") && it.payload.contains("\"off\":6") }).isTrue

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable resume accepts cursor and off compatibility offsets`(
        @TempDir tmp: Path,
    ) {
        val stableForCursor = "11111111-1111-1111-1111-111111111111"
        val stableForOff = "22222222-2222-2222-2222-222222222222"
        val store = transcriptStore(tmp)
        store.open(stableForCursor, 1)
        Files.writeString(store.activeSegmentPath(stableForCursor), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stableForCursor)
        store.open(stableForOff, 1)
        Files.writeString(store.activeSegmentPath(stableForOff), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stableForOff)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)

        val cursorWs = wsSession("cursor", "?mode=RESUME&epoch=1&cursor=3")
        every { sessions.get("cursor") } returns agent("cursor", tmp, stableSessionId = stableForCursor)
        val cursorSent = mutableListOf<TextMessage>()
        every { cursorWs.sendMessage(capture(cursorSent)) } returns Unit

        durableHandler.afterConnectionEstablished(cursorWs)

        assertThat(cursorSent[0].payload).contains("\"control\":\"RESUME\"")
        assertThat(
            cursorSent.any { it.payload.contains("\"output\":\"def\"") && it.payload.contains("\"off\":6") },
        ).isTrue

        val offWs = wsSession("off", "?mode=RESUME&epoch=1&off=2")
        every { sessions.get("off") } returns agent("off", tmp, stableSessionId = stableForOff)
        val offSent = mutableListOf<TextMessage>()
        every { offWs.sendMessage(capture(offSent)) } returns Unit

        durableHandler.afterConnectionEstablished(offWs)

        assertThat(offSent[0].payload).contains("\"control\":\"RESUME\"")
        assertThat(
            offSent.any { it.payload.contains("\"output\":\"cdef\"") && it.payload.contains("\"off\":6") },
        ).isTrue

        durableHandler.afterConnectionClosed(cursorWs, org.springframework.web.socket.CloseStatus.NORMAL)
        durableHandler.afterConnectionClosed(offWs, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable attach snapshots when requested epoch does not match`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 2)
        Files.writeString(store.activeSegmentPath(stable), "abcdef", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=3")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable).copy(epoch = 2)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        assertThat(sent.any { it.payload.contains("\"reset\":true") }).isTrue
        assertThat(sent.any { it.payload.contains("\"cursor\":0") }).isTrue
        assertThat(sent.any { it.payload.contains("\"output\":\"abcdef\"") && it.payload.contains("\"off\":6") }).isTrue

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable attach snapshots when requested offset is outside transcript window`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp, segmentBytes = 4, capBytes = 8)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "1111", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.activeSegmentPath(stable), "2222", StandardOpenOption.APPEND)
        store.rotateIfNeeded(stable)
        Files.writeString(store.activeSegmentPath(stable), "3333", StandardOpenOption.APPEND)
        store.trimIfNeeded(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        assertThat(sent.any { it.payload.contains("\"reset\":true") }).isTrue
        assertThat(sent.any { it.payload.contains("\"trim\":4") }).isTrue
        assertThat(sent.any { it.payload.contains("\"cursor\":4") }).isTrue
        assertThat(
            sent.any { it.payload.contains("\"output\":\"22223333\"") && it.payload.contains("\"off\":12") },
        ).isTrue

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable attach emits replay complete before live tailing`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = CopyOnWriteArrayList<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)
        Files.writeString(store.activeSegmentPath(stable), "-live", StandardOpenOption.APPEND)

        await().atMost(Duration.ofSeconds(2)).until { sent.any { it.payload.contains("-live") } }
        val replayOutputIndex = sent.indexOfFirst { it.payload.contains("\"output\":\"hello\"") }
        val replayCompleteIndex = sent.indexOfFirst { it.payload.contains("\"control\":\"REPLAY_COMPLETE\"") }
        val liveOutputIndex = sent.indexOfFirst { it.payload.contains("-live") }
        assertThat(replayOutputIndex).isGreaterThan(0)
        assertThat(replayCompleteIndex).isGreaterThan(replayOutputIndex)
        assertThat(liveOutputIndex).isGreaterThan(replayCompleteIndex)

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `malformed durable offset falls back to snapshot`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "abc", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableHandler = AgentAttachHandler(sessions, mapper, props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=nope")
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

    private fun transcriptStore(
        tmp: Path,
        segmentBytes: Long = 1024 * 1024,
        capBytes: Long = 8 * 1024 * 1024,
    ): TranscriptStore =
        TranscriptStore(
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.resolve("tmux").toString()),
                cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = segmentBytes,
                        capBytes = capBytes,
                    ),
            ),
        )
}
