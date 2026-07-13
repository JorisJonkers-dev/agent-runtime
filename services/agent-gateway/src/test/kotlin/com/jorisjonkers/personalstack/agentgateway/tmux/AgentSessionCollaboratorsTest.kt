package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AgentSessionCollaboratorsTest {
    private val tmux = mockk<TmuxClient>(relaxed = true)

    @Test
    fun `registry lists sessions by creation time and reports idle milliseconds`(
        @TempDir tmp: Path,
    ) {
        val registry = AgentSessionRegistry()
        val olderLog = tmp.resolve("older.log")
        val newerLog = tmp.resolve("newer.log")
        Files.writeString(olderLog, "older")
        Files.writeString(newerLog, "newer")
        registry.put(session("newer", AgentKind.CODEX, newerLog, Instant.parse("2026-01-02T00:00:00Z")))
        registry.put(session("older", AgentKind.CLAUDE, olderLog, Instant.parse("2026-01-01T00:00:00Z")))

        assertThat(registry.list().map { it.id }).containsExactly("older", "newer")
        assertThat(registry.idleMillis("older")).isNotNull().isGreaterThanOrEqualTo(0L)
        assertThat(registry.idleMillis("missing")).isNull()
        assertThat(registry.countsByKind()).containsEntry(AgentKind.CLAUDE, 1)
    }

    @Test
    fun `command factory resumes claude only when a matching transcript exists`(
        @TempDir tmp: Path,
    ) {
        val workspace = tmp.resolve("repo")
        Files.createDirectories(workspace)
        val prior = "11111111-2222-4333-8444-555555555555"
        val locator = ClaudeTranscriptLocator(tmp.resolve("claude/projects"))
        val transcript = locator.transcriptPath(workspace.toString(), prior)
        Files.createDirectories(transcript.parent)
        Files.writeString(transcript, """{"sessionId":"$prior"}""" + "\n")
        val factory = AgentCommandFactory(props(tmp), locator)

        val launch = factory.commandAndSessionIdFor(AgentKind.CLAUDE, workspace.toString(), prior)

        assertThat(launch.cliSessionId).isEqualTo(prior)
        assertThat(launch.command).containsSequence("--resume", prior)
        assertThat(launch.command).doesNotContain("--session-id")
    }

    @Test
    fun `codex session homes capture rollout id from isolated sessions directory`(
        @TempDir tmp: Path,
    ) {
        val stable = "33333333-3333-4333-8333-333333333333"
        val rolloutId = "019edf83-d3a0-7c73-8572-fec943c6091f"
        val homes = CodexSessionHomes(props(tmp))
        val sessionsDir = homes.homeFor(stable).resolve("sessions/2026/06/19")
        Files.createDirectories(sessionsDir)
        Files.writeString(
            sessionsDir.resolve("rollout-2026-06-19T10-53-39-$rolloutId.jsonl"),
            """{"type":"session_meta"}""",
        )

        assertThat(homes.captureSessionId(homes.homeFor(stable))).isEqualTo(rolloutId)
    }

    @Test
    fun `maintenance rotates durable transcript pipe when active segment changes`(
        @TempDir tmp: Path,
    ) {
        val props = props(tmp, transcripts = GatewayProperties.Transcripts(segmentBytes = 8, capBytes = 8))
        val store = TranscriptStore(props)
        val registry = AgentSessionRegistry()
        val telemetry = AgentSessionTelemetry(registry, AgentGatewayTelemetry.NOOP, ObservationRegistry.NOOP)
        val stable = "11111111-1111-1111-1111-111111111111"
        val lease = store.acquireLease(stable, "agent-one", 1)
        store.open(stable, 1)
        val firstSegment = store.activeSegmentPath(stable)
        Files.write(firstSegment, ByteArray(16) { 'x'.code.toByte() })
        registry.put(
            session("one", AgentKind.SHELL, firstSegment).copy(
                tmuxSession = "agent-one",
                stableSessionId = stable,
                transcriptFile = firstSegment,
                transcriptLease = lease,
            ),
        )

        AgentTranscriptMaintenance(tmux, props, store, registry, telemetry).maintain()

        val current = requireNotNull(registry.get("one"))
        assertThat(current.logFile).isEqualTo(store.activeSegmentPath(stable))
        assertThat(current.logFile).isNotEqualTo(firstSegment)
        verify { tmux.startPipeToFile("agent-one", current.logFile) }
    }

    @Test
    fun `spawn workflow releases transcript lease when tmux startup fails`(
        @TempDir tmp: Path,
    ) {
        val props = props(tmp)
        val store = TranscriptStore(props)
        val registry = AgentSessionRegistry()
        val telemetry = AgentSessionTelemetry(registry, AgentGatewayTelemetry.NOOP, ObservationRegistry.NOOP)
        val workflow =
            AgentSessionSpawnWorkflow(
                tmux = tmux,
                props = props,
                transcriptStore = store,
                registry = registry,
                telemetry = telemetry,
                claudeTranscriptLocator = ClaudeTranscriptLocator(tmp.resolve("claude/projects")),
            )
        val stable = "11111111-1111-1111-1111-111111111111"
        every { tmux.newSession(any(), any(), any()) } throws IOException("tmux failed")

        assertThatThrownBy { workflow.spawn(AgentKind.SHELL, stableSessionId = stable) }
            .isInstanceOf(IOException::class.java)

        val replacement = store.acquireLease(stable, "replacement", 1)
        assertThat(replacement.owner).isEqualTo("replacement")
    }

    private fun props(
        tmp: Path,
        transcripts: GatewayProperties.Transcripts = GatewayProperties.Transcripts(trimIntervalSeconds = 600),
    ): GatewayProperties {
        val workspace = tmp.resolve("workspace")
        Files.createDirectories(workspace)
        return GatewayProperties(
            workspaceRoot = workspace.toString(),
            tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.toString()),
            cli =
                GatewayProperties.Cli(
                    claude = "/usr/local/bin/claude",
                    codex = "/usr/local/bin/codex",
                    claudeArgs = listOf("--dangerously-skip-permissions"),
                    codexArgs = listOf("--dangerously-bypass-approvals-and-sandbox"),
                ),
            transcripts = transcripts,
            codex =
                GatewayProperties.Codex(
                    home = tmp.resolve("codex-home").toString(),
                    captureTimeoutMs = 50,
                    capturePollMs = 5,
                ),
        )
    }

    private fun session(
        id: String,
        kind: AgentKind,
        logFile: Path,
        createdAt: Instant = Instant.now(),
    ): AgentSession =
        AgentSession(
            id = id,
            kind = kind,
            tmuxSession = "agent-$id",
            logFile = logFile,
            cwd = logFile.parent.toString(),
            createdAt = createdAt,
        )
}
