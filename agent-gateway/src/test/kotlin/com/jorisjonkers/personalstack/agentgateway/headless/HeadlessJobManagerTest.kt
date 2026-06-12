package com.jorisjonkers.personalstack.agentgateway.headless

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.observability.AgentGatewayTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayActiveSessionsSample
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayFailureReasonLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationLabel
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOperationTelemetry
import com.jorisjonkers.personalstack.agentgateway.observability.GatewayOutcomeLabel
import com.jorisjonkers.personalstack.agentgateway.tmux.AgentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HeadlessJobManagerTest {
    private fun manager(
        tmp: Path,
        telemetry: AgentGatewayTelemetry = AgentGatewayTelemetry.NOOP,
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
        return HeadlessJobManager(
            props = props,
            telemetry = telemetry,
            processFactory = processFactory,
        )
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
    fun `records bounded launch completion and cleanup telemetry without sensitive labels`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val rawExitText = "result: ok from private run"
        val prompt = "summarize secret prompt"
        val mgr = manager(tmp, telemetry = telemetry) { _, _ -> processOf(exitCode = 0, output = rawExitText) }

        val job = mgr.launch(AgentKind.CLAUDE, prompt, workspacePath = tmp.resolve("workspace-a").toString())

        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.COMPLETED }
        waitUntil {
            telemetry.operations.any {
                it.operation == GatewayOperationLabel.STOP && it.outcome == GatewayOutcomeLabel.SUCCESS
            }
        }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.SPAWN)
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.NONE)
            }.anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.HEADLESS_JOB)
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.NONE)
            }.anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.STOP)
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.SUCCESS)
            }
        assertThat(telemetry.activeSamples)
            .anySatisfy {
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.status.label).isEqualTo("running")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.count).isEqualTo(1)
            }.anySatisfy {
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.status.label).isEqualTo("completed")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.count).isEqualTo(1)
            }
        assertNoForbiddenLabels(
            telemetry,
            job.id,
            tmp.toString(),
            prompt,
            job.outputFile.toString(),
            rawExitText,
        )
        mgr.destroy()
    }

    @Test
    fun `records failed exit telemetry without raw exit text labels`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val rawExitText = "raw exit text with /private/workspace"
        val mgr = manager(tmp, telemetry = telemetry) { _, _ -> processOf(exitCode = 17, output = rawExitText) }

        val job = mgr.launch(AgentKind.CODEX, "prompt should not become a label")

        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.FAILED }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.HEADLESS_JOB)
                assertThat(it.kind.label).isEqualTo("codex")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.PROCESS_EXITED)
            }
        assertNoForbiddenLabels(
            telemetry,
            job.id,
            tmp.toString(),
            "prompt should not become a label",
            job.outputFile.toString(),
            rawExitText,
            "17",
        )
        mgr.destroy()
    }

    @Test
    fun `records process start failure telemetry without exception message labels`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val exceptionMessage = "cannot start in ${tmp.resolve("secret-workspace")}"
        val mgr =
            manager(tmp, telemetry = telemetry) { _, _ -> throw IOException(exceptionMessage) }

        val job = mgr.launch(AgentKind.SHELL, "secret shell prompt")

        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.FAILED }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.SPAWN)
                assertThat(it.kind.label).isEqualTo("shell")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.IO_ERROR)
            }
        assertNoForbiddenLabels(
            telemetry,
            job.id,
            tmp.toString(),
            "secret shell prompt",
            job.outputFile.toString(),
            exceptionMessage,
        )
        mgr.destroy()
    }

    @Test
    fun `records timeout telemetry with bounded reason`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val mgr = manager(tmp, telemetry = telemetry) { _, _ -> sleepingProcess() }

        val job = mgr.launch(AgentKind.CLAUDE, "timeout prompt", timeoutSeconds = 0)

        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.FAILED }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.HEADLESS_JOB)
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.TIMEOUT)
            }
        assertNoForbiddenLabels(
            telemetry,
            job.id,
            tmp.toString(),
            "timeout prompt",
            job.outputFile.toString(),
        )
        mgr.destroy()
    }

    @Test
    fun `records cancel telemetry separately from cleanup`(
        @TempDir tmp: Path,
    ) {
        val telemetry = RecordingTelemetry()
        val started = CountDownLatch(1)
        val mgr =
            manager(tmp, telemetry = telemetry) { _, _ ->
                sleepingProcess().also { started.countDown() }
            }

        val job = mgr.launch(AgentKind.CLAUDE, "cancel prompt")
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue()

        waitUntil {
            mgr.cancel(job.id) && mgr.get(job.id)?.status == HeadlessJobStatus.CANCELLED
        }
        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.CANCELLED }
        waitUntil {
            telemetry.operations.any {
                it.operation == GatewayOperationLabel.STOP &&
                    it.outcome == GatewayOutcomeLabel.SKIPPED &&
                    it.reason == GatewayFailureReasonLabel.CANCELLED
            }
        }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(GatewayOperationLabel.STOP)
                assertThat(it.kind.label).isEqualTo("claude")
                assertThat(it.mode.label).isEqualTo("headless")
                assertThat(it.outcome).isEqualTo(GatewayOutcomeLabel.CANCELLED)
                assertThat(it.reason).isEqualTo(GatewayFailureReasonLabel.CANCELLED)
            }
        assertNoForbiddenLabels(
            telemetry,
            job.id,
            tmp.toString(),
            "cancel prompt",
            job.outputFile.toString(),
        )
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
        val started = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _ ->
                sleepingProcess().also { started.countDown() }
            }

        val job = mgr.launch(AgentKind.CLAUDE, "long running task")
        assertThat(job.status).isEqualTo(HeadlessJobStatus.RUNNING)
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue()

        waitUntil {
            mgr.cancel(job.id) && mgr.get(job.id)?.status == HeadlessJobStatus.CANCELLED
        }

        assertThat(mgr.get(job.id)?.status).isEqualTo(HeadlessJobStatus.CANCELLED)
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

    private fun sleepingProcess(): Process =
        ProcessBuilder("sh", "-c", "sleep 30")
            .redirectErrorStream(true)
            .start()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertThat(condition()).isTrue()
    }

    private fun assertNoForbiddenLabels(
        telemetry: RecordingTelemetry,
        vararg forbiddenValues: String,
    ) {
        val labels =
            telemetry.operations.flatMap { it.labels() } +
                telemetry.activeSamples.flatMap { it.labels() }
        val joined = labels.joinToString("|")
        forbiddenValues
            .filter { it.isNotBlank() }
            .forEach { assertThat(joined).doesNotContain(it) }
    }

    private fun GatewayOperationTelemetry.labels(): List<String> =
        listOf(operation.label, kind.label, mode.label, outcome.label, reason.label)

    private fun GatewayActiveSessionsSample.labels(): List<String> =
        listOf(status.label, kind.label, mode.label, count.toString())

    private class RecordingTelemetry : AgentGatewayTelemetry {
        val activeSamples = CopyOnWriteArrayList<GatewayActiveSessionsSample>()
        val operations = CopyOnWriteArrayList<GatewayOperationTelemetry>()

        override fun recordActiveSessions(sample: GatewayActiveSessionsSample) {
            activeSamples += sample
        }

        override fun recordOperation(event: GatewayOperationTelemetry) {
            operations += event
        }
    }
}
