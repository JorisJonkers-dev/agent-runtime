package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AgentSessionManagerTest {
    private val tmux = mockk<TmuxClient>(relaxed = true)

    private fun manager(
        tmp: Path,
        stagedInputs: GatewayProperties.StagedInputs = GatewayProperties.StagedInputs(),
    ): AgentSessionManager {
        val props =
            GatewayProperties(
                workspaceRoot = "/workspace",
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
            )
        every { tmux.ensureStateDir() } returns tmp
        return AgentSessionManager(tmux, props)
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
        assertThat(s.logFile.parent).isEqualTo(tmp)
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
                "/workspace",
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
                "/workspace",
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
                "/workspace",
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
                "/workspace",
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
        verify { tmux.newSession(s.tmuxSession, listOf("/bin/bash", "-l"), "/workspace") }
    }

    @Test
    fun `spawn uses workspaceRoot when no path provided`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp)
        val s = mgr.spawn(AgentKind.CODEX)
        assertThat(s.cwd).isEqualTo("/workspace")
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
    fun `trims a session log once it outgrows its disk cap`(
        @TempDir tmp: Path,
    ) {
        val props =
            GatewayProperties(
                workspaceRoot = "/workspace",
                tmux =
                    GatewayProperties.Tmux(
                        socketName = "agent-gw",
                        stateDir = tmp.toString(),
                        logCapBytes = 64,
                        logTrimIntervalSeconds = 1,
                    ),
                cli = GatewayProperties.Cli(claude = "/c", codex = "/x"),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
            )
        every { tmux.ensureStateDir() } returns tmp
        val mgr = AgentSessionManager(tmux, props)
        try {
            val s = mgr.spawn(AgentKind.SHELL)
            Files.write(s.logFile, ByteArray(200) { 'x'.code.toByte() })
            assertThat(Files.size(s.logFile)).isEqualTo(200L)
            await().atMost(Duration.ofSeconds(5)).until { Files.size(s.logFile) == 0L }
        } finally {
            mgr.shutdown()
        }
    }
}
