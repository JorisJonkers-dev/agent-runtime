package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService

class AgentSessionManagerTest {
    private val tmux = mockk<TmuxClient>(relaxed = true)
    private val managers = mutableListOf<AgentSessionManager>()

    @AfterEach
    fun shutdownManagers() {
        managers.forEach(AgentSessionManager::shutdown)
        managers.clear()
    }

    private fun manager(
        tmp: Path,
        stagedInputs: GatewayProperties.StagedInputs = GatewayProperties.StagedInputs(),
        transcripts: GatewayProperties.Transcripts = GatewayProperties.Transcripts(trimIntervalSeconds = 1),
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
    ): AgentSessionManager {
        val workspace = tmp.resolve("workspace")
        Files.createDirectories(workspace)
        val props = gatewayProperties(tmp, workspace, stagedInputs, transcripts)
        return AgentSessionManager(tmux, props, TranscriptStore(props), telemetry).also(managers::add)
    }

    private fun gatewayProperties(
        tmp: Path,
        workspace: Path,
        stagedInputs: GatewayProperties.StagedInputs = GatewayProperties.StagedInputs(),
        transcripts: GatewayProperties.Transcripts = GatewayProperties.Transcripts(trimIntervalSeconds = 1),
    ): GatewayProperties =
        GatewayProperties(
            workspaceRoot = workspace.toString(),
            tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.toString()),
            cli =
                GatewayProperties.Cli(
                    claude = "/usr/local/bin/claude",
                    codex = "/usr/local/bin/codex",
                    claudeArgs = listOf("--dangerously-skip-permissions"),
                    codexArgs =
                        listOf(
                            "--dangerously-bypass-approvals-and-sandbox",
                            "--dangerously-bypass-hook-trust",
                        ),
                ),
            git = GatewayProperties.Git(deployKeyDir = "/x"),
            stagedInputs = stagedInputs,
            transcripts = transcripts,
        )

    @Test
    fun `spawn uses injected transcript store`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val workspace = tmp.resolve("workspace")
        Files.createDirectories(workspace)
        val props = gatewayProperties(tmp, workspace)
        val store = spyk(TranscriptStore(props))
        val mgr = AgentSessionManager(tmux, props, store).also(managers::add)

        val session = mgr.spawn(AgentKind.SHELL, stableSessionId = stable)

        assertThat(session.logFile).isEqualTo(store.activeSegmentPath(stable))
        verify { store.acquireLease(stable, session.tmuxSession, 1) }
    }

    @Test
    fun `shutdown terminates transcript maintenance executor`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val first = mgr.spawn(AgentKind.SHELL)
        val second = mgr.spawn(AgentKind.CODEX)

        mgr.shutdown()

        val trimmer =
            AgentSessionManager::class.java
                .getDeclaredField("trimmer")
                .apply { isAccessible = true }
                .get(mgr) as ScheduledExecutorService
        assertThat(trimmer.isShutdown).isTrue
        assertThat(trimmer.isTerminated).isTrue
        assertThat(mgr.get(first.id)).isNull()
        assertThat(mgr.get(second.id)).isNull()
        verify { tmux.killSession(first.tmuxSession) }
        verify { tmux.killSession(second.tmuxSession) }
    }

    @Test
    fun `spawn registers session and starts tmux + pipe`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE, workspacePath = "/workspace/repo")
        assertThat(s.kind).isEqualTo(AgentKind.CLAUDE)
        assertThat(s.tmuxSession).isEqualTo("agent-${s.id}")
        assertThat(s.cwd).isEqualTo("/workspace/repo")
        assertThat(s.stableSessionId).isNotNull()
        assertThat(s.logFile.toString()).contains(".agent-transcripts")
        assertThat(s.cliSessionId).isNotNull()
        verify {
            tmux.newSession(
                s.tmuxSession,
                match { cmd ->
                    cmd.containsAll(listOf("/usr/local/bin/claude", "--dangerously-skip-permissions")) &&
                        cmd.contains("--session-id") &&
                        cmd[cmd.indexOf("--session-id") + 1] == s.cliSessionId
                },
                "/workspace/repo",
            )
        }
        verify { tmux.startPipeToFile(s.tmuxSession, s.logFile) }
        assertThat(mgr.get(s.id)).isEqualTo(s)
        assertThat(mgr.list()).hasSize(1)
    }

    @Test
    fun `spawn launches claude in full-trust mode with configured flags`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        // Claude gets --session-id <uuid> appended so each gateway agent has
        // a fresh native session instead of inheriting another conversation.
        assertThat(s.cliSessionId).isNotNull()
        verify {
            tmux.newSession(
                s.tmuxSession,
                match { cmd ->
                    cmd.containsAll(listOf("/usr/local/bin/claude", "--dangerously-skip-permissions")) &&
                        cmd.contains("--session-id") &&
                        cmd[cmd.indexOf("--session-id") + 1] == s.cliSessionId
                },
                tmp.resolve("workspace").toString(),
            )
        }
    }

    @Test
    fun `spawn never resumes claude from another session`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        verify {
            tmux.newSession(
                s.tmuxSession,
                match { cmd ->
                    cmd.containsAll(listOf("/usr/local/bin/claude", "--dangerously-skip-permissions")) &&
                        cmd.contains("--session-id") &&
                        cmd[cmd.indexOf("--session-id") + 1] == s.cliSessionId &&
                        !cmd.contains("--resume")
                },
                tmp.resolve("workspace").toString(),
            )
        }
    }

    @Test
    fun `spawn never resumes codex last session`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CODEX)
        assertThat(s.cliSessionId).isNull()
        verify {
            tmux.newSession(
                s.tmuxSession,
                match { cmd ->
                    cmd.containsAll(
                        listOf(
                            "/usr/local/bin/codex",
                            "--dangerously-bypass-approvals-and-sandbox",
                            "--dangerously-bypass-hook-trust",
                        ),
                    ) &&
                        !cmd.contains("resume") &&
                        !cmd.contains("--last")
                },
                tmp.resolve("workspace").toString(),
            )
        }
    }

    @Test
    fun `spawn launches codex with approval+sandbox bypass flag`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CODEX)
        // Codex has no deterministic create-time session id flag; discovery is async.
        assertThat(s.cliSessionId).isNull()
        verify {
            tmux.newSession(
                s.tmuxSession,
                listOf(
                    "/usr/local/bin/codex",
                    "--dangerously-bypass-approvals-and-sandbox",
                    "--dangerously-bypass-hook-trust",
                ),
                tmp.resolve("workspace").toString(),
            )
        }
    }

    @Test
    fun `spawn launches shell bare with no trust flags`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.SHELL)
        assertThat(s.cliSessionId).isNull()
        verify { tmux.newSession(s.tmuxSession, listOf("/bin/bash", "-l"), tmp.resolve("workspace").toString()) }
    }

    @Test
    fun `spawn uses workspaceRoot when no path provided`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CODEX)
        assertThat(s.cwd).isEqualTo(tmp.resolve("workspace").toString())
    }

    @Test
    fun `stop kills tmux session and removes from registry`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.SHELL)
        assertThat(mgr.stop(s.id)).isTrue
        assertThat(mgr.get(s.id)).isNull()
        verify { tmux.killSession(s.tmuxSession) }
        assertThat(Files.exists(s.logFile)).isTrue
    }

    @Test
    fun `stop returns false for unknown id`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        assertThat(mgr.stop("does-not-exist")).isFalse
    }

    @Test
    fun `send delegates to tmux sendKeys with enter flag`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        mgr.send(s.id, "list files", enter = true)
        verify { tmux.sendKeys(s.tmuxSession, "list files", enter = true) }
    }

    @Test
    fun `stageInput writes content under session cwd with sanitized name`(
        @TempDir tmp: Path,
    ) {
        val workspace = tmp.resolve("workspace")
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE, workspacePath = workspace.toString())

        val staged = mgr.stageInput(s.id, "large document", "../source notes?.md")

        assertThat(staged.name).isEqualTo("source-notes-.md")
        assertThat(staged.bytes).isEqualTo("large document".toByteArray().size.toLong())
        assertThat(Path.of(staged.path).parent).isEqualTo(workspace.resolve(".agent-inputs"))
        assertThat(Files.readString(Path.of(staged.path))).isEqualTo("large document")
    }

    @Test
    fun `stageInput rejects content over configured byte cap`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp, GatewayProperties.StagedInputs(maxBytes = 4))
        val s = mgr.spawn(AgentKind.SHELL, workspacePath = tmp.resolve("workspace").toString())

        assertThatThrownBy { mgr.stageInput(s.id, "12345", "too-large.txt") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("exceeds 4 bytes")
    }

    @Test
    fun `stageInput rejects staging directory outside workspace`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp, GatewayProperties.StagedInputs(dirName = "../outside"))
        val s = mgr.spawn(AgentKind.SHELL, workspacePath = tmp.resolve("workspace").toString())

        assertThatThrownBy { mgr.stageInput(s.id, "content", "input.txt") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("inside the workspace")
    }

    @Test
    fun `capture delegates to tmux capture`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        every { tmux.capture(s.tmuxSession, 1_000) } returns "screen content"
        assertThat(mgr.capture(s.id)).isEqualTo("screen content")
    }

    @Test
    fun `captureWithEscapes delegates to tmux captureWithEscapes`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        every { tmux.captureWithEscapes(s.tmuxSession) } returns "ansi screen"
        assertThat(mgr.captureWithEscapes(s.id)).isEqualTo("ansi screen")
    }

    @Test
    fun `resize delegates to tmux resize`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CLAUDE)
        mgr.resize(s.id, 100, 30)
        verify { tmux.resize(s.tmuxSession, 100, 30) }
    }

    @Test
    fun `records interactive operation outcomes and active session gauges with bounded labels`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val mgr = manager(tmp, telemetry = telemetry)
        val workspace = tmp.resolve("workspace")
        val session = mgr.spawn(AgentKind.CLAUDE, workspacePath = workspace.toString())
        every { tmux.capture(session.tmuxSession, 1_000) } returns "screen content"

        mgr.send(session.id, "list files", enter = true)
        mgr.resize(session.id, 100, 30)
        assertThat(mgr.capture(session.id)).isEqualTo("screen content")
        assertThatThrownBy { mgr.send("missing-agent", "x") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { mgr.stageInput(session.id, "", "source.txt") }
            .isInstanceOf(IllegalArgumentException::class.java)
        mgr.stop(session.id)

        assertThat(telemetry.operations.map { Triple(it.operation, it.outcome, it.reason) })
            .contains(
                Triple(GatewayOperationLabel.SPAWN, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.INPUT, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.RESIZE, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.REPLAY, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
                Triple(GatewayOperationLabel.INPUT, GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.NOT_FOUND),
                Triple(
                    GatewayOperationLabel.INPUT,
                    GatewayOutcomeLabel.FAILURE,
                    GatewayFailureReasonLabel.INVALID_REQUEST,
                ),
                Triple(GatewayOperationLabel.STOP, GatewayOutcomeLabel.SUCCESS, GatewayFailureReasonLabel.NONE),
            )
        assertThat(telemetry.activeSamples)
            .anySatisfy {
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.mode.label).isEqualTo("interactive")
                assertThat(it.status.label).isEqualTo("running")
                assertThat(it.count).isEqualTo(1L)
            }.anySatisfy {
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.mode.label).isEqualTo("interactive")
                assertThat(it.status.label).isEqualTo("running")
                assertThat(it.count).isEqualTo(0L)
            }
        assertThat(telemetry.operations.flatMap { it.labels() } + telemetry.activeSamples.flatMap { it.labels() })
            .doesNotContain(
                session.id,
                requireNotNull(session.stableSessionId),
                workspace.toString(),
                "source.txt",
                "missing-agent",
            )
    }

    @Test
    fun `records durable transcript lease conflict as bounded spawn failure`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val firstTelemetry = RecordingTelemetry()
        val secondTelemetry = RecordingTelemetry()
        val first = manager(tmp, telemetry = firstTelemetry)
        val second = manager(tmp, telemetry = secondTelemetry)

        first.spawn(AgentKind.SHELL, stableSessionId = stable)

        assertThatThrownBy { second.spawn(AgentKind.SHELL, stableSessionId = stable) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(secondTelemetry.operations.map { Triple(it.operation, it.outcome, it.reason) })
            .contains(Triple(GatewayOperationLabel.SPAWN, GatewayOutcomeLabel.FAILURE, GatewayFailureReasonLabel.OTHER))
        assertThat(secondTelemetry.operations.flatMap { it.labels() })
            .doesNotContain(stable, "agent-")
    }

    @Test
    fun `trims a session log once it outgrows its disk cap`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val props =
            GatewayProperties(
                workspaceRoot = tmp.resolve("workspace").also { Files.createDirectories(it) }.toString(),
                tmux =
                    GatewayProperties.Tmux(
                        socketName = "agent-gw",
                        stateDir = tmp.toString(),
                    ),
                cli = GatewayProperties.Cli(claude = "/c", codex = "/x"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
                transcripts =
                    GatewayProperties.Transcripts(
                        segmentBytes = 64,
                        capBytes = 64,
                        trimIntervalSeconds = 1,
                    ),
            )
        val store = TranscriptStore(props)
        val mgr = AgentSessionManager(tmux, props, store).also(managers::add)
        try {
            val s = mgr.spawn(AgentKind.SHELL, stableSessionId = stable)
            val firstSegment = store.activeSegmentPath(requireNotNull(s.stableSessionId))
            val payload = ByteArray(96) { 'x'.code.toByte() }
            assertThat(firstSegment).isEqualTo(s.logFile)

            Files.write(firstSegment, payload)

            await().atMost(Duration.ofSeconds(5)).until {
                val current = mgr.get(s.id)
                current != null &&
                    current.logFile != firstSegment &&
                    !Files.exists(firstSegment) &&
                    store.recoverMetadata(stable).logicalStart == payload.size.toLong()
            }
            val current = requireNotNull(mgr.get(s.id))
            val metadata = store.recoverMetadata(stable)
            assertThat(current.logFile).isEqualTo(store.activeSegmentPath(stable))
            assertThat(current.transcriptFile).isEqualTo(current.logFile)
            assertThat(metadata.logicalStart).isEqualTo(payload.size.toLong())
            assertThat(metadata.byteCount).isLessThanOrEqualTo(props.transcripts.capBytes)
            assertThat(Files.size(current.logFile)).isLessThanOrEqualTo(props.transcripts.segmentBytes)
        } finally {
            mgr.shutdown()
        }
    }

    @Test
    fun `spawn rejects malformed stable session id`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)

        assertThatThrownBy { mgr.spawn(AgentKind.SHELL, stableSessionId = "not-a-uuid") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `spawn writes one continuation delimiter per epoch`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val mgr = manager(tmp)
        val first =
            mgr.spawn(
                AgentKind.SHELL,
                stableSessionId = stable,
                epoch = 2,
                continuation = AgentContinuation(reason = "restart", previousEpoch = 1),
            )
        mgr.stop(first.id)

        val second =
            mgr.spawn(
                AgentKind.SHELL,
                stableSessionId = stable,
                epoch = 2,
                continuation = AgentContinuation(reason = "restart", previousEpoch = 1),
            )

        val text = Files.readString(second.logFile)
        assertThat(Regex("continuation epoch=2").findAll(text).toList()).hasSize(1)
        assertThat(text).contains("agent restarted")
        assertThat(text).doesNotContain("updated setup")
    }

    @Test
    fun `spawn writes setup transition labels in continuation delimiter`(
        @TempDir tmp: Path,
    ) {
        val stable = "11111111-1111-1111-1111-111111111111"
        val mgr = manager(tmp)
        try {
            val session =
                mgr.spawn(
                    AgentKind.SHELL,
                    stableSessionId = stable,
                    epoch = 2,
                    continuation =
                        AgentContinuation(
                            reason = "restart",
                            previousEpoch = 1,
                            fromSetupLabel = "Default runner token=ghp_1234567890123456",
                            toSetupLabel = "GPU runner",
                        ),
                )

            val text = Files.readString(session.logFile)
            assertThat(text).contains("setup transition")
            assertThat(text).contains("fromSetup=\"Default runner token=[redacted]\"")
            assertThat(text).contains("toSetup=\"GPU runner\"")
            assertThat(text).doesNotContain("ghp_1234567890123456")
        } finally {
            mgr.shutdown()
        }
    }

    private fun GatewayOperationTelemetry.labels(): List<String> =
        listOf(operation.label, kind.label, mode.label, outcome.label, reason.label)

    private fun GatewayActiveSessionsSample.labels(): List<String> = listOf(status.label, kind.label, mode.label)

    private class RecordingTelemetry : AgentGatewayTelemetry {
        val operations = CopyOnWriteArrayList<GatewayOperationTelemetry>()
        val activeSamples = CopyOnWriteArrayList<GatewayActiveSessionsSample>()

        override fun recordOperation(event: GatewayOperationTelemetry) {
            operations += event
        }

        override fun recordActiveSessions(sample: GatewayActiveSessionsSample) {
            activeSamples += sample
        }
    }
}
