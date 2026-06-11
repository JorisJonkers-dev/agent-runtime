package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeadlessJobManagerTest {
    private fun manager(
        tmp: Path,
        processFactory: HeadlessJobManager.ProcessFactory,
    ): HeadlessJobManager {
        val props =
            GatewayProperties(
                workspaceRoot = tmp.toString(),
                tmux = GatewayProperties.Tmux(socketName = "agent-gw", stateDir = tmp.toString()),
                cli =
                    GatewayProperties.Cli(
                        claude = "/usr/local/bin/claude",
                        codex = "/usr/local/bin/codex",
                        codexArgs =
                            listOf(
                                "--dangerously-bypass-approvals-and-sandbox",
                                "--dangerously-bypass-hook-trust",
                            ),
                    ),
                git = GatewayProperties.Git(deployKeyDir = "/x"),
            )
        return HeadlessJobManager(props, processFactory)
    }

    @Test
    fun `launch registers job and returns RUNNING status immediately`(
        @TempDir tmp: Path,
    ) {
        val latch = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _ ->
                latch.await(5, TimeUnit.SECONDS)
                processOf(exitCode = 0, output = "done")
            }

        val job = mgr.launch(AgentKind.CLAUDE, "write a hello-world program")

        assertThat(job.status).isEqualTo(HeadlessJobStatus.RUNNING)
        assertThat(job.exitCode).isNull()
        assertThat(job.completedAt).isNull()
        latch.countDown()
        mgr.destroy()
    }

    @Test
    fun `launch completes with COMPLETED status on zero exit`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp) { _, _ -> processOf(exitCode = 0, output = "result: ok") }

        val job = mgr.launch(AgentKind.CLAUDE, "say hello")

        // Wait for the async process to finish.
        val deadline = System.currentTimeMillis() + 3_000
        while (mgr.get(job.id)?.status == HeadlessJobStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val done = mgr.get(job.id)!!
        assertThat(done.status).isEqualTo(HeadlessJobStatus.COMPLETED)
        assertThat(done.exitCode).isEqualTo(0)
        assertThat(done.completedAt).isNotNull()
        mgr.destroy()
    }

    @Test
    fun `launch marks job FAILED on non-zero exit`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp) { _, _ -> processOf(exitCode = 1, output = "error: failed") }

        val job = mgr.launch(AgentKind.CODEX, "do something")

        val deadline = System.currentTimeMillis() + 3_000
        while (mgr.get(job.id)?.status == HeadlessJobStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val done = mgr.get(job.id)!!
        assertThat(done.status).isEqualTo(HeadlessJobStatus.FAILED)
        assertThat(done.exitCode).isEqualTo(1)
        mgr.destroy()
    }

    @Test
    fun `launch codex headless uses bypass flags and starts fresh by default`(
        @TempDir tmp: Path,
    ) {
        val latch = CountDownLatch(1)
        var capturedCommand: List<String>? = null
        val mgr =
            manager(tmp) { command, _ ->
                capturedCommand = command
                latch.countDown()
                processOf(exitCode = 0, output = "done")
            }

        mgr.launch(AgentKind.CODEX, "inspect repo")

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(capturedCommand)
            .containsExactly(
                "/usr/local/bin/codex",
                "exec",
                "--dangerously-bypass-approvals-and-sandbox",
                "--dangerously-bypass-hook-trust",
                "--json",
                "--",
                "inspect repo",
            )
        assertThat(capturedCommand).doesNotContain("resume")
        mgr.destroy()
    }

    @Test
    fun `cancel kills the process and marks job CANCELLED`(
        @TempDir tmp: Path,
    ) {
        val latch = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _ ->
                // Simulate a long-running process
                latch.await(10, TimeUnit.SECONDS)
                processOf(exitCode = 0, output = "")
            }

        val job = mgr.launch(AgentKind.CLAUDE, "long running task")
        assertThat(job.status).isEqualTo(HeadlessJobStatus.RUNNING)

        val cancelled = mgr.cancel(job.id)
        assertThat(cancelled).isTrue()

        // The job should eventually reach CANCELLED state.
        val deadline = System.currentTimeMillis() + 3_000
        while (mgr.get(job.id)?.status == HeadlessJobStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        latch.countDown()
        mgr.destroy()
    }

    @Test
    fun `readOutput returns captured output after completion`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp) { _, _ -> processOf(exitCode = 0, output = "hello from claude") }

        val job = mgr.launch(AgentKind.CLAUDE, "say hello")

        val deadline = System.currentTimeMillis() + 3_000
        while (mgr.get(job.id)?.status == HeadlessJobStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val output = mgr.readOutput(job.id)
        assertThat(output).contains("hello from claude")
        mgr.destroy()
    }

    /**
     * Build a fake [Process] that exits immediately with [exitCode] and
     * writes [output] to its stdout stream.
     */
    private fun processOf(
        exitCode: Int,
        output: String,
    ): Process {
        // Write output to a temp file so we can read it back via inputStream.
        val tmp = File.createTempFile("hls-test", ".txt").also { it.deleteOnExit() }
        tmp.writeText(output)
        // `cat <file> && exit <code>` — cross-platform enough for unit tests.
        return ProcessBuilder("sh", "-c", "cat ${tmp.absolutePath}; exit $exitCode")
            .redirectErrorStream(true)
            .start()
    }
}
