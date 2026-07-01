package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TmuxClientTest {
    private val runner = mockk<ProcessRunner>(relaxed = true)
    private val props =
        GatewayProperties(
            workspaceRoot = "/workspace",
            tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = "/tmp/agent-gateway-test"),
            cli = GatewayProperties.Cli(claude = "claude", codex = "codex"),
        )
    private val client = TmuxClient(runner, props)

    @Test
    fun `newSession invokes tmux with socket name and cwd`() {
        // newSession issues new-session then a window-size set-option, so
        // capture every call and assert against the new-session one.
        val calls = mutableListOf<List<String>>()
        every { runner.run(capture(calls), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "", "")

        client.newSession("agent-abc", listOf("claude"), "/workspace/repo")

        val create = calls.single { it.contains("new-session") }
        assertThat(create).containsSubsequence("tmux", "-L", "agent-gw")
        assertThat(create).contains("-s", "agent-abc")
        assertThat(create).contains("-c", "/workspace/repo")
        assertThat(create).endsWith("claude")

        // The pane is pinned to manual sizing so browser resize frames win.
        val manualSize = calls.single { it.contains("window-size") }
        assertThat(manualSize).containsSubsequence("set-option", "-t", "agent-abc", "window-size", "manual")

        // focus-events on lets the running TUI re-query size and repaint when
        // the pane regains focus, so a sidebar fold-out actually reflows it.
        val focusEvents = calls.single { it.contains("focus-events") }
        assertThat(focusEvents).containsSubsequence("set-option", "-g", "focus-events", "on")
    }

    @Test
    fun `sendKeys with enter sends the text then Enter as a separate invocation`() {
        every { runner.run(any(), any(), any(), any(), any()) } returns ProcessRunner.Result(0, "", "")

        client.sendKeys("agent-abc", "hello world")

        verify {
            runner.run(
                match { it.containsAll(listOf("send-keys", "-t", "agent-abc:0.0", "-l", "hello world")) },
                any(),
                any(),
                any(),
                any(),
            )
            runner.run(
                match { it.containsAll(listOf("send-keys", "-t", "agent-abc:0.0", "Enter")) },
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `captureWithEscapes captures visible screen with ansi and no history flag`() {
        val argv = slot<List<String>>()
        every { runner.run(capture(argv), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "screen with [31mansi[0m", "")

        val out = client.captureWithEscapes("agent-abc")

        assertThat(out).contains("ansi")
        assertThat(argv.captured).containsSubsequence("tmux", "-L", "agent-gw")
        assertThat(argv.captured).containsSubsequence("capture-pane", "-e", "-p")
        assertThat(argv.captured).contains("-t", "agent-abc:0.0")
        assertThat(argv.captured).doesNotContain("-S")
    }

    @Test
    fun `resize invokes tmux resize-window with cols and rows`() {
        val argv = slot<List<String>>()
        every { runner.run(capture(argv), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "", "")

        client.resize("agent-abc", 120, 40)

        assertThat(argv.captured).containsSubsequence("tmux", "-L", "agent-gw")
        assertThat(argv.captured).contains("resize-window", "-t", "agent-abc:0.0")
        assertThat(argv.captured).containsSubsequence("-x", "120")
        assertThat(argv.captured).containsSubsequence("-y", "40")
    }

    @Test
    fun `listSessions parses tmux list-sessions output`() {
        every { runner.run(any(), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "agent-abc\nagent-def\n", "")

        assertThat(client.listSessions()).containsExactly("agent-abc", "agent-def")
    }

    @Test
    fun `listSessions returns empty list when tmux server is not running`() {
        every { runner.run(any(), any(), any(), any(), any()) } returns
            ProcessRunner.Result(1, "", "no server running")

        assertThat(client.listSessions()).isEmpty()
    }

    @Test
    fun `ensureStateDir creates the directory`(
        @TempDir tmp: Path,
    ) {
        val withTmp =
            props.copy(
                tmux = props.tmux.copy(stateDir = tmp.resolve("agent-gw").toString()),
            )
        val tmuxWithTmp = TmuxClient(runner, withTmp)
        val dir = tmuxWithTmp.stateDir
        assertThat(File(dir.toString())).exists().isDirectory()
    }

    @Test
    fun `startPipeToFile quotes the gateway generated path for tmux shell`() {
        val argv = slot<List<String>>()
        every { runner.run(capture(argv), any(), any(), any(), any()) } returns
            ProcessRunner.Result(0, "", "")

        client.startPipeToFile("agent-abc", Path.of("/workspace/.agent-transcripts/id/seg'ment.log"))

        assertThat(argv.captured).containsSubsequence("pipe-pane", "-O", "-t", "agent-abc:0.0")
        assertThat(argv.captured.last()).isEqualTo("cat >> '/workspace/.agent-transcripts/id/seg'\"'\"'ment.log'")
    }
}
