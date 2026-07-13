package com.jorisjonkers.personalstack.agentgateway.ws

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayModeLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSession
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentSessionManager
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptMetadata
import com.jorisjonkers.personalstack.agentgateway.tmux.TranscriptStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import java.io.IOException
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
        )
    private val handlers = mutableListOf<AgentAttachHandler>()
    private val handler = attachHandler(props, TranscriptStore(props))

    @AfterEach
    fun shutdownHandlers() {
        handlers.forEach(AgentAttachHandler::shutdown)
        handlers.clear()
    }

    private fun attachHandler(
        props: GatewayProperties,
        store: TranscriptStore,
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    ): AgentAttachHandler = AgentAttachHandler(sessions, mapper, props, store, telemetry).also(handlers::add)

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
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
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
        telemetry.attachAttempts.single().let {
            assertThat(it.mode).isEqualTo(GatewayModeLabel.SNAPSHOT)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        }
        telemetry.replayEvents.single().let {
            assertThat(it.bytes).isEqualTo(5)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        }
        assertThat(telemetry.replayFailures).isEmpty()

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable cold attach caps replay to a recent tail of a long transcript`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        val total = AgentAttachHandler.MAX_COLD_REPLAY_BYTES + 10
        Files.writeString(
            store.activeSegmentPath(stable),
            "x".repeat(total.toInt()),
            StandardOpenOption.APPEND,
        )
        store.recoverMetadata(stable)
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)
        val ws = wsSession("abc")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        // The replay tails in a bounded window from the end instead of from
        // offset 0, so attach cost does not grow with the transcript length.
        val expectedStart = total - AgentAttachHandler.MAX_COLD_REPLAY_BYTES
        assertThat(sent.any { it.payload.contains("\"cursor\":$expectedStart") }).isTrue
        assertThat(sent.none { it.payload.contains("\"cursor\":0") }).isTrue

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
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)
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
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)

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
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)
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
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)
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
    fun `resume request records resume attach mode even when replay falls back to snapshot`(
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
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        telemetry.attachAttempts.single().let {
            assertThat(it.mode).isEqualTo(GatewayModeLabel.RESUME)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        }
        assertThat(telemetry.attachFailures).isEmpty()

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
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store)
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
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=nope")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent[0].payload).contains("\"control\":\"SNAPSHOT\"")
        assertThat(sent.any { it.payload.contains("\"reset\":true") }).isTrue
        telemetry.attachAttempts.single().let {
            assertThat(it.mode).isEqualTo(GatewayModeLabel.RESUME)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        }
        telemetry.attachFailures.single().let {
            assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.INVALID_REQUEST)
            assertThat(it.mode).isEqualTo(GatewayModeLabel.RESUME)
        }

        durableHandler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL)
    }

    @Test
    fun `durable attach keeps websocket compatible when replay reporting fails`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = mockk<TranscriptStore>()
        every { store.recoverMetadata(stable) } returns
            TranscriptMetadata(stableSessionId = stable, logicalStart = 0, logicalEnd = 12)
        every { store.readRaw(stable, any(), any()) } throws IllegalStateException("storage unavailable")
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
        val ws = wsSession("abc")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent.any { it.payload.contains("\"control\":\"REPLAY_COMPLETE\"") }).isTrue
        assertThat(sent.none { it.payload.contains("storage unavailable") || it.payload.contains("failure") }).isTrue
        verify(exactly = 0) { ws.close(any<CloseStatus>()) }
        telemetry.attachAttempts.single().let {
            assertThat(it.mode).isEqualTo(GatewayModeLabel.SNAPSHOT)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
        }
        telemetry.replayEvents.single().let {
            assertThat(it.bytes).isEqualTo(0)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
        }
        telemetry.replayFailures.single().let { assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE) }

        durableHandler.afterConnectionClosed(ws, CloseStatus.NORMAL)
    }

    @Test
    fun `metadata recovery failure records terminal attach failure`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = mockk<TranscriptStore>()
        every { store.recoverMetadata(stable) } throws IllegalStateException("metadata unavailable")
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
        val ws = wsSession("abc")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)

        durableHandler.afterConnectionEstablished(ws)

        telemetry.attachAttempts.single().let {
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
            assertThat(it.mode).isEqualTo(GatewayModeLabel.SNAPSHOT)
        }
        telemetry.attachFailures.single().let { assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE) }
        verify { ws.close(any<CloseStatus>()) }
    }

    @Test
    fun `websocket send failure before replay complete records terminal attach failure`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val telemetry = RecordingTelemetry()
        val durableHandler = attachHandler(props.copy(workspaceRoot = tmp.toString()), store, telemetry)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = mutableListOf<TextMessage>()
        every { ws.sendMessage(any()) } answers {
            val msg = firstArg<TextMessage>()
            if (msg.payload.contains("\"output\"")) throw IOException("send failed")
            sent += msg
            Unit
        }

        durableHandler.afterConnectionEstablished(ws)

        assertThat(sent.none { it.payload.contains("\"control\":\"REPLAY_COMPLETE\"") }).isTrue
        telemetry.attachAttempts.single().let {
            assertThat(it.mode).isEqualTo(GatewayModeLabel.RESUME)
            assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
            assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.IO_ERROR)
        }
        telemetry.attachFailures.single().let { assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.IO_ERROR) }
        telemetry.replayFailures.single().let { assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.IO_ERROR) }

        durableHandler.afterConnectionClosed(ws, CloseStatus.NORMAL)
    }

    @Test
    fun `durable tailer is closed when websocket closes`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableProps =
            props.copy(
                workspaceRoot = tmp.toString(),
                tmux = props.tmux.copy(tailIntervalMs = 10),
            )
        val durableHandler = attachHandler(durableProps, store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = CopyOnWriteArrayList<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)
        durableHandler.afterConnectionClosed(ws, CloseStatus.NORMAL)
        Files.writeString(store.activeSegmentPath(stable), "-after-close", StandardOpenOption.APPEND)
        Thread.sleep(100)

        assertThat(sent.any { it.payload.contains("\"output\":\"hello\"") }).isTrue
        assertThat(sent.none { it.payload.contains("-after-close") }).isTrue
    }

    @Test
    fun `shutdown closes active durable tailers`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val store = transcriptStore(tmp)
        store.open(stable, 1)
        Files.writeString(store.activeSegmentPath(stable), "hello", StandardOpenOption.APPEND)
        store.recoverMetadata(stable)
        val durableProps =
            props.copy(
                workspaceRoot = tmp.toString(),
                tmux = props.tmux.copy(tailIntervalMs = 10),
            )
        val durableHandler = attachHandler(durableProps, store)
        val ws = wsSession("abc", "?mode=RESUME&epoch=1&offset=0")
        every { sessions.get("abc") } returns agent("abc", tmp, stableSessionId = stable)
        val sent = CopyOnWriteArrayList<TextMessage>()
        every { ws.sendMessage(capture(sent)) } returns Unit

        durableHandler.afterConnectionEstablished(ws)
        durableHandler.shutdown()
        Files.writeString(store.activeSegmentPath(stable), "-after-shutdown", StandardOpenOption.APPEND)
        Thread.sleep(100)

        assertThat(sent.any { it.payload.contains("\"output\":\"hello\"") }).isTrue
        assertThat(sent.none { it.payload.contains("-after-shutdown") }).isTrue
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
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = segmentBytes,
                        capBytes = capBytes,
                    ),
            ),
        )
}
