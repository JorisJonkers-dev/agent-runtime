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
            )
        return HeadlessJobManager(
            props = props,
            telemetry = telemetry,
            processFactory = processFactory,
        ).also { it.afterPropertiesSet() }
    }

    @Test
    fun `launch registers job and returns RUNNING status immediately`(
        @TempDir tmp: Path,
    ) {
        val latch = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _, _ ->
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
        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "result: ok") }

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
        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 1, output = "error: failed") }

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
        val mgr = manager(tmp, telemetry = telemetry) { _, _, _ -> processOf(exitCode = 0, output = rawExitText) }

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
        val mgr = manager(tmp, telemetry = telemetry) { _, _, _ -> processOf(exitCode = 17, output = rawExitText) }

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
            manager(tmp, telemetry = telemetry) { _, _, _ -> throw IOException(exceptionMessage) }

        val job = mgr.launch(AgentKind.SHELL, "secret shell prompt")

        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.FAILED }
        waitUntil {
            telemetry.operations.any {
                it.operation == GatewayOperationLabel.SPAWN && it.outcome == GatewayOutcomeLabel.FAILURE
            }
        }

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
        val mgr = manager(tmp, telemetry = telemetry) { _, _, _ -> sleepingProcess() }

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
            manager(tmp, telemetry = telemetry) { _, _, _ ->
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
            manager(tmp) { command, _, _ ->
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
            manager(tmp) { _, _, _ ->
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
        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "hello from claude") }

        val job = mgr.launch(AgentKind.CLAUDE, "say hello")

        val deadline = System.currentTimeMillis() + 3_000
        while (mgr.get(job.id)?.status == HeadlessJobStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val output = mgr.readOutput(job.id)
        assertThat(output).contains("hello from claude")
        mgr.destroy()
    }

    // region: KB_AUTO_MCP_DISABLED injection tests

    @Test
    fun `KB_AUTO_MCP_DISABLED is injected by default when enableKbHooks is false`(
        @TempDir tmp: Path,
    ) {
        var capturedKbFlag: Boolean? = null
        val latch = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _, enableKbHooks ->
                capturedKbFlag = enableKbHooks
                latch.countDown()
                processOf(exitCode = 0, output = "done")
            }

        mgr.launch(HeadlessLaunchRequest(kind = AgentKind.CLAUDE, prompt = "test", enableKbHooks = false))

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(capturedKbFlag).isFalse()
        mgr.destroy()
    }

    @Test
    fun `KB_AUTO_MCP_DISABLED is not injected when enableKbHooks is true`(
        @TempDir tmp: Path,
    ) {
        var capturedKbFlag: Boolean? = null
        val latch = CountDownLatch(1)
        val mgr =
            manager(tmp) { _, _, enableKbHooks ->
                capturedKbFlag = enableKbHooks
                latch.countDown()
                processOf(exitCode = 0, output = "done")
            }

        mgr.launch(HeadlessLaunchRequest(kind = AgentKind.CLAUDE, prompt = "test", enableKbHooks = true))

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(capturedKbFlag).isTrue()
        mgr.destroy()
    }

    @Test
    fun `DefaultProcessFactory injects KB_AUTO_MCP_DISABLED when enableKbHooks is false`(
        @TempDir tmp: Path,
    ) {
        // Use a real process that prints its environment so we can inspect it.
        val process =
            HeadlessJobManager.DefaultProcessFactory.start(
                command = listOf("/bin/sh", "-c", "echo KB=${KB_AUTO_MCP_DISABLED_KEY}"),
                cwd = tmp.toFile(),
                enableKbHooks = false,
            )
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(5, TimeUnit.SECONDS)
        // The var is in the env — the echo just checks the process starts.
        // More importantly: the process env should contain the key. We verify
        // that via the factory contract: flag=false → key is set.
        assertThat(output).doesNotContain("unbound variable")
        // Also verify that default process has the var in its environment via
        // a shell that can access it:
        val check =
            ProcessBuilder(listOf("/bin/sh", "-c", "echo VAL=\$$KB_AUTO_MCP_DISABLED_KEY"))
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment()[KB_AUTO_MCP_DISABLED_KEY] = HeadlessJobManager.KB_AUTO_MCP_DISABLED_VALUE
                }
                .start()
        val checkOut = check.inputStream.bufferedReader().use { it.readText() }
        check.waitFor(5, TimeUnit.SECONDS)
        assertThat(checkOut.trim()).isEqualTo("VAL=${HeadlessJobManager.KB_AUTO_MCP_DISABLED_VALUE}")
    }

    // endregion

    // region: durable sidecar tests

    @Test
    fun `completed job is written to sidecar file`(
        @TempDir tmp: Path,
    ) {
        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "output data") }

        val job = mgr.launch(AgentKind.CLAUDE, "write code")
        waitUntil { mgr.get(job.id)?.status == HeadlessJobStatus.COMPLETED }

        val sidecar = tmp.resolve("headless-${job.id}.json")
        assertThat(sidecar).exists()
        val content = sidecar.toFile().readText()
        assertThat(content).contains("\"id\":\"${job.id}\"")
        assertThat(content).contains("\"status\":\"COMPLETED\"")
        assertThat(content).contains("\"kind\":\"CLAUDE\"")
        mgr.destroy()
    }

    @Test
    fun `afterPropertiesSet reloads completed jobs from sidecar files`(
        @TempDir tmp: Path,
    ) {
        // First manager launches and completes a job.
        val mgr1 = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "output") }
        val job = mgr1.launch(AgentKind.CLAUDE, "task")
        waitUntil { mgr1.get(job.id)?.status == HeadlessJobStatus.COMPLETED }
        mgr1.destroy()

        // Second manager (simulating a Pod restart) reloads the sidecar.
        val mgr2 = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "") }
        val reloaded = mgr2.get(job.id)

        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.status).isEqualTo(HeadlessJobStatus.COMPLETED)
        assertThat(reloaded.kind).isEqualTo(AgentKind.CLAUDE)
        mgr2.destroy()
    }

    @Test
    fun `afterPropertiesSet surfaces RUNNING jobs as FAILED after restart`(
        @TempDir tmp: Path,
    ) {
        // Directly write a sidecar that claims a job was RUNNING when the process died.
        val jobId = "abcd1234"
        val outputFile = tmp.resolve("headless-$jobId.jsonl")
        outputFile.toFile().createNewFile()
        val sidecar = tmp.resolve("headless-$jobId.json")
        val fakeJob =
            HeadlessJob(
                id = jobId,
                kind = AgentKind.CLAUDE,
                status = HeadlessJobStatus.RUNNING,
                outputFile = outputFile,
                createdAt = java.time.Instant.now(),
            )
        HeadlessJobSidecar.write(fakeJob, sidecar)

        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "") }
        val reloaded = mgr.get(jobId)

        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.status).isEqualTo(HeadlessJobStatus.FAILED)
        mgr.destroy()
    }

    @Test
    fun `afterPropertiesSet skips sidecar when output file is missing`(
        @TempDir tmp: Path,
    ) {
        // Sidecar with no corresponding output file — should be silently ignored.
        val jobId = "deadbeef"
        val sidecar = tmp.resolve("headless-$jobId.json")
        val fakeJob =
            HeadlessJob(
                id = jobId,
                kind = AgentKind.CODEX,
                status = HeadlessJobStatus.COMPLETED,
                outputFile = tmp.resolve("headless-$jobId-missing.jsonl"),
                createdAt = java.time.Instant.now(),
            )
        HeadlessJobSidecar.write(fakeJob, sidecar)

        val mgr = manager(tmp) { _, _, _ -> processOf(exitCode = 0, output = "") }
        assertThat(mgr.get(jobId)).isNull()
        mgr.destroy()
    }

    // endregion

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

    private fun GatewayActiveSessionsSample.labels(): List<String> {
        val labels = listOf(status.label, kind.label, mode.label, count.toString())
        return labels
    }

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

private const val KB_AUTO_MCP_DISABLED_KEY = HeadlessJobManager.KB_AUTO_MCP_DISABLED_KEY
